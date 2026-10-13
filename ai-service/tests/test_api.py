def test_health_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_knowledge_base_loads_and_retrieves(client):
    from app.deps import knowledge

    assert len(knowledge.entries) >= 20

    hits = knowledge.search("怎么退款", top_k=3)
    assert hits, "「怎么退款」应能命中知识库"
    assert any("退款" in h["title"] or "退款" in h["snippet"] for h in hits)


def test_knowledge_search_returns_score(client):
    from app.deps import knowledge

    hits = knowledge.search("怎么发布闲置", top_k=3)
    assert hits
    assert hits[0]["score"] > 0


def test_embed_returns_vectors(client):
    resp = client.post("/ai/embed", json={"texts": ["降噪耳机", "高等数学"]})
    assert resp.status_code == 200
    body = resp.json()
    assert body["dim"] > 0
    assert len(body["vectors"]) == 2
    assert len(body["vectors"][0]) == body["dim"]


def test_index_then_search(client):
    rebuild = client.post("/ai/index/rebuild", json={"items": [
        {"goodsId": 8, "text": "索尼 WH-1000XM5 头戴式耳机 电子产品 行业领先的降噪"},
        {"goodsId": 9, "text": "高等数学 第七版 图书教材 同济大学数学系编"},
        {"goodsId": 10, "text": "飞利浦台灯 生活用品 LED 护眼灯"},
    ]})
    assert rebuild.status_code == 200
    assert rebuild.json()["total"] == 3

    resp = client.post("/ai/search", json={"query": "降噪耳机", "top_k": 3, "min_score": 0.0})
    assert resp.status_code == 200
    results = resp.json()["results"]
    assert results[0]["goodsId"] == 8


def test_index_upsert_replaces(client):
    client.post("/ai/index/rebuild", json={"items": [
        {"goodsId": 1, "text": "旧标题 耳机"},
    ]})
    client.post("/ai/index", json={"items": [
        {"goodsId": 1, "text": "新标题 台灯"},
    ]})
    resp = client.post("/ai/search", json={"query": "标题", "top_k": 5, "min_score": 0.0})
    assert len(resp.json()["results"]) == 1


def test_chat_falls_back_to_extractive_without_api_key(client):
    resp = client.post("/ai/chat", json={"question": "怎么退款", "top_k": 3})
    assert resp.status_code == 200
    body = resp.json()
    assert body["mode"] == "extractive"
    assert body["answer"]
    assert body["sources"], "应返回引用来源"


def test_chat_with_empty_question_still_answers(client):
    resp = client.post("/ai/chat", json={"question": "", "top_k": 3})
    assert resp.status_code == 200
    assert resp.json()["answer"]


def test_health_reports_real_modes(client):
    body = client.get("/health").json()
    assert body["embedding"]["mode"] in ("vector", "tfidf")
    assert body["llm"]["mode"] in ("api", "extractive")
    assert "count" in body["index"]
