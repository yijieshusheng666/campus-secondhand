"""SSE 流式问答（需求规格 §2.1.1：支持 SSE 流式输出，首字响应 ≤1 秒）。

事件协议：
    data: {"event":"meta",  ...}   检索完成后的元信息（traceId / 置信度 / 是否拒答 / 来源）
    data: {"event":"delta", "text":"..."}  文本增量
    data: {"event":"error", "text":"..."}  正文作废，text 为替代正文（可缺省）
    data: {"event":"done",  ...}   结束事件（带完整答案与统计）

契约：`done.answer` 等于所有 `delta` 的拼接。唯一的例外是「模型流到一半中断」——
此时已推出去的 delta 收不回来，后端改发一条带 `text` 的 `error`，由前端整段替换，
`done.answer` 与该 `text` 一致。

配了 LLM 时是真流式（逐块转发模型增量）；没配 LLM 时走抽取式答案的
分段推送，保证前端体验与协议一致。
"""

import asyncio
import json
import time

from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from app.config import settings
from app.deps import knowledge
from app.llm import LLMUnavailable, llm
from app.observability import record_event, trace_span
from app.routers.chat import (
    MAX_HISTORY_ITEMS,
    MAX_QUESTION_LEN,
    HistoryItem,
    _sources,
)
from app.prompts import (
    RAG_QA,
    REFUSAL_ANSWER,
    build_context,
    build_history_block,
    PERSONAL_QA,
    build_structured_answer,
    is_personal_question,
    pick_fuzzy_hint,
    pick_safety_tip,
)

router = APIRouter(prefix="/ai", tags=["chat"])

# 无 LLM 时的分段推送：按中文标点切，短语级推，视觉上接近逐字
CHUNK_SIZE = 12


# personal_context 是前端按站内数据压出来的文本块（最多各 6 行商品/订单/求购），
# 正常几 KB 以内；给足余量的同时设上限，避免被当成往 prompt 里灌垃圾的通道。
MAX_PERSONAL_CONTEXT_LEN = 8000


class StreamRequest(BaseModel):
    # 前端 / Java 侧一律用 camelCase，这里两种写法都收。
    # 不配 alias 的话 Pydantic v2 会静默忽略 personalContext 这个 key：
    # 不报错、字段永远是空串，个人通路看起来
    # 像是开了实则完全没生效。
    model_config = ConfigDict(populate_by_name=True)

    question: str = Field(max_length=MAX_QUESTION_LEN)
    history: list[HistoryItem] = Field(default=[], max_length=MAX_HISTORY_ITEMS)
    top_k: int | None = Field(default=None, ge=1, le=50)
    # 调用方（前端）压好的「当前登录用户站内数据」文本块，见 prompts.is_personal_question
    personal_context: str = Field(default="", alias="personalContext",
                                  max_length=MAX_PERSONAL_CONTEXT_LEN)


def _sse(payload: dict) -> str:
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def _retrieval_digest(trace: dict, hits, latency_ms: float) -> dict:
    """把内部检索 trace 压成一份「可展示的检索链路」。

    为什么需要它：混合检索（BM25 稀疏 + 向量稠密 + RRF 融合 + 重排）内部
    算得挺多，但这些信息以前只留在服务端日志里，前端只知道"命中了什么"，
    用户看不到"是怎么命中的"。对做毕设/答辩来说，检索过程本身就是卖点，
    应该让人看见：问题被怎么改写、两个通道各自召回了什么、
    融合后谁排第一、哪些被重排淘汰。

    只截取 top5 并压平 score，避免把整个 trace 原样抖给前端。
    """
    def _flat(pairs):
        return [{"id": d, "score": round(float(s), 4)} for d, s in (pairs or [])[:5]]

    fused = _flat(trace.get("fused"))
    dropped = trace.get("dropped_by_rerank") or []
    return {
        "latencyMs": round(float(latency_ms), 1),
        "normalized": trace.get("normalized") or "",
        "intents": trace.get("intents") or [],
        "queries": trace.get("queries") or [],
        "bm25": _flat(trace.get("bm25_top")),
        "vector": _flat(trace.get("vec_top")),
        "fused": fused,
        "confidenceMode": trace.get("confidenceMode") or "",
        "absFactor": trace.get("absFactor"),
        "dropped": [
            {"id": d.get("id"), "title": d.get("title"),
             "confidence": round(float(d.get("confidence") or 0), 4)}
            for d in dropped[:3]
        ],
        "hitCount": len(hits),
        "threshold": settings.refuse_threshold,
    }


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

    _t0 = time.perf_counter()
    hits, trace, trace_id = await asyncio.to_thread(retrieve)
    retrieval_ms = (time.perf_counter() - _t0) * 1000

    # 个人数据通路：问题在问「我的xxx」时，答案只能来自 personal_context，
    # 知识库命中与否完全无关。必须把 KB 拒答短路掉，
    # 否则「我有几件闲置」会因为检索不到而直接拒答 —— 那正是要修的 bug。
    personal = (req.personal_context or "").strip()
    personal_mode = is_personal_question(question, personal)
    refused = (not personal_mode) and ((not hits) or hits[0].confidence < settings.refuse_threshold)

    async def gen():
        loop = asyncio.get_running_loop()
        safety = pick_safety_tip(question)
        # 1) 元信息：前端拿到它就能先渲染"依据"卡片，不等正文
        yield _sse({
            "event": "meta",
            "traceId": trace_id,
            "refused": refused,
            "confidence": hits[0].confidence if hits else 0.0,
            # 检索链路明细：让前端把「怎么检索到的」展示出来，
            # 而不是只甩一个最终答案。
            "retrieval": _retrieval_digest(trace, hits, retrieval_ms),
            "personal": personal_mode,
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
            if personal_mode:
                prompt = PERSONAL_QA.format(personal=personal, question=question,
                                           history=build_history_block(history))
                mode = "personal"
            else:
                prompt = RAG_QA.format(context=build_context(hits[:top_k]), question=question,
                                       history=build_history_block(history))
                mode = "api"
            hint = pick_fuzzy_hint(question)
            suffix = ""
            if safety:
                suffix += f"\n\n{safety}"
            if hint:
                suffix += f"\n\n💡 {hint}"

            answer = ""
            emitted = False     # 是否已经推过至少一个 delta
            superseded = False  # 已发 error，正文由前端整段替换，后端不再补 delta
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
                    emitted = True
                    yield _sse({"event": "delta", "text": piece})
            except LLMUnavailable:
                mode = "extractive"
                fallback = build_structured_answer(hits[:top_k]) + suffix
                answer = fallback
                if emitted:
                    # 模型吐了一部分又中断。已推出去的 delta 收不回来，再叠一整段
                    # 就会粘成「半截模型文本 + 完整抽取式答案」；而且 done.answer 只
                    # 等于后者、不等于 delta 拼接，违反本文件开头声明的事件契约。
                    # 改为发一条携带替代正文的 error，前端会清空已渲染文本并整段替换
                    # （见 ChatPanel.handleEvent 的 error 分支）。
                    superseded = True
                    yield _sse({"event": "error", "text": fallback})
                else:
                    for piece in _chunk_text(fallback):
                        yield _sse({"event": "delta", "text": piece})
                        await asyncio.sleep(0.015)

            # 合规后处理与非流式 _compose 对齐：风险提示与模糊引导都并进正文末尾。
            # ⚠️ 必须继续以 delta 推出去，不能只写进 done.answer：
            # 那样前端流式渲染出的文本和 done.answer 就不一致（实测 delta≠answer），
            # 用户看不到提示，还得靠前端二次拼接。
            # 两种情况下跳过：抽取式降级已把 suffix 并进 fallback；error 已整段替换。
            if suffix and not superseded and mode != "extractive":
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
            "retrieval": _retrieval_digest(trace, hits, retrieval_ms),
            "personal": personal_mode,
        }
        if safety:
            done_payload["safetyTip"] = safety
        if hint and not refused:
            done_payload["hint"] = hint
        yield _sse(done_payload)

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})