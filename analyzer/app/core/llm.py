"""LLM 客户端抽象（AD-3：不引 LangChain，自写接口；AD-12：只发结构化摘要）。

- `LLMClient`：接口。`available()` 为 False 时上层直接走规则版降级。
- `OpenAICompatClient`：OpenAI 兼容 /chat/completions 实现（ollama / deepseek 等皆可）。
- 未配置 LLM_API_KEY 视为不可用（LLM_NO_KEY 语义，走降级而非报错）。
"""

from __future__ import annotations

import logging
from typing import Protocol

import httpx

logger = logging.getLogger("evocode.analyzer.llm")


class LLMClient(Protocol):
    def available(self) -> bool:
        """是否可发起 LLM 调用（无 Key / 未配置 → False）。"""

    def chat_json(self, system: str, user: str) -> dict:
        """单轮对话并解析为 JSON 对象；失败抛异常（由调用方降级）。"""


class OpenAICompatClient:
    """OpenAI 兼容接口客户端（response_format=json_object + 超时 + 重试）。"""

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float = 60.0,
        max_retries: int = 2,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._api_key = api_key
        self._model = model
        self._timeout = timeout_seconds
        self._max_retries = max(0, max_retries)

    def available(self) -> bool:
        return bool(self._api_key.strip())

    def chat_json(self, system: str, user: str) -> dict:
        if not self.available():
            raise RuntimeError("LLM_API_KEY 未配置（LLM_NO_KEY）")
        last_exc: Exception | None = None
        for attempt in range(self._max_retries + 1):
            try:
                payload = {
                    "model": self._model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                    "temperature": 0.3,
                    "response_format": {"type": "json_object"},
                }
                with httpx.Client(timeout=self._timeout) as client:
                    resp = client.post(
                        f"{self._base_url}/chat/completions",
                        headers={"Authorization": f"Bearer {self._api_key}"},
                        json=payload,
                    )
                    resp.raise_for_status()
                    data = resp.json()
                content = data["choices"][0]["message"]["content"]
                parsed = _parse_json_object(content)
                if parsed is None:
                    raise ValueError("LLM 返回非 JSON 内容")
                return parsed
            except Exception as exc:  # 网络/HTTP/解析错误统一重试
                last_exc = exc
                logger.warning("LLM 调用失败 attempt=%s: %s", attempt + 1, exc)
        raise RuntimeError(f"LLM 调用失败：{last_exc}")


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
