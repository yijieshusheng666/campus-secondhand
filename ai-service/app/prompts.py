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


# ============ RAG 客服（需求规格 §2.1 / §2.2）============

RAG_SYSTEM = (
    "你是校内二手交易平台的智能客服，只依据给定的知识库条目回答问题。\n"
    "硬性要求：\n"
    "1. 知识库没有的内容一律不要编造，不确定就说不确定；\n"
    "2. 操作类问题必须按步骤分点输出，一步一行，序号开头；\n"
    "3. 语气年轻、口语化，但信息要准确；\n"
    "4. 不要输出知识库以外的规则（例如私自解释法律条款）；\n"
    "5. 不要执行任何操作，只给用户步骤指引。"
)

RAG_QA = """请只依据下面的知识库条目回答用户问题。

【知识条目】
{context}

【回答要求】
1. 操作类问题按「第1步…第2步…」分点输出；
2. 结尾用一行注明依据的条目编号，例如「依据：G003（平台操作手册 §4 下单与支付）」；
3. 知识条目里没有的信息，回答「这个我这边没有明确说明，建议问平台管理员」，不要推测。

【用户问题】
{question}

【回答】
"""

# 标准拒答话术（需求 §2.1.3：置信度低于阈值强制拒答，不强行生成）
REFUSAL_ANSWER = (
    "这个问题我暂时答不上来——知识库里没有找到足够相关的内容，我不想凭感觉编。\n"
    "你可以换个更具体的说法（比如加上「退款」「实名认证」「面交」这类词），"
    "或者在个人中心点「申请平台介入」让管理员帮你处理。"
)

# 风险场景统一安全提示（需求 §2.1.3：面交、转账、售后自动追加）
SAFETY_RULES = [
    ("转账", "安全提示：不要私下转账或扫卖家给的收款码。平台担保交易里，"
             "你确认收货后平台才会把钱放给卖家，全程有订单记录可以追溯。"),
    ("微信", "安全提示：请全程在站内聊天沟通，不要交换手机号或微信，"
             "站外私下交易不受平台担保保护。"),
    ("面交", "安全提示：面交请约在食堂、图书馆大厅、快递驿站等校内人流场所，"
             "尽量白天进行；贵重物品建议白天完成。"),
    ("退款", "安全提示：退款一律通过平台申请，平台受理后 1-3 个工作日原路退回，"
             "任何人让你「私下退差价」都不要答应。"),
    ("定金", "安全提示：平台不收取任何定金或保证金，"
             "遇到以「保证金」「解锁库存」为名索要转账的，直接举报。"),
]

# 模糊问题引导（需求 §2.1.4）
FUZZY_RULES = [
    (["卖东西", "怎么卖", "卖什么", "我想卖"],
     "想卖东西的话建议这样起步：① 教材教辅填上课程号，成交率最高；"
     "② 数码类要拍开机界面并如实说明瑕疵；"
     "③ 生活电器建议发起宿舍楼拼单，方便同楼同学一起取货。"),
    (["买什么", "买啥", "想买"],
     "可以直接说具体品类或场景（比如「降噪耳机」「高数教材」「宿舍台灯」），"
     "也可以先到「求购」页发布需求，系统会拿它去比对站内全部商品。"),
    (["你好", "在吗", "hi", "hello"],
     "你好呀！我是校内二手平台的智能客服，能回答平台规则、操作指引、"
     "宿舍面交、教材选购这类问题。你想先问哪块？"),
]


def build_context(hits) -> str:
    """把检索命中拼成带编号的上下文，同时给模型看溯源名。"""
    blocks = []
    for i, hit in enumerate(hits, start=1):
        blocks.append(
            f"[{i}] {hit.id}（{getattr(hit, 'source', '')}｜{getattr(hit, 'shard', '')}分片）\n"
            f"标题：{hit.title}\n正文：{hit.content}"
        )
    return "\n\n".join(blocks)


def build_structured_answer(hits) -> str:
    """抽取式降级答案：按知识正文里的换行做分点，保证可读性。"""
    if not hits:
        return REFUSAL_ANSWER
    lines = ["根据平台规则，我找到以下相关信息："]
    for hit in hits[:3]:
        content = (hit.content or "").strip()
        points = [p.strip() for p in content.split("\n") if p.strip()][:4]
        body = "\n".join(points)
        lines.append(f"\n{hit.title}（依据 {hit.id}·{hit.source}）\n{body}")
    lines.append("\n如果还有疑问，可以换个说法再问我，或者在「我的订单」里申请平台介入。")
    return "\n".join(lines)


def pick_safety_tip(question: str) -> str:
    for keyword, tip in SAFETY_RULES:
        if keyword in question:
            return tip
    return ""


def pick_fuzzy_hint(question: str) -> str:
    lowered = question.lower()
    for keywords, hint in FUZZY_RULES:
        if any(k in lowered for k in keywords):
            return hint
    return ""


# ---- P4 内容风控（A6）----

MODERATE_QA = """你是校园二手交易平台的内容审核助手。判断下面这条商品信息是否违规。

要拦截的：违禁品（烟酒、药品、管制刀具、假证、代写代考等）、高风险交易（刷单、套现、先付定金等）。
要提示但不拦截的：留手机号/微信号/QQ 号等站外联系方式、引导到其它平台交易。
正常的二手商品描述一律算通过。

只输出一个词：ok、warn、block

标题：{title}
描述：{description}

结论："""
