"""Prompt 模板集中管理，避免散落在业务代码里。

注意：RAG 的模板只在本文件「RAG 客服」段定义一次。
早先版本在文件开头还有一套同名的 RAG_SYSTEM / RAG_QA / build_context，
后面的定义会把它们覆盖掉 —— 留着只会让人改到根本没生效的那一份，已删除。
"""


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
{history}
【回答要求】
1. 直接回答用户的问题。不要复述用户的问题，也不要写「我找到以下相关信息」这类过程性的话；
2. 操作类问题用「第1步…第2步…」一行一步输出，方便用户照着做；
3. 只使用知识条目里的事实。条目里没有的信息，回答「这个我这边没有明确说明，建议问平台管理员」，
   不要用常识推测，也不要编造规则；
4. 涉及「上面提到的那件」「它」这类指代时，结合【最近的对话】理解用户在说什么。

【用户问题】
{question}

【回答】
"""


# ---- 个人数据问答（助手不再只会念规则，也能回答"我的"）----
#
# 之前助手的能力边界是"查静态知识库"，所以「我有几件闲置」「我的求购有人接了吗」
# 这类问题一个都答不了 —— 知识库里根本没有用户数据，检索必然低于拒答阈值，
# 最后只能回一句"没找到依据"。而这恰恰是学生打开二手平台最想问的。
#
# 现在由调用方（前端）把当前登录用户的站内数据压成一小段文本传进来，
# 个人类问题直接走这条路：不再检索知识库、不再判拒答，只让模型基于真实数据回答。

PERSONAL_MARKERS = (
    "我的", "我卖", "我买", "我发布", "我求购", "我下过", "我在售",
    "我有几件", "我出了", "我赚", "我卖了多少", "我有多少", "我的东西",
)


def is_personal_question(question: str, personal_context: str) -> bool:
    """判断这个问题该不该走个人数据通路。

    两个条件都要满足：
      1. 调用方确实带上了站内数据（未登录 / 没数据时不走这条路）；
      2. 问题里有第一人称 + 我的/我的这类指向。
    宁可漏判也不要误判 —— 把"怎么退款"这种通用规则问题错当成个人问题，
    会让模型在一个空的个人数据集上硬编，反而比原来的拒答更糟。
    """
    if not (personal_context or "").strip():
        return False
    return any(m in (question or "") for m in PERSONAL_MARKERS)


PERSONAL_QA = """你是校内二手交易平台的智能助手。下面是**当前登录用户本人**的站内数据。

【我的站内数据】
{personal}
{history}
【回答要求】
1. 只依据上面的「我的站内数据」回答，不要去讲通用规则；
2. 数据里没有的，就直说「你名下没有这类记录」，绝对不要编；
3. 一两句话说清结论。需要列举时最多列 5 条，每条一行，格式「标题 · ¥价格 · 状态」；
4. 结尾可以顺带提一句下一步能做什么（例如去求购广场看看有没有人接），最多一句；
5. 语气简短口语化，不要客套开场白，不要复述用户的问题。

【用户问题】
{question}

【回答】
"""


def build_history_block(history: list[dict] | None) -> str:
    """把最近几轮对话拼成提示词片段，没有历史就返回空串。

    检索侧（lexicon.build_query_variants）已经用历史做查询改写，
    生成侧同样需要：否则「那退款呢」「它多少钱」这类追问进了模型眼里就是孤立的一句，
    模型只能硬答或者反问，多轮上下文等于白传。
    """
    if not history:
        return ""
    lines = []
    for item in history[-6:]:
        content = str(item.get("content", "")).strip()
        if not content:
            continue
        role = "助手" if item.get("role") in ("assistant", "ai") else "用户"
        lines.append(f"{role}：{content}")
    if not lines:
        return ""
    return "\n【最近的对话】\n" + "\n".join(lines) + "\n"

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
     "想卖东西的话建议这样起步：① 数码类要拍开机界面并如实说明瑕疵，写清型号和配件；"
     "② 教材教辅在标题里写清课程名、作者和版次，方便同学搜到；"
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


# 抽取式答案的主体最多取几行：太少答不全，太多又变成"把检索结果糊上去"
EXTRACTIVE_MAX_POINTS = 5


def build_structured_answer(hits) -> str:
    """没有大模型时的抽取式答案。

    原则：内容仍然**逐字来自知识库**（可溯源、不编造），但组织方式要像"回答"而不是"检索结果罗列"：
      - 只用最相关的那一条做主体，内容确实互补时再补一条，不堆三条同类条目；
      - 不写「根据平台规则，我找到以下相关信息」这类过程话，正文直接就是答案；
      - 正文不掺任何「依据/来源/编号」标注 —— 溯源信息由前端单独渲染，混进正文只会被当成噪声。
    """
    if not hits:
        return REFUSAL_ANSWER

    main = hits[0]
    blocks = [_extractive_body(main)]

    # 次优条目只在"讲的不是同一件事"时补进来，避免同主题条目反复说同一句话
    for extra in hits[1:]:
        if _is_duplicate_topic(main, extra):
            continue
        blocks.append(_extractive_body(extra))
        break

    return "\n\n".join(blocks)


def _extractive_body(hit) -> str:
    """条目正文：本来就是分点的原样保留，单段的就直接给这一段。"""
    points = [p.strip() for p in (hit.content or "").split("\n") if p.strip()]
    if not points:
        return hit.title
    return "\n".join(points[:EXTRACTIVE_MAX_POINTS])


def _is_duplicate_topic(a, b) -> bool:
    """两条条目是否在讲同一件事：分片相同 + 标题有重叠词，就认为不必都答。"""
    if getattr(a, "shard", "") != getattr(b, "shard", ""):
        return False
    title_a = set(str(getattr(a, "title", "")))
    title_b = set(str(getattr(b, "title", "")))
    if not title_a or not title_b:
        return False
    return len(title_a & title_b) / max(len(title_a), len(title_b)) > 0.5


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
