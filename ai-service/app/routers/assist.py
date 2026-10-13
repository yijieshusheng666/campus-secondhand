from fastapi import APIRouter
from pydantic import BaseModel

from app import assist
from app.llm import LLMUnavailable, llm
from app.prompts import NEGOTIATE_QA, SUMMARY_QA, build_transcript

router = APIRouter(prefix="/ai", tags=["assist"])


class MessageItem(BaseModel):
    role: str = "user"
    content: str


class SummarizeRequest(BaseModel):
    messages: list[MessageItem] = []
    goodsTitle: str = ""
    goodsPrice: float | None = None


class NegotiateRequest(BaseModel):
    goodsTitle: str = ""
    goodsPrice: float | None = None
    description: str = ""
    tone: str = ""


@router.post("/summarize")
def summarize(req: SummarizeRequest):
    """对话摘要：规则抽取兜底，配了 LLM 就换成模型总结。"""
    messages = [m.model_dump() for m in req.messages]
    base = assist.summarize(messages, req.goodsTitle, req.goodsPrice)

    if llm.available():
        try:
            goods = (
                f"{req.goodsTitle}（{assist._fmt_price(req.goodsPrice)} 元）"
                if req.goodsTitle
                else "未提供"
            )
            prompt = SUMMARY_QA.format(goods=goods, transcript=build_transcript(messages))
            return {**base, "summary": llm.generate(prompt), "mode": "api"}
        except LLMUnavailable:
            pass

    return {**base, "mode": "rule"}


@router.post("/negotiate")
def negotiate(req: NegotiateRequest):
    """议价话术：规则模板兜底，配了 LLM 就换成模型生成。"""
    if llm.available():
        try:
            prompt = NEGOTIATE_QA.format(
                goods=req.goodsTitle or "未提供",
                price=assist._fmt_price(req.goodsPrice) if req.goodsPrice else "未提供",
                description=req.description or "无",
            )
            scripts = _parse_scripts(llm.generate(prompt))
            if scripts:
                return {"scripts": scripts, "mode": "api"}
        except LLMUnavailable:
            pass

    return {
        "scripts": assist.negotiate_scripts(req.goodsTitle, req.goodsPrice, req.description),
        "mode": "rule",
    }


def _parse_scripts(text: str) -> list[dict]:
    """解析「标签：话术」格式的模型输出；解析不出来就放弃，回落到规则话术。"""
    scripts = []
    for line in text.splitlines():
        line = line.strip().lstrip("-•* 0123456789.")
        if "：" in line or ":" in line:
            label, _, content = line.replace("：", ":", 1).partition(":")
            if content.strip():
                scripts.append({"label": label.strip(), "text": content.strip()})
    return scripts[:3]
