"""文本向量化：优先本地语义模型，失败时降级为 jieba 词袋哈希。"""

import logging
import os

import numpy as np

from app.config import settings

logger = logging.getLogger(__name__)

# 词袋降级模式的向量维度：固定值以保证跨请求可比。
# 512 维时短文本撞桶概率过高，实测「退款」与「主动」同桶，导致「怎么退款」
# 命中 AirPods 等无关商品；提到 4096 维后撞桶概率降到可忽略。
TFIDF_DIM = 4096

# 词袋基线的停用词。这些词几乎出现在任何中文句子里，而词袋没有 IDF 加权，
# 不滤掉就会凭一个虚词把无关文档顶到前面：
# 实测「有没有便宜的台灯」曾以 0.2085 命中「修改昵称」，唯一共享词就是「的」。
STOPWORDS = {
    "的", "了", "吗", "呢", "啊", "吧", "呀", "哦", "嘛", "的话",
    "是", "在", "有", "没", "和", "与", "或", "就", "都", "也", "很", "太", "还",
    "我", "你", "他", "她", "它", "们", "这", "那", "个", "一", "些",
    "会", "要", "想", "能", "可", "可以", "怎么", "什么", "哪个", "哪些",
    "请问", "帮我", "有没有", "多少", "如何", "为什么", "一下",
}


def _jieba_tokenize(text: str) -> list[str]:
    """分词后滤掉停用词与单字，作为词袋基线的一步基本清洗。"""
    import jieba
    return [
        t for t in jieba.cut(text)
        if t.strip() and len(t) > 1 and t not in STOPWORDS
    ]


def _l2_normalize(matrix: np.ndarray) -> np.ndarray:
    """按行做 L2 归一化；零向量保持为零向量，避免除零。"""
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return matrix / norms


def _local_model_ready(model_name: str) -> bool:
    """模型是否已完整缓存到本地。

    先做这个检查再 import sentence_transformers：它的 import 链会连带拉起
    tensorflow，空跑一次要十几秒，而需求要求首字响应 ≤1 秒。模型没缓存就直接
    判定降级，不去做那次昂贵的 import。

    注意：只看目录是否存在是不够的 —— 下载失败会留下空的 models--xxx 目录，
    必须确认快照里同时有 config.json 和权重文件才算就绪。
    """
    if os.path.isdir(model_name):          # 直接给了本地路径
        return True

    folder = "models--" + model_name.replace("/", "--")
    roots = [
        os.getenv("HF_HOME"),
        os.path.join(os.path.expanduser("~"), ".cache", "huggingface", "hub"),
        os.path.join(os.path.expanduser("~"), ".cache", "huggingface"),
    ]
    for base in roots:
        if not base:
            continue
        snapshots = os.path.join(base, folder, "snapshots")
        if not os.path.isdir(snapshots):
            continue
        for snapshot in os.listdir(snapshots):
            snap_dir = os.path.join(snapshots, snapshot)
            if not os.path.isdir(snap_dir):
                continue
            if not os.path.exists(os.path.join(snap_dir, "config.json")):
                continue
            files = os.listdir(snap_dir)
            if any(f.endswith((".safetensors", ".bin")) for f in files):
                return True
    return False


class Embedder:
    def __init__(self):
        self._model = None
        self._hasher = None
        self._mode = "tfidf" if settings.emb_force_tfidf else None

    # ---- 内部：模式解析与惰性加载 ----

    def _load_semantic(self):
        if not _local_model_ready(settings.emb_model):
            raise RuntimeError(f"本地未缓存模型 {settings.emb_model}，跳过向量模式")
        from sentence_transformers import SentenceTransformer
        # 离线加载：网络不可达时 sentence-transformers 会重试 5 次、每次 10 秒，
        # 启动要卡 3 分钟，直接违背需求里的"首字响应 ≤1 秒"。离线快速失败后降级词袋。
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
        self._model = SentenceTransformer(settings.emb_model, local_files_only=True)

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
