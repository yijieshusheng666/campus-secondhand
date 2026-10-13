"""生成能力抽象：云端 OpenAI 兼容接口。不可用时由调用方决定降级策略。"""

import json
import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class LLMUnavailable(Exception):
    """云端生成不可用（未配置或调用失败）。"""


class LLM:
    def available(self) -> bool:
        return settings.llm_enabled

    def mode(self) -> str:
        return "api" if self.available() else "extractive"

    def generate(self, prompt: str) -> str:
        if not self.available():
            raise LLMUnavailable("未配置 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL")

        url = f"{settings.llm_base_url}/chat/completions"
        payload = {
            "model": settings.llm_model,
            "messages": [
                {"role": "system", "content": _system_content()},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
        }
        headers = {"Authorization": f"Bearer {settings.llm_api_key}"}

        try:
            resp = httpx.post(url, json=payload, headers=headers, timeout=settings.llm_timeout)
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()
        except Exception as exc:
            logger.warning("LLM 调用失败，交由调用方降级：%s", exc)
            raise LLMUnavailable(str(exc)) from exc

    def stream(self, prompt: str):
        """流式生成，逐段 yield 文本增量。不可用时抛 LLMUnavailable。

        用于 SSE 接口：把模型侧的开销摊到生成过程里，拿到首字就能开始推给前端。
        """
        if not self.available():
            raise LLMUnavailable("未配置 LLM_BASE_URL / LLM_API_KEY / LLM_MODEL")

        url = f"{settings.llm_base_url}/chat/completions"
        payload = {
            "model": settings.llm_model,
            "messages": [
                {"role": "system", "content": _system_content()},
                {"role": "user", "content": prompt},
            ],
            "temperature": 0.3,
            "stream": True,
        }
        headers = {"Authorization": f"Bearer {settings.llm_api_key}"}

        try:
            with httpx.Client(timeout=settings.llm_timeout) as client:
                with client.stream("POST", url, json=payload, headers=headers) as response:
                    response.raise_for_status()
                    for line in response.iter_lines():
                        if not line or not line.startswith("data:"):
                            continue
                        data = line[5:].strip()
                        if data == "[DONE]":
                            break
                        try:
                            chunk = json.loads(data)
                            delta = chunk["choices"][0]["delta"].get("content") or ""
                        except (json.JSONDecodeError, KeyError, IndexError):
                            continue
                        if delta:
                            yield delta
        except Exception as exc:
            logger.warning("LLM 流式调用失败，交由调用方降级：%s", exc)
            raise LLMUnavailable(str(exc)) from exc


def _system_content() -> str:
    from app.prompts import RAG_SYSTEM
    return RAG_SYSTEM


llm = LLM()
