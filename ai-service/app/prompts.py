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


# ---- P1 助手类能力（A5）----

SUMMARY_QA = """下面是校园二手平台上买家与卖家的沟通记录，请用中文总结。

要求：
1. 先用一句话概括这段对话在谈什么；
2. 再列出 2-4 条要点，覆盖：谈到的价格、见面约定的时间地点、还没确认的事项；
3. 不要编造记录里没有的信息。

商品：{goods}

沟通记录：
{transcript}

总结："""


NEGOTIATE_QA = """你是帮大学生在校园二手平台上讲价的助手。请针对下面这件商品写 3 条议价话术。

要求：
1. 三条分别对应「礼貌试探」「理由压价」「收尾成交」，语气自然口语化，不要显得难缠；
2. 每条不超过 80 字，可以直接复制发给卖家；
3. 在同校当面取货这点上做文章（省去卖家发货的麻烦）；
4. 只输出三行，每行格式为「标签：话术」，不要其它解释。

商品：{goods}
价格：{price} 元
描述：{description}

话术："""


def build_transcript(messages: list[dict]) -> str:
    """把消息列表拼成 LLM 可读的对话记录。"""
    lines = []
    for m in messages:
        role = "买家" if m.get("role") in ("mine", "user", "buyer", "我") else "卖家"
        content = str(m.get("content", "")).strip()
        if content:
            lines.append(f"{role}：{content}")
    return "\n".join(lines)
