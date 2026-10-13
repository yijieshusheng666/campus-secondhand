from fastapi import APIRouter
from pydantic import BaseModel

from app.config import settings
from app.deps import store
from app.embedding import embedder
from app.routers.index import GOODS_COLLECTION

router = APIRouter(prefix="/ai", tags=["search"])


class SearchRequest(BaseModel):
    query: str
    top_k: int = 5
    min_score: float | None = None


@router.post("/search")
def search(req: SearchRequest):
    threshold = settings.sim_threshold if req.min_score is None else req.min_score
    vector = embedder.embed_texts([req.query])[0]
    hits = store.search(GOODS_COLLECTION, vector, req.top_k, threshold)
    return {
        "mode": embedder.mode(),
        "results": [{"goodsId": h["id"], "score": h["score"]} for h in hits],
    }
