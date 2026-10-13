"""RAG 客服专项测试（需求规格 §6 验收）：

覆盖查询预处理、BM25 稀疏检索、RRF 融合、置信度拒答、来源溯源、
风险提示、模糊引导、多轮上下文、SSE 流式、运营端知识管理与统计。
"""

from app.bm25 import BM25Index, tokenize
from app.lexicon import build_query_variants, normalize, strip_noise
from app.retrieval import rrf_fuse


# ---------------- 查询预处理 ----------------


def test_normalize_strips_noise_and_fullwidth():
    assert strip_noise("请问一下怎么退款呀") == "怎么退款"
    assert normalize("ＡＢＣ　１２３") == "ABC 123"
    assert normalize("问问问退款") == "问退款"      # 叠词压缩


def test_campus_alias_expansion():
    prep = build_query_variants("线代教材多少钱")
    aliases = {item["alias"] for item in prep["alias_hits"]}
    assert "线代" in aliases
    assert any("线性代数" in v for v in prep["variants"]), "应生成词典展开后的查询变体"


def test_intent_and_shard_routing():
    prep = build_query_variants("怎么退款")
    assert "售后" in prep["intents"]
    assert "rules" in prep["shard_hints"]

    prep = build_query_variants("大英教材多少钱")
    assert "价格" in prep["intents"] and "教材" in prep["intents"]
    assert "reference" in prep["shard_hints"]


def test_season_detection():
    assert "毕业季" in build_query_variants("毕业卖什么最划算")["seasons"]
    assert "开学季" in build_query_variants("开学要买教材")["seasons"]


def test_tokenize_drops_function_words():
    tokens = tokenize("请问一下怎么退款呀")
    assert "退款" in tokens
    assert "请问" not in tokens and "呀" not in tokens


# ---------------- BM25 ----------------


def test_bm25_ranks_exact_match_first():
    index = BM25Index()
    index.build([
        {"id": "A", "text": "如何申请退款 退款流程说明"},
        {"id": "B", "text": "如何完成学号实名认证"},
        {"id": "C", "text": "推荐面交地点"},
    ])
    hits = index.search("退款怎么申请", top_k=3)
    assert hits[0]["id"] == "A"
    assert "退款" in hits[0]["hits"]


def test_bm25_incremental_add_and_remove():
    index = BM25Index()
    index.build([{"id": "A", "text": "退款规则"}])
    assert index.search("退货") == [], "索引里还没有退货条目，不该命中"

    index.add("D", "退货与售后规则")
    assert any(h["id"] == "D" for h in index.search("退货"))

    index.remove("D")
    assert not any(h["id"] == "D" for h in index.search("退货"))
    assert len(index) == 1


def test_bm25_allowed_ids_filter():
    index = BM25Index()
    index.build([{"id": "A", "text": "退款"}, {"id": "B", "text": "退款"}])
    hits = index.search("退款", allowed_ids={"A"})
    assert [h["id"] for h in hits] == ["A"]


# ---------------- RRF ----------------


def test_rrf_fuses_two_rankings():
    fused = rrf_fuse([["A", "B"], ["B", "A"]], k=60)
    assert fused["A"] == fused["B"]          # 两路名次相同则得分相同
    fused = rrf_fuse([["A", "B"], ["B", "C"]], k=60)
    assert fused["B"] > fused["A"] > fused["C"]


# ---------------- 问答行为 ----------------


def test_chat_attaches_source_and_confidence(client):
    body = client.post("/ai/chat", json={"question": "怎么退款"}).json()
    assert body["refused"] is False
    assert body["sources"], "规则类回答必须挂来源"
    first = body["sources"][0]
    assert first["id"] and first["source"], "每条来源要带知识ID与溯源展示名"
    assert first["evidence"], "每条来源要说明为什么被召回"
    assert body["traceId"]


def test_chat_refuses_out_of_scope(client):
    for question in ["量子计算机怎么组装", "今天天气怎么样", "帮我订一张去北京的机票"]:
        body = client.post("/ai/chat", json={"question": question}).json()
        assert body["refused"] is True, f"「{question}」应拒答"
        assert body["mode"] == "refused"
        assert body["answer"]


def test_chat_appends_safety_tip(client):
    body = client.post("/ai/chat", json={"question": "能不能私下转账给他"}).json()
    assert body.get("safetyTip"), "转账类问题应追加安全提示"
    assert "转账" in body["answer"]


def test_chat_gives_hint_for_vague_question(client):
    body = client.post("/ai/chat", json={"question": "想卖东西"}).json()
    assert body.get("hint"), "宽泛问题应给出场景化建议"
    assert "教材" in body["hint"]


def test_chat_uses_history_for_short_followup(client):
    body = client.post("/ai/chat", json={
        "question": "那大英呢",
        "history": [
            {"role": "user", "content": "线代教材多少钱"},
            {"role": "assistant", "content": "20-45 元"},
        ],
    }).json()
    titles = [s["title"] for s in body["sources"]]
    assert any("教材" in t or "价格" in t for t in titles), "应借助上一轮上下文命中教材价格"


def test_chat_structured_steps_for_how_to(client):
    body = client.post("/ai/chat", json={"question": "怎么发布二手商品"}).json()
    assert body["refused"] is False
    assert "第1步" in body["answer"], "操作指引类应分点输出"


# ---------------- SSE ----------------


def test_sse_stream_emits_meta_delta_done(client):
    resp = client.post("/ai/chat/stream", json={"question": "怎么退款"})
    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]

    events = []
    text = ""
    for line in resp.text.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        import json
        payload = json.loads(line[5:])
        events.append(payload["event"])
        if payload["event"] == "delta":
            text += payload["text"]
    assert events[0] == "meta" and events[-1] == "done"
    assert "delta" in events, "应推送文本增量"
    assert text.strip(), "增量拼起来应组成完整回答"


def test_sse_stream_refuses_out_of_scope(client):
    resp = client.post("/ai/chat/stream", json={"question": "量子计算机怎么组装"})
    assert "refused" in resp.text


# ---------------- 运营端 ----------------


def test_ops_lists_shards_and_entries(client):
    shards = client.get("/ai/ops/kb/shards").json()
    assert len(shards["data"]) == 5
    assert shards["total"] >= 60

    entries = client.get("/ai/ops/kb/entries", params={"shard": "rules"}).json()
    assert entries["data"] and all(e["shard"] == "rules" for e in entries["data"])


def test_ops_create_then_retrievable(client):
    created = client.post("/ai/ops/kb/entries", headers={"X-Operator": "tester"}, json={
        "shard": "faq", "title": "测试新增知识条目",
        "content": "这是一条通过运营接口新增的测试知识，用于验证增量向量化是否生效。",
        "tags": ["测试"], "source": "自动化测试",
    }).json()
    assert created["success"] and created["created"] is True

    # 新增后应立刻能被检索到（BM25 增量同步）
    from app.deps import knowledge
    hits, _ = knowledge.search("增量向量化是否生效", top_k=3)
    assert any(h.id == created["id"] for h in hits)

    audit = client.get("/ai/ops/kb/audit", params={"limit": 5}).json()
    assert any(a["action"] == "create" and a["operator"] == "tester" for a in audit["data"])

    deleted = client.delete(f"/ai/ops/kb/entries/{created['id']}",
                            headers={"X-Operator": "tester"}).json()
    assert deleted["success"]
    hits, _ = knowledge.search("增量向量化是否生效", top_k=3)
    assert not any(h.id == created["id"] for h in hits)


def test_ops_reindex_and_stats(client):
    rebuilt = client.post("/ai/ops/kb/reindex", headers={"X-Operator": "tester"}).json()
    assert rebuilt["success"] and rebuilt["count"] >= 60

    summary = client.get("/ai/ops/stats/summary").json()["data"]
    assert summary["total"] > 0
    assert "successRate" in summary and "refused" in summary
    assert summary["retrieval"] == "BM25 + 向量 + RRF"

    top = client.get("/ai/ops/stats/top-questions", params={"limit": 5}).json()["data"]
    assert isinstance(top, list)

    csv_resp = client.get("/ai/ops/stats/misses.csv")
    assert csv_resp.status_code == 200
    assert "text/csv" in csv_resp.headers["content-type"]