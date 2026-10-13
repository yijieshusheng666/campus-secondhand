"""内容风控（A6）。

两层：
1. **规则层**（纯本地，永远可用）：违禁品词表 + 导流/诈骗话术 + 手机号/微信号正则。
2. **模型层**（配了 LLM 才走）：让模型补充判断规则没覆盖到的表述。

规则层的设计原则是"宁可漏报也不误杀"：只把明确的违禁品和诈骗话术判为 block，
单纯留联系方式只判 warn，避免正常交易被卡死。
"""

import re

LEVEL_OK = "ok"
LEVEL_WARN = "warn"
LEVEL_BLOCK = "block"

# 明确违禁 / 违规，直接拦截
BLOCK_WORDS = {
    "违禁品": [
        "香烟", "电子烟", "烟弹", "烟油", "打火机油", "白酒", "洋酒", "啤酒",
        "处方药", "麻醉药", "安眠药", "笑气", "管制刀具", "弹簧刀", "甩棍",
        "防狼喷雾", "电击器", "仿真枪", "弩", "假证", "假学生证", "代写论文", "代考",
    ],
    "高风险交易": [
        "刷单", "刷好评", "兼职日结", "日结兼职", "返利", "高额返现",
        "先付定金再", "垫付", "代收货款", "免密支付", "扫码付款", "加微信转账",
        "低价出iPhone", "白条套现", "花呗套现", "洗白",
    ],
}

# 站外导流 / 联系方式，提示但不拦截
WARN_WORDS = {
    "站外导流": ["微信号", "加微信", "加QQ", "加V", "vx", "VX", "QQ号", "抖音号", "小红书",
                 "闲鱼", "转转", "拼多多", "淘宝", "走微店"],
    "联系方式": ["手机号", "电话", "电话联系", "私聊转账"],
}

# 手机号 / 疑似微信号
PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
WECHAT_RE = re.compile(r"(?i)\b(?:wx|vx|wechat|微信号?|v信)[\s:：]*([a-zA-Z][-_a-zA-Z0-9]{5,19})")
QQ_RE = re.compile(r"(?i)\bqq[\s:：]*([1-9]\d{4,10})")


def _scan(text: str, table: dict, level: str) -> list[dict]:
    hits = []
    for category, words in table.items():
        for word in words:
            if word in text:
                hits.append({"type": level, "category": category, "word": word})
    return hits


def moderate(title: str = "", description: str = "") -> dict:
    """返回 {level, hits, message}；level 取 ok / warn / block。"""
    text = f"{title} {description}"

    blocks = _scan(text, BLOCK_WORDS, LEVEL_BLOCK)
    warns = _scan(text, WARN_WORDS, LEVEL_WARN)

    # 正则命中单独加一条，避免把整串号码写进词表
    if PHONE_RE.search(text):
        warns.append({"type": LEVEL_WARN, "category": "联系方式", "word": "疑似手机号"})
    wechat = WECHAT_RE.search(text)
    if wechat:
        warns.append({"type": LEVEL_WARN, "category": "站外导流", "word": f"疑似微信号 {wechat.group(1)}"})
    qq = QQ_RE.search(text)
    if qq:
        warns.append({"type": LEVEL_WARN, "category": "站外导流", "word": f"疑似 QQ 号 {qq.group(1)}"})

    # 去重（同词可能命中多个类目）
    seen = set()
    hits = []
    for hit in blocks + warns:
        key = (hit["type"], hit["word"])
        if key in seen:
            continue
        seen.add(key)
        hits.append(hit)

    if blocks:
        level = LEVEL_BLOCK
        words = "、".join(sorted({h["word"] for h in blocks}))
        message = f"描述里含有平台禁止交易的内容（{words}），请修改后再发布"
    elif warns:
        level = LEVEL_WARN
        words = "、".join(sorted({h["word"] for h in warns}))
        message = f"描述里可能包含站外联系方式或导流信息（{words}）。校内交易建议全程用站内消息沟通，避免被骗"
    else:
        level = LEVEL_OK
        message = "内容检查通过"

    return {"level": level, "hits": hits, "message": message}
