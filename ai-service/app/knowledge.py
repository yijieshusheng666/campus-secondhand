"""知识库加载与混合检索入口。

需求规格 §3：知识分为 5 个分片（平台规则 / 操作指引 / 高频FAQ / 校园场景 / 商品参考），
每条知识包含 知识ID、所属分片、标题、正文、标签、更新时间、溯源展示名。
"""

import json
import logging
import os

from app.bm25 import BM25Index
from app.config import settings
from app.embedding import embedder
from app.retrieval import Hit, HybridRetriever
from app.vector_store import VectorStore

logger = logging.getLogger(__name__)

# 分片加载顺序：越靠前越权威，检索结果按 RRF 融合后仍以排名体现差异
SHARD_FILES = {
    "rules": "rules.json",
    "guides": "guides.json",
    "faq": "faq.json",
    "campus": "campus.json",
    "reference": "reference.json",
}

# 检索时拼进向量的文本（标题与标签加权，正文为主体）
VECTOR_TEXT_TEMPLATE = "{title} {tags} {content}"


class KnowledgeBase:
    def __init__(self, store: VectorStore):
        self.store = store
        self.entries: dict[str, dict] = {}
        self.shards: dict[str, list[str]] = {}
        self._raw: dict[str, dict] = {}     # 分片的原始 JSON 载荷，用于回写
        self._files: dict[str, str] = {}    # shard -> 文件路径
        self.bm25 = BM25Index()
        self._retriever: HybridRetriever | None = None

    # ---- 加载 ----

    def load(self) -> int:
        """读取全部分片 → 建 BM25 索引 + 建向量索引。返回知识总条数。"""
        self.entries.clear()
        self.shards.clear()
        self._raw.clear()
        self._files.clear()

        for shard, filename in SHARD_FILES.items():
            path = os.path.join(settings.knowledge_dir, filename)
            if not os.path.exists(path):
                logger.warning("知识分片缺失：%s", filename)
                continue
            with open(path, encoding="utf-8") as f:
                payload = json.load(f)
            self._raw[shard] = payload
            self._files[shard] = path

            ids = []
            for item in payload.get("items", []):
                doc_id = str(item["id"])
                entry = {
                    "id": doc_id,
                    "shard": shard,
                    "shard_name": payload.get("name", shard),
                    "title": item.get("title", ""),
                    "content": item.get("content", ""),
                    "tags": item.get("tags", []),
                    "source": item.get("source", payload.get("name", shard)),
                    "updated": item.get("updated", ""),
                }
                self.entries[doc_id] = entry
                ids.append(doc_id)
            self.shards[shard] = ids

        # 稀疏索引：标题 + 正文 + 标签
        docs = [
            {
                "id": doc_id,
                "text": f"{entry['title']} {' '.join(entry['tags'])} {entry['content']}",
            }
            for doc_id, entry in self.entries.items()
        ]
        self.bm25.build(docs)

        # 稠密索引：同一批文档写入向量库
        texts = [
            VECTOR_TEXT_TEMPLATE.format(
                title=e["title"], tags=" ".join(e["tags"]), content=e["content"])
            for e in self.entries.values()
        ]
        vectors = embedder.embed_texts(texts)
        self.store.rebuild(
            settings.kb_collection,
            [
                {"id": doc_id, "text": texts[i], "vector": vectors[i]}
                for i, doc_id in enumerate(self.entries)
            ],
        )

        self._retriever = HybridRetriever(self.bm25, self.entries, self._vec_search)
        logger.info("知识库已装载 %d 条（分片：%s）",
                    len(self.entries), ", ".join(self.shards))
        return len(self.entries)

    def _vec_search(self, query: str, top_k: int, min_score: float) -> list[dict]:
        vector = embedder.embed_texts([query])[0]
        threshold = min_score if min_score is not None else settings.sim_threshold
        return self.store.search(settings.kb_collection, vector, top_k, threshold)

    # ---- 检索 ----

    @property
    def retriever(self) -> HybridRetriever:
        if self._retriever is None:
            self._retriever = HybridRetriever(self.bm25, self.entries, self._vec_search)
        return self._retriever

    def search(self, question: str, history=None, top_k: int = 4) -> tuple[list[Hit], dict]:
        """混合检索。返回 (命中列表, 检索链路 trace)。"""
        return self.retriever.search(question, history=history, top_k=top_k)

    def entry(self, doc_id: str) -> dict:
        return self.entries.get(doc_id, {})

    def all_entries(self) -> list[dict]:
        return list(self.entries.values())

    def count(self) -> int:
        return len(self.entries)

    def list_entries(self, shard: str | None = None, keyword: str | None = None) -> list[dict]:
        items = list(self.entries.values())
        if shard:
            items = [e for e in items if e.get("shard") == shard]
        if keyword:
            key = keyword.strip()
            items = [e for e in items
                     if key in e["title"] or key in e["content"]
                     or any(key in t for t in e["tags"])]
        return sorted(items, key=lambda e: (e.get("shard", ""), e["id"]))

    def shards_info(self) -> list[dict]:
        return [
            {
                "shard": shard,
                "name": self._raw.get(shard, {}).get("name", shard),
                "description": self._raw.get(shard, {}).get("description", ""),
                "count": len(ids),
            }
            for shard, ids in self.shards.items()
        ]

    # ---- 运营端：单条增删改后增量同步两种索引 ----

    def upsert(self, doc_id: str, entry: dict) -> None:
        """新增或更新一条知识，同时增量更新 BM25 与向量索引，并回写分片文件。"""
        existed = doc_id in self.entries
        self.entries[doc_id] = entry
        shard = entry.get("shard", "faq")
        if doc_id not in self.shards.setdefault(shard, []):
            self.shards[shard].append(doc_id)

        text = f"{entry.get('title', '')} {' '.join(entry.get('tags', []))} {entry.get('content', '')}"
        self.bm25.add(doc_id, text)
        vector_text = VECTOR_TEXT_TEMPLATE.format(
            title=entry.get("title", ""), tags=" ".join(entry.get("tags", [])),
            content=entry.get("content", ""))
        self.store.upsert(settings.kb_collection, [{
            "id": doc_id, "text": vector_text,
            "vector": embedder.embed_texts([vector_text])[0],
        }])
        self._sync_raw(entry, doc_id)
        self._retriever = HybridRetriever(self.bm25, self.entries, self._vec_search)
        return not existed     # True 表示新增

    def delete(self, doc_id: str) -> bool:
        if doc_id not in self.entries:
            return False
        entry = self.entries[doc_id]
        shard = entry.get("shard")
        del self.entries[doc_id]
        self.shards[shard] = [i for i in self.shards.get(shard, []) if i != doc_id]
        self.bm25.remove(doc_id)

        items = self._raw.get(shard, {}).get("items")
        if isinstance(items, list):
            self._raw[shard]["items"] = [i for i in items if str(i.get("id")) != str(doc_id)]
        self._persist(shard)

        # 知识库条数不多，删除时直接全量重建向量索引，保证不留孤儿向量
        self.store.rebuild(settings.kb_collection, [
            {"id": i, "text": VECTOR_TEXT_TEMPLATE.format(
                title=e["title"], tags=" ".join(e["tags"]), content=e["content"]),
             "vector": embedder.embed_texts([VECTOR_TEXT_TEMPLATE.format(
                 title=e["title"], tags=" ".join(e["tags"]), content=e["content"])])[0]}
            for i, e in self.entries.items()
        ])
        self._retriever = HybridRetriever(self.bm25, self.entries, self._vec_search)
        return True

    def rebuild_all(self) -> int:
        """全量重建两种索引（运营端「重建向量化」按钮）。"""
        return self.load()

    # ---- 分片文件回写 ----

    def _sync_raw(self, entry: dict, doc_id: str) -> None:
        """把新增/更新的知识写回所在分片文件的原始载荷。"""
        shard = entry.get("shard", "faq")
        payload = self._raw.setdefault(shard, {"shard": shard, "name": shard, "items": []})
        payload.setdefault("items", [])
        item = {
            "id": doc_id,
            "title": entry.get("title", ""),
            "tags": entry.get("tags", []),
            "source": entry.get("source", ""),
            "updated": entry.get("updated", ""),
            "content": entry.get("content", ""),
        }
        for index, existing in enumerate(payload["items"]):
            if str(existing.get("id")) == str(doc_id):
                payload["items"][index] = item
                break
        else:
            payload["items"].append(item)
        self._persist(shard)

    def _persist(self, shard: str) -> None:
        path = self._files.get(shard)
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(self._raw[shard], f, ensure_ascii=False, indent=2)
        except OSError as exc:
            logger.error("知识分片回写失败 %s：%s", shard, exc)