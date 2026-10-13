from fastapi import APIRouter
from pydantic import BaseModel

from app.llm import LLMUnavailable, llm
from app.moderation import LEVEL_BLOCK, LEVEL_OK, LEVEL_WARN, moderate

router = APIRouter(prefix="/ai", tags=["moderate"])


class ModerateRequest(BaseModel):
    title: str = ""
    description: str = ""


@router.post("/moderate")
def moderate_content(req: ModerateRequest):
    """内容风控：规则层永远可用，配了 LLM 再叠加一次模型判断。"""
    result = moderate(req.title, req.description)
    mode = "rule"

    if llm.available():
        try:
            verdict = _llm_check(req.title, req.description)
            mode = "api"
            if verdict == LEVEL_BLOCK and result["level"] != LEVEL_BLOCK:
                result["level"] = LEVEL_BLOCK
                result["message"] = "模型判断这段描述可能涉及违规内容，请修改后再发布"
            elif verdict == LEVEL_WARN and result["level"] == LEVEL_OK:
                result["level"] = LEVEL_WARN
                result["message"] = "模型判断这段描述可能有风险，请检查后再发布"
        except LLMUnavailable:
            pass

    return {**result, "mode": mode}


def _llm_check(title: str, description: str) -> str:
    """让模型只回一个等级词，解析失败就当 ok，不影响主流程。"""
    from app.prompts import MODERATE_QA
    answer = llm.generate(MODERATE_QA.format(title=title, description=description))
    lowered = answer.strip().lower()
    if LEVEL_BLOCK in lowered or "违禁" in answer or "禁止" in answer:
        return LEVEL_BLOCK
    if LEVEL_WARN in lowered or "风险" in answer or "可疑" in answer:
        return LEVEL_WARN
    return LEVEL_OK
