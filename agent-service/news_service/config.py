"""YAML whitelist loading for configured news sources."""
from __future__ import annotations

import os
import logging
from pathlib import Path
from urllib.parse import urlparse

import yaml
from pydantic import ValidationError

from news_service.models import NewsSourceConfig


# Resolve relative to this module rather than the process working directory.
# Local development servers may be started from different folders; every
# instance must therefore load the same project-owned whitelist.
DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent / "config" / "news_sources.yaml"
logger = logging.getLogger(__name__)


def _upgrade_legacy_entry(item: object) -> object:
    if not isinstance(item, dict):
        return item
    upgraded = dict(item)
    if "column_urls" not in upgraded and upgraded.get("column_url"):
        upgraded["column_urls"] = [upgraded.pop("column_url")]
    if not upgraded.get("domain") and upgraded.get("column_urls"):
        upgraded["domain"] = urlparse(str(upgraded["column_urls"][0])).hostname or ""
    return upgraded


def load_sources(config_path: str | Path | None = None) -> list[NewsSourceConfig]:
    path = Path(config_path or os.getenv("NEWS_SOURCES_CONFIG", DEFAULT_CONFIG_PATH))
    if not path.exists():
        raise ValueError(f"新闻白名单配置不存在: {path}")
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as exc:
        raise ValueError(f"新闻白名单 YAML 格式错误: {exc}") from exc
    entries = raw.get("sources", [])
    if not isinstance(entries, list):
        raise ValueError("新闻白名单配置 sources 必须为列表")
    sources: list[NewsSourceConfig] = []
    for index, item in enumerate(entries):
        try:
            sources.append(NewsSourceConfig.model_validate(_upgrade_legacy_entry(item)))
        except ValidationError as exc:
            logger.warning("[NEWS_CONFIG][WARNING] source_index=%s skipped: %s", index, exc)
    return sources


