"""运营管理端接口（需求规格 §2.3）。

- 知识库管理：列表 / 筛选、新增、更新、删除、批量导入导出
- 向量化管理：单条增量（增删改时自动触发）、全量重建
- 数据统计：问答量、回答成功率、拒答次数、Top N 高频问题、未命中问题导出
- 变更审计：记录操作人与操作时间

权限：默认本地演示不校验；配置 `OPS_TOKEN` 后要求请求头 `X-Ops-Token` 一致。
"""

import csv
import io
import json
import logging
import os
from datetime import datetime

from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.config import settings
from app.deps import knowledge
from app.embedding import embedder
from app.observability import METRICS_DIR, misses, summary, top_questions

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/ops", tags=["ops"])

AUDIT_FILE = os.path.join(METRICS_DIR, "audit.jsonl")


# ---- 权限（可选）----


def _check_token(ops_token: str | None) -> None:
    if settings.ops_token and ops_token != settings.ops_token:
        raise HTTPException(status_code=401, detail="运营接口令牌不正确")


def _audit(action: str, operator: str, **fields) -> None:
    os.makedirs(METRICS_DIR, exist_ok=True)
    record = {"at": datetime.now().isoformat(timespec="seconds"),
              "operator": operator or "unknown", "action": action, **fields}
    try:
        with open(AUDIT_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError as exc:
        logger.warning("审计日志写入失败：%s", exc)


def audit_list(limit: int = 50) -> list[dict]:
    if not os.path.exists(AUDIT_FILE):
        return []
    items = []
    with open(AUDIT_FILE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    items.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return list(reversed(items))[:limit]


# ---- 知识库管理 ----


class KnowledgeUpsert(BaseModel):
    id: str | None = None
    shard: str = "faq"
    title: str
    content: str
    tags: list[str] = []
    source: str = ""
    updated: str = ""


@router.get("/kb/shards")
def kb_shards():
    return {"success": True, "data": knowledge.shards_info(), "total": knowledge.count()}


@router.get("/kb/entries")
def kb_entries(shard: str | None = None, q: str | None = None):
    items = knowledge.list_entries(shard, q)
    return {"success": True, "total": len(items), "data": items}


@router.post("/kb/entries")
def kb_save(body: KnowledgeUpsert,
            x_ops_token: str | None = Header(None, alias="X-Ops-Token"),
            x_operator: str | None = Header(None, alias="X-Operator")):
    _check_token(x_ops_token)
    doc_id = (body.id or "").strip()
    if not doc_id:
        doc_id = f"{body.shard[:1].upper()}{knowledge.count() + 1:03d}"
    if not body.title.strip():
        raise HTTPException(status_code=400, detail="标题不能为空")

    entry = {
        "id": doc_id,
        "shard": body.shard,
        "title": body.title.strip(),
        "content": body.content,
        "tags": [t.strip() for t in body.tags if t.strip()],
        "source": body.source.strip() or f"{body.shard} 分片",
        "updated": body.updated.strip() or datetime.now().strftime("%Y-%m-%d"),
    }
    created = knowledge.upsert(doc_id, entry)
    _audit("create" if created else "update", x_operator, id=doc_id, title=entry["title"])
    return {"success": True, "created": created, "id": doc_id,
            "message": "知识已保存，索引已增量更新"}


@router.delete("/kb/entries/{doc_id}")
def kb_delete(doc_id: str,
              x_ops_token: str | None = Header(None, alias="X-Ops-Token"),
              x_operator: str | None = Header(None, alias="X-Operator")):
    _check_token(x_ops_token)
    entry = knowledge.entry(doc_id)
    ok = knowledge.delete(doc_id)
    if not ok:
        raise HTTPException(status_code=404, detail="知识条目不存在")
    _audit("delete", x_operator, id=doc_id, title=entry.get("title", ""))
    return {"success": True, "message": "知识已删除，索引已更新"}


@router.get("/kb/entries.csv")
def kb_export():
    """批量导出知识库（需求 §2.3.1）。"""
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["知识ID", "所属分片", "标题", "正文", "标签", "溯源展示名", "更新时间"])
    for entry in knowledge.all_entries():
        writer.writerow([entry["id"], entry.get("shard", ""), entry["title"],
                         entry["content"].replace("\n", " / "),
                         "|".join(entry.get("tags", [])),
                         entry.get("source", ""), entry.get("updated", "")])
    return StreamingResponse(
        io.BytesIO(buffer.getvalue().encode("utf-8-sig")),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=knowledge.csv"},
    )


class KnowledgeImport(BaseModel):
    entries: list[KnowledgeUpsert]


@router.post("/kb/import")
def kb_import(body: KnowledgeImport,
              x_ops_token: str | None = Header(None, alias="X-Ops-Token"),
              x_operator: str | None = Header(None, alias="X-Operator")):
    """批量导入（需求 §2.3.1）。"""
    _check_token(x_ops_token)
    imported = 0
    for item in body.entries:
        doc_id = (item.id or "").strip() or f"{item.shard[:1].upper()}{knowledge.count() + 1:03d}"
        knowledge.upsert(doc_id, {
            "id": doc_id, "shard": item.shard, "title": item.title,
            "content": item.content, "tags": item.tags,
            "source": item.source or f"{item.shard} 分片",
            "updated": item.updated or datetime.now().strftime("%Y-%m-%d"),
        })
        imported += 1
    _audit("import", x_operator, count=imported)
    return {"success": True, "imported": imported, "total": knowledge.count()}


@router.post("/kb/reindex")
def kb_reindex(x_ops_token: str | None = Header(None, alias="X-Ops-Token"),
               x_operator: str | None = Header(None, alias="X-Operator")):
    """全量重建向量索引与 BM25 索引（需求 §2.3.2）。"""
    _check_token(x_ops_token)
    count = knowledge.rebuild_all()
    _audit("reindex", x_operator, count=count)
    return {"success": True, "count": count, "message": f"已重建索引，共 {count} 条"}


@router.get("/kb/audit")
def kb_audit(limit: int = 50):
    return {"success": True, "data": audit_list(limit)}


# ---- 数据统计 ----


@router.get("/stats/summary")
def stats_summary():
    data = summary()
    data["knowledgeCount"] = knowledge.count()
    data["embeddingMode"] = embedder.mode()
    data["llmMode"] = "api" if settings.llm_enabled else "extractive"
    data["refuseThreshold"] = settings.refuse_threshold
    data["retrieval"] = "BM25 + 向量 + RRF"
    return {"success": True, "data": data}


@router.get("/stats/top-questions")
def stats_top(limit: int = 10):
    return {"success": True, "data": top_questions(limit)}


@router.get("/stats/misses")
def stats_misses(limit: int = 50):
    return {"success": True, "data": misses(limit)}


@router.get("/stats/misses.csv")
def stats_misses_csv(limit: int = 200):
    """未命中问题导出，供人工补充进知识库（需求 §2.3.3）。"""
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["时间", "未命中问题", "最高置信度"])
    for item in misses(limit):
        writer.writerow([item["at"], item["question"], item["confidence"]])
    return StreamingResponse(
        io.BytesIO(buffer.getvalue().encode("utf-8-sig")),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=misses.csv"},
    )