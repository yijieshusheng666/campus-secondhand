import os
import sys
import tempfile

# 必须在导入 app 之前设置环境变量（app.config 在导入时即读取）
os.environ["EMB_FORCE_TFIDF"] = "true"          # 测试一律走词袋降级，避免下载模型
os.environ["INDEX_DIR"] = tempfile.mkdtemp(prefix="ai-index-test-")  # 不污染仓库目录
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
