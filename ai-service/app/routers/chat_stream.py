"""SSE 流式问答（需求规格 §2.1.1：支持 SSE 流式输出，首字响应 ≤1 秒）。

事件协议：
    data: {"event":"meta",  ...}   检索完成后的元信息（traceId / 置信度 / 是否拒答 / 来源）
    data: {"event":"delta", "text":"..."}  文本增量
    data: {"event":"done",  ...}   结束事件（带完整答案与统计）

配了 LLM 时是真流式（逐块转发模型增量）；没配 LLM 时走抽取式答案的
分段推送，保证前端体验与协议一致。
"""

import asyncio
import json

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.config import settings
from app.deps import knowledge
from app.llm import LLMUnavailable, llm
from app.observability import record_event, trace_span
from app.routers.chat import HistoryItem, _sources
from app.prompts import (
    RAG_QA,
    REFUSAL_ANSWER,
    build_context,
    build_structured_answer,
    pick_fuzzy_hint,
    pick_safety_tip,
)

router = APIRouter(prefix="/ai", tags=["chat"])

# 无 LLM 时的分段推送：按中文标点切，短语级推，视觉上接近逐字
CHUNK_SIZE = 12


class StreamRequest(BaseModel):
    question: str
    history: list[HistoryItem] = []
    top_k: int | None = None


def _sse(payload: dict) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _chunk_text(text: str):
    for start in range(0, len(text), CHUNK_SIZE):
        yield text[start:start + CHUNK_SIZE]


@router.post("/chat/stream")
async def chat_stream(req: StreamRequest):
    question = (req.question or "").strip()
    history = [h.model_dump() for h in (req.history or [])][-settings.history_turns * 2:]
    top_k = req.top_k or settings.max_hits

    # 检索是 CPU 同步计算，放线程池里跑，避免阻塞事件循环
    def retrieve():
        with trace_span("chat_stream") as span:
            hits, trace = knowledge.search(question, history=history, top_k=top_k)
            span.update({
                "hitIds": [h.id for h in hits],
                "confidence": hits[0].confidence if hits else 0,
                "rewritten": trace.get("normalized"),
            })
            return hits, trace, span.trace_id

    hits, trace, trace_id = await asyncio.to_thread(retrieve)

    refused = (not hits) or hits[0].confidence < settings.refuse_threshold

    async def gen():
        loop = asyncio.get_running_loop()
        safety = pick_safety_tip(question)
        # 1) 元信息：前端拿到它就能先渲染"依据"卡片，不等正文
        yield _sse({
            "event": "meta",
            "traceId": trace_id,
            "refused": refused,
            "confidence": hits[0].confidence if hits else 0.0,
            # 拒答时不下发来源：置信度没过线的召回项挂上去会让用户误以为
            # "平台就是这么规定的"，反而制造误导（实测「订机票」conf=0.139
            # 却带着 4 条来源，其中一条是「校园卡与饭卡能不能交易」）。
            "sources": _sources(hits[:top_k]) if not refused else [],
            "safetyTip": safety,
            # 模糊问题（"我想卖东西"）检索不到是正常的，把引导话术下发给前端，
            # 由前端在答案下方渲染成可点击的示例问题。
            # 不能只在 done 里给：前端已经先收到 meta 并显示了拒答文案。
            "hint": pick_fuzzy_hint(question),
        })
        if refused:
            logger_msg = f"[{trace_id}] 流式拒答"
            yield _sse({"event": "log", "text": logger_msg})
            hint = pick_fuzzy_hint(question)
            refusal_text = REFUSAL_ANSWER
            # 模糊问题不算"答不上来"，给引导比给拒答有用
            if hint:
                refusal_text = f"{refusal_text}\n\n💡 {hint}"
            for piece in _chunk_text(refusal_text):
                yield _sse({"event": "delta", "text": piece})
                await asyncio.sleep(0.01)
            mode = "refused"
            answer = refusal_text
        else:
            prompt = RAG_QA.format(context=build_context(hits[:top_k]), question=question)
            mode = "api"
            answer = ""
            try:
                iterator = llm.stream(prompt)

                def _next():
                    try:
                        return next(iterator)
                    except StopIteration:
                        return None

                while True:
                    piece = await loop.run_in_executor(None, _next)
                    if piece is None:
                        break
                    answer += piece
                    yield _sse({"event": "delta", "text": piece})
            except LLMUnavailable:
                mode = "extractive"
                answer = build_structured_answer(hits[:top_k])
                for piece in _chunk_text(answer):
                    yield _sse({"event": "delta", "text": piece})
                    await asyncio.sleep(0.015)

            # 合规后处理与非流式 _compose 对齐：风险提示与模糊引导都并进正文末尾。
            # ⚠️ 必须继续以 delta 推出去，不能只写进 done.answer：
            # 那样前端流式渲染出的文本和 done.answer 就不一致（实测 delta≠answer），
            # 用户看不到提示，还得靠前端二次拼接。
            hint = pick_fuzzy_hint(question)
            suffix = ""
            if safety:
                suffix += f"\n\n{safety}"
            if hint:
                suffix += f"\n\n💡 {hint}"
            if suffix:
                answer += suffix
                for piece in _chunk_text(suffix):
                    yield _sse({"event": "delta", "text": piece})
                    await asyncio.sleep(0.015)

        record_event("chat_stream", question, refused, hits, mode)
        done_payload = {
            "event": "done",
            "traceId": trace_id,
            "mode": mode,
            "refused": refused,
            "answer": answer,
            "confidence": hits[0].confidence if hits else 0.0,
            "hitIds": [h.id for h in hits],
        }
        if safety:
            done_payload["safetyTip"] = safety
        if hint and not refused:
            done_payload["hint"] = hint
        yield _sse(done_payload)

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})