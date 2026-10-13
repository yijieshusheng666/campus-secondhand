# P0 设计文档：AI 能力底座

- 日期：2026-10-13
- 范围：本文件只覆盖 P0（AI 能力底座）。P1-P4 不在本文件内。
- 状态：已确认，待实施

---

## 1. 背景与目标

### 1.1 背景

校园二手交易平台（Spring Boot 3.5 + Vue 3 + MySQL）已完成基础业务：商品发布/浏览/搜索、下单、订单管理、智能客服、买卖双方沟通。当前存在两个问题：

- **创新点不足**：现有功能均为通用二手平台的通用功能。
- **AI 名不副实**：现有"智能客服"是关键词匹配 + 规则兜底，`/api/recommend` 是手工打分的字符串包含，无法支撑答辩深挖。

### 1.2 目标的锁定

后续 P1-P4 共 15 项功能中，有 6 项属于 AI 能力（RAG 问答、语义搜索、对话摘要、智能定价、内容风控、多模态识别）。若按功能顺序开发，这 6 项会各自重复实现"出向量 / 检索 / 调模型"三件事。

**P0 的目标：把这三件事做成一次建设、处处复用的能力层。** P0 完成后，P1 的语义搜索应退化为"Java 加一个接口 + 前端换个参数"，而非从零开发。

### 1.3 成功标准

| 编号 | 标准 | 验证方式 |
|---|---|---|
| S1 | 能对任意中文文本产出向量 | 调用 `/ai/embed` 返回固定维度向量 |
| S2 | 能按语义相似度召回商品 | 检索"能降噪的耳机"命中"索尼 WH-1000XM5 头戴式耳机" |
| S3 | 无 API key / 网络失败时仍可用 | 切断外部网络后 `/ai/chat` 仍返回可用回答 |
| S4 | 索引与数据库一致 | 新发布商品后立即可被检索命中 |
| S5 | Java 服务能调通整条链路 | 浏览器发一个请求，Java 转发到 AI 服务并返回结果 |

---

## 2. 范围

### 2.1 本阶段实现

- 新建独立 Python 服务 `ai-service/`（FastAPI）
- 文本向量化能力（本地模型 + TF-IDF 降级）
- 向量存储与余弦相似度检索（内存 + 持久化文件）
- 生成能力抽象（云端 API + 抽取式降级）
- Prompt 模板集中管理
- 平台知识库（FAQ）加载
- HTTP 接口：`/health`、`/ai/embed`、`/ai/search`、`/ai/index`、`/ai/index/rebuild`、`/ai/chat`
- Java 侧调用 AI 服务的客户端封装

### 2.2 本阶段明确不做

- 不引入 Milvus / Elasticsearch / pgvector（数据量仅几十至数百条，numpy 暴力计算即可）
- 不做模型微调、不做分布式部署、不做流式输出
- 不做用户自建知识库
- 不做 Docker 编排（本机未安装 Docker，见 7.1）
- 不实现 `/ai/summarize`、`/ai/negotiate`、`/ai/price-suggest`、`/ai/moderate`（分别属于 P1、P4，本阶段不写空壳接口）

### 2.3 边界澄清：`/ai/chat` 为什么在 P0 而不是 P1

AI 能力层若只包含"能算向量"，不构成完整的能力层。生成能力、Prompt 管理、降级策略本身就是底座的组成部分，因此 **P0 交付 `/ai/chat` 的最小可用版本（检索 + 生成 + 降级）**。

P1 负责的是"产品化"：把它接到前端客服面板、扩充知识库、加入多轮记忆、补充对比实验。二者不重复。

---

## 3. 架构与调用链

```
Vue (5173) ──HTTP──> Spring Boot (8080) ──HTTP──> FastAPI (8000)
   业务前端              业务逻辑 / 鉴权 / 落库          AI 能力（无状态）
```

### 3.1 为什么前端不直连 AI 服务

| 方案 | 优点 | 缺点 |
|---|---|---|
| 前端直连 FastAPI | 少一跳 | 需前端维护两份跨域配置；鉴权分散；AI 服务需公网暴露 |
| **前端 → Java → FastAPI（采用）** | 统一入口，鉴权与日志集中；AI 服务可只在内网暴露；符合微服务叙事 | 多一跳，增加约 10-30ms |

对毕设规模，多一跳的代价可接受，换取的是架构清晰度。

### 3.2 AI 服务的状态性

AI 服务**不直接访问 MySQL**。所有商品文本由 Java 侧推送（见 4.2）。这样 AI 服务对业务数据库无依赖，职责单一，也便于独立测试。

---

## 4. 模块划分

新增目录 `ai-service/`，位于项目根目录下（与 `demo1/`、`frontend/` 平级）。

```
ai-service/
├── app/
│   ├── main.py            # FastAPI 入口 + CORS + 路由注册
│   ├── config.py          # 环境变量配置（模型名、API key、阈值、索引目录）
│   ├── embedding.py       # 文本向量化（本地模型 / TF-IDF 降级）
│   ├── vector_store.py    # 向量存取 + 余弦相似度检索 + 持久化
│   ├── llm.py             # 生成能力抽象（API Provider / 抽取式降级）
│   ├── prompts.py         # Prompt 模板集中管理
│   ├── knowledge.py       # FAQ 知识库加载与检索
│   └── routers/
│       ├── health.py
│       ├── embed.py
│       ├── index.py
│       ├── search.py
│       └── chat.py
├── data/
│   ├── faq.json           # 平台规则 / 交易流程知识库（30-50 条）
│   └── index/             # 向量索引持久化文件（运行时生成）
├── requirements.txt
└── README.md
```

### 4.1 各模块职责

| 模块 | 单一职责 | 对外能力 | 依赖 |
|---|---|---|---|
| `embedding.py` | 文本 → 向量 | `embed_texts(list[str]) -> list[list[float]]`、`dim() -> int` | sentence-transformers 或 sklearn |
| `vector_store.py` | 向量存取与相似度检索，支持多个命名集合 | `upsert(collection, items)`、`search(collection, vector, top_k) -> list[(id, score)]`、`rebuild(collection, items)`、`save()`、`load()` | numpy |
| `llm.py` | 文本生成 | `generate(prompt: str) -> str`、`mode() -> "api" \| "extractive"` | httpx |
| `prompts.py` | 模板集中管理 | `RAG_QA`、`EXTRACTIVE` 等模板常量 | 无 |
| `knowledge.py` | 知识库加载与检索 | `load()`、`search(query_vector, top_k)`（内部使用 `faq` 集合） | vector_store |
| `routers/*.py` | HTTP 协议适配 | 见第 5 节 | 上述模块 |

**关键约束**：`embedding.py`、`vector_store.py`、`llm.py` 三个模块不感知 HTTP，也不感知"商品"这个业务概念，因此可独立单元测试。

### 4.2 商品索引的构建方式

**索引内容**：每条记录为 `{goodsId, text, vector}`，存放在 `vector_store` 的 **`goods` 集合**中。`text` 由 Java 侧拼接，格式建议：

```
{标题} {分类} {描述}
```

例：`索尼 WH-1000XM5 头戴式耳机 电子产品 行业领先的降噪，30 小时续航，黑色`

**同步策略：增量为主 + 全量兜底**

- **增量**：商品发布 / 修改 / 下架时，Java 调 `/ai/index` 推送该商品，AI 服务按 `goodsId` 覆盖更新。
- **全量兜底**：提供 `/ai/index/rebuild`，Java 推送全量商品列表，AI 服务清空后重建。演示前调用一次，保证索引与数据库一致。

选择"Java 推送"而非"Python 定时拉取 MySQL"的原因：避免 AI 服务承担数据库耦合；索引更新时机与业务写操作对齐，无延迟。

**已知取舍**：若推送失败（AI 服务未启动），索引会与数据库不一致。缓解方式为演示前调用一次 `/ai/index/rebuild`，不做额外的一致性保障机制。

---

## 5. 接口契约

所有接口均为 `POST`（除 `/health`），请求与响应均为 `application/json`。

### 5.1 `GET /health`

响应：

```json
{
  "status": "ok",
  "embedding": { "mode": "vector", "dim": 512, "model": "BAAI/bge-small-zh-v1.5" },
  "llm": { "mode": "api", "model": "qwen-plus" },
  "index": { "count": 28 }
}
```

`embedding.mode` 取值：`vector`（本地模型）或 `tfidf`（降级）。
`llm.mode` 取值：`api`（云端可用）或 `extractive`（降级）。

### 5.2 `POST /ai/embed`

请求：

```json
{ "texts": ["索尼WH-1000XM5 降噪耳机", "高等数学 教材"] }
```

响应：

```json
{ "dim": 512, "vectors": [[0.12, -0.03, ...], [0.07, 0.21, ...]] }
```

### 5.3 `POST /ai/index`

按 `goodsId` 覆盖式更新（存在则替换，不存在则追加）。

请求：

```json
{
  "items": [
    { "goodsId": 8, "text": "索尼 WH-1000XM5 头戴式耳机 电子产品 行业领先的降噪" }
  ]
}
```

响应：

```json
{ "success": true, "indexed": 1, "total": 28 }
```

### 5.4 `POST /ai/index/rebuild`

全量重建。请求体同 `/ai/index`；`items` 为空数组表示清空索引。

响应：

```json
{ "success": true, "total": 28 }
```

### 5.5 `POST /ai/search`

请求：

```json
{
  "query": "能降噪的耳机",
  "top_k": 5,
  "min_score": 0.35
}
```

`top_k` 默认 `5`，`min_score` 默认取配置项 `SIM_THRESHOLD`；两者都可被请求覆盖。低于 `min_score` 的候选直接丢弃。

响应：

```json
{
  "mode": "vector",
  "results": [
    { "goodsId": 8, "score": 0.82 }
  ]
}
```

**关于结构化过滤（价格 / 分类）**：本接口**不做**价格与分类过滤，只做语义召回。原因：商品的价格、分类、在售状态以 MySQL 为唯一数据源，若在 AI 侧复制一份会造成数据不一致。

因此由 **Java 侧承担过滤职责**：

1. Java 以 `top_k * 3` 作为召回车数（超取），避免过滤后结果不足；
2. Java 用召回的 `goodsId` 从 MySQL 取完整商品；
3. 在 Java 侧按分类 / 价格区间 / 在售状态过滤，再截断到 `top_k`。

**已知取舍**：过滤是后置的，若筛选条件很紧，最终结果可能少于 `top_k`。数据集规模小（数百条）时超取 3 倍已足够覆盖。

### 5.6 `POST /ai/chat`

请求：

```json
{
  "question": "怎么退款？",
  "history": [
    { "role": "user", "content": "你好" },
    { "role": "assistant", "content": "你好呀！" }
  ],
  "top_k": 4
}
```

响应：

```json
{
  "answer": "在「我的订单」中，待发货状态的订单可以申请退款，退款后宝贝会自动重新上架。",
  "mode": "api",
  "sources": [
    { "type": "faq", "title": "退款规则", "snippet": "在\"我的订单\"中，待发货状态的订单可以申请退款..." },
    { "type": "goods", "goodsId": 8, "title": "索尼 WH-1000XM5 头戴式耳机", "score": 0.78 }
  ]
}
```

`mode` 取值：`api`（LLM 生成）或 `extractive`（降级，见 6.2）。
`sources` 为引用来源，前端用于展示"依据"卡片。

### 5.7 错误约定

| 情况 | HTTP 状态 | 响应体 |
|---|---|---|
| 参数缺失/非法 | 400 | `{"detail": "..."}`（FastAPI 默认格式） |
| 索引为空 | 200 | `{"results": []}`，不算错误 |
| LLM 调用失败 | 200 | `mode: "extractive"`，返回降级回答，不报错 |

**设计意图**：AI 能力不可用时降级而非报错，保证前端体验连续。

---

## 6. 关键设计：两级降级

这是整个底座的保险丝，用于保证答辩现场断网或缺少 API key 时系统依然可用。

### 6.1 生成能力降级

```
有 API key 且网络通        →  LLM 真实生成        mode = "api"
无 key / 网络失败 / 超时   →  抽取式回答          mode = "extractive"
```

**抽取式回答的做法**：不做生成，将检索到的 Top-N 原文片段模板化拼装：

```
根据平台资料，我找到以下相关信息：
1. {片段1}
2. {片段2}
如果还需要更详细的说明，可以再问我～
```

该路径不依赖任何外部服务，纯本地执行。

### 6.2 向量化降级

```
本地模型加载成功  →  sentence-transformers 向量  mode = "vector"
模型加载失败      →  jieba 分词 + TF-IDF         mode = "tfidf"
```

### 6.3 降级的附带价值

TF-IDF 与向量检索的并存，直接构成了论文中的**对比实验**：在同一批查询上比较两种召回方式的准确率。这回答了评委常见的"你怎么证明引入 AI 是有效的"。

---

## 7. 依赖、配置与风险

### 7.1 Docker 的处理（环境探测结论）

**本机未安装 Docker。** 因此：

- 演示与开发走**本地三进程**手动启动：Vite(5173) / Spring Boot(8080) / FastAPI(8000)
- Dockerfile 与 docker-compose.yml 作为论文"部署方案"章节的产物后续补充，但**不作为运行前提**

### 7.2 依赖清单（`requirements.txt`）

```
fastapi
uvicorn[standard]
pydantic
numpy
httpx
sentence-transformers
jieba
scikit-learn
python-dotenv
```

**风险**：`sentence-transformers` 会引入 PyTorch，下载体积较大（数百 MB 至 GB 级）。

**缓解**：默认模型选用体积较小的中文模型 `BAAI/bge-small-zh-v1.5`（约 100MB，512 维）。若 PyTorch 安装受阻，可直接使用 TF-IDF 降级路径运行，功能不受影响（仅召回质量下降）。

### 7.3 配置项（环境变量，`.env`）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `EMB_MODEL` | `BAAI/bge-small-zh-v1.5` | 向量模型 |
| `EMB_FORCE_TFIDF` | `false` | 强制走 TF-IDF 降级 |
| `LLM_BASE_URL` | 空 | OpenAI 兼容接口地址 |
| `LLM_API_KEY` | 空 | 为空则直接走抽取式降级 |
| `LLM_MODEL` | 空 | 模型名 |
| `LLM_TIMEOUT` | `20` | 秒 |
| `SIM_THRESHOLD` | `0.35` | 召回最低相似度 |
| `INDEX_DIR` | `data/index` | 索引持久化目录 |

### 7.4 其他风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 索引与数据库不一致 | 新商品搜不到 | 演示前调用 `/ai/index/rebuild` |
| 后置过滤导致结果偏少 | 搜索结果不足 | 超取 `top_k * 3` |
| 相似度阈值不适用 | 无关结果被召回 | 阈值可配置，实施时用真实数据调 |
| AI 服务未启动 | Java 调用失败 | Java 侧捕获异常并回退到原有的关键词搜索 |

---

## 8. 目录与启动方式

启动顺序（本地开发）：

```
1. MySQL（已随环境运行）
2. cd ai-service && uvicorn app.main:app --port 8000
3. cd demo1 && mvn spring-boot:run          # 8080
4. cd frontend && npm run dev               # 5173
```

---

## 9. 验证方案

| 编号 | 验证项 | 命令 / 方式 | 通过标准 |
|---|---|---|---|
| V1 | 服务健康 | `curl localhost:8000/health` | 返回 `status: ok`，含 embedding/llm/index 实际模式 |
| V2 | 向量化 | `curl -X POST localhost:8000/ai/embed -d '{"texts":["耳机"]}'` | 返回 512 维向量 |
| V3 | 索引与召回 | 造 5 条商品 → `/ai/index/rebuild` → `/ai/search` 查"降噪耳机" | 命中"索尼 WH-1000XM5" |
| V4 | 生成降级 | 清空 `LLM_API_KEY` 后调 `/ai/chat` | `mode: "extractive"` 且 answer 非空 |
| V5 | 向量降级 | 设 `EMB_FORCE_TFIDF=true` 重启后重复 V3 | 仍能命中，`mode: "tfidf"` |
| V6 | Java 链路 | 浏览器触发一次搜索，观察 Spring Boot 日志与返回 | Java 成功转发并返回结果 |
| V7 | 索引一致性 | 新发布一件商品 → 立即搜索其关键词 | 能命中 |
| V8 | 单元测试 | `pytest`（embedding / vector_store / llm 三个模块） | 全部通过 |

---

## 10. 后续阶段（不在本文件范围）

| 阶段 | 内容 | 深度定位 |
|---|---|---|
| P1 | A1 RAG 客服接入前端、A2 语义搜索、A5 对话摘要/议价助手 | 做深，出论文核心章节与对比实验 |
| P2 | 学号实名、担保交易状态机、双向评价 + 信用分、纠纷仲裁 | 做通 |
| P3 | 求购反向撮合、课程号教材匹配、宿舍楼拼单、以物换物 | 做通 |
| P4 | A3 图片识别发布、A4 智能定价、A6 内容风控 | 做薄，可砍 |

**可退可进策略**：若时间紧张，从 P4 起往前砍。