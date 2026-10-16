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
from app.embedding import embedder


def vector_confidence(vec_score: float, bm25_score: float) -> float:
    """向量模式下的置信度：**绝对余弦**走斜坡 + BM25 绝对强命中的小幅加成。

    为什么不能沿用 tfidf 那套「相对本查询最高分」归一：
    两路归一后，本查询的 Top1 天然就是 bm25_norm=1、vec_norm=1，raw 直接 1.0；
    唯一的绝对刹车 abs_factor = min(1, best_vec/0.25) 是按词袋余弦标的，
    而 bge 的余弦普遍在 0.5-0.9，刹车永远不生效 —— 实测「量子纠缠退相干怎么处理」
    也能拿 1.0 被当成正常回答。

    改用绝对余弦后（2026-10-14 实测，65 条知识库）：
      库内问题最高余弦 0.636~0.850；库外 0.319~0.502，斜坡线取 0.45/0.75 能干净分开。
    """
    span = max(settings.vec_ramp_ceil - settings.vec_ramp_floor, 1e-6)
    ramp = (vec_score - settings.vec_ramp_floor) / span
    ramp = min(1.0, max(0.0, ramp))
    bonus = 0.0
    if bm25_score > 0:
        bonus = settings.vec_bonus_weight * min(1.0, bm25_score / max(settings.bm25_strong, 1e-6))
    return round(min(1.0, ramp + bonus), 4)


# 追问信号词：带指代 / 省略的说法，或者信息量很短的问句。
# 命中这些才把上一轮的话题词并进查询 —— 否则会把「怎么退款」这种自足的问题也污染掉。
FOLLOWUP_WORDS = ("那", "它", "这个", "那个", "上面", "刚才", "还有", "呢", "也一样", "同样")
# 短于这个长度才算「信息量不足的追问」：6 字以内。
# 注意是严格小于：「怎么申请退款」正好 6 字，它是自足的问题，不该被上一轮污染。
FOLLOWUP_MAX_LEN = 6


def _is_followup(question: str) -> bool:
    """判断这句话是不是在上文基础上的追问（需要借上一轮的话题词去检索）。"""
    if len(question) < FOLLOWUP_MAX_LEN:
        return True
    return any(word in question for word in FOLLOWUP_WORDS)


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

        # 多路查询：原句 + 词典展开 +（追问时）上一轮用户问句的关键词。
        # 「那大英呢」「它多少钱」这类追问单独拿去检索必然召回不到，
        # 必须把上一轮的话题词并进来。放在第 2 位是为了不被下面的 [:3] 截掉。
        queries = list(prep["variants"])
        if prep["context_terms"] and _is_followup(prep["normalized"]):
            followup = f"{queries[0]} {' '.join(prep['context_terms'][-2:])}".strip()
            if followup not in queries:
                queries.insert(1, followup)
        queries = [q for q in dict.fromkeys(queries) if q][:3]
        trace["queries"] = queries
        if not queries:
            return [], trace

        candidates = self._dual_channel(queries, allowed, trace)

        # 兜底：分片受限时若一条都召回不到，放开全量再找一次。
        # 少了这层，「校园卡可以卖吗」会因为「禁售」意图只放行 rules/faq 分片、而答案
        # C009 落在 campus 分片，于是库里明明收录了却走拒答（实测 BM25 不限分片时 C009
        # 得分 7.09，是强命中）。
        # 只有放开后确实找到了「过线的」东西才采纳：库外问题（如「量子纠缠退相干」）
        # 放开后会召回到 0.2 左右的噪声，那种情况仍应保持拒答，不能把噪声挂上去。
        # 门槛用 refuse_threshold —— 它就是本项目对「算不算找到了答案」的定义。
        if not candidates and allowed is not None:
            probe: dict = {}
            widened = self._dual_channel(queries, None, probe)
            if widened and max(h.confidence for h in widened) >= settings.refuse_threshold:
                trace["shard_fallback"] = True
                trace.update(probe)   # 仅采纳时才把放开检索的链路写回 trace
                candidates = widened

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

    def _dual_channel(self, queries: list[str], allowed: set[str] | None,
                      trace: dict) -> list[Hit]:
        """两路召回（BM25 稀疏 + 向量稠密）→ RRF 融合 → 组装候选。

        allowed 为 None 表示不限分片；否则两路都只在给定 id 集合内取，用于意图/季节
        分片的优先召回。调用方负责在一条都没召回时用 None 重试一次。
        """
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

        # 置信度算法随向量模式切换：词袋用「相对归一 × 绝对刹车」（已按 0.20 阈值标定），
        # 向量用绝对余弦斜坡（词袋的相对归一在向量模式下会饱和失效）
        use_vector_ramp = embedder.mode() == "vector"
        trace["confidenceMode"] = "vector-ramp" if use_vector_ramp else "relative"

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
            if use_vector_ramp:
                # 向量模式：用绝对余弦算置信度（见 vector_confidence 的说明）
                confidence = vector_confidence(vec_score, bm25_score)
            else:
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

        return candidates

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