"""全链路日志与埋点统计（需求规格 §2.3.3 数据统计 / §5.3 日志）。

- `trace_span`：一次问答一个 traceId，把「改写后的问句 / 意图 / 两路召回 / 命中条目 /
  置信度 / 是否拒答」串成一条可回放的日志。
- `record_event`：落一条问答事件到 JSONL，供运营端统计与导出未命中问题。
"""

import json
import logging
import os
import threading
import time
import uuid
from collections import Counter, defaultdict
from contextlib import contextmanager
from datetime import date, datetime

logger = logging.getLogger("rag.trace")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METRICS_DIR = os.path.join(BASE_DIR, "data", "metrics")
EVENT_FILE = os.path.join(METRICS_DIR, "events.jsonl")

_lock = threading.Lock()
_state = {
    "total": 0,
    "answered": 0,
    "refused": 0,
    "by_day": defaultdict(lambda: {"total": 0, "answered": 0, "refused": 0}),
    "modes": Counter(),
    "questions": Counter(),
    "misses": [],       # 拒答/低置信度的问题，用于导出
}


def _ensure_dir() -> None:
    os.makedirs(METRICS_DIR, exist_ok=True)


class Trace:
    def __init__(self, name: str):
        self.name = name
        self.trace_id = uuid.uuid4().hex[:12]
        self.started = time.perf_counter()
        self.data: dict = {}

    def update(self, fields: dict | None = None, **kwargs) -> None:
        """同时支持 update(a=1) 与 update({"a": 1}) 两种写法。"""
        if fields:
            self.data.update(fields)
        if kwargs:
            self.data.update(kwargs)

    def finish(self) -> None:
        cost = round((time.perf_counter() - self.started) * 1000)
        logger.info(
            "trace=%s op=%s cost=%dms %s",
            self.trace_id, self.name, cost,
            json.dumps(self.data, ensure_ascii=False),
        )


@contextmanager
def trace_span(name: str):
    trace = Trace(name)
    try:
        yield trace
    finally:
        trace.finish()


def record_event(kind: str, question: str, refused: bool, hits, mode: str) -> None:
    """记录一次问答，用于 Top N / 未命中 / 成功率统计（需求 §2.3.3）。"""
    today = date.today().isoformat()
    with _lock:
        _state["total"] += 1
        _state["by_day"][today]["total"] += 1
        if refused:
            _state["refused"] += 1
            _state["by_day"][today]["refused"] += 1
            _state["misses"].append({
                "question": question,
                "at": datetime.now().isoformat(timespec="seconds"),
                "confidence": hits[0].confidence if hits else 0.0,
            })
            if len(_state["misses"]) > 200:
                _state["misses"] = _state["misses"][-200:]
        else:
            _state["answered"] += 1
            _state["by_day"][today]["answered"] += 1
        _state["modes"][mode] += 1
        _state["questions"][question.strip()] += 1

    try:
        _ensure_dir()
        with open(EVENT_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps({
                "kind": kind,
                "question": question.strip(),
                "refused": refused,
                "mode": mode,
                "hitIds": [h.id for h in hits],
                "confidence": hits[0].confidence if hits else 0.0,
                "at": datetime.now().isoformat(timespec="seconds"),
            }, ensure_ascii=False) + "\n")
    except OSError as exc:
        logger.warning("问答事件落盘失败：%s", exc)


def summary(days: int = 7) -> dict:
    """运营端核心指标：问答量、回答成功率、拒答次数、模式分布。"""
    total = _state["total"]
    answered = _state["answered"]
    return {
        "total": total,
        "answered": answered,
        "refused": _state["refused"],
        "successRate": round(answered / total, 4) if total else 0.0,
        "refuseRate": round(_state["refused"] / total, 4) if total else 0.0,
        "modes": dict(_state["modes"]),
        "byDay": {day: dict(values) for day, values in
                  sorted(_state["by_day"].items())[-days:]},
    }


def top_questions(limit: int = 10) -> list[dict]:
    """高频问题 Top N（需求 §2.3.3）。"""
    counter = Counter(_state["questions"])
    total = sum(counter.values()) or 1
    return [
        {"question": q, "count": c, "ratio": round(c / total, 4)}
        for q, c in counter.most_common(limit)
    ]


def misses(limit: int = 50) -> list[dict]:
    """未命中/拒答问题清单（需求 §2.3.3：导出后人工补充进知识库）。"""
    return list(reversed(_state["misses"]))[:limit]