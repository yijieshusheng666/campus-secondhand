# 校园二手交易平台

面向高校场景的二手交易平台：Spring Boot + Vue 3 + **独立 AI 服务**（FastAPI）。
除常规的发布 / 浏览 / 下单 / 订单管理外，还包含语义搜索、RAG 智能客服、担保交易状态机、
双向评价、纠纷仲裁、求购反向撮合等校园特色功能。

## 只需改 N 处（拉下来跑不起来基本都是这里）

| # | 位置 | 改成什么 | 不改的后果 |
|---|---|---|---|
| 1 | `demo1/src/main/resources/application.properties` 的 `username` / `password` | 你本机 MySQL 的账号密码 | 启动即失败，报 `Access denied for user` |
| 2 | 先手动建库 | `CREATE DATABASE IF NOT EXISTS secondhand CHARACTER SET utf8mb4;` | 报 `Unknown database 'secondhand'`（Hibernate 只建表不建库） |
| 3 | （可选）`ai-service/.env` 的 `LLM_API_KEY` | 填了才走模型生成 | 不填也**能跑**，AI 生成自动降级为本地规则/抽取式 |
| 4 | （可选）`ai-service/.env` 的 `VISION_*` | 填了才能用图片识别发布 | 不填时该功能如实提示"未配置多模态模型" |

> 第 1 处的真实配置文件**已被 .gitignore 忽略**（含密码，不进仓库）。
> 首次请从 `application-template.properties` 复制一份：
> `cp demo1/src/main/resources/application-template.properties demo1/src/main/resources/application.properties`

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.5.13 / Java 17 / Spring Data JPA / MySQL 8.0 |
| AI 服务 | Python 3.11 / FastAPI / sentence-transformers / jieba + scikit-learn（降级） |
| 前端 | Vue 3 + Vite + Element Plus + Vue Router + Axios |

## 目录结构

```
├── demo1/                              # 后端 Spring Boot（8080）
│   └── src/main/java/com/example/demo/
│       ├── controller/                 # 业务接口：商品 / 订单 / 消息 / 评价 / 纠纷 / 求购 / AI 代理
│       │                               # 12 个 Controller，按职责拆分（Auth / Goods / User / Recommend / Index / AdminQuery / Dispute / Order / Wanted / Review / Message / Ai）
│       ├── web/                         # 通用工具：RequestUtils（参数解析）、ApiResponses（统一响应包装）
│       ├── entity/                     # JPA 实体（含 reviews / disputes / wanted）
│       ├── repository/                 # Spring Data JPA 仓库
│       ├── service/                    # AiClient / OrderStateMachine / CreditService / WantedMatchService …
│       └── DataInitializer.java        # 启动时按 seed-goods.json 初始化 30 件演示商品
│   └── src/main/resources/
│       └── seed-goods.json             # 演示商品种子数据（标题/分类/价格/描述/配图占位）
├── ai-service/                         # AI 能力服务（8000，不直接访问 MySQL）
│   ├── app/                            # config / embedding / vector_store / llm / knowledge / assist /
│   │                                   # moderation / vision / routers
│   ├── data/knowledge/                 # 知识库（faq / campus / guides / rules / reference）
│   └── tests/                          # 79 个单测
├── frontend/                           # 前端 Vue 3（5173，Vite 6）
│   └── src/
│       ├── api.js                      # axios 实例（baseURL=/api，超时 10s）
│       ├── session.js                  # 当前登录用户的唯一数据源（共享 ref + 持久化）
│       ├── icons.js / avatars.js       # 图标 / 头像路径集中导出
│       ├── composables/                # 可复用组合式函数
│       │   ├── useCurrentUser.js       # 包装 session.js 的 ref，自动响应式
│       │   └── useApiRequest.js        # 统一 loading + ElMessage.error + 抛出，13 个视图调用
│       ├── views/                      # 9 个路由视图（按需懒加载）
│       └── components/                 # ChatPanel / ReviewDialog / SectionTabs
│   └── public/
│       ├── images/                     # logo + 内置头像/分类图标/订单状态图标（统一静态资源）
│       └── favicon.png / apple-touch-icon.png
├── docs/
│   ├── ROADMAP.md                      # 功能「界面入口 + 现状」对照表
│   ├── verify/                         # 功能验收截图
│   └── superpowers/                    # P0 AI 底座的设计文档与实施计划
└── README.md
```

## 端口约定

| 服务 | 端口 | 说明 |
|---|---|---|
| MySQL | 3306 | 库名 `secondhand` |
| AI 服务 | 8000 | 只监听 127.0.0.1，**不对外暴露** |
| 后端 | 8080 | 前端通过 Vite 代理访问它 |
| 前端 | 5173 | 访问入口 |

改端口要同步改**两处**：`frontend/vite.config.js` 的 `proxy` 目标、以及
`application.properties` 的 `ai.service.url`（如果改的是 AI 服务端口）。

**前端不直连 AI 服务**：Vue(5173) → Spring Boot(8080) → FastAPI(8000)，鉴权与日志集中在后端。

## 启动步骤

### 0. 建库（最容易漏的一步）

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS secondhand CHARACTER SET utf8mb4;"
```

### 1. AI 服务（可选，但语义搜索/客服/摘要都依赖它）

```bash
cd ai-service
# 用虚拟环境或你本机的 Python 3.11，不要用 PATH 上的裸 python
python -m pip install -r requirements.txt
python -m pytest tests/ -q          # 应为 79 passed（测试一律走词袋模式，且已与本地 .env 隔离）
python -m uvicorn app.main:app --port 8000
```

**Windows 下推荐双击 `ai-service/start-ai-service.cmd` 启动**：脚本会自动检查 `.venv`、
把 `TEMP/TMP` 指到项目内（绕开系统 TEMP 的权限问题）、检测 8000 端口是否已被占用，
然后前台运行，关窗口即停。健康检查 <http://127.0.0.1:8000/health>，
接口文档 <http://127.0.0.1:8000/docs>。

### 智能客服的流式输出

客服面板走 SSE 流式，链路是：

```
ChatPanel.vue  fetch('/api/ai/chat/stream') + ReadableStream   ← 前端
  → Vite 代理 /api → 8080（已关掉 SSE 缓冲，否则增量会被攒成一坨）
  → AiController#chatStream  StreamingResponseBody            ← Java 中转
  → AiClient#streamChat  逐行 readLine + flush
  → ai-service  POST /ai/chat/stream                          ← Python
```

事件协议：`meta`（检索元信息）→ `delta`（文本增量，可重复多次）→ `done`（完整答案 + 统计）。
模型流到一半中断时会补一条 `error`（带 `text`＝抽取式答案），前端整段替换已渲染内容。
配了 `LLM_*` 三项就是真模型流式增量；没配也会按中文标点分段推送，协议保持一致。
未配 LLM 时 Java 侧会在流里补一条 `event: error`，前端据此回退到关键词推荐。

注意：Java 是中转而非旁路，所以 Spring Boot 不启动时客服面板拿不到流式回答。

中文向量模型 `BAAI/bge-small-zh-v1.5`（约 100MB）**建议先下好**，语义检索才算真正生效：

```bash
# huggingface.co 连不上时用镜像（实测可用）
set HF_ENDPOINT=https://hf-mirror.com          # macOS/Linux: export HF_ENDPOINT=...
python -c "from huggingface_hub import snapshot_download; snapshot_download('BAAI/bge-small-zh-v1.5')"
```

没下也没关系：会自动降级为 jieba 词袋模式，`/health` 里如实显示 `"mode": "tfidf"`，
功能不中断、只是语义召回弱一些（阈值也按模式分别标定，见 `ai-service/README.md`）。

### 2. 后端

```bash
cd demo1
mvn spring-boot:run                 # macOS / Linux
mvn.cmd spring-boot:run             # Windows
```

启动后访问 <http://localhost:8080/api> 应返回 `{"success":true,...}`。

### 3. 前端

```bash
cd frontend
npm install
npm run dev
```

访问 <http://localhost:5173>。**请用 `localhost` 而不是 `127.0.0.1`**：
Vite 在部分环境下只监听 IPv6 回环（`[::1]:5173`），用 IPv4 直连会连不上。

### 4. 演示前建议做一次索引对齐

```bash
curl -X POST http://localhost:8080/api/ai/reindex
```

把当前在售商品推给 AI 服务建索引，避免"新发布的商品搜不到"。

## 测试账号

| 账号 | 密码 | 身份 |
|---|---|---|
| `admin` | admin123 | 平台管理员（可进「管理端」裁定纠纷） |
| `test` | test123 | 普通用户 / 卖家 |
| `buyer01` | buyer123 | 普通用户（买家小明） |

## 功能一览

13 项功能的**界面入口与验收截图**见 [`docs/ROADMAP.md`](docs/ROADMAP.md)，此处只列分组：

- **AI 能力**：RAG 智能客服、语义搜索、对话摘要/议价助手、智能定价、内容风控、图片识别发布
- **交易信任**：学号实名、担保交易状态机（货款平台托管）、双向评价 + 信用分、纠纷仲裁
- **校园场景**：课程号匹配教材、求购反向撮合
  （原「宿舍楼拼单」「以物换物」已下线，见 ROADMAP）

关键设计取舍记录在 `docs/ROADMAP.md` 与 `ai-service/README.md`。

## 常见问题排查

| 现象 | 原因 | 解决 |
|---|---|---|
| 启动报 `Unknown database 'secondhand'` | 没建库 | 执行上面的建库语句 |
| 启动报 `Access denied for user` | 数据库账号密码没填对 | 改 `application.properties` |
| 页面能打开但商品列表为空 | 后端没起，或端口不一致 | 确认 8080 有服务；核对 Vite 代理目标 |
| 搜索没有"语义匹配"标记 | AI 服务没起 | 启动 8000，再点一次搜索会回退为关键词匹配 |
| 客服回答是分点步骤、没有开场白，依据来源显示在回答下方的卡片里 | 没配 LLM key，走的是抽取式降级 | 属正常设计（答案逐字来自知识库，溯源由前端按 `sources` 单独渲染）；配 `LLM_API_KEY` 后由模型组织语言 |
| 客服说「这个我暂时答不上来」 | 检索置信度没过阈值，主动拒答 | 属正常设计（宁可不答也不编）；换个具体说法，或启动 AI 服务后重试 |
| 首页商品数量比预期少 | 有商品被「待付款/待发货/已发货/退款中/仲裁中」的订单锁住 | 属正常；处理掉那笔订单即会重新上架 |
| `pip install -r` 报 `UnicodeDecodeError: 'gbk'` | requirements 里出现了非 ASCII 字符 | 本仓库的 requirements 已保证纯 ASCII |
| 改了 `ai-service/.env` 没反应 | .env 没被加载 | 已修复（config.py 显式加载 `ai-service/.env`）；确认文件放在 `ai-service/` 下 |

## 已知短板（答辩建议主动交代）

- **未接入真实多模态模型**：图片识别（A3）的接口与界面已就位，配 `VISION_*` 即可用，
  当前未配置时会如实返回"不可用"。
- **已接入真向量模型**（2026-10-14）：`bge-small-zh-v1.5` 装好后 `/health` 显示
  `"mode": "vector", "dim": 512`，语义检索真正生效；置信度也随之改成「绝对余弦斜坡」
  （词袋那套相对归一在向量模式下会饱和，实测库外问题也能拿 1.0，详见
  `ai-service/README.md` 的阈值标定一节）。没装模型时自动退回词袋模式，功能不中断。
- **AI 服务是单进程内存索引**：商品索引放在 `ai-service/data/index/`，多实例部署需要换成外部向量库。
- **鉴权已补齐为签名 Token**（2026-10-16）：登录返回 HMAC-SHA256 签名的 token
  （载荷只有 `uid/role/exp`），前端放进 `Authorization: Bearer`，后端由
  `AuthInterceptor` 统一校验；管理端与运营端（`/api/admin/**`、`/api/ai/ops/**`、
  `/api/ai/reindex`、纠纷列表与裁定）额外要求 `role=admin`。密码改存 BCrypt，
  老的明文账号在**首次登录成功时自动升级**为哈希。
  签名密钥在 `app.auth.secret`（留空则每次重启随机生成、登录态全失效）。
- **仍不是生产级权限模型**：token 无法主动吊销（没有黑名单），登录没有失败限流与验证码，
  `users.stuid` 也没有唯一约束（同一学号并发注册仍可能写入两行）。
