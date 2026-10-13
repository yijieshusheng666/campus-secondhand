"""Prompt 模板集中管理，避免散落在业务代码里。"""

RAG_SYSTEM = (
    "你是校园二手交易平台的智能助手，只依据提供的资料回答问题。"
    "资料中没有的内容不要编造，可以建议用户换个说法再问。回答要简短、口语化。"
)

RAG_QA = """请只依据下面的资料回答用户问题。

资料：
{context}

用户问题：{question}

回答："""


def build_extractive_answer(snippets: list[str]) -> str:
    """抽取式降级：不做生成，直接拼装检索到的原文片段。"""
    if not snippets:
        return "抱歉，暂时没有找到相关资料。可以换个说法再问我，比如直接说商品名称或想问的业务（例如「怎么退款」）。"
    lines = "\n".join(f"{i + 1}. {s}" for i, s in enumerate(snippets))
    return f"根据平台资料，我找到以下相关信息：\n{lines}\n\n如果还需要更详细的说明，可以继续问我～"


def build_context(snippets: list[str]) -> str:
    return "\n".join(f"- {s}" for s in snippets)
