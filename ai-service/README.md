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
| 怎么退款 | 0.6396 | 退款规则 | 0.2132 |
| 怎么发布闲置 | 0.5547 | 发布闲置 | 0.3162 |
| 商品被别人买走了怎么办 | 0.2462 | 商品已售 | 0.1741 |
| 能降噪的耳机 | 0.3651 | 索尼 WH-1000XM5 头戴式耳机 | 0.2357（AirPods Pro 2，也算相关） |
| 有没有便宜的台灯 | 0.2236 | 飞利浦台灯 | 0.0000 |
| 高数教材 | 0.2673 | 线性代数（第六版） | 0.1890 |
| 发霉（领域外） | 0.0000 | — | 0.0000 |

**分词清洗的作用**：词袋没有 IDF 加权，若不过滤停用词，一个「的」就能制造假命中
（实测「有没有便宜的台灯」曾以 0.2085 命中「修改昵称」）。`embedding._jieba_tokenize`
因此滤掉停用词与单字，命中分数整体抬升、假命中归零。**改词表后必须重建索引**
（`POST /api/ai/reindex`），否则查询向量与索引向量不同源。

## 测试

```bash
cd ai-service
python -m pytest tests/ -v
```
