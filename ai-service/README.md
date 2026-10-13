# AI 能力底座（P0）

校园二手交易平台的 AI 能力服务，为语义搜索、RAG 问答等提供向量化、向量检索与文本生成能力。

## 启动

```bash
cd ai-service
python -m pip install -r requirements.txt
uvicorn app.main:app --port 8000
```

启动后访问 http://localhost:8000/docs 查看接口文档。

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 服务状态与**实际生效**的模式（embedding / llm / 索引条数） |
| POST | `/ai/embed` | 文本 → 向量 |
| POST | `/ai/index` | 增量更新商品索引（按 goodsId 覆盖） |
| POST | `/ai/index/rebuild` | 全量重建 / 清空索引 |
| POST | `/ai/search` | 语义召回（只返回 goodsId + 分数，过滤交给 Java 侧） |
| POST | `/ai/chat` | RAG 问答：检索 FAQ + 商品 → 生成，附引用来源（A1） |
| POST | `/ai/chat/stream` | **SSE 流式问答**，首字 ≤1 秒（需求 §2.1.1） |
| POST | `/ai/summarize` | 对话摘要：规则抽取价格 / 见面约定 / 待确认事项（A5） |
| POST | `/ai/negotiate` | 议价话术：三档可直接发送的讲价说法（A5） |
| POST | `/ai/moderate` | 内容风控：违禁品 / 诈骗话术 / 站外联系方式（A6） |
| POST | `/ai/vision` | 图片识别自动发布（A3，**需要配置多模态模型**） |

`/ai/summarize` 与 `/ai/negotiate` 的 `mode` 取值：`api`（模型生成）或 `rule`（本地规则模板）。
`/ai/moderate` 的 `level` 取值：`ok` / `warn`（提示，可继续发布）/ `block`（禁止发布）。

## SSE 流式协议

`POST /ai/chat/stream`，响应 `text/event-stream`，以**空行**分事件：

| 事件 | 载荷 | 前端用途 |
|---|---|---|
| `meta` | `traceId` / `refused` / `confidence` / `sources` / `safetyTip` / `hint` | 先渲染依据卡片，不等正文 |
| `delta` | `text` | 正文增量，追加到气泡 |
| `done` | `mode` / `answer` / `hitIds` | 终态，`answer` 应等于所有 delta 拼接结果 |
| `error` | `available=false` | **仅由 Java 代理补发**（AI 服务不可用时），前端据此走兜底 |

无 LLM key 时走抽取式降级，但按 12 字一段推送，协议与体验保持一致。

> ⚠️ 转发 SSE 时**空行必须原样透传**。SSE 靠空行派发事件，
> 跳过空行会让所有事件糊在一起，前端一个都收不到。

## 运营端接口（需求 §2.3）

前缀 `/ai/ops`。配了 `OPS_TOKEN` 时需带请求头 `X-Ops-Token`；
带 `X-Operator` 会记入审计日志。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ai/ops/kb/shards` | 5 个分片及其条数 |
| GET | `/ai/ops/kb/entries?shard=&q=` | 知识列表 / 按分片或关键词筛选 |
| POST | `/ai/ops/kb/entries` | 新增或更新（自动增量同步两种索引） |
| DELETE | `/ai/ops/kb/entries/{id}` | 删除（会全量重建向量索引，避免孤儿向量） |
| POST | `/ai/ops/kb/import` | 批量导入 |
| GET | `/ai/ops/kb/entries.csv` | 批量导出（UTF-8 BOM，Excel 直接打开不乱码） |
| POST | `/ai/ops/kb/reindex` | 全量重建 BM25 + 向量索引 |
| GET | `/ai/ops/kb/audit?limit=` | 变更审计日志 |
| GET | `/ai/ops/stats/summary` | 问答量 / 成功率 / 拒答率 / 生效模式 |
| GET | `/ai/ops/stats/top-questions?limit=` | Top N 高频问题 |
| GET | `/ai/ops/stats/misses?limit=` | 未命中（拒答）样本 |
| GET | `/ai/ops/stats/misses.csv` | 未命中样本导出 |

埋点落盘在 `data/metrics/events.jsonl` 与 `data/metrics/audit.jsonl`（不进 git）。

## 检索架构

```
查询 → lexicon（归一化/错别字/校园简称/意图/季节加权）
     → BM25 稀疏召回 top-20  ┐
     → 向量稠密召回 top-20   ├→ RRF 融合 → 置信度 → 相对分裁剪 → Top-N
                             ┘
```

置信度公式：`conf = (0.6 × bm25归一 + 0.4 × vec归一) × abs_factor`

`abs_factor = min(1, best_vec / 0.25)`，下限 0.15。
**必须有这个因子**：否则向量分很低的查询（如「订机票」只有 0.09）
相对归一后变成 1.0，置信度越过拒答阈值，模型开始编。

需求 §2.2.2 指定的 CrossEncoder 重排**未接入**，改用 RRF + 相对分裁剪，
理由与代价见 `docs/AI-客服需求差异说明.md` §2.2.2。

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
| `VISION_BASE_URL` | 空 | **A3** 多模态模型地址（OpenAI 兼容） |
| `VISION_API_KEY` | 空 | 为空则图片识别返回不可用 |
| `VISION_MODEL` | 空 | 如 `glm-4v-flash` |
| `KNOWLEDGE_DIR` | `data/knowledge` | 5 个知识分片 JSON 所在目录 |
| `OPS_TOKEN` | 空 | 运营端接口令牌；留空表示本地演示不校验 |
| `REFUSE_THRESHOLD` | `0.35` | 低于此置信度强制拒答 |
| `HISTORY_TURNS` | `3` | 多轮上下文保留轮数 |

也可以写进 `ai-service/.env`（服务启动时会显式加载）。

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
python -m pytest tests/ -v     # 69 passed
```

## Docker

```bash
# 从仓库根目录构建（Dockerfile 需要 ai-service/ 的上下文）
docker build -f ai-service/Dockerfile -t campus-ai .

# 或用编排文件起全套
docker compose up -d
```

镜像默认装 **CPU 版 torch**：`sentence-transformers` 默认会拖 CUDA 版，
进而拉来 `nvidia-cublas` 等 400MB+ 依赖，小内存机器直接 OOM。
需要真 GPU 时加 `--build-arg TORCH_INDEX=cuda`。

> 本机未安装 Docker，镜像构建**未经实测**，仅做了 compose YAML 语法校验。

## 与需求规格的差异

见 `docs/AI-客服需求差异说明.md` —— 逐条对照需求规格，
标明完整实现 / 替代方案（CrossEncoder → RRF）/ 未实测项。
