from fastapi import APIRouter
from pydantic import BaseModel

from app.embedding import embedder

router = APIRouter(prefix="/ai", tags=["embedding"])


class EmbedRequest(BaseModel):
    texts: list[str]


@router.post("/embed")
def embed(req: EmbedRequest):
    vectors = embedder.embed_texts(req.texts)
    # 空输入时 dim 仍要给出确定值，供调用方预分配
    return {"dim": embedder.dim(), "vectors": vectors}
