"""RAG 智能问答（需求规格 §2.1）。

流水线：查询预处理 → 混合检索（BM25+向量+RRF+重排）→ 置信度判定 → 生成 → 合规后处理
（强制溯源 / 风险提示 / 模糊引导 / 拒答）。
"""

import logging

from fastapi import APIRouter
from pydantic import BaseModel

from app.config import settings
from app.deps import knowledge
from app.llm import LLMUnavailable, llm
from app.observability import record_event, trace_span
from app.prompts import (
    RAG_QA,
    REFUSAL_ANSWER,
    build_context,
    build_history_block,
    build_structured_answer,
    pick_fuzzy_hint,
    pick_safety_tip,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai", tags=["chat"])

EMPTY_ANSWER = "请告诉我你想问什么，比如「怎么退款」「怎么实名认证」「中区在哪面交」。"


class HistoryItem(BaseModel):
    role: str = "user"
    content: str = ""


class ChatRequest(BaseModel):
    question: str
    history: list[HistoryItem] = []
    top_k: int | None = None


def _sources(hits) -> list[dict]:
    """来源溯源：需求 §2.1.3 要求规则类回答强制挂载。"""
    return [
        {
            "type": "knowledge",
            "id": hit.id,
            "title": hit.title,
            "snippet": hit.content[:120],
            "source": hit.source,          # 溯源展示名
            "shard": hit.shard,
            "tags": hit.tags,
            "updated": hit.updated,
            "evidence": hit.evidence,      # 为什么召回它
            "score": hit.confidence,
        }
        for hit in hits
    ]


def _compose(question: str, hits, trace, trace_id: str, top_k: int, history=None) -> dict:
    """置信度判定 + 生成 + 合规后处理。"""
    # 置信度低于阈值强制拒答，不强行生成（需求 §2.1.3）
    refused = (not hits) or hits[0].confidence < settings.refuse_threshold
    if refused:
        logger.info("[%s] 拒答，最高置信度=%s 阈值=%s", trace_id,
                    hits[0].confidence if hits else 0, settings.refuse_threshold)
        answer, mode = REFUSAL_ANSWER, "refused"
    else:
        try:
            answer = llm.generate(RAG_QA.format(
                context=build_context(hits[:top_k]), question=question,
                history=build_history_block(history)))
            mode = "api"
        except LLMUnavailable:
            answer = build_structured_answer(hits[:top_k])
            mode = "extractive"

    safety = pick_safety_tip(question)
    hint = pick_fuzzy_hint(question)
    if safety:
        answer = f"{answer}\n\n{safety}"
    if hint and not refused:
        answer = f"{answer}\n\n💡 {hint}"

    result = {
        "answer": answer,
        "mode": mode,
        "refused": refused,
        "confidence": hits[0].confidence if hits else 0.0,
        "sources": _sources(hits[:top_k]),
        "traceId": trace_id,
    }
    if safety:
        result["safetyTip"] = safety
    if hint:
        result["hint"] = hint
    rewritten = trace.get("normalized")
    if rewritten and rewritten != question:
        result["rewrittenQuery"] = rewritten
    record_event("chat", question, refused, hits, mode)
    return result


@router.post("/chat")
def chat(req: ChatRequest):
    question = (req.question or "").strip()
    if not question:
        return {"answer": EMPTY_ANSWER, "mode": "empty", "refused": False,
                "sources": [], "confidence": 0.0}

    history = [h.model_dump() for h in (req.history or [])][-settings.history_turns * 2:]
    top_k = req.top_k or settings.max_hits

    with trace_span("chat") as span:
        hits, trace = knowledge.search(question, history=history, top_k=top_k)
        span.update({
            "rewritten": trace.get("normalized"),
            "intents": trace.get("intents"),
            "seasons": trace.get("seasons"),
            "bm25Top": trace.get("bm25_top", [])[:3],
            "vecTop": trace.get("vec_top", [])[:3],
            "hitIds": [h.id for h in hits],
            "confidence": hits[0].confidence if hits else 0,
            "refuseThreshold": settings.refuse_threshold,
        })
        trace_id = span.trace_id

    return _compose(question, hits, trace, trace_id, top_k, history=history)