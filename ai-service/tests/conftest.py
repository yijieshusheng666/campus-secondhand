import os
import shutil
import sys
import tempfile

# 必须在导入 app 之前设置环境变量（app.config 在导入时即读取）

# 1) 一律走词袋降级，避免下载模型
os.environ["EMB_FORCE_TFIDF"] = "true"

# 2) 索引目录不污染仓库
os.environ["INDEX_DIR"] = tempfile.mkdtemp(prefix="ai-index-test-")

# 3) LLM / Vision 配置必须清空成空串，不能用 pop。
#    config.py 导入时会 load_dotenv(".env", override=False)，override=False 只认
#    「键已存在」，不认值。用 pop 的话 .env 里的真 key 会被原样填回来，于是那些
#    「没有 key 时应降级到 rule/extractive」的用例会集体失败——实测 5 failed。
#    设成空串既能让 load_dotenv 跳过，又让 llm_enabled/vision_enabled 为 False，
#    保证「本机有没有 .env，测试结果都一样」。
for _key in (
    "LLM_BASE_URL", "LLM_API_KEY", "LLM_MODEL",
    "VISION_BASE_URL", "VISION_API_KEY", "VISION_MODEL",
    "OPS_TOKEN",
):
    os.environ[_key] = ""

# 4) 知识库也要隔离。test_ops_create_then_retrievable 会经运营接口写回
#    settings.knowledge_dir，不隔离就会改写仓库里的 data/knowledge/*.json
#    （曾经把测试条目 F065 提交进了主干）。这里复制一份分片到临时目录供测试读写。
_KB_SRC = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "knowledge"
)
_KB_DST = tempfile.mkdtemp(prefix="ai-kb-test-")
for _name in os.listdir(_KB_SRC):
    if _name.endswith(".json"):
        shutil.copyfile(os.path.join(_KB_SRC, _name), os.path.join(_KB_DST, _name))
os.environ["KNOWLEDGE_DIR"] = _KB_DST

# 不再手工压低阈值：config.default_threshold 已按向量模式取默认值，
# 词袋模式对应 0.20（实测标定），测试直接用真实默认值更有覆盖意义。

# 让 pytest 能 import app 包
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest


@pytest.fixture
def client():
    from fastapi.testclient import TestClient
    from app.main import app
    return TestClient(app)


@pytest.fixture(autouse=True, scope="session")
def _load_knowledge():
    """会话级装载一次知识库，使依赖 FAQ 检索的用例不依赖执行顺序。"""
    from app.deps import knowledge
    knowledge.load()
