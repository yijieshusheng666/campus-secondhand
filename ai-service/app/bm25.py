"""BM25 稀疏检索（需求规格 §2.2.2「BM25 稀疏检索解决专有名词、短文本匹配」）。

自己实现而不是引 rank_bm25，原因：
1. 该库不支持中文分词，需要外部喂 tokens，多一层依赖；
2. 增量更新（运营端改一条知识）需要自己维护倒排，rank_bm25 不提供；
3. 答辩时"检索为什么这样打分"要能讲清楚公式，而不是背一个黑盒。

公式（标准 BM25）：
    score(q,d) = Σ_t IDF(t) · (f(t,d)·(k1+1)) / (f(t,d) + k1·(1 - b + b·|d|/avgdl))
    IDF(t)     = ln(1 + (N - n(t) + 0.5) / (n(t) + 0.5))
"""

import math
from collections import Counter

from app.lexicon import strip_noise

# 检索时统一丢弃的高频虚词（保留否定与疑问，它们对判定拒答很重要）
BM25_STOPWORDS = {
    "的", "了", "吗", "呢", "啊", "吧", "是", "在", "和", "与", "或", "我", "你", "他",
    "这", "那", "个", "一", "就", "都", "也", "很", "要", "会", "能", "可以", "什么",
    "怎么", "如何", "哪", "多少", "为什么", "请问", "一下", "没有", "不", "没",
}


def tokenize(text: str) -> list[str]:
    """jieba 分词 + 丢弃虚词与单字。

    丢弃单字是关键：jieba 会把「退相干」切成「退 / 相干」，单字「退」就能命中
    「退款」，实测让「量子纠缠退相干是怎么回事」以 0.6 置信度答成了退款流程。
    中文检索里单字的噪声远大于收益，只有数字/型号这类整体有意义的短词才保留。
    """
    import jieba

    cleaned = strip_noise(text or "")
    tokens = []
    for token in jieba.cut(cleaned):
        token = token.strip()
        if not token or token in BM25_STOPWORDS:
            continue
        if len(token) == 1 and not token.isdigit():
            continue
        tokens.append(token.lower())
    return tokens


class BM25Index:
    """支持整体重建（build）与单条增量（add）。"""

    def __init__(self, k1: float = 1.5, b: float = 0.75):
        self.k1 = k1
        self.b = b
        self._doc_tokens: dict[str, list[str]] = {}
        self._doc_freq: dict[str, Counter] = {}   # token -> 出现在多少文档
        self._tf: dict[str, Counter] = {}         # doc_id -> token 计数
        self._len_sum = 0

    # ---- 构建 / 增量 ----

    def build(self, docs: list[dict]) -> None:
        """整体重建。docs 每项需含 id 与可检索文本。"""
        self._doc_tokens.clear()
        self._doc_freq.clear()
        self._tf.clear()
        self._len_sum = 0
        for doc in docs:
            self._index_one(str(doc["id"]), doc.get("text", ""))

    def add(self, doc_id: str, text: str) -> None:
        """单条新增或替换（运营端改知识后增量更新用）。"""
        if doc_id in self._doc_tokens:
            self.remove(doc_id)
        self._index_one(doc_id, text)

    def remove(self, doc_id: str) -> None:
        tokens = self._doc_tokens.pop(doc_id, None)
        if tokens is None:
            return
        for token in set(tokens):
            freq = self._doc_freq.get(token)
            if freq is not None:
                freq[doc_id] -= 1
                if freq[doc_id] <= 0:
                    del self._doc_freq[token]
        self._tf.pop(doc_id, None)
        self._len_sum -= len(tokens)

    def _index_one(self, doc_id: str, text: str) -> None:
        tokens = tokenize(text)
        if not tokens:
            tokens = ["\u0000empty"]
        counts = Counter(tokens)
        self._doc_tokens[doc_id] = tokens
        self._tf[doc_id] = counts
        for token in counts:
            self._doc_freq.setdefault(token, Counter())[doc_id] = 1
        self._len_sum += len(tokens)

    # ---- 检索 ----

    def __len__(self) -> int:
        return len(self._doc_tokens)

    def idf(self, token: str) -> float:
        n_docs = len(self._doc_tokens)
        if n_docs == 0:
            return 0.0
        n_tok = len(self._doc_freq.get(token, ()))
        return math.log(1 + (n_docs - n_tok + 0.5) / (n_tok + 0.5))

    def search(self, query: str, top_k: int = 20,
                allowed_ids: set[str] | None = None) -> list[dict]:
        """返回 [{id, score, hits}]，hits 是命中的关键词（用于可解释性）。"""
        if not self._doc_tokens:
            return []
        avgdl = self._len_sum / len(self._doc_tokens) or 1.0
        query_tokens = tokenize(query)
        results = []

        for doc_id, tokens in self._doc_tokens.items():
            if allowed_ids is not None and doc_id not in allowed_ids:
                continue
            tf = self._tf[doc_id]
            doc_len = len(tokens)
            score = 0.0
            hits = []
            for token in query_tokens:
                freq = tf.get(token, 0)
                if not freq:
                    continue
                idf = self.idf(token)
                denom = freq + self.k1 * (1 - self.b + self.b * doc_len / avgdl)
                score += idf * (freq * (self.k1 + 1)) / denom
                hits.append(token)
            if score > 0:
                results.append({"id": doc_id, "score": round(score, 4), "hits": hits})

        results.sort(key=lambda item: item["score"], reverse=True)
        return results[:top_k]