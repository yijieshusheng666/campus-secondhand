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
