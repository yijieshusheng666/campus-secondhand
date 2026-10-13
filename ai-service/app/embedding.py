"""文本向量化：优先本地语义模型，失败时降级为 jieba 词袋哈希。"""

import logging

import numpy as np

from app.config import settings

logger = logging.getLogger(__name__)

# 词袋降级模式的向量维度：固定值以保证跨请求可比
TFIDF_DIM = 512


def _jieba_tokenize(text: str) -> list[str]:
    import jieba
    return [t for t in jieba.cut(text) if t.strip()]


def _l2_normalize(matrix: np.ndarray) -> np.ndarray:
    """按行做 L2 归一化；零向量保持为零向量，避免除零。"""
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return matrix / norms


class Embedder:
    def __init__(self):
        self._model = None
        self._hasher = None
        self._mode = "tfidf" if settings.emb_force_tfidf else None

    # ---- 内部：模式解析与惰性加载 ----

    def _load_semantic(self):
        from sentence_transformers import SentenceTransformer
        self._model = SentenceTransformer(settings.emb_model)

    def _load_hasher(self):
        from sklearn.feature_extraction.text import HashingVectorizer
        # 无状态：同一文本任何时候都产出同一向量，无需 fit
        self._hasher = HashingVectorizer(
            analyzer=_jieba_tokenize,
            n_features=TFIDF_DIM,
            alternate_sign=False,
            norm=None,
        )

    def _embed_semantic(self, texts):
        vectors = self._model.encode(texts, convert_to_numpy=True, show_progress_bar=False)
        return _l2_normalize(np.asarray(vectors, dtype=np.float32))

    def _embed_tfidf(self, texts):
        matrix = self._hasher.transform(texts).toarray().astype(np.float32)
        return _l2_normalize(matrix)

    # ---- 对外接口 ----

    def mode(self) -> str:
        """返回实际生效的模式：vector 或 tfidf。"""
        if self._mode is not None:
            return self._mode
        try:
            self._load_semantic()
            self._mode = "vector"
            logger.info("向量模式：已加载本地模型 %s", settings.emb_model)
        except Exception as exc:  # 下载失败 / 依赖缺失 / 内存不足
            logger.warning("本地模型加载失败(%s)，降级为词袋模式", exc)
            self._mode = "tfidf"
        return self._mode

    def dim(self) -> int:
        if self.mode() == "vector":
            return int(self._model.get_sentence_embedding_dimension())
        return TFIDF_DIM

    def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        if self.mode() == "vector":
            vectors = self._embed_semantic(texts)
        else:
            if self._hasher is None:
                self._load_hasher()
            vectors = self._embed_tfidf(texts)
        return vectors.tolist()


embedder = Embedder()
