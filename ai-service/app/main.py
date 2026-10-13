from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.deps import knowledge
from app.routers import chat, embed, health, index, search


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
app.include_router(chat.router)
