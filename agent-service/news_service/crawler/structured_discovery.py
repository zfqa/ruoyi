"""Safe, configuration-driven discovery of article URLs from public data.

This module intentionally parses data only.  It never evaluates JavaScript or
executes remote source code.  Its result has the same shape as HTML link
discovery, so existing detail parsing and persistence remain unchanged.
"""
from __future__ import annotations

import json
import logging
import re
from copy import deepcopy
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any
from urllib.parse import urlencode, urljoin
from urllib.parse import urlsplit, urlunsplit

from bs4 import BeautifulSoup

from news_service.crawler.link_discovery import discover_article_links, normalize_article_url
from news_service.crawler.url_utils import is_allowed_domain, is_html_candidate
from news_service.models import (
    NewsSourceConfig,
    StructuredDiscoveryConfig,
    StructuredOffsetPaginationConfig,
)
from news_service.utils.date_utils import parse_publish_date

logger = logging.getLogger(__name__)


class StructuredDiscoveryError(ValueError):
    """Raised for an invalid or unusable public structured data response."""


def _fetch_public_post(
    fetch_request: Callable[..., str],
    url: str,
    body: Mapping[str, object],
    headers: Mapping[str, str] | None,
    encoding: str,
) -> str:
    """Call the shared public-request adapter, retaining old JSON-only callers."""
    if encoding == "json":
        # Existing diagnostic/test adapters predate form support and accept
        # four arguments.  Keep them source-agnostic and compatible.
        return fetch_request(url, "POST", body, headers)
    return fetch_request(url, "POST", body, headers, encoding)


@dataclass(frozen=True)
class StructuredDiscoveryResult:
    """Structured candidates plus the pagination work that produced them."""

    article_links: list[tuple[str, str, str | None, str | None]]
    # Counts unique article URLs seen in every fetched structured page,
    # including the all-before-start page used solely as a safe boundary.
    discovered_count: int = 0
    pages_visited: int = 1
    stop_reason: str | None = None


def _value_at(value: Any, path: str | None) -> Any:
    """Read a mapping/list value through dotted paths with numeric indexes.

    Both existing ``data.0.items`` and conventional ``data[0].items`` forms
    are accepted.  No expressions, wildcards, or arbitrary code are allowed.
    """
    if not path:
        return value
    normalized_path = re.sub(r"\[(\d+)\]", r".\1", path)
    if "[" in normalized_path or "]" in normalized_path:
        return None
    current = value
    for part in normalized_path.split("."):
        if not part:
            return None
        if isinstance(current, Mapping):
            current = current.get(part)
        elif isinstance(current, list) and part.isdigit():
            index = int(part)
            current = current[index] if 0 <= index < len(current) else None
        else:
            return None
        if current is None:
            return None
    return current


def _format_url(template: str, record: Mapping[str, Any]) -> str | None:
    """Format a configured URL template from top-level record keys safely."""
    class _Missing(dict[str, Any]):
        def __missing__(self, key: str) -> str:
            raise KeyError(key)

    try:
        return template.format_map(_Missing(record))
    except (KeyError, ValueError):
        return None


def _decode_assignment(text: str, prefix: str) -> Any:
    """Decode the JSON immediately following an explicit assignment prefix."""
    start = text.find(prefix)
    if start < 0:
        raise StructuredDiscoveryError("未找到配置的 JSON 赋值前缀")
    payload = text[start + len(prefix) :].lstrip()
    try:
        return json.JSONDecoder().raw_decode(payload)[0]
    except json.JSONDecodeError as exc:
        raise StructuredDiscoveryError("公开 JS 数据不是可安全解析的 JSON") from exc


def _data_url(
    config: StructuredDiscoveryConfig,
    page_url: str,
    *,
    query_overrides: Mapping[str, str | int | float | bool] | None = None,
) -> str:
    assert config.data_url is not None
    # Pydantic may keep a configured URL as an HttpUrl/Url object.  The
    # standard-library URL helpers require homogeneous string arguments.
    value = urljoin(page_url, str(config.data_url))
    # A POST endpoint receives both its fixed values and the page number in
    # the JSON body. Query parameters remain available for GET endpoints.
    query_params = {**config.query_params}
    if config.request_method == "GET":
        query_params.update(query_overrides or {})
    if query_params:
        separator = "&" if "?" in value else "?"
        value = f"{value}{separator}{urlencode(query_params)}"
    return value


def _request_body_values_from_page(config: StructuredDiscoveryConfig, page_html: str) -> dict[str, str]:
    """Extract configured public context values from the fetched list page."""
    if not config.request_body_from_page:
        return {}
    soup = BeautifulSoup(page_html, "html.parser")
    output: dict[str, str] = {}
    for field, location in config.request_body_from_page.items():
        node = soup.select_one(location.selector)
        if node is None:
            raise StructuredDiscoveryError(
                f"request_body_from_page 未命中节点: {field} ({location.selector})"
            )
        value = node.get(location.attribute)
        if value is None or not str(value).strip():
            raise StructuredDiscoveryError(
                f"request_body_from_page 未读取到属性: {field} ({location.attribute})"
            )
        output[field] = str(value)
    return output


def _set_request_value(body: dict[str, object], path: str, value: object) -> None:
    """Set a public request value using a dotted JSON path safely.

    Structured pagination traditionally uses a top-level ``page`` field.
    Public CMS endpoints may instead use an ordinary nested payload such as
    ``paging.page``.  This helper keeps both shapes configuration-driven.
    """
    parts = path.split(".")
    target: dict[str, object] = body
    for part in parts[:-1]:
        existing = target.get(part)
        if existing is None:
            nested: dict[str, object] = {}
            target[part] = nested
            target = nested
        elif isinstance(existing, dict):
            target = existing
        else:
            raise StructuredDiscoveryError(f"分页参数路径不是对象: {path}")
    target[parts[-1]] = value


def _request_body(
    config: StructuredDiscoveryConfig,
    *,
    page_html: str,
    query_overrides: Mapping[str, str | int | float | bool] | None,
) -> dict[str, object]:
    """Build each POST body independently so pagination never mutates YAML data."""
    body: dict[str, object] = deepcopy(config.request_body)
    body.update(_request_body_values_from_page(config, page_html))
    for name, value in (query_overrides or {}).items():
        _set_request_value(body, name, value)
    return body


def _load_document(
    config: StructuredDiscoveryConfig,
    *,
    page_url: str,
    page_html: str,
    fetch_text: Callable[[str], str],
    fetch_request: Callable[[str, str, Mapping[str, object] | None, Mapping[str, str] | None, str], str] | None = None,
    query_overrides: Mapping[str, str | int | float | bool] | None = None,
) -> tuple[Any, str]:
    if config.mode in {"json_endpoint", "json_html_fragment"}:
        data_url = _data_url(config, page_url, query_overrides=query_overrides)
        try:
            if config.request_method == "POST":
                if fetch_request is None:
                    raise StructuredDiscoveryError("公开 JSON POST endpoint 缺少请求函数")
                request_body = _request_body(
                    config, page_html=page_html, query_overrides=query_overrides
                )
                payload = _fetch_public_post(fetch_request, data_url, request_body, config.request_headers, config.request_encoding)
            else:
                # Some public GET listings accept requests only from the
                # ordinary same-origin context established by their own page.
                # Keep that transport choice declarative and shared by every
                # structured source; no source/domain branching is involved.
                if config.request_crawler_type == "dynamic":
                    if fetch_request is None:
                        raise StructuredDiscoveryError("公开 JSON GET endpoint 缺少请求函数")
                    payload = fetch_request(data_url, "GET", None, config.request_headers, "json")
                else:
                    payload = fetch_text(data_url)
            return json.loads(payload), data_url
        except json.JSONDecodeError as exc:
            raise StructuredDiscoveryError("公开 JSON endpoint 返回了无效 JSON") from exc
    if config.mode == "html_endpoint":
        data_url = _data_url(config, page_url, query_overrides=query_overrides)
        if config.request_method == "POST":
            if fetch_request is None:
                raise StructuredDiscoveryError("公开 HTML POST endpoint 缺少请求函数")
            request_body = _request_body(
                config, page_html=page_html, query_overrides=query_overrides
            )
            return _fetch_public_post(fetch_request, data_url, request_body, config.request_headers, config.request_encoding), data_url
        return fetch_text(data_url), data_url
    if config.mode == "script_json":
        node = BeautifulSoup(page_html, "html.parser").select_one(config.script_selector or "")
        if node is None:
            raise StructuredDiscoveryError("未找到配置的 JSON script 节点")
        try:
            return json.loads(node.get_text()), page_url
        except json.JSONDecodeError as exc:
            raise StructuredDiscoveryError("页面 script 节点不是有效 JSON") from exc
    if config.mode == "js_data_file":
        data_url = _data_url(config, page_url, query_overrides=query_overrides)
        return _decode_assignment(fetch_text(data_url), config.json_assignment_prefix or ""), data_url
    raise StructuredDiscoveryError(f"不支持的 structured discovery mode: {config.mode}")


def _records_from_document(config: StructuredDiscoveryConfig, document: Any) -> list[Any]:
    records = _value_at(document, config.records_path)
    if not isinstance(records, list):
        raise StructuredDiscoveryError("records_path 未定位到文章数组")

    if not config.item_records_path:
        return records

    flattened: list[Any] = []
    for container in records:
        if not isinstance(container, Mapping):
            continue
        nested = _value_at(container, config.item_records_path)
        if isinstance(nested, list):
            flattened.extend(nested)
    return flattened


def _links_from_records(
    source: NewsSourceConfig,
    config: StructuredDiscoveryConfig,
    records: list[Any],
    *,
    page_url: str,
    limit: int | None,
    seen: set[str],
) -> list[tuple[str, str, str | None, str | None]]:
    base_url = config.url_base or page_url
    output: list[tuple[str, str, str | None, str | None]] = []
    for record in records:
        if not isinstance(record, Mapping):
            continue
        if any(_value_at(record, field) != expected for field, expected in config.record_filters.items()):
            continue
        if any(_value_at(record, field) == excluded for field, excluded in config.record_exclude_filters.items()):
            continue
        raw_url = _value_at(record, config.article_url_field) if config.article_url_field else None
        if not raw_url and config.article_url_template:
            raw_url = _format_url(config.article_url_template, record)
        if not isinstance(raw_url, str) or not raw_url.strip():
            logger.warning("[STRUCTURED_DISCOVERY][SKIP] source=%s reason=missing_url", source.name)
            continue
        if any(re.search(pattern, raw_url) for pattern in config.article_url_exclude_patterns):
            continue
        original_url = urljoin(base_url, raw_url)
        canonical_value = raw_url
        for suffix in config.article_url_strip_suffixes:
            if suffix and canonical_value.rstrip("/").endswith(suffix.rstrip("/")):
                canonical_value = canonical_value.rstrip("/")[: -len(suffix.rstrip("/"))] or "/"
                break
        canonical_url = normalize_article_url(source, canonical_value, base_url)
        allowed_domains = source.allowed_domains or [source.domain]
        if not canonical_url or not is_allowed_domain(canonical_url, allowed_domains) or not is_html_candidate(canonical_url):
            logger.warning("[STRUCTURED_DISCOVERY][SKIP] source=%s reason=invalid_url url=%s", source.name, original_url)
            continue
        if canonical_url in seen:
            continue
        seen.add(canonical_url)
        title = _value_at(record, config.article_title_field) if config.article_title_field else None
        published_at = _value_at(record, config.article_time_field) if config.article_time_field else None
        if published_at is not None and config.article_time_format:
            try:
                published_at = datetime.strptime(
                    str(published_at).strip(), config.article_time_format
                ).date().isoformat()
            except ValueError:
                # Some public APIs mix a configured local format for older
                # archive pages with ISO timestamps on their newest page.
                # Leave already-supported absolute timestamps untouched; log
                # only genuinely unusable values.
                if parse_publish_date(str(published_at)) is None:
                    logger.warning(
                        "[STRUCTURED_DISCOVERY][DATE] source=%s format=%s value=%s",
                        source.name,
                        config.article_time_format,
                        published_at,
                    )
        output.append((original_url, canonical_url, str(title) if title is not None else None, str(published_at) if published_at is not None else None))
        if limit is not None and len(output) >= limit:
            break
    return output


def _normalize_fragment_link_dates(
    config: StructuredDiscoveryConfig,
    article_links: list[tuple[str, str, str | None, str | None]],
) -> list[tuple[str, str, str | None, str | None]]:
    """Apply an optional configured list-date format to HTML fragments.

    ``html_endpoint`` and ``json_html_fragment`` use the normal selector-based
    link discovery path, rather than JSON record fields.  They therefore need
    the same explicit date-format support as ``_links_from_records`` so an
    ambiguous publisher value is normalized before shared range filtering.
    """
    if not config.article_time_format and not config.article_url_strip_suffixes:
        return article_links

    normalized: list[tuple[str, str, str | None, str | None]] = []
    for original_url, canonical_url, title, published_at in article_links:
        normalized_date = published_at
        if published_at is not None and config.article_time_format:
            try:
                normalized_date = datetime.strptime(
                    published_at.strip(), config.article_time_format
                ).date().isoformat()
            except ValueError:
                # Preserve the publisher value for the shared parser when this
                # format is not applicable to an individual mixed-format record.
                if parse_publish_date(published_at) is None:
                    logger.warning(
                        "[STRUCTURED_DISCOVERY][DATE] format=%s value=%s",
                        config.article_time_format,
                        published_at,
                    )
        normalized_canonical = canonical_url
        for suffix in config.article_url_strip_suffixes:
            parsed = urlsplit(normalized_canonical)
            normalized_path = parsed.path.rstrip("/")
            normalized_suffix = suffix.rstrip("/")
            if normalized_suffix and normalized_path.endswith(normalized_suffix):
                trimmed_path = normalized_path[: -len(normalized_suffix)] or "/"
                normalized_canonical = urlunsplit((parsed.scheme, parsed.netloc, trimmed_path, parsed.query, ""))
                break
        normalized.append((original_url, normalized_canonical, title, normalized_date))
    return normalized


def discover_structured_articles(
    source: NewsSourceConfig,
    *,
    page_url: str,
    page_html: str,
    limit: int | None,
    fetch_text: Callable[[str], str],
    fetch_request: Callable[[str, str, Mapping[str, object] | None, Mapping[str, str] | None, str], str] | None = None,
    publish_time_start: date | None = None,
    max_pages: int | None = None,
) -> StructuredDiscoveryResult:
    """Discover public structured records as normal article-link candidates."""
    config = source.structured_discovery
    if config is None or (limit is not None and limit <= 0):
        return StructuredDiscoveryResult(article_links=[], pages_visited=0, stop_reason="no_records")

    effective_max_pages = max_pages if max_pages is not None else source.max_pages
    pagination = config.pagination
    output: list[tuple[str, str, str | None, str | None]] = []
    seen: set[str] = set()
    discovered_count = 0
    pages_visited = 0
    stop_reason: str | None = None
    page_number = pagination.start_page if pagination is not None and not isinstance(
        pagination, StructuredOffsetPaginationConfig
    ) else 1
    offset = pagination.start_offset if isinstance(pagination, StructuredOffsetPaginationConfig) else 0
    visited_page_numbers: set[int] = set()
    while pages_visited < effective_max_pages and (limit is None or len(output) < limit):
        position = offset if isinstance(pagination, StructuredOffsetPaginationConfig) else page_number
        if pagination is not None and position in visited_page_numbers:
            stop_reason = "visited_page"
            break
        visited_page_numbers.add(position)
        if isinstance(pagination, StructuredOffsetPaginationConfig):
            offset_value: str | int = (
                pagination.parameter_template.format(offset=offset, limit=pagination.page_size)
                if pagination.parameter_template
                else offset
            )
            overrides = {pagination.offset_param: offset_value}
        else:
            overrides = {pagination.page_param: page_number} if pagination is not None else None
        document, data_origin = _load_document(
            config,
            page_url=page_url,
            page_html=page_html,
            fetch_text=fetch_text,
            fetch_request=fetch_request,
            query_overrides=overrides,
        )
        if config.mode in {"json_html_fragment", "html_endpoint"}:
            fragment = _value_at(document, config.html_path) if config.mode == "json_html_fragment" else document
            # Some public JSON APIs return one rendered card per array item.
            # Joining those inert fragments is equivalent to parsing the
            # server-rendered response and remains entirely configuration-led.
            if isinstance(fragment, list) and all(isinstance(item, str) for item in fragment):
                fragment = "\n".join(fragment)
            # A public HTML-fragment endpoint commonly signals the end with
            # an empty response.  Treat that as the normal pagination stop,
            # exactly like an empty JSON article array, rather than an error.
            if not isinstance(fragment, str) or not fragment.strip():
                record_count = 0
                page_links = []
            else:
                fragment_links = discover_article_links(
                    source,
                    page_url,
                    fragment,
                    limit=None if limit is None else limit - len(output),
                ).article_links
                fragment_links = _normalize_fragment_link_dates(config, fragment_links)
                record_count = len(fragment_links)
                page_links = []
                for link in fragment_links:
                    if link[1] in seen:
                        continue
                    seen.add(link[1])
                    page_links.append(link)
        else:
            records = _records_from_document(config, document)
            page_links = _links_from_records(
                source,
                config,
                records,
                page_url=page_url,
                limit=None if limit is None else limit - len(output),
                seen=seen,
            )
            record_count = len(records)
        pages_visited += 1
        if not page_links:
            # A non-empty API response that contributes no new URLs means a
            # later numeric offset/page repeated an earlier collection.  It
            # is not a normal end-of-list signal and must remain observable
            # to the caller's range-completeness decision.
            stop_reason = "structured_repeated_page" if record_count else "structured_empty_page"
            break
        page_dates = [parse_publish_date(item[3]) for item in page_links]
        discovered_count += len(page_links)
        # The service owns requested-time filtering.  Keep the final
        # all-before-start boundary page in discovery output as well: those
        # URLs were truly discovered and must be counted as list-stage
        # filtered rather than disappearing from the accounting.
        output.extend(page_links)
        if (
            publish_time_start is not None
            and source.published_time_order == "desc"
            and page_dates
            and all(item is not None and item < publish_time_start for item in page_dates)
        ):
            stop_reason = "publish_time_before_start"
            break
        logger.info(
            "[STRUCTURED_DISCOVERY] source=%s mode=%s origin=%s page=%s records=%s links=%s",
            source.name,
            config.mode,
            data_origin,
            position,
            record_count,
            len(page_links),
        )
        if limit is not None and len(output) >= limit:
            stop_reason = "max_articles"
            break
        if pagination is None:
            stop_reason = "structured_single_page"
            break
        if isinstance(pagination, StructuredOffsetPaginationConfig):
            offset += pagination.effective_offset_step
        else:
            page_number += pagination.page_step

    if stop_reason is None:
        stop_reason = "max_pages" if pagination is not None and pages_visited >= effective_max_pages else "structured_single_page"
    return StructuredDiscoveryResult(
        article_links=output,
        discovered_count=discovered_count,
        pages_visited=pages_visited,
        stop_reason=stop_reason,
    )


