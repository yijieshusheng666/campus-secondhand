"""混合检索引擎：BM25 稀疏 + 向量稠密 → RRF 融合 → 重排（需求规格 §2.2.2）。

关于 CrossEncoder：需求里要求用 CrossEncoder 做最终重排。本项目**没有**引入，
原因与替代方案见 README「与需求文档的差异」一节：一是要下载 cross-encoder 模型
（约 400MB，本机网络不可达），二是它是黑盒、无法在答辩里解释打分依据。
这里用「RRF 融合 + 双通道证据 + 相对分裁剪」替代，效果可解释、可单测。
"""

from dataclasses import dataclass, field

from app import lexicon
from app.bm25 import BM25Index
from app.config import settings


@dataclass
class Hit:
    """一条命中的知识条目。"""

    id: str
    title: str
    content: str
    source: str
    shard: str
    tags: list[str] = field(default_factory=list)
    updated: str = ""
    bm25_rank: int = 0
    vec_rank: int = 0
    bm25_score: float = 0.0
    vec_score: float = 0.0
    bm25_hits: list[str] = field(default_factory=list)
    rrf_score: float = 0.0
    confidence: float = 0.0
    evidence: str = ""


def rrf_fuse(rankings: list[list[str]], k: int = 60) -> dict[str, float]:
    """Reciprocal Rank Fusion：多路排序按名次倒数加权合并。"""
    fused: dict[str, float] = {}
    for ranking in rankings:
        for position, doc_id in enumerate(ranking, start=1):
            fused[doc_id] = fused.get(doc_id, 0.0) + 1.0 / (k + position)
    return fused


class HybridRetriever:
    """依赖外部注入的 BM25 索引、知识条目表与向量检索能力，便于单测替换。"""

    def __init__(self, bm25: BM25Index, entries: dict[str, dict], vec_search):
        self.bm25 = bm25
        self.entries = entries            # {id: {title, content, source, shard, tags, updated}}
        self.vec_search = vec_search      # callable(query: str, top_k: int, min_score: float) -> [{id, score}]

    # ---- 主流程 ----

    def search(self, raw_question: str, history=None, top_k: int = 4) -> tuple[list[Hit], dict]:
        prep = lexicon.build_query_variants(raw_question, history)
        trace = {
            "normalized": prep["normalized"],
            "intents": prep["intents"],
            "seasons": prep["seasons"],
            "shard_hints": prep["shard_hints"],
            "alias_hits": prep["alias_hits"],
            "queries": [],
            "bm25_top": [],
            "vec_top": [],
            "fused": [],
            "dropped_by_rerank": [],
        }

        # 意图/季节命中的分片会被优先召回：先只在这些分片里找，找不到再放开全量
        preferred = prep["shard_hints"] or prep["boost_shards"]
        allowed = self._ids_of_shards(preferred) if preferred else None

        # 多路查询：原句 + 词典展开 +（问句过短时）上一轮核心词
        queries = list(prep["variants"])
        if len(queries[-1]) < 6 and prep["context_terms"]:
            queries.append(queries[-1] + " " + " ".join(prep["context_terms"][-2:]))
        queries = [q for q in dict.fromkeys(queries) if q][:3]
        trace["queries"] = queries
        if not queries:
            return [], trace

        # 通道一：BM25 稀疏
        bm25_pool: dict[str, dict] = {}
        for query in queries:
            for item in self.bm25.search(query, top_k=settings.bm25_top_k, allowed_ids=allowed):
                prev = bm25_pool.get(item["id"])
                if prev is None or item["score"] > prev["score"]:
                    bm25_pool[item["id"]] = item
        bm25_ranked = sorted(bm25_pool.items(), key=lambda kv: -kv[1]["score"])
        trace["bm25_top"] = [(doc_id, item["score"]) for doc_id, item in bm25_ranked[:5]]

        # 通道二：向量稠密
        vec_pool: dict[str, float] = {}
        for query in queries:
            for item in self.vec_search(query, settings.vec_top_k, settings.vec_min_score):
                doc_id = str(item["id"])
                if allowed is not None and doc_id not in allowed:
                    continue
                vec_pool[doc_id] = max(vec_pool.get(doc_id, 0.0), float(item["score"]))
        vec_ranked = sorted(vec_pool.items(), key=lambda kv: -kv[1])
        trace["vec_top"] = vec_ranked[:5]

        # RRF 融合
        fused = rrf_fuse(
            [[doc_id for doc_id, _ in bm25_ranked], [doc_id for doc_id, _ in vec_ranked]],
            k=settings.rrf_k,
        )

        # 通道内归一化，用于计算可解释的置信度
        best_bm25 = bm25_ranked[0][1]["score"] if bm25_ranked else 0.0
        best_vec = vec_ranked[0][1] if vec_ranked else 0.0
        bm25_hits_map = {doc_id: item["hits"] for doc_id, item in bm25_pool.items()}

        # 组装候选
        candidates: list[Hit] = []
        bm25_rank_map = {doc_id: i + 1 for i, (doc_id, _) in enumerate(bm25_ranked)}
        vec_rank_map = {doc_id: i + 1 for i, (doc_id, _) in enumerate(vec_ranked)}

        # 绝对可信度：最高向量分太低时，"相对排第一"并不代表真的像。
        # 只做相对归一会把噪声放大：实测「帮我订一张去北京的机票」向量分仅 0.09，
        # 归一后变 1.0，置信度 0.4 越过拒答阈值，答成了饭卡能不能交易。
        abs_factor = 1.0 if best_vec <= 0 else min(
            1.0, best_vec / max(settings.vec_confidence_ref, 1e-6))
        abs_factor = max(abs_factor, 0.15)   # 再低也留一线，避免误杀真实命中
        trace["absFactor"] = round(abs_factor, 4)

        for doc_id, rrf in fused.items():
            entry = self.entries.get(doc_id)
            if entry is None:
                continue
            bm25_score = bm25_pool[doc_id]["score"] if doc_id in bm25_pool else 0.0
            vec_score = vec_pool.get(doc_id, 0.0)

            bm25_norm = bm25_score / best_bm25 if best_bm25 > 0 else 0.0
            vec_norm = 0.0
            if best_vec > settings.vec_min_score:
                vec_norm = max(0.0, (vec_score - settings.vec_min_score)
                               / (best_vec - settings.vec_min_score))
            raw_confidence = 0.6 * bm25_norm + 0.4 * vec_norm
            # 两路都没证据的条目直接丢弃，别把 0 分的凑数条目返给前端
            if bm25_score <= 0 and vec_score <= settings.vec_min_score:
                continue
            confidence = round(raw_confidence * abs_factor, 4)

            hit = Hit(
                id=doc_id,
                title=entry.get("title", ""),
                content=entry.get("content", ""),
                source=entry.get("source", ""),
                shard=entry.get("shard", ""),
                tags=entry.get("tags", []),
                updated=entry.get("updated", ""),
                bm25_rank=bm25_rank_map.get(doc_id, 0),
                vec_rank=vec_rank_map.get(doc_id, 0),
                bm25_score=round(bm25_score, 4),
                vec_score=round(vec_score, 4),
                bm25_hits=bm25_hits_map.get(doc_id, []),
                rrf_score=round(rrf, 6),
                confidence=confidence,
            )
            hit.evidence = self._evidence(hit)
            candidates.append(hit)

        # 重排：按置信度排序后，砍掉明显低分的尾部（相对分裁剪，跨模式都成立）
        candidates.sort(key=lambda h: (-h.confidence, h.id))
        if candidates:
            floor = candidates[0].confidence * settings.rerank_min_ratio
            kept = []
            for hit in candidates:
                if kept and hit.confidence < floor:
                    trace["dropped_by_rerank"].append(
                        {"id": hit.id, "title": hit.title, "confidence": hit.confidence})
                    continue
                kept.append(hit)
                if len(kept) >= settings.max_hits:
                    break
            candidates = kept

        trace["fused"] = [(h.id, h.confidence) for h in candidates[:5]]
        return candidates[:top_k], trace

    # ---- 辅助 ----

    def _ids_of_shards(self, shards: list[str]) -> set[str]:
        return {
            doc_id for doc_id, entry in self.entries.items()
            if entry.get("shard") in shards
        }

    def _evidence(self, hit: Hit) -> str:
        """人能看懂的一句话依据：为什么这条被召回。"""
        parts = []
        if hit.bm25_rank:
            kw = "、".join(hit.bm25_hits[:4])
            parts.append(f"关键词命中「{kw}」" if kw else "关键词通道召回")
        if hit.vec_rank:
            parts.append(f"语义相似 {hit.vec_score:.2f}")
        if not parts:
            parts.append("RRF 融合进入")
        return " ｜ ".join(parts) + f" ｜ 置信度 {hit.confidence:.2f}"