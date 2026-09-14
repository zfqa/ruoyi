import os
from dataclasses import dataclass

from dotenv import load_dotenv
from langchain_openai import ChatOpenAI

load_dotenv()


@dataclass(frozen=True)
class LlmRuntimeConfig:
    """Per-request model settings supplied by the RuoYi control plane."""

    api_url: str
    model: str
    api_key: str


def get_chat_model(runtime: LlmRuntimeConfig | None = None) -> ChatOpenAI:
    """Return a ChatOpenAI client compatible with OpenAI-style endpoints."""
    api_key = runtime.api_key if runtime is not None else os.getenv("OPENAI_API_KEY")
    if not api_key or api_key == "your-api-key":
        raise ValueError("未配置 OPENAI_API_KEY，请复制 .env.example 为 .env 后填写模型配置")

    return ChatOpenAI(
        model=runtime.model if runtime is not None else os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
        api_key=api_key,
        base_url=runtime.api_url if runtime is not None else os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1"),
        temperature=0.2,
    )

