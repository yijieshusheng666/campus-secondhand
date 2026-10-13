"""平台知识库（FAQ）的加载与检索。"""

import json
import logging

from app.config import settings
from app.embedding import embedder
from app.vector_store import VectorStore

logger = logging.getLogger(__name__)

FAQ_COLLECTION = "faq"


class KnowledgeBase:
    def __init__(self, store: VectorStore):
        self.store = store
        self.entries: dict[int, dict] = {}

    def load(self) -> int:
        """读取 faq.json，向量化后写入 faq 集合。返回条目数。"""
        with open(settings.faq_path, encoding="utf-8") as f:
            faqs = json.load(f)

        self.entries = {int(item["id"]): item for item in faqs}
        texts = [f"{item['title']} {item['content']}" for item in faqs]
        vectors = embedder.embed_texts(texts)

        items = [
            {"id": faq["id"], "text": texts[i], "vector": vectors[i]}
            for i, faq in enumerate(faqs)
        ]
        self.store.rebuild(FAQ_COLLECTION, items)
        logger.info("知识库已装载 %d 条", len(faqs))
        return len(faqs)

    def search(self, question: str, top_k: int) -> list[dict]:
        """返回 [{id, title, snippet, score}]。"""
        vector = embedder.embed_texts([question])[0]
        hits = self.store.search(FAQ_COLLECTION, vector, top_k, settings.sim_threshold)

        results = []
        for hit in hits:
            entry = self.entries.get(hit["id"])
            if entry is None:
                continue
            results.append({
                "id": entry["id"],
                "title": entry["title"],
                "snippet": entry["content"],
                "score": hit["score"],
            })
        return results
