"""查询预处理：校园专有名词词典、口语化改写、意图与分片路由。

需求来源：需求规格 §2.2.1「查询预处理」——文本纠错、口语化改写、校园专有名词词典、
意图识别与知识分片匹配。
"""

import re

# —— 校园口语 / 简称 → 规范词（学生日常说法与课程简称）——
ALIASES = {
    "线代": "线性代数",
    "高数": "高等数学",
    "大英": "大学英语",
    "大物": "大学物理",
    "马原": "马克思主义基本原理",
    "毛概": "毛泽东思想和中国特色社会主义理论体系概论",
    "思修": "思想道德与法治",
    "四六级": "英语四六级",
    "专四": "英语专业四级",
    "毕设": "毕业设计",
    "中区": "中区宿舍",
    "西区": "西区宿舍",
    "东区": "东区宿舍",
    "南苑": "南苑宿舍",
    "研一": "研究生公寓",
    "耳机": "耳机",
    "充电宝": "移动电源",
    "充电线": "数据线",
    "自习": "自习室",
    "三方": "三方",
    "咸鱼": "闲鱼",
    "微信": "微信",
    "加微": "微信",
    "转转": "转转",
    "被宰": "被骗",
    "坑人": "欺诈",
}

# —— 常见错别字 / 拼音误写 → 正确词 ——
TYPOS = {
    "帐": "账",
    "帐款": "账款",
    "付了钱": "已付款",
    "怎么退钱": "如何退款",
    "钱啥时候到": "货款什么时候到账",
    "看不到货": "未收到货",
    "对不上": "与描述不符",
    "假一赔十": "假货赔付承诺",
}

# 语气词与客套话，问答场景里没有检索价值
NOISE = [
    "请问一下", "想问下", "想问一下", "麻烦问一下", "帮我看看", "怎么办啊",
    "呀", "啊", "呢", "吧", "嘛", "哦", "嗯", "谢谢", "感谢", "你好",
    "在吗", "有人吗", "求助", "急",
]

FULLWIDTH_MAP = {chr(i): chr(i - 0xFEE0) for i in range(0xFF01, 0xFF5F)}
FULLWIDTH_MAP["　"] = " "


def normalize(text: str) -> str:
    """全角转半角、去标点空白、压缩叠词（问问问 → 问）。"""
    if not text:
        return ""
    out = "".join(FULLWIDTH_MAP.get(ch, ch) for ch in text)
    out = re.sub(r"[\s]+", " ", out).strip()
    out = re.sub(r"(.)\1{2,}", r"\1", out)   # 叠词压缩
    return out


def correct_typos(text: str) -> str:
    for wrong, right in TYPOS.items():
        text = text.replace(wrong, right)
    return text


def strip_noise(text: str) -> str:
    for phrase in NOISE:
        text = text.replace(phrase, "")
    return re.sub(r"\s+", " ", text).strip()


def expand_aliases(text: str) -> list[str]:
    """命中词典的简称替换成规范词；同时保留原句（两路都参与检索）。"""
    expanded = text
    hits = []
    for alias, formal in ALIASES.items():
        if alias in text:
            expanded = expanded.replace(alias, f"{formal} {alias}")
            hits.append((alias, formal))
    variants = [text]
    if expanded != text:
        variants.append(expanded)
    return variants, hits


# —— 意图识别：决定检索时优先看哪些知识分片 ——
INTENT_PATTERNS = [
    ("售后", ["退款", "退货", "退钱", "不发货", "没收到", "纠纷", "仲裁", "维权", "投诉"]),
    ("账号", ["账号", "实名", "认证", "学号", "昵称", "密码", "注销", "信用分", "资料"]),
    ("操作", ["怎么", "如何", "步骤", "流程", "操作", "在哪", "教程", "怎么弄"]),
    ("价格", ["多少钱", "价格", "定价", "参考价", "便宜", "贵", "区间", "估价"]),
    ("面交", ["面交", "见面", "在哪交", "配送", "快递", "宿舍", "楼栋", "取货"]),
    ("禁售", ["禁售", "能卖吗", "可以卖", "违规", "禁止", "限售"]),
    ("教材", ["教材", "课本", "书", "高数", "线代", "大英", "课程"]),
]

INTENT_SHARDS = {
    "售后": ["rules", "faq", "guides"],
    "账号": ["rules", "faq", "guides"],
    "操作": ["guides", "faq"],
    "价格": ["reference", "faq"],
    "面交": ["campus", "faq", "rules"],
    "禁售": ["rules", "faq"],
    "教材": ["guides", "reference", "campus", "faq"],
}

SEASON_PATTERNS = {
    "毕业季": ["毕业", "毕设", "离校", "搬走", "清仓", "毕业卖"],
    "开学季": ["开学", "新生", "军训", "大二", "大三", "教材回收"],
}

SEASON_BOOST_SHARDS = {
    "毕业季": ["campus"],
    "开学季": ["campus", "reference", "guides"],
}


def detect_intents(text: str) -> list[str]:
    return [name for name, keys in INTENT_PATTERNS if any(k in text for k in keys)]


def detect_seasons(text: str) -> list[str]:
    return [name for name, keys in SEASON_PATTERNS.items() if any(k in text for k in keys)]


def shard_hints(text: str) -> list[str]:
    """意图 → 优先检索的知识分片；识别不到就返回空列表（检索全部分片）。"""
    hints = []
    for intent in detect_intents(text):
        for shard in INTENT_SHARDS.get(intent, []):
            if shard not in hints:
                hints.append(shard)
    return hints


def season_boost(text: str) -> list[str]:
    boosted = []
    for season in detect_seasons(text):
        for shard in SEASON_BOOST_SHARDS.get(season, []):
            if shard not in boosted:
                boosted.append(shard)
    return boosted


def build_query_variants(raw_question: str, history=None) -> dict:
    """完整预处理流水线，返回检索用的查询变体与诊断信息。"""
    base = normalize(raw_question)
    typo_fixed = correct_typos(base)
    cleaned = strip_noise(typo_fixed)
    variants, alias_hits = expand_aliases(cleaned)

    # 多轮改写：当前问句太短或带指代时，把上一轮的核心词带过来（解决「那它呢」这类追问）
    # 只取**用户自己**说过的话：助手那一轮是整段回答，并进查询会把关键词稀释掉
    # （实测把「参考区间 15-30 元。」并进去后，置信度反而从 0.3+ 掉到 0.29）。
    context_terms = []
    if history:
        for turn in reversed(history[-3:]):
            if not isinstance(turn, dict):
                continue
            role = turn.get("role")
            if role and role not in ("user", "我"):
                continue
            content = turn.get("content") or ""
            if not content or content.strip() == raw_question.strip():
                continue
            # 截断：用户问句一般很短，留 30 字足够带住话题，也避免长句污染查询
            context_terms.append(correct_typos(normalize(content))[:30])

    intents = detect_intents(cleaned)
    seasons = detect_seasons(cleaned)

    return {
        "raw": raw_question,
        "normalized": cleaned,
        "variants": variants,
        "context_terms": context_terms,
        "alias_hits": [{"alias": a, "formal": f} for a, f in alias_hits],
        "intents": intents,
        "seasons": seasons,
        "shard_hints": shard_hints(cleaned),
        "boost_shards": season_boost(cleaned),
    }