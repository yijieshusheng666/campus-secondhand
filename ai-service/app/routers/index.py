from fastapi import APIRouter
from pydantic import BaseModel

from app.deps import store
from app.embedding import embedder

router = APIRouter(prefix="/ai/index", tags=["index"])

GOODS_COLLECTION = "goods"


class GoodsItem(BaseModel):
    goodsId: int
    text: str


class IndexRequest(BaseModel):
    items: list[GoodsItem]


def _to_vectors(items: list[GoodsItem]) -> list[dict]:
    if not items:
        return []
    vectors = embedder.embed_texts([item.text for item in items])
    return [
        {"id": item.goodsId, "text": item.text, "vector": vectors[i]}
        for i, item in enumerate(items)
    ]


@router.post("")
def upsert_index(req: IndexRequest):
    store.upsert(GOODS_COLLECTION, _to_vectors(req.items))
    return {"success": True, "indexed": len(req.items), "total": store.count(GOODS_COLLECTION)}


@router.post("/rebuild")
def rebuild_index(req: IndexRequest):
    store.rebuild(GOODS_COLLECTION, _to_vectors(req.items))
    return {"success": True, "total": store.count(GOODS_COLLECTION)}
