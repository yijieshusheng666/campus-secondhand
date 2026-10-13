from fastapi import APIRouter
from pydantic import BaseModel

from app.config import settings
from app.deps import knowledge, store
from app.embedding import embedder
from app.knowledge import FAQ_COLLECTION
from app.llm import LLMUnavailable, llm
from app.prompts import RAG_QA, build_context, build_extractive_answer
from app.routers.index import GOODS_COLLECTION

router = APIRouter(prefix="/ai", tags=["chat"])


class HistoryItem(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    question: str
    history: list[HistoryItem] = []
    top_k: int = 4


@router.post("/chat")
def chat(req: ChatRequest):
    question = (req.question or "").strip()
    if not question:
        return {"answer": "请告诉我你想问什么，比如「怎么退款」「有没有便宜的台灯」。",
                "mode": llm.mode(), "sources": []}

    sources = _retrieve(question, req.top_k)
    snippets = [s["snippet"] for s in sources]

    try:
        prompt = RAG_QA.format(context=build_context(snippets), question=question)
        answer = llm.generate(prompt)
        mode = "api"
    except LLMUnavailable:
        answer = build_extractive_answer(snippets)
        mode = "extractive"

    return {"answer": answer, "mode": mode, "sources": sources}


def _retrieve(question: str, top_k: int) -> list[dict]:
    """同时检索 FAQ 与在售商品，合并后按相似度降序取前 top_k。"""
    vector = embedder.embed_texts([question])[0]

    faq_hits = store.search(FAQ_COLLECTION, vector, top_k, settings.sim_threshold)
    goods_hits = store.search(GOODS_COLLECTION, vector, top_k, settings.sim_threshold)

    sources = []
    for hit in faq_hits:
        entry = knowledge.entries.get(hit["id"])
        if entry:
            sources.append({
                "type": "faq",
                "title": entry["title"],
                "snippet": entry["content"],
                "score": hit["score"],
            })
    for hit in goods_hits:
        sources.append({
            "type": "goods",
            "title": hit["text"],
            "snippet": hit["text"],
            "goodsId": hit["id"],
            "score": hit["score"],
        })

    sources.sort(key=lambda s: s["score"], reverse=True)
    return sources[:top_k]
