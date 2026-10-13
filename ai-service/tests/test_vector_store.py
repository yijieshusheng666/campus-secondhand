from app.vector_store import VectorStore


def _items():
    """三条 3 维向量，便于手算期望结果。"""
    return [
        {"id": 1, "text": "耳机", "vector": [1.0, 0.0, 0.0]},
        {"id": 2, "text": "教材", "vector": [0.0, 1.0, 0.0]},
        {"id": 3, "text": "台灯", "vector": [0.0, 0.0, 1.0]},
    ]


def test_search_returns_most_similar_first(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    results = store.search("goods", [0.9, 0.1, 0.0], top_k=3, min_score=0.0)
    assert results[0]["id"] == 1
    assert results[0]["score"] > results[1]["score"]


def test_min_score_filters_out_low_similarity(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    # 与 id=2 完全正交，相似度为 0
    results = store.search("goods", [1.0, 0.0, 0.0], top_k=3, min_score=0.5)
    assert [r["id"] for r in results] == [1]


def test_upsert_overwrites_same_id(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    store.upsert("goods", [{"id": 1, "text": "新耳机", "vector": [0.0, 0.0, 1.0]}])
    assert store.count("goods") == 3
    results = store.search("goods", [0.0, 0.0, 1.0], top_k=1, min_score=0.0)
    assert results[0]["id"] == 1
    assert results[0]["score"] > 0.99


def test_collections_are_isolated(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    store.rebuild("faq", [{"id": 99, "text": "退款规则", "vector": [1.0, 0.0, 0.0]}])
    assert store.count("goods") == 3
    assert store.count("faq") == 1


def test_rebuild_with_empty_clears(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    store.rebuild("goods", [])
    assert store.count("goods") == 0


def test_persistence_across_instances(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())

    reopened = VectorStore(str(tmp_path))
    assert reopened.count("goods") == 3
    results = reopened.search("goods", [0.9, 0.1, 0.0], top_k=1, min_score=0.0)
    assert results[0]["id"] == 1


def test_search_empty_collection_returns_empty(tmp_path):
    store = VectorStore(str(tmp_path))
    assert store.search("nothing", [1.0, 0.0, 0.0], top_k=5, min_score=0.0) == []


def test_vector_dimension_mismatch_raises(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    try:
        store.search("goods", [1.0, 0.0], top_k=1, min_score=0.0)
        assert False, "维度不一致时应抛错"
    except ValueError:
        pass
