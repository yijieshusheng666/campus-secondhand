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
| `SIM_THRESHOLD` | `0.35` | **vector 模式**的召回最低相似度 |
| `SIM_THRESHOLD_TFIDF` | `0.20` | **tfidf 模式**的召回最低相似度（实测标定，见下） |
| `INDEX_DIR` | `data/index` | 索引持久化目录 |
| `FAQ_PATH` | `data/faq.json` | 知识库文件 |

## 降级设计

- **生成**：无 API key 或调用失败 → 抽取式回答（模板拼装检索原文）
- **向量**：模型加载失败或 `EMB_FORCE_TFIDF=true` → jieba + HashingVectorizer 词袋

两条降级路径都不依赖外部服务，保证离线可用。

## 召回阈值的标定

两种模式的余弦分布量级不同，因此 `config.default_threshold(mode)` 按模式取默认值：

| 模式 | 阈值 | 依据 |
|---|---|---|
| `vector` | 0.35 | bge 语义向量下同类文本余弦多在 0.6-0.9 |
| `tfidf` | 0.20 | 2026-10-13 真实数据实测：命中项 0.21-0.33，噪声项 ≤0.20 |

实测样例（tfidf 模式，27 件在售商品 + 20 条 FAQ）：

| 查询 | Top1 分数 | Top1 内容 | 噪声分数 |
|---|---|---|---|
| 能降噪的耳机 | 0.3273 | 索尼 WH-1000XM5 头戴式耳机 | 0.1387（加湿器） |
| 怎么退款 | 0.3162 | 退款规则 | 0.1031 |
| 怎么发布闲置 | 0.3333 | 发布闲置 | 0.1952 |
| 商品被别人买走了怎么办 | 0.3241 | 商品已售 | 0.1654 |
| 发霉（领域外） | 0.0000 | — | 0.0000 |

## 测试

```bash
cd ai-service
python -m pytest tests/ -v
```
