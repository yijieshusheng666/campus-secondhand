import numpy as np

from app.embedding import Embedder


def test_dim_is_positive():
    e = Embedder()
    assert e.dim() > 0


def test_embed_texts_shape_and_normalization():
    e = Embedder()
    vectors = e.embed_texts(["降噪耳机", "高等数学教材"])
    assert len(vectors) == 2
    assert len(vectors[0]) == e.dim()
    # L2 归一化：模长应为 1
    assert abs(np.linalg.norm(vectors[0]) - 1.0) < 1e-5


def test_embed_empty_list():
    e = Embedder()
    assert e.embed_texts([]) == []


def test_semantic_similarity_ranking():
    """同类文本的相似度应高于跨类文本——这是词袋基线的基本能力。"""
    e = Embedder()
    base, similar, different = e.embed_texts([
        "降噪耳机",
        "头戴式降噪耳机",
        "高等数学教材",
    ])
    base, similar, different = (
        np.array(base), np.array(similar), np.array(different)
    )
    assert float(base @ similar) > float(base @ different)


def test_mode_is_tfidf_in_test_env():
    e = Embedder()
    assert e.mode() == "tfidf"
