"""向量存储与余弦检索。数据量小（数百条），直接用 numpy 暴力计算，不引入向量数据库。"""

import json
import logging
import os

import numpy as np

logger = logging.getLogger(__name__)


class VectorStore:
    def __init__(self, index_dir: str):
        self.index_dir = index_dir
        os.makedirs(self.index_dir, exist_ok=True)
        # 集合名 -> {"ids": list, "texts": list, "vectors": np.ndarray}
        self._collections: dict[str, dict] = {}
        self.load()

    # ---- 路径 ----

    def _meta_path(self, collection: str) -> str:
        return os.path.join(self.index_dir, f"{collection}.json")

    def _vec_path(self, collection: str) -> str:
        return os.path.join(self.index_dir, f"{collection}.npy")

    # ---- 写 ----

    def rebuild(self, collection: str, items: list[dict]) -> None:
        """全量重建：items 为空即清空该集合。

        id 保留传入时的类型：商品用 int、知识条目用 "R001" 这类字符串，
        调用方拿到的仍是它自己传进去的类型。
        """
        self._collections[collection] = {
            "ids": [i["id"] for i in items],
            "texts": [str(i.get("text", "")) for i in items],
            "vectors": (
                np.asarray([i["vector"] for i in items], dtype=np.float32)
                if items
                else np.zeros((0, 0), dtype=np.float32)
            ),
        }
        self.save(collection)

    def upsert(self, collection: str, items: list[dict]) -> None:
        """按 id 覆盖式更新：已存在则替换，不存在则追加。"""
        for item in items:
            vector = np.asarray(item["vector"], dtype=np.float32)
            entry = self._collections.get(collection)
            if entry is None or len(entry["ids"]) == 0:
                self._collections[collection] = {
                    "ids": [item["id"]],
                    "texts": [str(item.get("text", ""))],
                    "vectors": vector.reshape(1, -1),
                }
                continue

            positions = [i for i, v in enumerate(entry["ids"]) if str(v) == str(item["id"])]
            if positions:
                pos = positions[0]
                entry["vectors"][pos] = vector
                entry["texts"][pos] = str(item.get("text", ""))
            else:
                entry["ids"].append(item["id"])
                entry["texts"].append(str(item.get("text", "")))
                entry["vectors"] = np.vstack([entry["vectors"], vector.reshape(1, -1)])
        self.save(collection)

    # ---- 读 ----

    def count(self, collection: str) -> int:
        entry = self._collections.get(collection)
        return len(entry["ids"]) if entry else 0

    def search(self, collection: str, query_vector, top_k: int, min_score: float) -> list[dict]:
        entry = self._collections.get(collection)
        if not entry or len(entry["ids"]) == 0:
            return []

        query = np.asarray(query_vector, dtype=np.float32).reshape(-1)
        matrix = entry["vectors"]
        if matrix.shape[1] != query.shape[0]:
            raise ValueError(
                f"向量维度不一致：索引为 {matrix.shape[1]} 维，查询为 {query.shape[0]} 维。"
                "索引可能由另一种向量模式建立，请重建索引。"
            )

        # 向量均已 L2 归一化，点积即余弦相似度
        scores = matrix @ query
        order = np.argsort(-scores)[:top_k]

        results = []
        for idx in order:
            score = float(scores[idx])
            if score < min_score:
                continue
            results.append({
                "id": entry["ids"][idx],
                "score": round(score, 4),
                "text": entry["texts"][idx],
            })
        return results

    # ---- 持久化 ----

    def save(self, collection: str) -> None:
        entry = self._collections.get(collection)
        if entry is None:
            return
        with open(self._meta_path(collection), "w", encoding="utf-8") as f:
            json.dump({"ids": entry["ids"], "texts": entry["texts"]}, f, ensure_ascii=False)
        np.save(self._vec_path(collection), entry["vectors"])

    def load(self) -> None:
        for name in self._discover_collections():
            try:
                with open(self._meta_path(name), encoding="utf-8") as f:
                    meta = json.load(f)
                vectors = np.load(self._vec_path(name))
                self._collections[name] = {
                    "ids": meta["ids"],
                    "texts": meta["texts"],
                    "vectors": vectors,
                }
                logger.info("已加载集合 %s：%d 条", name, len(meta["ids"]))
            except Exception as exc:
                logger.warning("集合 %s 加载失败：%s", name, exc)

    def _discover_collections(self) -> list[str]:
        names = []
        for filename in os.listdir(self.index_dir):
            if filename.endswith(".json"):
                names.append(filename[: -len(".json")])
        return names
