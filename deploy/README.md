# 部署到阿里云 ECS（2 核 2G，与另外三个项目共存）

这台机器上已经跑着三个项目，本站是**第四个**。所有决策都是围绕「不把邻居顶 OOM」做的。

## 一、端口分配（已生效）

| 端口 | 用途 | 监听 | 说明 |
|---|---|---|---|
| **8091** | 本站公网入口（Nginx） | 0.0.0.0 | 前端 dist + 反代 /api |
| 8081 | 本站后端 Spring Boot | 127.0.0.1 | 不对公网暴露 |
| 8010 | 本站 AI 服务 FastAPI | 127.0.0.1 | 只被 8081 调用 |
| 80 | 校园综合服务平台 | 0.0.0.0 | 早就在，且 `server_name _` 是 catch-all |
| 8080 | 在线简历 | 0.0.0.0 | |
| 8090 | 医疗安全问答 | 0.0.0.0 | |
| 8000/8001/8002 | 上述项目的内部后端 | 127.0.0.1 | |
| 3306 / 6379 | MySQL / Redis | 127.0.0.1 | |

**为什么必须换端口**：80 上那个站 `server_name _` 是 catch-all。用 IP 访问时
Host 头就是那个 IP，同端口上不可能再挂第二个 `server_name _`。只有不同端口能并存。
（想共用 80 就得绑域名 —— 而绑域名要走 ICP 备案。）

## 二、上线前必须做的一步：安全组放行 8091

阿里云控制台 → ECS 实例 → 安全组 → 配置规则 → 入方向 → 手动添加：

| 项 | 值 |
|---|---|
| 协议类型 | 自定义 TCP |
| 端口范围 | **8091/8091** |
| 授权对象 | 0.0.0.0/0 |
| 描述 | secondhand |

不加这条，服务器本机 `curl 127.0.0.1:8091` 是 200，**公网访问会一直超时** ——
这是最容易误判成"服务没起来"的一步（本机所有服务都正常，就是外面进不来）。

> 临时替代方案（不想动安全组时）：SSH 隧道
> `ssh -N -L 8091:127.0.0.1:8091 root@116.62.243.122`，然后浏览器打开
> `http://127.0.0.1:8091`。注意端口号必须一致：CORS 白名单里写的是
> `http://127.0.0.1:8091`，换成别的端口（如 18091）会被 403 拦掉。

## 三、首次部署（本机 → 服务器）

```bash
# ---- 1. 本机构建（服务器上不装 Maven，省内存）----
cd demo1
# 关键：把本地配置移出去再打包，否则本地库密码会被打进 jar 带到线上
mv src/main/resources/application.properties /tmp/
mvn -o -q -DskipTests package
mv /tmp/application.properties src/main/resources/
cd ../frontend && npm run build

# ---- 2. 打包上传（只传产物，不传源码树/依赖）----
cd ..
tar -czf /tmp/sh.tar.gz \
  --exclude='__pycache__' --exclude='*.pyc' \
  ai-service/app ai-service/data/knowledge \
  ai-service/requirements.txt ai-service/requirements-prod.txt \
  frontend/dist deploy demo1/target/demo1-0.0.1-SNAPSHOT.jar
scp /tmp/sh.tar.gz root@116.62.243.122:/tmp/

# ---- 3. 服务器上展开（只需第一次）----
ssh root@116.62.243.122
useradd -r -m -d /home/secondhand -s /bin/bash secondhand
mkdir -p /opt/secondhand/backend /opt/secondhand/uploads
tar -xzf /tmp/sh.tar.gz -C /opt/secondhand
mv /opt/secondhand/demo1/target/*.jar /opt/secondhand/backend/demo1.jar
rm -rf /opt/secondhand/demo1

# 配置从模板生成（占位符交给 deploy.sh 填）
cp /opt/secondhand/deploy/application-prod.template /opt/secondhand/backend/application.properties
cp /opt/secondhand/deploy/ai-service.env.template    /opt/secondhand/ai-service/.env

# systemd + nginx
cp /opt/secondhand/deploy/secondhand-*.service /etc/systemd/system/
cp /opt/secondhand/deploy/nginx-secondhand.conf /etc/nginx/conf.d/secondhand.conf

# Python 依赖（瘦身版，不含 torch）
cd /opt/secondhand/ai-service
python3 -m venv .venv
.venv/bin/pip install -r requirements-prod.txt

# ---- 4. 一条命令完成建库/填配置/起服务/健康检查 ----
bash /opt/secondhand/deploy/deploy.sh --reindex
```

`deploy.sh` 是幂等的，可以反复跑。它做的事：
建库建账号（密码 `openssl` 生成后落盘，用同一个 shell 变量同时写进 MySQL 和
properties，**人手不碰密码**）→ 生成 `app.auth.secret` → 从
`/opt/campus/backend/.env` 复用 GLM 凭据 → 设权限 → 重启两服务 → 等健康检查
→ `nginx -t` 并 reload → 可选重建索引。

## 四、日常更新

```bash
# 本机：重新构建 → 上传 → 服务器上跑一遍脚本
scp /tmp/sh.tar.gz root@116.62.243.122:/tmp/
ssh root@116.62.243.122
tar -xzf /tmp/sh.tar.gz -C /opt/secondhand      # 直接覆盖，.env / .venv 原样保留
bash /opt/secondhand/deploy/deploy.sh
```

**不要 `rm -rf /opt/secondhand`**：`.env`、`.db_password`、`.venv` 都不在包里，
删掉就得重新生成密码、重建虚拟环境。

## 五、内存预算（实测）

| 进程 | RSS |
|---|---|
| secondhand-api（java，-Xmx320m） | **283 MB** |
| secondhand-ai（tfidf 模式） | **182 MB** |
| medsafe / campus / resume | 188 / 101 / 34 MB |
| mysqld | 146 MB |
| nginx（master+2 worker） | 12 MB |

上线后 `available` 从 991MB 降到 ~350MB，swap 使用从 641MB **降到 37MB**（大部分空闲页被回收）。
四个项目全部 active。

### 为什么 AI 服务跑 tfidf 而不是真向量

`requirements-prod.txt` 故意不含 `sentence-transformers` —— 它会拖来 torch，
仅 import 就常驻 250-300MB，加上 bge 模型约 400MB。这台机器腾不出。

不装不会报错：`embedding.py` 加载模型失败会**自动降级**成
jieba + HashingVectorizer 词袋模式（`mode="tfidf"`，阈值 0.20 是实测标定过的）。
这是项目两级降级设计的另一半，答辩时正好可以讲「为什么降级是设计而不是补丁」。

**生成侧完全不受影响**：LLM 是远程 HTTP 调用，`/health` 显示
`"llm":{"mode":"api","model":"glm-4.5-flash"}`，客服回答仍是真模型流式生成。

想要回真向量：腾出 ~500MB（比如停掉一个别的项目）→ 换回 `requirements.txt`
重装 → 下模型到 `ai-service/models/` → `.env` 里 `EMB_FORCE_TFIDF=false` →
`deploy.sh --reindex` 重建索引（维度 4096 → 512，不重建会直接报维度不匹配）。

## 六、排查手册

| 现象 | 查什么 |
|---|---|
| 公网打不开、本机 curl 200 | 安全组没放行 8091 |
| 页面 403 Invalid CORS request | 访问的 Origin 不在 `app.cors.allowed-origins` 里 |
| 客服只回一句短话、标签显示「离线兜底」 | 前端没连上 `/api/ai/chat/stream`，看浏览器 Network |
| AI 回答整段蹦出而不是逐字 | Nginx 缓冲了 SSE，检查 `location /api/ai/chat/stream` 的 `proxy_buffering off` |
| 搜索/定价不生效但 `/health` 200 | `index.count` 为 0，跑 `deploy.sh --reindex`（索引目录不持久化到 git） |
| 上传成功但图片 404 | `APP_UPLOAD_DIR` 没生效；它是**环境变量**，写进 properties 无效 |
| 启动日志说「初始化完成」但库里 0 件商品 | 已有历史 bug（`ensureUser` 用 `count()==0` 判据），已在 `DataInitializer` 修掉 |
| 服务起不来 | `journalctl -u secondhand-api -n 50 --no-pager` |

```bash
# 常用
systemctl status secondhand-api secondhand-ai --no-pager
journalctl -u secondhand-api -f
curl -s http://127.0.0.1:8010/health          # 看 index.count 是否等于在售商品数
mysql -e "USE secondhand; SELECT COUNT(*) FROM goods WHERE status='在售';"
tail -f /var/log/nginx/secondhand.error.log
```

## 七、演示账号

| 账号 | 密码 | 身份 |
|---|---|---|
| `admin` | `admin123` | 管理员（管理端、纠纷裁定、知识库运营） |
| `test` | `test123` | 普通用户 / 卖家 |

密码是 `DataInitializer` 里的种子值，落库时已用 BCrypt 哈希。
**管理员密码务必在答辩前改掉**，这个仓库是公开的。
