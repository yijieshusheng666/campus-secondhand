"""对话摘要与议价话术。

设计原则与 P0 底座一致：核心逻辑是**纯函数、不感知 HTTP、不依赖外部服务**，
所以断网也能用；配了 LLM 时由路由层换成模型生成。
"""

import re

# 价格：抓「300」「300元」「¥300」「300 块」等写法
PRICE_RE = re.compile(r"(?:¥|￥)?\s?(\d{1,6}(?:\.\d{1,2})?)\s?(?:元|块钱|块)?")
# 时间词与地点词，用于识别「见面约定」
TIME_WORDS = ("今天", "明天", "后天", "今晚", "上午", "中午", "下午", "晚上",
              "周末", "周一", "周二", "周三", "周四", "周五", "周六", "周日",
              "星期", "点", "号")
PLACE_WORDS = ("宿舍", "楼下", "食堂", "图书馆", "门口", "教学楼", "体育馆",
               "操场", "快递", "驿站", "超市")


def _contents(messages: list[dict]) -> list[str]:
    return [str(m.get("content", "")).strip() for m in messages if str(m.get("content", "")).strip()]


def extract_prices(texts: list[str], limit: int = 3) -> list[str]:
    """从对话里抽出出现过的价格，按出现顺序去重。"""
    found: list[str] = []
    for text in texts:
        for match in PRICE_RE.finditer(text):
            value = match.group(1)
            # 过滤掉明显不是价格的数字（如「95新」「42码」「8成新」）
            tail = text[match.end():match.end() + 1]
            if tail in ("新", "码", "成"):
                continue
            if value not in found:
                found.append(value)
            if len(found) >= limit:
                return found
    return found


def extract_meetings(texts: list[str], limit: int = 2) -> list[str]:
    """抽出像「约定见面」的句子：同时含时间词与地点词。"""
    meetings: list[str] = []
    for text in texts:
        if any(w in text for w in TIME_WORDS) and any(w in text for w in PLACE_WORDS):
            if len(text) > 40:
                text = text[:40] + "…"
            meetings.append(text)
            if len(meetings) >= limit:
                break
    return meetings


def extract_open_question(texts: list[str]) -> str:
    """找最后一条像提问的消息，作为「还没定」的事项。"""
    for text in reversed(texts):
        if text.endswith(("?", "？", "吗", "呢")) or "能不能" in text or "可不可以" in text:
            return text if len(text) <= 60 else text[:60] + "…"
    return ""


def _fmt_price(value) -> str:
    """价格显示：2899.0 -> 2899，避免出现「2899.0 元」这种别扭写法。"""
    try:
        return f"{float(value):g}"
    except (TypeError, ValueError):
        return str(value)


def summarize(messages: list[dict], goods_title: str = "", goods_price=None) -> dict:
    """规则版摘要：统计 + 价格 + 见面约定 + 未定事项。"""
    texts = _contents(messages)
    title = goods_title or "这件宝贝"

    if not texts:
        return {
            "summary": "这段对话还没有内容，先和对方打个招呼吧。",
            "points": [],
            "stats": {"message_count": 0},
        }

    prices = extract_prices(texts)
    meetings = extract_meetings(texts)
    open_question = extract_open_question(texts)

    head = f"你和对方围绕「{title}」一共聊了 {len(texts)} 句。"
    if goods_price not in (None, ""):
        head += f"挂牌价 {_fmt_price(goods_price)} 元。"
    if prices:
        head += f"对话里出现过 {' / '.join(p + ' 元' for p in prices)}。"
    else:
        head += "目前还没谈到价格。"
    if not meetings:
        head += "见面时间地点还没约定。"

    points = []
    if prices:
        points.append(f"聊到的价格：{' / '.join(p + ' 元' for p in prices)}")
    else:
        points.append("还没谈价格，可以主动问一句能否优惠")
    for meeting in meetings:
        points.append(f"见面约定：{meeting}")
    if not meetings:
        points.append("还没约定见面时间地点")
    if open_question and open_question not in meetings:
        points.append(f"待确认：{open_question}")
    elif not open_question:
        points.append("没有待确认的提问")

    return {
        "summary": head,
        "points": points,
        "stats": {"message_count": len(texts), "prices": prices, "meetings": meetings},
    }


def negotiate_scripts(goods_title: str = "", goods_price=None, description: str = "") -> list[dict]:
    """议价话术：三档递进，每档都能直接复制发出去。

    `description` 供 LLM 路径引用（规则路径用不到），保留以统一签名。
    """
    title = goods_title or "这件宝贝"
    try:
        price = float(goods_price) if goods_price not in (None, "") else None
    except (TypeError, ValueError):
        price = None

    if price is None:
        return [
            {"label": "礼貌试探", "text": f"同学你好～「{title}」还在吗？价格方便商量一下吗？"},
            {"label": "理由压价", "text": f"我是同校的，可以自己过去取，省了你发货的麻烦，「{title}」能便宜一点吗？"},
            {"label": "收尾成交", "text": "那你报个最低价吧，合适我现在就下单，咱们约个时间我过去拿～"},
        ]

    soft = round(price * 0.9)
    anchor = round(price * 0.85)
    final = round(price * 0.92)

    return [
        {
            "label": "礼貌试探",
            "text": f"同学你好～「{title}」还在吗？挂的是 {price:g} 元，有点超我预算，能便宜一点吗？",
        },
        {
            "label": "理由压价",
            "text": (
                f"我是同校的，可以今天就自己过去取，省得你打包发货。"
                f"「{title}」{anchor:g} 元出吗？成色要是和图片一致我马上拍。"
            ),
        },
        {
            "label": "收尾成交",
            "text": f"那咱们各让一步，{final:g} 元我立刻下单，约个时间我到你楼下拿～",
        },
    ]
