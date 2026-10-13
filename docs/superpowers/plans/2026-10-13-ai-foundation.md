# P0 AI 能力底座 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建成一个可复用的 AI 能力层（向量化 / 向量检索 / 生成降级），使 P1-P4 的 6 个 AI 功能只需编写业务代码。

**Architecture:** 新建独立 Python 服务 `ai-service/`（FastAPI，8000 端口），不直接访问 MySQL。商品文本由 Java 侧推送建立索引；前端请求经 Spring Boot(8080) 转发到 FastAPI。向量化与生成能力各有两级实现，失败时自动降级，保证断网可用。

**Tech Stack:** Python 3.11 / FastAPI / uvicorn / numpy / sentence-transformers（本地 BAAI/bge-small-zh-v1.5）/ jieba + scikit-learn（降级）/ httpx（调用 LLM）；Java 侧用 Spring 的 RestTemplate。

**Spec:** `docs/superpowers/specs/2026-10-13-ai-foundation-design.md`

---

## 环境前提（已核实）

| 项 | 状态 |
|---|---|
| Python | 3.11.9 ✓ |
| fastapi / uvicorn / numpy / httpx | 已安装 ✓ |
| sentence-transformers 3.4.1 / torch 2.12.0 | 已安装 ✓ |
| scikit-learn 1.3.0 | 已安装 ✓ |
| pytest 8.3.5 | 已安装 ✓ |
| jieba | **需安装** |
| Docker | 未安装（本计划不使用） |
| git 仓库 | **不存在**（见任务 0） |

---

## 与 Spec 的一处偏差（需知悉）

**Spec 第 6.2 节写的是"jieba 分词 + TF-IDF"，实现改为"jieba 分词 + scikit-learn `HashingVectorizer`（无状态词频哈希）"。**

原因：`TfidfVectorizer` 需要先在整个语料上 `fit` 才能产出向量，而本服务的 `/ai/embed` 必须能对任意单条文本独立产出可与索引比较的向量。若用 TF-IDF，就必须把 vectorizer 与索引绑在一起持久化，导致"未建索引前无法 embed"的耦合。`HashingVectorizer` 无状态、确定性强、同样基于 jieba 分词，语义上仍是"词袋基线"，且能跨请求直接算余弦相似度。

代价：失去 IDF 加权（高频词权重不被压低）。对"基线 vs 向量检索"的对比实验而言，词袋基线依然成立，且更简单。降级模式的对外名称仍为 `tfidf`，保持与 Spec 的接口约定一致。

---

## 文件结构

**新建（Python 服务）**

| 文件 | 职责 |
|---|---|
| `ai-service/requirements.txt` | 依赖清单 |
| `ai-service/README.md` | 启动方式与环境变量说明 |
| `ai-service/app/config.py` | 环境变量配置，唯一配置入口 |
| `ai-service/app/deps.py` | 共享单例（`store`、`knowledge`），供 main 与各路由导入，避免循环依赖 |
| `ai-service/app/embedding.py` | 文本→向量，含 tfidf 降级 |
| `ai-service/app/vector_store.py` | 命名集合的向量存取、余弦检索、持久化 |
| `ai-service/app/prompts.py` | Prompt 模板常量 |
| `ai-service/app/llm.py` | 生成能力，含抽取式降级 |
| `ai-service/app/knowledge.py` | FAQ 知识库加载与检索 |
| `ai-service/app/main.py` | FastAPI 入口、CORS、启动时装载 FAQ |
| `ai-service/app/routers/health.py` | `GET /health` |
| `ai-service/app/routers/embed.py` | `POST /ai/embed` |
| `ai-service/app/routers/index.py` | `POST /ai/index`、`POST /ai/index/rebuild` |
| `ai-service/app/routers/search.py` | `POST /ai/search` |
| `ai-service/app/routers/chat.py` | `POST /ai/chat` |
| `ai-service/data/faq.json` | 平台规则知识库 |
| `ai-service/tests/conftest.py` | 测试夹具：强制 tfidf 模式、临时索引目录 |
| `ai-service/tests/test_embedding.py` | 向量化单测 |
| `ai-service/tests/test_vector_store.py` | 向量存储单测 |
| `ai-service/tests/test_llm.py` | 生成降级单测 |
| `ai-service/tests/test_api.py` | 接口层单测（health/embed/index/search/chat） |

**新建（Java 侧）**

| 文件 | 职责 |
|---|---|
| `demo1/.../service/AiClient.java` | 调用 FastAPI 的客户端封装，失败返回空 |
| `demo1/.../controller/AiController.java` | 语义搜索、全量重建索引接口 |

**修改（Java 侧）**

| 文件 | 改动 |
|---|---|
| `demo1/.../controller/ApiController.java` | 发布商品后推送索引 |

---

## 任务 0（可选，但强烈建议）：初始化版本控制

**为什么建议做**：当前项目没有 git，一旦改坏无法回退。毕设代码丢失的代价极高。若你不想做，跳过本任务并**忽略后续所有 "Commit" 步骤**。

**Files:**
- Verify: `.gitignore`
- Create: `.git`（由 init 生成）

- [ ] **Step 1: 确认 .gitignore 已覆盖不该提交的内容**

Run: `Get-Content "d:\作业\毕业设计\campus-secondhand\.gitignore"`
Expected: 内容中包含 `target/`、`node_modules/`、`dist/` 等条目；若缺少 `__pycache__/`、`*.pyc`、`ai-service/data/index/`、`.env`，追加进去。

- [ ] **Step 2: 初始化并首次提交**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" init
git -C "d:\作业\毕业设计\campus-secondhand" add -A
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "chore: 初始化版本控制，纳入现有前后端代码与设计文档"
```

Expected: 提交成功，`git status` 显示 working tree clean。

---

## 任务 1：服务骨架与配置

**Files:**
- Create: `ai-service/requirements.txt`
- Create: `ai-service/app/__init__.py`
- Create: `ai-service/app/config.py`
- Create: `ai-service/app/main.py`
- Create: `ai-service/app/routers/__init__.py`
- Create: `ai-service/app/routers/health.py`
- Create: `ai-service/tests/__init__.py`
- Create: `ai-service/tests/conftest.py`
- Create: `ai-service/tests/test_api.py`
- Create: `ai-service/README.md`

- [ ] **Step 1: 安装缺失依赖**

Run: `python -m pip install jieba`
Expected: `Successfully installed jieba-0.42.1`

- [ ] **Step 2: 写 requirements.txt**

```
fastapi
uvicorn[standard]
pydantic
numpy
httpx
sentence-transformers
jieba
scikit-learn
```

- [ ] **Step 3: 写 config.py**

`ai-service/app/config.py`：

```python
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
```

- [ ] **Step 4: 写 routers/health.py（最小版）**

`ai-service/app/routers/health.py`：

```python
from fastapi import APIRouter

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}
```

- [ ] **Step 5: 写 main.py**

`ai-service/app/main.py`：

```python
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import health

app = FastAPI(title="校园二手交易平台 AI 服务", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
```

- [ ] **Step 6: 写 tests/conftest.py**

```python
import os
import sys
import tempfile

# 必须在导入 app 之前设置环境变量（app.config 在导入时即读取）
os.environ["EMB_FORCE_TFIDF"] = "true"          # 测试一律走词袋降级，避免下载模型
os.environ["INDEX_DIR"] = tempfile.mkdtemp(prefix="ai-index-test-")  # 不污染仓库目录
# 词袋模式的余弦值普遍低于语义向量模式，测试环境用低阈值
os.environ["SIM_THRESHOLD"] = "0.05"

# 让 pytest 能 import app 包
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest


@pytest.fixture
def client():
    from fastapi.testclient import TestClient
    from app.main import app
    return TestClient(app)
```

**关于 `SIM_THRESHOLD`**：语义向量模式下同类文本余弦通常在 0.6-0.9，0.35 是合理的召回门槛；词袋模式因缺乏 IDF 加权，余弦普遍偏低。因此阈值必须按模式校准——这是 Spec 7.4 已登记的"待调项"，最后要拿真实数据调。

- [ ] **Step 7: 写 tests/test_api.py（健康检查用例）**

```python
def test_health_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
```

- [ ] **Step 8: 运行测试（应先失败于缺少 TestClient 之外的原因）**

Run: `python -m pytest ai-service/tests/test_api.py -v`（在 `ai-service/` 目录下执行 `python -m pytest tests/test_api.py -v`）
Expected: PASS。若报 `ModuleNotFoundError: No module named 'app'`，检查 conftest.py 的 `sys.path` 行。

- [ ] **Step 9: 写 README.md**

`ai-service/README.md`：

```markdown
# AI 能力底座（P0）

校园二手交易平台的 AI 能力服务，为语义搜索、RAG 问答等提供向量化、向量检索与文本生成能力。

## 启动

```bash
cd ai-service
python -m pip install -r requirements.txt
uvicorn app.main:app --port 8000
```

启动后访问 http://localhost:8000/docs 查看接口文档。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EMB_MODEL` | `BAAI/bge-small-zh-v1.5` | 向量模型 |
| `EMB_FORCE_TFIDF` | `false` | 置 `true` 强制走词袋降级 |
| `LLM_BASE_URL` | 空 | OpenAI 兼容接口地址 |
| `LLM_API_KEY` | 空 | 为空则生成走抽取式降级 |
| `LLM_MODEL` | 空 | 模型名 |
| `LLM_TIMEOUT` | `20` | 生成超时（秒） |
| `SIM_THRESHOLD` | `0.35` | 召回最低相似度 |
| `INDEX_DIR` | `data/index` | 索引持久化目录 |
| `FAQ_PATH` | `data/faq.json` | 知识库文件 |

## 降级设计

- **生成**：无 API key 或调用失败 → 抽取式回答（模板拼装检索原文）
- **向量**：模型加载失败或 `EMB_FORCE_TFIDF=true` → jieba + HashingVectorizer 词袋

两条降级路径都不依赖外部服务，保证离线可用。

## 测试

```bash
cd ai-service
python -m pytest tests/ -v
```
```

- [ ] **Step 10: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add ai-service
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 搭建 AI 服务骨架与配置"
```

---

## 任务 2：embedding.py（文本向量化 + 词袋降级）

**Files:**
- Create: `ai-service/app/embedding.py`
- Create: `ai-service/tests/test_embedding.py`

**设计要点**：对外只暴露 `embed_texts` 与 `dim`、`mode`；内部懒加载模型；向量统一做 L2 归一化，使余弦相似度退化为点积。

- [ ] **Step 1: 写失败测试**

`ai-service/tests/test_embedding.py`：

```python
import numpy as np

from app.embedding import Embedder


def test_dim_is_positive():
    e = Embedder()
    assert e.dim() > 0


def test_embed_texts_shape_and_normalization():
    e = Embedder()
    vectors = e.embed_texts(["降噪耳机", "高等数学教材"])
    assert len(vectors) == 2
    assert len(vectors[0]) == e.dim()
    # L2 归一化：模长应为 1
    assert abs(np.linalg.norm(vectors[0]) - 1.0) < 1e-5


def test_embed_empty_list():
    e = Embedder()
    assert e.embed_texts([]) == []


def test_semantic_similarity_ranking():
    """同类文本的相似度应高于跨类文本——这是词袋基线的基本能力。"""
    e = Embedder()
    base, similar, different = e.embed_texts([
        "降噪耳机",
        "头戴式降噪耳机",
        "高等数学教材",
    ])
    base, similar, different = (
        np.array(base), np.array(similar), np.array(different)
    )
    assert float(base @ similar) > float(base @ different)


def test_mode_is_tfidf_in_test_env():
    e = Embedder()
    assert e.mode() == "tfidf"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_embedding.py -v`
Expected: FAIL，报 `ModuleNotFoundError: No module named 'app.embedding'`

- [ ] **Step 3: 写 embedding.py**

`ai-service/app/embedding.py`：

```python
"""文本向量化：优先本地语义模型，失败时降级为 jieba 词袋哈希。"""

import logging

import numpy as np

from app.config import settings

logger = logging.getLogger(__name__)

# 词袋降级模式的向量维度：固定值以保证跨请求可比
TFIDF_DIM = 512


def _jieba_tokenize(text: str) -> list[str]:
    import jieba
    return [t for t in jieba.cut(text) if t.strip()]


def _l2_normalize(matrix: np.ndarray) -> np.ndarray:
    """按行做 L2 归一化；零向量保持为零向量，避免除零。"""
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    norms[norms == 0] = 1.0
    return matrix / norms


class Embedder:
    def __init__(self):
        self._model = None
        self._hasher = None
        self._mode = "tfidf" if settings.emb_force_tfidf else None

    # ---- 内部：模式解析与惰性加载 ----

    def _load_semantic(self):
        from sentence_transformers import SentenceTransformer
        self._model = SentenceTransformer(settings.emb_model)

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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd ai-service && python -m pytest tests/test_embedding.py -v`
Expected: 5 passed

- [ ] **Step 5: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add ai-service
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增文本向量化模块，支持词袋降级"
```

---

## 任务 3：vector_store.py（命名集合的向量检索）

**Files:**
- Create: `ai-service/app/vector_store.py`
- Create: `ai-service/app/deps.py`
- Create: `ai-service/tests/test_vector_store.py`

**设计要点**：支持多个命名集合（`goods`、`faq`）；检索用矩阵乘（向量已归一化，点积即余弦）；持久化为 `<集合名>.json`（id 与文本）+ `<集合名>.npy`（向量矩阵）。

- [ ] **Step 1: 写失败测试**

`ai-service/tests/test_vector_store.py`：

```python
import numpy as np

from app.vector_store import VectorStore


def _items():
    """三条 3 维向量，便于手算期望结果。"""
    return [
        {"id": 1, "text": "耳机", "vector": [1.0, 0.0, 0.0]},
        {"id": 2, "text": "教材", "vector": [0.0, 1.0, 0.0]},
        {"id": 3, "text": "台灯", "vector": [0.0, 0.0, 1.0]},
    ]


def test_search_returns_most_similar_first(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    results = store.search("goods", [0.9, 0.1, 0.0], top_k=3, min_score=0.0)
    assert results[0]["id"] == 1
    assert results[0]["score"] > results[1]["score"]


def test_min_score_filters_out_low_similarity(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    # 与 id=2 完全正交，相似度为 0
    results = store.search("goods", [1.0, 0.0, 0.0], top_k=3, min_score=0.5)
    assert [r["id"] for r in results] == [1]


def test_upsert_overwrites_same_id(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    store.upsert("goods", [{"id": 1, "text": "新耳机", "vector": [0.0, 0.0, 1.0]}])
    assert store.count("goods") == 3
    results = store.search("goods", [0.0, 0.0, 1.0], top_k=1, min_score=0.0)
    assert results[0]["id"] == 1
    assert results[0]["score"] > 0.99


def test_collections_are_isolated(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    store.rebuild("faq", [{"id": 99, "text": "退款规则", "vector": [1.0, 0.0, 0.0]}])
    assert store.count("goods") == 3
    assert store.count("faq") == 1


def test_rebuild_with_empty_clears(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    store.rebuild("goods", [])
    assert store.count("goods") == 0


def test_persistence_across_instances(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())

    reopened = VectorStore(str(tmp_path))
    assert reopened.count("goods") == 3
    results = reopened.search("goods", [0.9, 0.1, 0.0], top_k=1, min_score=0.0)
    assert results[0]["id"] == 1


def test_search_empty_collection_returns_empty(tmp_path):
    store = VectorStore(str(tmp_path))
    assert store.search("nothing", [1.0, 0.0, 0.0], top_k=5, min_score=0.0) == []


def test_vector_dimension_mismatch_raises(tmp_path):
    store = VectorStore(str(tmp_path))
    store.rebuild("goods", _items())
    try:
        store.search("goods", [1.0, 0.0], top_k=1, min_score=0.0)
        assert False, "维度不一致时应抛错"
    except ValueError:
        pass
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_vector_store.py -v`
Expected: FAIL，报 `ModuleNotFoundError: No module named 'app.vector_store'`

- [ ] **Step 3: 写 vector_store.py**

`ai-service/app/vector_store.py`：

```python
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
        """全量重建：items 为空即清空该集合。"""
        self._collections[collection] = {
            "ids": [int(i["id"]) for i in items],
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
                    "ids": [int(item["id"])],
                    "texts": [str(item.get("text", ""))],
                    "vectors": vector.reshape(1, -1),
                }
                continue

            positions = [i for i, v in enumerate(entry["ids"]) if v == int(item["id"])]
            if positions:
                pos = positions[0]
                entry["vectors"][pos] = vector
                entry["texts"][pos] = str(item.get("text", ""))
            else:
                entry["ids"].append(int(item["id"]))
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


def create_store() -> VectorStore:
    from app.config import settings
    return VectorStore(settings.index_dir)
```

- [ ] **Step 4: 写 deps.py（共享单例，避免循环依赖）**

`ai-service/app/deps.py`：

```python
"""共享单例：main.py 与各路由都从这里取 store / knowledge。

若路由模块直接 `from app.main import store`，而 main.py 又要 include_router 这些路由，
就会形成循环 import。共享状态集中在此，两侧只依赖 deps。
"""

from app.config import settings
from app.vector_store import VectorStore

store = VectorStore(settings.index_dir)
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd ai-service && python -m pytest tests/test_vector_store.py -v`
Expected: 8 passed

- [ ] **Step 6: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add ai-service
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增向量存储与余弦检索"
```

---

## 任务 4：prompts.py 与 llm.py（生成能力 + 抽取式降级）

**Files:**
- Create: `ai-service/app/prompts.py`
- Create: `ai-service/app/llm.py`
- Create: `ai-service/tests/test_llm.py`

**设计要点**：`LLM.generate()` 只负责"能生成就生成、不能就抛 `LLMUnavailable`"；降级决策放在调用方（`chat.py`），保证 `llm.py` 职责单一、可测。

- [ ] **Step 1: 写失败测试**

`ai-service/tests/test_llm.py`：

```python
from app.llm import LLM, LLMUnavailable
from app.prompts import build_extractive_answer


def test_without_api_key_mode_is_extractive(monkeypatch):
    from app import config
    monkeypatch.setattr(config.settings, "llm_base_url", "")
    monkeypatch.setattr(config.settings, "llm_api_key", "")
    monkeypatch.setattr(config.settings, "llm_model", "")
    llm = LLM()
    assert llm.mode() == "extractive"
    assert llm.available() is False


def test_generate_raises_when_unavailable(monkeypatch):
    from app import config
    monkeypatch.setattr(config.settings, "llm_base_url", "")
    monkeypatch.setattr(config.settings, "llm_api_key", "")
    monkeypatch.setattr(config.settings, "llm_model", "")
    llm = LLM()
    try:
        llm.generate("你好")
        assert False, "不可用时应抛 LLMUnavailable"
    except LLMUnavailable:
        pass


def test_extractive_answer_contains_snippets():
    answer = build_extractive_answer(["在售商品支持退款。", "待发货订单可申请退款。"])
    assert "在售商品支持退款。" in answer
    assert "待发货订单可申请退款。" in answer


def test_extractive_answer_when_no_context():
    answer = build_extractive_answer([])
    assert answer  # 空上下文也要给出可读回复
    assert "暂时" in answer or "没有" in answer
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_llm.py -v`
Expected: FAIL，报 `ModuleNotFoundError: No module named 'app.llm'`

- [ ] **Step 3: 写 prompts.py**

`ai-service/app/prompts.py`：

```python
"""Prompt 模板集中管理，避免散落在业务代码里。"""

RAG_SYSTEM = (
    "你是校园二手交易平台的智能助手，只依据提供的资料回答问题。"
    "资料中没有的内容不要编造，可以建议用户换个说法再问。回答要简短、口语化。"
)

RAG_QA = """请只依据下面的资料回答用户问题。

资料：
{context}

用户问题：{question}

回答："""


def build_extractive_answer(snippets: list[str]) -> str:
    """抽取式降级：不做生成，直接拼装检索到的原文片段。"""
    if not snippets:
        return "抱歉，暂时没有找到相关资料。可以换个说法再问我，比如直接说商品名称或想问的业务（例如「怎么退款」）。"
    lines = "\n".join(f"{i + 1}. {s}" for i, s in enumerate(snippets))
    return f"根据平台资料，我找到以下相关信息：\n{lines}\n\n如果还需要更详细的说明，可以继续问我～"


def build_context(snippets: list[str]) -> str:
    return "\n".join(f"- {s}" for s in snippets)
```

- [ ] **Step 4: 写 llm.py**

`ai-service/app/llm.py`：

```python
"""生成能力抽象：云端 OpenAI 兼容接口。不可用时由调用方决定降级策略。"""

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class LLMUnavailable(Exception):
    """云端生成不可用（未配置或调用失败）。"""


class LLM:
    def available(self) -> bool:
        return settings.llm_enabled

    def mode(self) -> str:
        return "api" if self.available() else "extractive"

    def generate(self, prompt: str) -> str:
        if not self.available():
            raise LLMUnavailable("未配置 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL")

        url = f"{settings.llm_base_url}/chat/completions"
        payload = {
            "model": settings.llm_model,
            "messages": [
                {"role": "system", "content": _system_content()},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
        }
        headers = {"Authorization": f"Bearer {settings.llm_api_key}"}

        try:
            resp = httpx.post(url, json=payload, headers=headers, timeout=settings.llm_timeout)
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()
        except Exception as exc:
            logger.warning("LLM 调用失败，交由调用方降级：%s", exc)
            raise LLMUnavailable(str(exc)) from exc


def _system_content() -> str:
    from app.prompts import RAG_SYSTEM
    return RAG_SYSTEM


llm = LLM()
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd ai-service && python -m pytest tests/test_llm.py -v`
Expected: 4 passed

- [ ] **Step 6: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add ai-service
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增生成能力与抽取式降级、Prompt 模板"
```

---

## 任务 5：knowledge.py 与 FAQ 知识库

**Files:**
- Create: `ai-service/data/faq.json`
- Create: `ai-service/app/knowledge.py`
- Modify: `ai-service/app/deps.py`（挂载 knowledge 单例）
- Modify: `ai-service/tests/conftest.py`（加入知识库装载夹具）

**设计要点**：FAQ 条目格式 `{"id": int, "title": str, "content": str}`；入库文本为 `title + " " + content`；启动时装载进 `faq` 集合。

- [ ] **Step 1: 写 data/faq.json**

`ai-service/data/faq.json`（20 条，覆盖平台规则与交易流程；后续 P1 可扩充到 30-50 条）：

```json
[
  {"id": 1, "title": "购买流程", "content": "在首页找到心仪的宝贝，点击「我想要」，选择或填写收货地址后确认付款即可。付款后订单会出现在「我的订单」里。"},
  {"id": 2, "title": "退款规则", "content": "在「我的订单」中，待发货状态的订单可以申请退款。退款成功后，宝贝会自动重新上架。"},
  {"id": 3, "title": "发货说明", "content": "卖家确认发货后，订单状态会变为「已发货」。同校交易一般当面交接或约定宿舍楼下自取。"},
  {"id": 4, "title": "发布闲置", "content": "登录后点击「发布商品」，填写标题、分类、价格和描述，并上传宝贝图片即可发布。最多上传 6 张图。"},
  {"id": 5, "title": "商品分类", "content": "平台共六个分类：电子产品、图书教材、生活用品、运动户外、二手家具、美妆护肤。"},
  {"id": 6, "title": "联系卖家", "content": "在商品卡片上点击「聊一聊」，或在宝贝详情里点击「联系卖家」，就能和卖家一对一沟通。可以先问清楚成色、价格再决定是否购买。"},
  {"id": 7, "title": "登录方式", "content": "使用学号和密码登录。没有账号可以在登录页切换到「用户注册」标签页注册。"},
  {"id": 8, "title": "修改昵称", "content": "进入「个人中心」，点击昵称旁的编辑按钮即可修改。"},
  {"id": 9, "title": "商品已售", "content": "商品被下单后会自动标记为「已售」，不再出现在首页列表中。卖家退款后会自动恢复「在售」。"},
  {"id": 10, "title": "面交安全", "content": "建议在校内公共场所（如宿舍楼下、食堂门口）当面交易，验货后再付款，避免提前转账。"},
  {"id": 11, "title": "收货地址", "content": "下单时可选择已保存的地址，也可以直接填写新地址。填写收货人、联系电话和详细地址即可。"},
  {"id": 12, "title": "无法下单", "content": "无法下单常见原因：未登录、商品已被他人买走、收货信息没填完整。请依次检查。"},
  {"id": 13, "title": "搜索商品", "content": "在首页顶部的搜索框输入关键词，比如「MacBook」或「高数书」，点击「搜一搜」即可筛选。"},
  {"id": 14, "title": "发布失败", "content": "发布失败请检查标题、分类、价格、描述是否都已填写，价格必须是数字。"},
  {"id": 15, "title": "联系客服", "content": "页面右侧有智能客服悬浮按钮，点击即可提问，支持按关键词帮你找宝贝。"},
  {"id": 16, "title": "订单状态", "content": "订单状态包括：待发货、已发货、已完成、已退款。买家可在「我的订单」查看。"},
  {"id": 17, "title": "卖家查看订单", "content": "在「我的订单」页面可以切换到卖家视角，查看自己卖出的商品记录。"},
  {"id": 18, "title": "图片上传要求", "content": "支持常见图片格式，单张不超过 10MB，每件商品最多 6 张。"},
  {"id": 19, "title": "价格填写", "content": "价格填写数字即可，支持两位小数，不需要输入货币符号。"},
  {"id": 20, "title": "账号安全", "content": "请勿将账号密码告知他人。发现异常请联系平台管理员。"}
]
```

- [ ] **Step 2: 写 knowledge.py**

`ai-service/app/knowledge.py`：

```python
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
```

- [ ] **Step 3: 在 deps.py 中挂载 knowledge 单例**

`ai-service/app/deps.py` 全量替换为：

```python
"""共享单例：main.py 与各路由都从这里取 store / knowledge。

若路由模块直接 `from app.main import store`，而 main.py 又要 include_router 这些路由，
就会形成循环 import。共享状态集中在此，两侧只依赖 deps。
"""

from app.config import settings
from app.knowledge import KnowledgeBase
from app.vector_store import VectorStore

store = VectorStore(settings.index_dir)
knowledge = KnowledgeBase(store)
```

- [ ] **Step 4: 在 conftest.py 中加入知识库装载夹具**

在 `ai-service/tests/conftest.py` 末尾追加：

```python
@pytest.fixture(autouse=True, scope="session")
def _load_knowledge():
    """会话级装载一次知识库，使依赖 FAQ 检索的用例不依赖执行顺序。"""
    from app.deps import knowledge
    knowledge.load()
```

- [ ] **Step 5: 写装载验证测试**

追加到 `ai-service/tests/test_api.py`：

```python
def test_knowledge_base_loads_and_retrieves(client):
    from app.deps import knowledge

    assert len(knowledge.entries) >= 20

    hits = knowledge.search("怎么退款", top_k=3)
    assert hits, "「怎么退款」应能命中知识库"
    assert any("退款" in h["title"] or "退款" in h["snippet"] for h in hits)


def test_knowledge_search_returns_score(client):
    from app.deps import knowledge

    hits = knowledge.search("怎么发布闲置", top_k=3)
    assert hits
    assert hits[0]["score"] > 0
```

- [ ] **Step 6: 运行测试**

Run: `cd ai-service && python -m pytest tests/test_api.py -v`
Expected: 3 passed

- [ ] **Step 7: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add ai-service
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增平台知识库与 FAQ 检索"
```

---

## 任务 6：接口层 —— /ai/embed、/ai/index、/ai/index/rebuild

**Files:**
- Create: `ai-service/app/routers/embed.py`
- Create: `ai-service/app/routers/index.py`
- Modify: `ai-service/app/main.py`（注册路由、创建 store、启动装载知识库）
- Modify: `ai-service/tests/test_api.py`（追加用例）

- [ ] **Step 1: 写失败测试**

追加到 `ai-service/tests/test_api.py`：

```python
def test_embed_returns_vectors(client):
    resp = client.post("/ai/embed", json={"texts": ["降噪耳机", "高等数学"]})
    assert resp.status_code == 200
    body = resp.json()
    assert body["dim"] > 0
    assert len(body["vectors"]) == 2
    assert len(body["vectors"][0]) == body["dim"]


def test_index_then_search(client):
    rebuild = client.post("/ai/index/rebuild", json={"items": [
        {"goodsId": 8, "text": "索尼 WH-1000XM5 头戴式耳机 电子产品 行业领先的降噪"},
        {"goodsId": 9, "text": "高等数学 第七版 图书教材 同济大学数学系编"},
        {"goodsId": 10, "text": "飞利浦台灯 生活用品 LED 护眼灯"},
    ]})
    assert rebuild.status_code == 200
    assert rebuild.json()["total"] == 3

    resp = client.post("/ai/search", json={"query": "降噪耳机", "top_k": 3, "min_score": 0.0})
    assert resp.status_code == 200
    results = resp.json()["results"]
    assert results[0]["goodsId"] == 8


def test_index_upsert_replaces(client):
    client.post("/ai/index/rebuild", json={"items": [
        {"goodsId": 1, "text": "旧标题 耳机"},
    ]})
    client.post("/ai/index", json={"items": [
        {"goodsId": 1, "text": "新标题 台灯"},
    ]})
    resp = client.post("/ai/search", json={"query": "标题", "top_k": 5, "min_score": 0.0})
    assert len(resp.json()["results"]) == 1
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_api.py -v`
Expected: FAIL，`/ai/embed` 返回 404

- [ ] **Step 3: 写 routers/embed.py**

`ai-service/app/routers/embed.py`：

```python
from fastapi import APIRouter
from pydantic import BaseModel

from app.embedding import embedder

router = APIRouter(prefix="/ai", tags=["embedding"])


class EmbedRequest(BaseModel):
    texts: list[str]


@router.post("/embed")
def embed(req: EmbedRequest):
    vectors = embedder.embed_texts(req.texts)
    # 空输入时 dim 仍要给出确定值，供调用方预分配
    return {"dim": embedder.dim(), "vectors": vectors}
```

- [ ] **Step 4: 写 routers/index.py**

`ai-service/app/routers/index.py`：

```python
from fastapi import APIRouter
from pydantic import BaseModel

from app.deps import store
from app.embedding import embedder

router = APIRouter(prefix="/ai/index", tags=["index"])

GOODS_COLLECTION = "goods"


class GoodsItem(BaseModel):
    goodsId: int
    text: str


class IndexRequest(BaseModel):
    items: list[GoodsItem]


def _to_vectors(items: list[GoodsItem]) -> list[dict]:
    if not items:
        return []
    vectors = embedder.embed_texts([item.text for item in items])
    return [
        {"id": item.goodsId, "text": item.text, "vector": vectors[i]}
        for i, item in enumerate(items)
    ]


@router.post("")
def upsert_index(req: IndexRequest):
    store.upsert(GOODS_COLLECTION, _to_vectors(req.items))
    return {"success": True, "indexed": len(req.items), "total": store.count(GOODS_COLLECTION)}


@router.post("/rebuild")
def rebuild_index(req: IndexRequest):
    store.rebuild(GOODS_COLLECTION, _to_vectors(req.items))
    return {"success": True, "total": store.count(GOODS_COLLECTION)}
```

**注意**：`/ai/index` 与 `/ai/index/rebuild` 都挂在 `prefix="/ai/index"` 下，分别对应 `POST /ai/index` 与 `POST /ai/index/rebuild`。

- [ ] **Step 5: 写 routers/search.py**

`ai-service/app/routers/search.py`：

```python
from fastapi import APIRouter
from pydantic import BaseModel

from app.config import settings
from app.deps import store
from app.embedding import embedder
from app.routers.index import GOODS_COLLECTION

router = APIRouter(prefix="/ai", tags=["search"])


class SearchRequest(BaseModel):
    query: str
    top_k: int = 5
    min_score: float | None = None


@router.post("/search")
def search(req: SearchRequest):
    threshold = settings.sim_threshold if req.min_score is None else req.min_score
    vector = embedder.embed_texts([req.query])[0]
    hits = store.search(GOODS_COLLECTION, vector, req.top_k, threshold)
    return {
        "mode": embedder.mode(),
        "results": [{"goodsId": h["id"], "score": h["score"]} for h in hits],
    }
```

- [ ] **Step 6: 改 main.py 注册路由**

`ai-service/app/main.py` 全量替换为：

```python
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.deps import knowledge
from app.routers import embed, health, index, search


@asynccontextmanager
async def lifespan(_: FastAPI):
    # 启动时装载知识库：保证 FAQ 检索始终可用
    knowledge.load()
    yield


app = FastAPI(title="校园二手交易平台 AI 服务", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
app.include_router(embed.router)
app.include_router(index.router)
app.include_router(search.router)
```

**注意**：`store` 与 `knowledge` 不在此文件创建，统一由 `app/deps.py` 提供，避免与 `routers/index.py` 形成循环 import。

- [ ] **Step 7: 运行测试确认通过**

Run: `cd ai-service && python -m pytest tests/ -v`
Expected: 全部 passed（含健康检查、知识库、embed、index、search）

- [ ] **Step 8: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add ai-service
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增向量化、索引与语义搜索接口"
```

---

## 任务 7：接口层 —— /ai/chat（RAG 问答）

**Files:**
- Create: `ai-service/app/routers/chat.py`
- Modify: `ai-service/app/main.py`（注册 chat 路由）
- Modify: `ai-service/tests/test_api.py`（追加用例）
- Modify: `ai-service/app/routers/health.py`（返回真实模式）

- [ ] **Step 1: 写失败测试**

追加到 `ai-service/tests/test_api.py`：

```python
def test_chat_falls_back_to_extractive_without_api_key(client):
    resp = client.post("/ai/chat", json={"question": "怎么退款", "top_k": 3})
    assert resp.status_code == 200
    body = resp.json()
    assert body["mode"] == "extractive"
    assert body["answer"]
    assert body["sources"], "应返回引用来源"


def test_chat_with_empty_question_still_answers(client):
    resp = client.post("/ai/chat", json={"question": "", "top_k": 3})
    assert resp.status_code == 200
    assert resp.json()["answer"]


def test_health_reports_real_modes(client):
    body = client.get("/health").json()
    assert body["embedding"]["mode"] in ("vector", "tfidf")
    assert body["llm"]["mode"] in ("api", "extractive")
    assert "count" in body["index"]
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_api.py -v`
Expected: FAIL，`/ai/chat` 返回 404

- [ ] **Step 3: 写 routers/chat.py**

`ai-service/app/routers/chat.py`：

```python
from fastapi import APIRouter
from pydantic import BaseModel

from app.config import settings
from app.deps import knowledge, store
from app.embedding import embedder
from app.knowledge import FAQ_COLLECTION
from app.llm import LLMUnavailable, llm
from app.prompts import RAG_QA, build_context, build_extractive_answer
from app.routers.index import GOODS_COLLECTION

router = APIRouter(prefix="/ai", tags=["chat"])


class HistoryItem(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    question: str
    history: list[HistoryItem] = []
    top_k: int = 4


@router.post("/chat")
def chat(req: ChatRequest):
    question = (req.question or "").strip()
    if not question:
        return {"answer": "请告诉我你想问什么，比如「怎么退款」「有没有便宜的台灯」。",
                "mode": llm.mode(), "sources": []}

    sources = _retrieve(question, req.top_k)
    snippets = [s["snippet"] for s in sources]

    try:
        prompt = RAG_QA.format(context=build_context(snippets), question=question)
        answer = llm.generate(prompt)
        mode = "api"
    except LLMUnavailable:
        answer = build_extractive_answer(snippets)
        mode = "extractive"

    return {"answer": answer, "mode": mode, "sources": sources}


def _retrieve(question: str, top_k: int) -> list[dict]:
    """同时检索 FAQ 与在售商品，合并后按相似度降序取前 top_k。"""
    vector = embedder.embed_texts([question])[0]

    faq_hits = store.search(FAQ_COLLECTION, vector, top_k, settings.sim_threshold)
    goods_hits = store.search(GOODS_COLLECTION, vector, top_k, settings.sim_threshold)

    sources = []
    for hit in faq_hits:
        entry = knowledge.entries.get(hit["id"])
        if entry:
            sources.append({
                "type": "faq",
                "title": entry["title"],
                "snippet": entry["content"],
                "score": hit["score"],
            })
    for hit in goods_hits:
        sources.append({
            "type": "goods",
            "title": hit["text"],
            "snippet": hit["text"],
            "goodsId": hit["id"],
            "score": hit["score"],
        })

    sources.sort(key=lambda s: s["score"], reverse=True)
    return sources[:top_k]
```

- [ ] **Step 4: 写 routers/health.py（完整版）**

`ai-service/app/routers/health.py` 全量替换为：

```python
from fastapi import APIRouter

from app.config import settings
from app.deps import store
from app.embedding import embedder
from app.llm import llm
from app.routers.index import GOODS_COLLECTION

router = APIRouter()


@router.get("/health")
def health():
    return {
        "status": "ok",
        "embedding": {
            "mode": embedder.mode(),
            "dim": embedder.dim(),
            "model": settings.emb_model,
        },
        "llm": {"mode": llm.mode(), "model": settings.llm_model or None},
        "index": {"count": store.count(GOODS_COLLECTION)},
    }
```

- [ ] **Step 5: 在 main.py 注册 chat 路由**

修改 `ai-service/app/main.py` 的两处：

```python
from app.routers import chat, embed, health, index, search
```
```python
app.include_router(chat.router)
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd ai-service && python -m pytest tests/ -v`
Expected: 全部 passed

- [ ] **Step 7: 手动验证真实模型路径（可选，耗时较长）**

```bash
cd ai-service
python -c "from app.embedding import Embedder; e=Embedder(); print(e.mode(), e.dim())"
```
不设 `EMB_FORCE_TFIDF` 时 Expected: 首次运行会下载约 100MB 模型，随后输出 `vector 512`。

- [ ] **Step 8: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add ai-service
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增 RAG 问答接口与真实模式健康检查"
```

---

## 任务 8：Java 侧接入 —— AiClient

**Files:**
- Create: `demo1/src/main/java/com/example/demo/service/AiClient.java`

**设计要点**：用 Spring 自带的 `RestTemplate`（无需新增依赖）；所有方法在异常时返回空集合，保证 AI 服务宕机时业务不崩。

- [ ] **Step 1: 写 AiClient.java**

```java
package com.example.demo.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 调用 Python AI 服务的客户端。
 * 设计原则：任何异常都吞掉并返回空结果，AI 服务不可用时业务退化为关键词搜索。
 */
@Component
public class AiClient {

    private static final Logger logger = LoggerFactory.getLogger(AiClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AiClient(@Value("${ai.service.url:http://localhost:8000}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(25));
        this.restTemplate = new RestTemplate(factory);
    }

    /** 语义搜索，返回 [{goodsId, score}]；失败返回空列表。 */
    public List<Map<String, Object>> search(String query, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("top_k", topK);

            JSONObject resp = post("/ai/search", body);
            if (resp == null) {
                return results;
            }
            JSONArray arr = resp.getJSONArray("results");
            if (arr == null) {
                return results;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                Map<String, Object> hit = new HashMap<>();
                hit.put("goodsId", item.getLong("goodsId"));
                hit.put("score", item.getDouble("score"));
                results.add(hit);
            }
        } catch (Exception e) {
            logger.warn("语义搜索调用失败，将退化为关键词搜索：{}", e.getMessage());
        }
        return results;
    }

    /** 增量更新单件商品的索引；失败静默。 */
    public void indexGoods(Long goodsId, String text) {
        try {
            Map<String, Object> item = new HashMap<>();
            item.put("goodsId", goodsId);
            item.put("text", text);
            Map<String, Object> body = new HashMap<>();
            body.put("items", List.of(item));
            post("/ai/index", body);
        } catch (Exception e) {
            logger.warn("商品索引推送失败 goodsId={}：{}", goodsId, e.getMessage());
        }
    }

    /** 全量重建索引；返回是否成功。 */
    public boolean rebuildIndex(List<Map<String, Object>> items) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("items", items);
            JSONObject resp = post("/ai/index/rebuild", body);
            return resp != null && Boolean.TRUE.equals(resp.getBoolean("success"));
        } catch (Exception e) {
            logger.warn("索引重建失败：{}", e.getMessage());
            return false;
        }
    }

    private JSONObject post(String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request =
                new HttpEntity<>(JSON.toJSONString(body), headers);
        ResponseEntity<String> resp =
                restTemplate.postForEntity(baseUrl + path, request, String.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            return null;
        }
        return JSON.parseObject(resp.getBody());
    }
}
```

**文件说明**：`AiClient` 只用 Spring 自带的 `RestTemplate`（不新增依赖），并设置 2s 连接超时、25s 读超时；任何异常都返回空结果，因此 AI 服务宕机时业务自动退化为关键词搜索。

- [ ] **Step 2: 编译验证**

Run: `cd demo1 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add demo1
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增 Java 侧 AI 服务客户端"
```

---

## 任务 9：Java 侧接口 —— 语义搜索与索引重建

**Files:**
- Create: `demo1/src/main/java/com/example/demo/controller/AiController.java`
- Modify: `demo1/src/main/java/com/example/demo/controller/ApiController.java`（发布商品后推送索引）

**设计要点**：语义搜索在 Java 侧完成过滤与回填。流程：AI 召回 `top_k * 3` 个 id → 查 MySQL → 按分类/价格/在售过滤 → 截断 `top_k`。AI 不可用时返回空结果，由前端回退到普通搜索。

- [ ] **Step 1: 写 AiController.java**

```java
package com.example.demo.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.entity.Goods;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.service.AiClient;

/**
 * AI 相关业务接口。语义召回在 Python 服务完成，过滤与回填在本层完成，
 * 保证价格、分类、在售状态以 MySQL 为唯一数据源。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final int OVER_FETCH_FACTOR = 3;

    @Autowired
    private AiClient aiClient;

    @Autowired
    private GoodsRepository goodsRepository;

    /** 语义搜索：关键词→语义召回→MySQL 回填→分类/价格/在售过滤。 */
    @GetMapping("/search")
    public ResponseEntity<?> semanticSearch(
            @RequestParam String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "12") int topK) {

        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(Map.of("success", true, "semantic", false, "data", List.of()));
        }

        List<Map<String, Object>> hits = aiClient.search(keyword.trim(), topK * OVER_FETCH_FACTOR);
        if (hits.isEmpty()) {
            // AI 服务不可用或无召回：交由前端回退到关键词搜索
            return ResponseEntity.ok(Map.of("success", true, "semantic", false, "data", List.of()));
        }

        List<Goods> filtered = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            if (filtered.size() >= topK) {
                break;
            }
            Long goodsId = (Long) hit.get("goodsId");
            Goods goods = goodsRepository.findById(goodsId).orElse(null);
            if (goods == null) {
                continue;
            }
            if ("已售".equals(goods.getStatus())) {
                continue;
            }
            if (category != null && !category.isBlank() && !category.equals(goods.getCategory())) {
                continue;
            }
            if (maxPrice != null && goods.getPrice() != null
                    && goods.getPrice().doubleValue() > maxPrice) {
                continue;
            }
            filtered.add(goods);
        }

        return ResponseEntity.ok(Map.of("success", true, "semantic", true, "data", filtered));
    }

    /** 全量重建向量索引：把当前所有在售商品推送给 AI 服务。 */
    @PostMapping("/reindex")
    public ResponseEntity<?> reindex() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Goods goods : goodsRepository.findAll()) {
            if ("已售".equals(goods.getStatus())) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("goodsId", goods.getId());
            item.put("text", buildIndexText(goods));
            items.add(item);
        }
        boolean ok = aiClient.rebuildIndex(items);
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "indexed", ok ? items.size() : 0,
                "message", ok ? "索引重建完成" : "AI 服务不可用，索引未重建"));
    }

    /** 商品索引文本：标题 + 分类 + 描述，供 Python 侧向量化。 */
    public static String buildIndexText(Goods goods) {
        String title = goods.getTitle() == null ? "" : goods.getTitle();
        String category = goods.getCategory() == null ? "" : goods.getCategory();
        String description = goods.getDescription() == null ? "" : goods.getDescription();
        return (title + " " + category + " " + description).trim();
    }
}
```

- [ ] **Step 2: 在 ApiController 中接入索引推送**

修改 `demo1/src/main/java/com/example/demo/controller/ApiController.java`：

在 import 区加入：

```java
import com.example.demo.service.AiClient;
```

（`AiController` 与 `ApiController` 同属 `com.example.demo.controller` 包，无需 import。）

在字段区（与 `goodsImageRepository` 同级）加入：

```java
    @Autowired
    private AiClient aiClient;
```

在 `addGoods` 方法中，把这一行：

```java
            return ResponseEntity.ok("{\"success\":true,\"goodsId\":" + goods.getId() + "}");
```

替换为：

```java
            // 发布成功后推送索引，使新商品立即能被语义搜索命中；失败不影响发布结果
            aiClient.indexGoods(goods.getId(), AiController.buildIndexText(goods));

            return ResponseEntity.ok("{\"success\":true,\"goodsId\":" + goods.getId() + "}");
```

- [ ] **Step 3: 在 application.properties 中加入 AI 服务地址**

追加到 `demo1/src/main/resources/application.properties`：

```properties
# Python AI 服务地址
ai.service.url=http://localhost:8000
```

- [ ] **Step 4: 编译验证**

Run: `cd demo1 && mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add demo1
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "feat(ai): 新增语义搜索与索引重建接口，发布商品自动推送索引"
```

---

## 任务 10：端到端验收

**Files:**
- 无新增文件（纯验证）

**前置**：三个服务均已启动。

- [ ] **Step 1: 启动 AI 服务**

```bash
cd ai-service
uvicorn app.main:app --port 8000
```
Expected: 日志出现 `知识库已装载 20 条`

- [ ] **Step 2: V1 健康检查**

Run: `Invoke-RestMethod -Uri "http://localhost:8000/health" | ConvertTo-Json`
Expected: `status: ok`，且 `embedding.mode`、`llm.mode`、`index.count` 均为真实值

> **Windows 命令注意事项**：PowerShell 中 `curl` 是 `Invoke-WebRequest` 的别名，参数与 curl 不兼容；且含中文的 JSON 必须显式 UTF-8 编码，否则会乱码或被拒。以下统一用 `Invoke-RestMethod` + `UTF8.GetBytes`。

- [ ] **Step 3: V7 全量重建索引（演示前必做）**

Run: `Invoke-RestMethod -Uri "http://localhost:8080/api/ai/reindex" -Method Post | ConvertTo-Json`
Expected: `success: True`、`indexed: N`，N 为在售商品数

- [ ] **Step 4: V3 语义召回**

```powershell
$body  = '{"query":"能降噪的耳机","top_k":5}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri "http://localhost:8000/ai/search" -Method Post -Body $bytes -ContentType "application/json; charset=utf-8" | ConvertTo-Json
```
Expected: `results` 首个 `goodsId` 命中"索尼 WH-1000XM5 头戴式耳机"

- [ ] **Step 5: V6 Java 链路（keyword 用 URL 编码的"降噪耳机"）**

Run: `Invoke-RestMethod -Uri "http://localhost:8080/api/ai/search?keyword=%E9%99%8D%E5%99%AA%E8%80%B3%E6%9C%BA" | ConvertTo-Json -Depth 5`
Expected: `semantic: True`，`data` 数组首项为索尼耳机

- [ ] **Step 6: V4 生成降级**

```powershell
$body  = '{"question":"怎么退款","top_k":3}'
$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
Invoke-RestMethod -Uri "http://localhost:8000/ai/chat" -Method Post -Body $bytes -ContentType "application/json; charset=utf-8" | ConvertTo-Json -Depth 5
```
Expected: `mode: "extractive"`，`answer` 含退款相关原文，`sources` 非空

- [ ] **Step 7: 可用性兜底验证**

关闭 AI 服务进程，再执行 Step 5。
Expected: `semantic: False`、`data: []`，**Spring Boot 不抛异常、不返回 500**

- [ ] **Step 8: V8 全量单测**

Run: `cd ai-service && python -m pytest tests/ -v`
Expected: 全部 passed

- [ ] **Step 9: 最终提交**

```bash
git -C "d:\作业\毕业设计\campus-secondhand" add -A
git -C "d:\作业\毕业设计\campus-secondhand" commit -m "test(ai): P0 端到端验收通过"
```

---

## Spec 覆盖自查

| Spec 章节 | 对应任务 |
|---|---|
| 2.1 服务骨架 | 任务 1 |
| 2.1 向量化（含降级） | 任务 2 |
| 2.1 向量存储与检索 | 任务 3 |
| 2.1 生成能力（含降级） | 任务 4 |
| 2.1 Prompt 管理 | 任务 4 |
| 2.1 知识库加载 | 任务 5 |
| 5.1 `/health` | 任务 1（最小）、任务 7（完整） |
| 5.2 `/ai/embed` | 任务 6 |
| 5.3 `/ai/index` | 任务 6 |
| 5.4 `/ai/index/rebuild` | 任务 6 |
| 5.5 `/ai/search` | 任务 6 |
| 5.6 `/ai/chat` | 任务 7 |
| 5.5 Java 侧过滤与回填 | 任务 9 |
| 4.2 索引同步（增量 + 全量） | 任务 9 |
| 2.1 Java 侧客户端封装 | 任务 8 |
| 6.1 生成降级 | 任务 4、7 |
| 7.3 配置项 | 任务 1 |
| 9 V1-V8 验证 | 任务 10 |
| 5.7 错误约定 | 任务 6、7（参数非法由 FastAPI 返回 400；索引为空返回 200 空数组；LLM 失败返回 200 + `extractive`） |
| 6.2 向量降级 | 任务 2（实现改用 HashingVectorizer，偏差见文首说明） |
| 7.4 AI 不可用时业务回退 | 任务 8、9、任务 10 Step 7 |

**无未覆盖的 Spec 需求。** 唯一的实现偏差（6.2 节的 TF-IDF → HashingVectorizer）已在文首"与 Spec 的一处偏差"中说明理由。