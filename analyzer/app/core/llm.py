"""LLM 客户端抽象（AD-3：不引 LangChain，自写接口；AD-12：只发结构化摘要）。

- `LLMClient`：接口。`available()` 为 False 时上层直接走规则版降级。
- `OpenAICompatClient`：OpenAI 兼容 /chat/completions 实现（ollama / deepseek 等皆可）。
- 未配置 LLM_API_KEY 视为不可用（LLM_NO_KEY 语义，走降级而非报错）。
"""

from __future__ import annotations

import json
import logging
from collections.abc import Iterator
from typing import Protocol

import httpx

logger = logging.getLogger("evocode.analyzer.llm")


def _parse_stream_delta(chunk: str) -> str | None:
    """解析 SSE data 增量块 → choices[0].delta.content；无增量返回 None。"""
    try:
        payload = json.loads(chunk)
    except json.JSONDecodeError:
        return None
    choices = payload.get("choices") or []
    if not choices:
        return None
    delta = (choices[0].get("delta") or {}).get("content")
    return delta if isinstance(delta, str) else None


class LLMClient(Protocol):
    def available(self) -> bool:
        """是否可发起 LLM 调用（无 Key / 未配置 → False）。"""

    def chat_json(self, system: str, user: str) -> dict:
        """单轮对话并解析为 JSON 对象；失败抛异常（由调用方降级）。"""

    def embed(self, texts: list[str]) -> list[list[float]]:
        """文本向量化（/embeddings）；失败抛异常（由调用方降级关键词检索）。"""


class OpenAICompatClient:
    """OpenAI 兼容接口客户端（response_format=json_object + 超时 + 重试）。

    - chat_json：/chat/completions 结构化摘要
    - embed：/embeddings（P6 RAG，模型默认 bge-m3，1024 维）
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float = 60.0,
        max_retries: int = 2,
        embedding_model: str = "bge-m3",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._model = model
        self._timeout = timeout_seconds
        self._max_retries = max(0, max_retries)
        self._embedding_model = embedding_model

    def available(self) -> bool:
        return bool(self._api_key.strip())

    def _post(self, path: str, payload: dict) -> dict:
        last_exc: Exception | None = None
        for attempt in range(self._max_retries + 1):
            try:
                with httpx.Client(timeout=self._timeout) as client:
                    resp = client.post(
                        f"{self._base_url}{path}",
                        headers={"Authorization": f"Bearer {self._api_key}"},
                        json=payload,
                    )
                    resp.raise_for_status()
                    return resp.json()
            except Exception as exc:  # 网络/HTTP 错误统一重试
                last_exc = exc
                logger.warning("LLM 调用失败 %s attempt=%s: %s", path, attempt + 1, exc)
        raise RuntimeError(f"LLM 调用失败（{path}）：{last_exc}")

    def chat_json(self, system: str, user: str) -> dict:
        if not self.available():
            raise RuntimeError("LLM_API_KEY 未配置（LLM_NO_KEY）")
        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0.3,
            "response_format": {"type": "json_object"},
        }
        data = self._post("/chat/completions", payload)
        content = data["choices"][0]["message"]["content"]
        parsed = _parse_json_object(content)
        if parsed is None:
            raise ValueError("LLM 返回非 JSON 内容")
        return parsed

    def embed(self, texts: list[str]) -> list[list[float]]:
        """批量向量化；返回按输入顺序排列的向量（data 按 index 重排）。"""
        if not self.available():
            raise RuntimeError("LLM_API_KEY 未配置（LLM_NO_KEY）")
        if not texts:
            return []
        payload = {"model": self._embedding_model, "input": texts}
        data = self._post("/embeddings", payload)
        items = sorted(data["data"], key=lambda d: d.get("index", 0))
        return [item["embedding"] for item in items]

    def chat_stream(
        self, system: str, user: str, temperature: float = 0.7
    ) -> Iterator[str]:
        """流式对话（SSE）：逐 token yield 增量文本；失败抛异常（调用方降级）。"""
        if not self.available():
            raise RuntimeError("LLM_API_KEY 未配置（LLM_NO_KEY）")
        payload = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
            "stream": True,
        }
        last_exc: Exception | None = None
        for attempt in range(self._max_retries + 1):
            try:
                with httpx.Client(timeout=self._timeout) as client, client.stream(
                    "POST",
                    f"{self._base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {self._api_key}"},
                    json=payload,
                ) as resp:
                    resp.raise_for_status()
                    for line in resp.iter_lines():
                        if not line or not line.startswith("data:"):
                            continue
                        chunk = line[5:].strip()
                        if chunk == "[DONE]":
                            return
                        delta = _parse_stream_delta(chunk)
                        if delta:
                            yield delta
                return
            except Exception as exc:
                last_exc = exc
                logger.warning(
                    "LLM 流式调用失败 attempt=%s: %s", attempt + 1, exc
                )
        raise RuntimeError(f"LLM 流式调用失败：{last_exc}")


def _parse_json_object(content: str) -> dict | None:
    """容错解析：优先整段 JSON，失败则截取首个 {…} 块。"""
    import json

    text = content.strip()
    try:
        obj = json.loads(text)
        return obj if isinstance(obj, dict) else None
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")
        if start >= 0 and end > start:
            try:
                obj = json.loads(text[start : end + 1])
                return obj if isinstance(obj, dict) else None
            except json.JSONDecodeError:
                return None
        return None
