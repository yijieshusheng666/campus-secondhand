import os

# ai-service/app/config.py -> ai-service/
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 显式加载 ai-service/.env。不加载的话，README 里让用户写的 .env 会被静默忽略
# ——「改了配置没反应」是最难排查的一类问题。只从本目录读，避免误读上级目录的 .env；
# override=False 表示已存在的环境变量优先（方便临时覆盖做对照实验）。
try:
    from dotenv import load_dotenv
except ImportError:  # 没装 python-dotenv 时退化为纯环境变量，功能不受影响
    load_dotenv = None

if load_dotenv is not None:
    load_dotenv(os.path.join(BASE_DIR, ".env"), override=False)


class Settings:
    """全局配置：所有可调项集中在此，均可用环境变量覆盖。"""

    def __init__(self):
        self.emb_model = os.getenv("EMB_MODEL", "BAAI/bge-small-zh-v1.5")
        self.emb_force_tfidf = os.getenv("EMB_FORCE_TFIDF", "false").lower() == "true"

        self.llm_base_url = os.getenv("LLM_BASE_URL", "").rstrip("/")
        self.llm_api_key = os.getenv("LLM_API_KEY", "")
        self.llm_model = os.getenv("LLM_MODEL", "")
        self.llm_timeout = int(os.getenv("LLM_TIMEOUT", "20"))

        # A3 图片识别：多模态模型（OpenAI 兼容协议，智谱 glm-4v 之类同样适用）
        self.vision_base_url = os.getenv("VISION_BASE_URL", "").rstrip("/")
        self.vision_api_key = os.getenv("VISION_API_KEY", "")
        self.vision_model = os.getenv("VISION_MODEL", "")

        self.sim_threshold = float(os.getenv("SIM_THRESHOLD", "0.35"))
        # 词袋模式缺少 IDF 加权，余弦值整体偏低，必须单独校准（见 default_threshold）
        self.sim_threshold_tfidf = float(os.getenv("SIM_THRESHOLD_TFIDF", "0.20"))

        self.index_dir = os.getenv("INDEX_DIR", os.path.join(BASE_DIR, "data", "index"))

        # —— 知识库（RAG）——
        self.knowledge_dir = os.getenv("KNOWLEDGE_DIR", os.path.join(BASE_DIR, "data", "knowledge"))
        self.kb_collection = os.getenv("KB_COLLECTION", "kb")
        # 两路召回各自的候选数：融合前尽量多召回，重排再收敛
        self.bm25_top_k = int(os.getenv("BM25_TOP_K", "20"))
        self.vec_top_k = int(os.getenv("VEC_TOP_K", "20"))
        self.vec_min_score = float(os.getenv("VEC_MIN_SCORE", "0.0"))
        # RRF 平滑常数：越大越"温和"，名次差异被压得越小
        self.rrf_k = int(os.getenv("RRF_K", "60"))
        # 重排时相对最高分保留的比例
        self.rerank_min_ratio = float(os.getenv("RERANK_MIN_RATIO", "0.4"))
        # 绝对可信度参照值：最高向量分低于它时，判定"相对排第一不可信"，整体下调置信度
        self.vec_confidence_ref = float(os.getenv("VEC_CONFIDENCE_REF", "0.25"))
        # 向量模式的置信度斜坡：绝对余弦 ≤ floor 视为不相关、≥ ceil 视为确定相关，中间线性映射。
        # 为什么必须用绝对值：bge 的余弦普遍偏高，而两路召回原来是「相对本查询最高分」归一，
        # 在向量模式下会双双饱和到 1.0，任何问题都拿满分（实测「量子纠缠退相干」也得 1.0）。
        # 2026-10-14 用 bge-small-zh-v1.5 实测标定：库内问题最高余弦 0.636-0.850，
        # 库外 0.319-0.502，取 floor=0.45 / ceil=0.75 可把两类干净分开。
        self.vec_ramp_floor = float(os.getenv("VEC_RAMP_FLOOR", "0.45"))
        self.vec_ramp_ceil = float(os.getenv("VEC_RAMP_CEIL", "0.75"))
        # BM25 绝对强命中（精确关键词）的加分上限：bm25_score / bm25_strong 封顶到 1
        self.bm25_strong = float(os.getenv("BM25_STRONG", "8.0"))
        self.vec_bonus_weight = float(os.getenv("VEC_BONUS_WEIGHT", "0.2"))
        self.max_hits = int(os.getenv("MAX_HITS", "4"))
        # 置信度低于该值强制拒答（需求 §2.1.3）
        self.refuse_threshold = float(os.getenv("REFUSE_THRESHOLD", "0.35"))
        # 多轮对话保留轮数（需求 §2.1.1：最近 3 轮）
        self.history_turns = int(os.getenv("HISTORY_TURNS", "3"))
        # 毕业季 / 开学季加权检索（需求 §2.2.2）
        self.season_boost = os.getenv("SEASON_BOOST", "true").lower() == "true"
        # 运营接口令牌：留空表示本地演示不校验，配上后要求请求头 X-Ops-Token 一致
        self.ops_token = os.getenv("OPS_TOKEN", "")

    @property
    def llm_enabled(self) -> bool:
        """三项都配齐才认为云端生成可用，否则走抽取式降级。"""
        return bool(self.llm_base_url and self.llm_api_key and self.llm_model)

    @property
    def vision_enabled(self) -> bool:
        """三项都配齐才认为图片识别可用（A3 依赖多模态模型）。"""
        return bool(self.vision_base_url and self.vision_api_key and self.vision_model)

    def default_threshold(self, mode: str) -> float:
        """按实际生效的向量模式取默认召回阈值。

        两种模式的余弦分布量级不同，用同一个阈值必然二选一失效：
        - vector（bge 语义向量）：同类文本余弦多在 0.6-0.9，0.35 足以截断噪声；
        - tfidf（jieba 词袋哈希，无 IDF 加权）：2026-10-13 用真实数据实测，
          命中项落在 0.21-0.33，噪声项 ≤0.20，故取 0.20。
        """
        return self.sim_threshold_tfidf if mode == "tfidf" else self.sim_threshold


settings = Settings()
