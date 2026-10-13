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

        self.sim_threshold = float(os.getenv("SIM_THRESHOLD", "0.35"))

        self.index_dir = os.getenv("INDEX_DIR", os.path.join(BASE_DIR, "data", "index"))
        self.faq_path = os.getenv("FAQ_PATH", os.path.join(BASE_DIR, "data", "faq.json"))

    @property
    def llm_enabled(self) -> bool:
        """三项都配齐才认为云端生成可用，否则走抽取式降级。"""
        return bool(self.llm_base_url and self.llm_api_key and self.llm_model)


settings = Settings()
