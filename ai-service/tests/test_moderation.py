from app.moderation import LEVEL_BLOCK, LEVEL_OK, LEVEL_WARN, moderate


def test_normal_description_passes():
    result = moderate("索尼 WH-1000XM5 头戴式耳机", "九五新，降噪很好，包装齐全")
    assert result["level"] == LEVEL_OK
    assert result["hits"] == []


def test_banned_goods_are_blocked():
    result = moderate("出电子烟一支", "还剩两盒烟弹")
    assert result["level"] == LEVEL_BLOCK
    assert any("电子烟" in h["word"] for h in result["hits"])
    assert "修改" in result["message"]


def test_fraud_words_are_blocked():
    result = moderate("兼职日结", "帮店铺刷单，一单返利 20，先付定金")
    assert result["level"] == LEVEL_BLOCK


def test_phone_number_is_warned_not_blocked():
    result = moderate("台灯转让", "有意联系 13800001234")
    assert result["level"] == LEVEL_WARN
    assert any("手机号" in h["word"] for h in result["hits"])


def test_wechat_id_is_warned():
    result = moderate("出高数书", "加微信 abc12345 详聊")
    assert result["level"] == LEVEL_WARN
    assert any("微信" in h["word"] for h in result["hits"])


def test_block_wins_over_warn():
    """同时有违禁品和联系方式时，按最严重的判。"""
    result = moderate("出管制刀具", "电话联系")
    assert result["level"] == LEVEL_BLOCK


def test_offsite_platform_is_warned():
    result = moderate("MacBook", "也可以去闲鱼上搜我")
    assert result["level"] == LEVEL_WARN


def test_moderate_endpoint_uses_rule_mode(client):
    resp = client.post("/ai/moderate", json={"title": "出电子烟", "description": ""})
    assert resp.status_code == 200
    body = resp.json()
    assert body["mode"] == "rule"
    assert body["level"] == LEVEL_BLOCK
    assert body["hits"]


def test_vision_reports_unavailable_without_model(client):
    """A3 依赖多模态模型；未配置时要如实返回不可用，而不是报错。"""
    resp = client.post("/ai/vision", json={"image": "aGVsbG8=", "mime": "image/jpeg"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["available"] is False
    assert body["message"]
