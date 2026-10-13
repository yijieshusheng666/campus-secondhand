"""图片识别自动发布（A3）。

需要多模态模型。本机没有可用的视觉模型，所以做成**可配置的接入层**：
配了 `VISION_BASE_URL / VISION_API_KEY / VISION_MODEL` 就走真实识别（OpenAI 兼容协议，
智谱 glm-4v 之类同样适用），没配就如实返回 unavailable，由前端提示用户手动填写。

这样接口与界面都到位，一旦补上模型 key 立刻可用，不需要再改代码。
"""

import base64
import json
import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

CATEGORIES = ["电子产品", "图书教材", "生活用品", "运动户外", "二手家具", "美妆护肤"]

VISION_PROMPT = (
    "你是校园二手平台的发布助手。看这张商品照片，输出一个 JSON 对象，不要任何多余文字：\n"
    '{"title": "建议的商品标题（不超过 20 字，含品牌型号更好）",'
    ' "category": "从这些里选一个：' + "、".join(CATEGORIES) + '",'
    ' "condition": "成色描述，如 九五新",'
    ' "description": "一句话商品描述（不超过 40 字）"}'
)


class VisionUnavailable(Exception):
    """没有配置多模态模型。"""


def available() -> bool:
    return settings.vision_enabled


def recognize(image_base64: str, mime: str = "image/jpeg") -> dict:
    if not available():
        raise VisionUnavailable("未配置 VISION_BASE_URL / VISION_API_KEY / VISION_MODEL")

    payload = {
        "model": settings.vision_model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": VISION_PROMPT},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:{mime};base64,{image_base64}"},
                    },
                ],
            }
        ],
        "temperature": 0.2,
    }
    headers = {"Authorization": f"Bearer {settings.vision_api_key}"}

    try:
        resp = httpx.post(
            f"{settings.vision_base_url}/chat/completions",
            json=payload,
            headers=headers,
            timeout=settings.llm_timeout,
        )
        resp.raise_for_status()
        content = resp.json()["choices"][0]["message"]["content"]
    except Exception as exc:
        logger.warning("图片识别调用失败：%s", exc)
        raise VisionUnavailable(str(exc)) from exc

    return _parse(content)


def _parse(content: str) -> dict:
    """模型可能把 JSON 包在 ```json 里，容错解析，失败就返回空字段。"""
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        try:
            data = json.loads(text[start:end + 1])
            category = str(data.get("category", "")).strip()
            return {
                "title": str(data.get("title", "")).strip(),
                "category": category if category in CATEGORIES else "",
                "condition": str(data.get("condition", "")).strip(),
                "description": str(data.get("description", "")).strip(),
            }
        except json.JSONDecodeError:
            pass
    return {"title": "", "category": "", "condition": "", "description": ""}


def raw_size(image_base64: str) -> int:
    try:
        return len(base64.b64decode(image_base64))
    except Exception:
        return 0
