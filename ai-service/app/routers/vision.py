from fastapi import APIRouter
from pydantic import BaseModel

from app.vision import VisionUnavailable, available, recognize, raw_size

router = APIRouter(prefix="/ai", tags=["vision"])

MAX_IMAGE_BYTES = 5 * 1024 * 1024


class VisionRequest(BaseModel):
    """image 传 base64（不含 data URI 前缀），mime 例如 image/jpeg。"""

    image: str
    mime: str = "image/jpeg"


@router.post("/vision")
def vision(req: VisionRequest):
    """图片识别自动发布（A3）。未配置多模态模型时如实返回 available=false。"""
    if not available():
        return {
            "available": False,
            "mode": "none",
            "message": "图片识别需要配置多模态模型（VISION_BASE_URL / VISION_API_KEY / VISION_MODEL），"
                       "当前未配置，请手动填写商品信息",
        }

    size = raw_size(req.image)
    if size == 0:
        return {"available": False, "mode": "none", "message": "图片数据无效"}
    if size > MAX_IMAGE_BYTES:
        return {"available": False, "mode": "none", "message": "图片超过 5MB，请压缩后再试"}

    try:
        result = recognize(req.image, req.mime)
    except VisionUnavailable as exc:
        return {"available": False, "mode": "none", "message": f"图片识别暂时不可用：{exc}"}

    return {"available": True, "mode": "api", **result,
            "message": "已根据图片预填，请核对后再发布"}
