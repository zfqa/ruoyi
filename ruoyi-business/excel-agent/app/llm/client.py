"""Minimal OpenAI-compatible Chat Completions client (DeepSeek / Ark / etc.)."""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


DEFAULT_API_URL = "https://api.deepseek.com/v1/chat/completions"
DEFAULT_MODEL = "deepseek-chat"
_LOCAL_ENV: dict[str, str] | None = None


class LlmError(RuntimeError):
    """Base error for recoverable LLM failures."""


class LlmUnavailableError(LlmError):
    """Raised when LLM configuration is absent."""


class ArkChatClient:
    def __init__(
        self,
        api_key: str | None = None,
        api_url: str | None = None,
        model: str | None = None,
        timeout_seconds: float | None = None,
        max_retries: int | None = None,
    ):
        self.api_key = api_key or _setting("ARK_API_KEY", "")
        self.api_url = _normalize_completions_url(api_url or _setting("ARK_API_URL", DEFAULT_API_URL))
        self.model = model or _setting("ARK_MODEL", DEFAULT_MODEL)
        self.timeout_seconds = timeout_seconds or float(_setting("ARK_TIMEOUT_SECONDS", "30"))
        configured_retries = int(_setting("ARK_MAX_RETRIES", "0")) if max_retries is None else max_retries
        self.max_retries = max(0, configured_retries)

    @property
    def available(self) -> bool:
        return bool(self.api_key.strip())

    def complete_json(self, system_prompt: str, user_prompt: str, max_tokens: int = 3000) -> dict[str, Any]:
        if not self.available:
            raise LlmUnavailableError("LLM API Key未配置")
        payload = {
            "model": self.model,
            "stream": False,
            "temperature": 0,
            "max_tokens": max_tokens,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        }
        try:
            response = self._post(payload)
        except LlmError as exc:
            # 部分兼容网关不支持 response_format，降级为纯提示词约束。
            if "response_format" not in payload or "400" not in str(exc):
                raise
            payload.pop("response_format", None)
            response = self._post(payload)
        try:
            content = response["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise LlmError("LLM响应缺少choices[0].message.content") from exc
        if isinstance(content, list):
            content = "".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
        content_text = str(content)
        try:
            return _parse_json_object(content_text)
        except LlmError as first_error:
            # 部分兼容模型即使启用json_object仍可能在长响应中遗漏逗号或未转义引号。
            # 仅让模型修复序列化格式，不改变已有字段和值；失败后继续交由上层规则报告兜底。
            repair_payload = {
                "model": self.model,
                "stream": False,
                "temperature": 0,
                "max_tokens": max_tokens,
                "response_format": {"type": "json_object"},
                "messages": [
                    {
                        "role": "system",
                        "content": (
                            "你是JSON格式修复器。只修复缺失逗号、括号和字符串引号转义，"
                            "不得增删字段、改写文字或修改数值。只输出一个合法JSON对象。"
                        ),
                    },
                    {"role": "user", "content": content_text},
                ],
            }
            repaired_response = self._post(repair_payload)
            try:
                repaired = repaired_response["choices"][0]["message"]["content"]
                if isinstance(repaired, list):
                    repaired = "".join(
                        str(item.get("text", "")) for item in repaired if isinstance(item, dict)
                    )
                return _parse_json_object(str(repaired))
            except (KeyError, IndexError, TypeError, LlmError) as repair_error:
                raise LlmError(
                    f"LLM返回JSON无法修复: {first_error}; repair={repair_error}"
                ) from repair_error

    def _post(self, payload: dict[str, Any]) -> dict[str, Any]:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.api_url,
            data=body,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        last_error: Exception | None = None
        for attempt in range(self.max_retries + 1):
            try:
                with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                    return json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as exc:
                detail = exc.read(1000).decode("utf-8", errors="replace")
                last_error = LlmError(f"LLM API HTTP {exc.code}: {detail}")
                if exc.code not in {408, 429, 500, 502, 503, 504}:
                    break
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                last_error = LlmError(f"LLM API请求失败: {exc}")
            if attempt < self.max_retries:
                time.sleep(min(2 ** attempt, 4))
        raise last_error or LlmError("LLM API请求失败")


def _normalize_completions_url(url: str) -> str:
    value = (url or "").strip().rstrip("/")
    if not value:
        return DEFAULT_API_URL
    if value.endswith("/chat/completions"):
        return value
    if value.rsplit("/", 1)[-1].startswith("v") or value.endswith("/v1") or "/api/v" in value:
        return value + "/chat/completions"
    if "://" in value and value.count("/") <= 2:
        return value + "/v1/chat/completions"
    return value + "/chat/completions"


def _parse_json_object(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        first_newline = text.find("\n")
        text = text[first_newline + 1:] if first_newline >= 0 else text[3:]
        if text.endswith("```"):
            text = text[:-3]
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < start:
        raise LlmError("LLM未返回JSON对象")
    try:
        result = json.loads(text[start:end + 1])
    except json.JSONDecodeError as exc:
        raise LlmError(f"LLM返回JSON无法解析: {exc}") from exc
    if not isinstance(result, dict):
        raise LlmError("LLM返回值不是JSON对象")
    return result


def _setting(name: str, default: str) -> str:
    environment_value = os.getenv(name)
    if environment_value is not None:
        return environment_value
    return _local_env().get(name, default)


def _local_env() -> dict[str, str]:
    global _LOCAL_ENV
    if _LOCAL_ENV is not None:
        return _LOCAL_ENV
    _LOCAL_ENV = {}
    env_file = Path(__file__).resolve().parents[2] / ".env"
    if not env_file.is_file():
        return _LOCAL_ENV
    for line in env_file.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#") or "=" not in text:
            continue
        key, value = text.split("=", 1)
        _LOCAL_ENV[key.strip()] = value.strip().strip('"').strip("'")
    return _LOCAL_ENV
