from app.assist import extract_prices, negotiate_scripts, summarize


def _chat(*texts):
    return [{"role": "user" if i % 2 else "peer", "content": t} for i, t in enumerate(texts)]


def test_summarize_extracts_price_and_meeting():
    messages = _chat(
        "同学你好，耳机还在吗？",
        "在的，300 元",
        "能便宜点吗，我是同校的",
        "最低 280，明天下午三点图书馆门口给你",
    )
    result = summarize(messages, "索尼 WH-1000XM5", 2899)
    assert result["stats"]["message_count"] == 4
    assert "300" in result["stats"]["prices"]
    assert "280" in result["stats"]["prices"]
    assert any("图书馆" in m for m in result["stats"]["meetings"])
    assert "2899" in result["summary"]
    assert len(result["points"]) >= 3


def test_summarize_ignores_non_price_numbers():
    """「95新」「42码」不是价格，不能被当成报价。"""
    result = summarize(_chat("95新，42 码", "包邮吗"), "Nike 鞋")
    assert result["stats"]["prices"] == []


def test_summarize_without_price():
    result = summarize(_chat("在吗", "在的"), "台灯", 399)
    assert "还没谈到价格" in result["summary"]
    assert result["points"]


def test_summarize_empty_conversation():
    result = summarize([], "台灯")
    assert result["stats"]["message_count"] == 0
    assert result["summary"]


def test_summarize_flags_open_question():
    result = summarize(_chat("300 元", "能便宜点吗？"), "耳机", 300)
    assert any("待确认" in p for p in result["points"])


def test_summarize_does_not_repeat_meeting_as_open_question():
    """同一句话既像约定又像提问时，不要在两处重复出现。"""
    result = summarize(_chat("300 元", "能明天下午三点图书馆门口看看吗？"), "耳机", 300)
    assert sum(1 for p in result["points"] if "图书馆" in p) == 1


def test_extract_prices_deduplicates():
    assert extract_prices(["300 元可以", "300 元太贵了"]) == ["300"]


def test_negotiate_scripts_three_levels():
    scripts = negotiate_scripts("索尼耳机", 2899)
    assert [s["label"] for s in scripts] == ["礼貌试探", "理由压价", "收尾成交"]
    assert all(s["text"] for s in scripts)
    # 价格按 85% / 92% 折算，不能出现小数尾巴
    assert "2464" in scripts[1]["text"]
    assert "2667" in scripts[2]["text"]


def test_negotiate_without_price_still_works():
    scripts = negotiate_scripts("旧课本")
    assert len(scripts) == 3
    assert all("旧课本" in s["text"] or s["text"] for s in scripts)


def test_summarize_endpoint_uses_rule_mode(client):
    resp = client.post("/ai/summarize", json={
        "messages": [
            {"role": "peer", "content": "耳机 300 元"},
            {"role": "mine", "content": "能便宜点吗"},
        ],
        "goodsTitle": "索尼耳机",
        "goodsPrice": 300,
    })
    assert resp.status_code == 200
    body = resp.json()
    assert body["mode"] == "rule"
    assert body["summary"]
    assert len(body["points"]) >= 3


def test_negotiate_endpoint_uses_rule_mode(client):
    resp = client.post("/ai/negotiate", json={"goodsTitle": "台灯", "goodsPrice": 399})
    assert resp.status_code == 200
    body = resp.json()
    assert body["mode"] == "rule"
    assert len(body["scripts"]) == 3
