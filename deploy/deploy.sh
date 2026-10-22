#!/usr/bin/env bash
# ============================================================
# 校园二手交易平台 —— 服务器端幂等部署脚本
#
# 在**服务器上**以 root 执行：
#   bash /opt/secondhand/deploy/deploy.sh              # 常规部署 / 更新
#   bash /opt/secondhand/deploy/deploy.sh --reindex    # 额外重建 AI 商品索引
#
# 做六件事，任一步失败立即停：
#   0 前置检查 → 1 配置占位符替换 → 2 目录与权限
#   → 3 重启两服务 → 4 健康检查 → 5 Nginx 校验与重载
#
# 幂等：可以反复跑。已有的 .env / application.properties 不会被覆盖
#       （只有还留着 __占位符__ 才会被填），数据库与用户用 IF NOT EXISTS。
# ============================================================
set -euo pipefail

APP_DIR=/opt/secondhand
APP_USER=secondhand
BACKEND_DIR=$APP_DIR/backend
AI_DIR=$APP_DIR/ai-service
UI_DIR=$APP_DIR/frontend/dist
UPLOAD_DIR=$APP_DIR/uploads

DB_NAME=secondhand
DB_USER=secondhand
DB_PASS_FILE=$APP_DIR/.db_password

PUBLIC_HOST=${PUBLIC_HOST:-116.62.243.122}
API_PORT=8081
AI_PORT=8010
PUBLIC_PORT=8091

reindex=0
[[ "${1:-}" == "--reindex" ]] && reindex=1

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m[失败] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 0. 前置检查 ----------
log "0/5 前置检查"
[[ $EUID -eq 0 ]] || die "请用 root 执行"
[[ -f $BACKEND_DIR/demo1.jar ]] || die "缺少 $BACKEND_DIR/demo1.jar（先上传构建产物）"
[[ -f $UI_DIR/index.html ]]      || die "缺少 $UI_DIR/index.html（先上传前端 dist）"
[[ -x $AI_DIR/.venv/bin/uvicorn ]] || die "缺少 AI 虚拟环境 $AI_DIR/.venv（先跑一次 pip install）"
[[ -f $BACKEND_DIR/application.properties ]] || die "缺少 $BACKEND_DIR/application.properties"
[[ -f $AI_DIR/.env ]]            || die "缺少 $AI_DIR/.env"
id "$APP_USER" >/dev/null 2>&1 || die "系统用户 $APP_USER 不存在"
command -v java >/dev/null || die "未装 JDK（apt install openjdk-17-jre-headless）"
java -version 2>&1 | grep -qE '"(1[7-9]|2[0-9])' || die "JDK 版本低于 17：$(java -version 2>&1 | head -1)"

# ---------- 1. 配置占位符替换 ----------
log "1/5 检查配置占位符"

# 数据库密码：首次生成后落盘复用，以后不再变（变了就要 ALTER USER 同步）
if [[ ! -s $DB_PASS_FILE ]]; then
    openssl rand -base64 24 | tr -d '/+=\n' > "$DB_PASS_FILE"
    chmod 600 "$DB_PASS_FILE"
    echo "已生成新的数据库密码 → $DB_PASS_FILE"
fi
DB_PASS=$(cat "$DB_PASS_FILE")

# 建库建账号。⚠️ CREATE USER IF NOT EXISTS 在用户已存在时会**忽略** IDENTIFIED BY，
# 所以密码一律用 ALTER USER 兜一遍，保证配置文件与库里始终一致。
mysql <<SQL
CREATE DATABASE IF NOT EXISTS $DB_NAME DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
ALTER USER '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$DB_PASS';
GRANT ALL PRIVILEGES ON $DB_NAME.* TO '$DB_USER'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
echo "数据库 $DB_NAME 与账号 $DB_USER@127.0.0.1 就绪"

# 用同一个变量写进 properties，人手不碰密码
sed -i "s|^spring.datasource.username=.*|spring.datasource.username=$DB_USER|" "$BACKEND_DIR/application.properties"
sed -i "s|^spring.datasource.password=.*|spring.datasource.password=$DB_PASS|" "$BACKEND_DIR/application.properties"

# 鉴权密钥：只在还是占位符时生成，避免每次部署都把所有人踢下线
if grep -q '__AUTH_SECRET__' "$BACKEND_DIR/application.properties"; then
    SECRET=$(openssl rand -hex 32)
    sed -i "s|__AUTH_SECRET__|$SECRET|" "$BACKEND_DIR/application.properties"
    echo "已生成 app.auth.secret"
fi
sed -i "s|__PUBLIC_HOST__|$PUBLIC_HOST|g" "$BACKEND_DIR/application.properties"

# AI 服务：复用服务器上已有项目的 GLM 凭据（不打印任何密钥）
if grep -qE '__LLM_API_KEY__|__OPS_TOKEN__' "$AI_DIR/.env"; then
    SRC_ENV=/opt/campus/backend/.env
    if [[ -r $SRC_ENV ]]; then
        for k in LLM_BASE_URL LLM_API_KEY LLM_MODEL; do
            v=$(grep -m1 "^$k=" "$SRC_ENV" | cut -d= -f2- | tr -d '\r')
            [[ -n $v ]] && sed -i "s|__${k}__|$v|" "$AI_DIR/.env"
        done
        # 智谱的视觉模型与语言模型同端点同 key
        for k in VISION_BASE_URL VISION_API_KEY VISION_MODEL; do
            if grep -q "__${k}__" "$AI_DIR/.env"; then
                case $k in
                    VISION_BASE_URL) v=$(grep -m1 '^LLM_BASE_URL=' "$SRC_ENV" | cut -d= -f2- | tr -d '\r');;
                    VISION_API_KEY)  v=$(grep -m1 '^LLM_API_KEY='  "$SRC_ENV" | cut -d= -f2- | tr -d '\r');;
                    VISION_MODEL)    v=$(grep -m1 '^LLM_VISION_MODEL=' "$SRC_ENV" | cut -d= -f2- | tr -d '\r');;
                esac
                [[ -n ${v:-} ]] && sed -i "s|__${k}__|$v|" "$AI_DIR/.env"
            fi
        done
        echo "已从 $SRC_ENV 复用 LLM / 视觉模型凭据"
    else
        echo "⚠️  $SRC_ENV 不可读，LLM 相关占位符保持原样 → 生成侧会降级为抽取式回答"
    fi
    OPS=$(openssl rand -hex 16)
    sed -i "s|__OPS_TOKEN__|$OPS|" "$AI_DIR/.env"
    echo "已生成 OPS_TOKEN"
fi

# 兜底：没替换掉的占位符会把 uvicorn 搞成奇怪的状态（比如 api key 字面量）
# 只看非注释行 —— 注释里举例写占位符是正常的，不该被当成漏填
leftover=$(grep -hvE '^\s*#|^\s*$' "$AI_DIR/.env" "$BACKEND_DIR/application.properties" | grep -nE '__[A-Z_]+__' || true)
if [[ -n $leftover ]]; then
    echo "⚠️  仍有未替换的占位符（已忽略注释行）："
    echo "$leftover"
    echo "   LLM / VISION 类为空可以接受（会降级成抽取式回答）；其他项为空必须手工修"
fi

# ---------- 2. 目录与权限 ----------
log "2/5 目录与权限"
mkdir -p "$UPLOAD_DIR" "$AI_DIR/data/index" "$AI_DIR/data/metrics"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"
chmod 600 "$BACKEND_DIR/application.properties" "$AI_DIR/.env" "$DB_PASS_FILE"
# 前端静态文件由 Nginx 读，给组读权限即可
chmod -R a+rX "$UI_DIR"
echo "权限已就绪"

# ---------- 3. 重启两服务 ----------
log "3/5 重启服务"
systemctl daemon-reload
systemctl enable --now secondhand-api >/dev/null 2>&1 || true
systemctl enable --now secondhand-ai  >/dev/null 2>&1 || true
systemctl restart secondhand-ai
systemctl restart secondhand-api

# ---------- 4. 健康检查 ----------
log "4/5 健康检查（最多等 90s）"
wait_http() {   # $1=url $2=标签 $3=期望出现的字符串
    for i in $(seq 1 45); do
        body=$(curl -fsS -m 3 "$1" 2>/dev/null || true)
        if [[ -n $body ]] && grep -q "$3" <<<"$body"; then
            echo "✅ $2 正常：$(head -c 160 <<<"$body")"
            return 0
        fi
        sleep 2
    done
    echo "❌ $2 未就绪（90s 超时）"
    journalctl -u "$4" -n 30 --no-pager || true
    return 1
}
wait_http "http://127.0.0.1:$AI_PORT/health"  "AI 服务"  '"status":"ok"' secondhand-ai || die "AI 服务启动失败"
wait_http "http://127.0.0.1:$API_PORT/api"    "后端 API" 'API 服务运行正常' secondhand-api || die "后端启动失败"

# ---------- 5. Nginx ----------
log "5/5 Nginx 校验与重载"
nginx -t
systemctl reload nginx
echo "Nginx 已重载"

# ---------- 可选：重建商品索引 ----------
if [[ $reindex -eq 1 ]]; then
    log "重建 AI 商品索引"
    TOKEN=$(curl -fsS -m 10 -X POST \
        "http://127.0.0.1:$API_PORT/api/login?stuid=admin&password=admin123" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
    [[ -n $TOKEN ]] || die "登录失败，拿不到 token（admin 密码是不是改过？）"
    curl -fsS -m 120 -X POST "http://127.0.0.1:$API_PORT/api/ai/reindex" \
        -H "Authorization: Bearer $TOKEN" | head -c 300; echo
    sleep 1
    curl -fsS -m 10 "http://127.0.0.1:$AI_PORT/health" | head -c 300; echo
fi

# ---------- 收尾 ----------
log "部署完成"
echo "公网地址： http://$PUBLIC_HOST:$PUBLIC_PORT"
echo "进程内存： $(ps -o rss= -C java | awk '{s+=$1} END{printf "java %.0fMB ", s/1024}')$(ps -o rss= -C uvicorn | awk '{s+=$1} END{printf "uvicorn %.0fMB", s/1024}')"
free -m | awk 'NR<=2'
echo
echo "如页面打不开先查两处："
echo "  1) 阿里云安全组是否放行了 $PUBLIC_PORT 端口（控制台 → 安全组 → 入方向）"
echo "  2) systemctl status secondhand-api secondhand-ai --no-pager"
