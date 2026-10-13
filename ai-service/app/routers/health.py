from fastapi import APIRouter

from app.config import settings
from app.deps import store
from app.embedding import embedder
from app.llm import llm
from app.routers.index import GOODS_COLLECTION

router = APIRouter()


@router.get("/health")
def health():
    return {
        "status": "ok",
        "embedding": {
            "mode": embedder.mode(),
            "dim": embedder.dim(),
            "model": settings.emb_model,
        },
        "llm": {"mode": llm.mode(), "model": settings.llm_model or None},
        "index": {"count": store.count(GOODS_COLLECTION)},
    }
