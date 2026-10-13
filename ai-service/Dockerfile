# AI 服务（FastAPI）镜像
#
# ⚠️ 为什么默认用 CPU 版 torch：
#   sentence-transformers 默认会拖 CUDA 版 torch，进而拉 nvidia-cublas 等
#   单个 400MB+ 的依赖，2G 内存的机器直接 OOM。本项目检索在 CPU 上完全够用
#   （tfidf 模式实测 27 条商品 <1ms）。
#   需要真语义模型时用 --build-arg TORCH_INDEX=cuda 覆盖。
#
# 构建：
#   docker build -f ai-service/Dockerfile -t campus-ai .
#   docker build -f ai-service/Dockerfile -t campus-ai --build-arg TORCH_INDEX=cuda .

ARG PYTHON_VERSION=3.11-slim
FROM python:${PYTHON_VERSION}

ARG TORCH_INDEX=cpu

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/app/data/hf \
    # 关掉 tokenizer 的并行警告（容器里 fork 会报错）
    TOKENIZERS_PARALLELISM=false

WORKDIR /app

# 先装 torch 再装 requirements：否则 sentence-transformers 会把 CUDA 版拖进来
RUN pip install --no-cache-dir \
      "torch==2.2.2+cpu" torchvision==0.17.2+cpu \
    --extra-index-url https://download.pytorch.org/whl/cpu

# torch 装好再装其余依赖，避免连带拉 nvidia-*
COPY ai-service/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY ai-service/app ./app
# 知识库分片要进镜像（向量索引不放，重启后用 /ai/ops/kb/reindex 重建）
COPY ai-service/data/knowledge ./data/knowledge
RUN mkdir -p /app/data/index /app/data/metrics

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD python -c "import urllib.request,sys; \
sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health',timeout=4).status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]