import os

# ai-service/app/config.py -> ai-service/
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


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
        self.faq_path = os.getenv("FAQ_PATH", os.path.join(BASE_DIR, "data", "faq.json"))

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
