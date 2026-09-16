"""Passive network inspection for a manually operated Dongchedi browser tab.

The inspector intentionally never intercepts, rewrites, aborts or initiates
requests. It observes every page in one BrowserContext and stores only
sanitized JSON candidates needed to discover future vehicle-data sources.
"""
from __future__ import annotations

from collections import Counter
from collections.abc import Mapping
from copy import deepcopy
from datetime import datetime, timezone
import json
import logging
from pathlib import Path
import re
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

from playwright.sync_api import BrowserContext, Frame, Page, Request, Response

logger = logging.getLogger(__name__)

TARGET_FIELD_ALIASES: dict[str, tuple[str, ...]] = {
    "车型": ("车型", "车款", "model_name", "car_name", "vehicle_name"),
    "厂商": ("厂商", "厂家", "manufacturer", "brand_name"),
    "官方指导价": ("官方指导价", "指导价", "厂商指导价", "guide_price", "official_price"),
    "级别": ("级别", "车型级别", "vehicle_level", "level_name"),
    "能源类型": ("能源类型", "能源", "energy_type", "fuel_type"),
    "液晶仪表尺寸(英寸)": ("液晶仪表尺寸", "仪表尺寸", "instrument_size", "cluster_size"),
    "液晶仪表样式": ("液晶仪表样式", "仪表样式", "instrument_style", "cluster_style"),
    "中控屏尺寸(英寸)": ("中控屏尺寸", "中控屏幕尺寸", "central_screen_size", "screen_size"),
    "中控屏幕材质": ("中控屏幕材质", "中控屏材质", "screen_material", "central_screen_material"),
    "副驾驶屏幕尺寸(英寸)": ("副驾驶屏幕尺寸", "副驾屏尺寸", "passenger_screen_size"),
    "后排屏幕尺寸(英寸)": ("后排屏幕尺寸", "后排屏尺寸", "rear_screen_size"),
}
URL_CATEGORY_HINTS: dict[str, tuple[str, ...]] = {
    "brand": ("brand", "品牌"),
    "series": ("series", "车系"),
    "model": ("model", "车型", "car", "config", "配置"),
    "parameter": ("param", "spec", "config", "配置", "参数"),
}
SENSITIVE_KEY_PARTS = ("authorization", "cookie", "token", "password", "secret", "session", "phone", "mobile", "account", "bogus")
ID_KEY_PATTERN = re.compile(r"(^|[_\-])(brand|series|car|model|vehicle|config)[_\-]?id$|(^|[_\-])id$|编号|标识", re.IGNORECASE)


class VehicleNetworkInspector:
    """Observe all pages in a context and record likely vehicle JSON payloads."""

    def __init__(self, output_dir: str | Path) -> None:
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.total_requests = 0
        self.xhr_requests = 0
        self.fetch_requests = 0
        self.xhr_fetch_response_count = 0
        self.json_responses = 0
        self.response_body_read_success_count = 0
        self.response_body_read_failed_count = 0
        self.json_parse_success_count = 0
        self.json_parse_failed_count = 0
        self.new_page_count = 0
        self.navigation_count = 0
        self.document_request_failed_count = 0
        self._response_status_counts: Counter[str] = Counter()
        self._response_content_type_counts: Counter[str] = Counter()
        self._response_body_failure_samples: list[dict[str, str | int]] = []
        self._open_page_snapshots: list[dict[str, str | bool]] = []
        self._sequence = 0
        self._registered_pages: set[int] = set()
        self._candidate_apis: dict[str, list[dict[str, Any]]] = {category: [] for category in URL_CATEGORY_HINTS}
        self._matched_fields: set[str] = set()
        self._possible_ids: list[dict[str, str]] = []
        self._initialization_sources: list[dict[str, Any]] = []

    def attach_context(self, context: BrowserContext) -> None:
        """Attach one passive network listener for every current and future page."""
        context.on("response", self.handle_response)
        context.on("requestfailed", self.handle_request_failed)
        context.on("page", self.handle_new_page)
        for page in context.pages:
            self.register_page(page)

    def handle_new_page(self, page: Page) -> None:
        """Log and observe a popup/new tab without controlling its navigation."""
        self.new_page_count += 1
        print("[PAGE] detected new page")
        print(f"[PAGE] initial url: {self._safe_url(page.url)}")
        # Depending on browser timing, Playwright may deliver the page event
        # after a target=_blank popup has already committed its first URL.  In
        # that case the framenavigated event is not observable retroactively.
        # Record the current URL as an observed navigation, without changing it.
        if page.url and page.url != "about:blank":
            self.navigation_count += 1
            print(f"[PAGE] navigated: {self._safe_url(page.url)}")
        self.register_page(page)

    def register_page(self, page: Page) -> None:
        """Register lifecycle logs; network events remain context-wide."""
        page_id = id(page)
        if page_id in self._registered_pages:
            return
        self._registered_pages.add(page_id)
        page.on("framenavigated", lambda frame, observed_page=page: self._on_frame_navigated(observed_page, frame))
        page.on("domcontentloaded", lambda observed_page=page: self._on_domcontentloaded(observed_page))
        page.on("load", lambda observed_page=page: self._on_load(observed_page))

    def _on_frame_navigated(self, page: Page, frame: Frame) -> None:
        if frame != page.main_frame:
            return
        self.navigation_count += 1
        print(f"[PAGE] navigated: {self._safe_url(frame.url)}")

    def _on_domcontentloaded(self, page: Page) -> None:
        print(f"[PAGE] domcontentloaded: {self._safe_url(page.url)}")

    def _on_load(self, page: Page) -> None:
        print(f"[PAGE] load: {self._safe_url(page.url)}")

    def handle_request_failed(self, request: Request) -> None:
        """Report failed document navigation without headers or request data."""
        if request.resource_type != "document":
            return
        self.document_request_failed_count += 1
        print("[REQUEST FAILED]")
        print(f"URL: {self._safe_url(request.url)}")
        print(f"Resource Type: {request.resource_type}")
        print(f"Failure: {request.failure or 'unknown failure'}")

    def handle_response(self, response: Response) -> None:
        """Read XHR/fetch text, then attempt JSON based on MIME type or body."""
        self.total_requests += 1
        self._response_status_counts[str(response.status)] += 1
        resource_type = response.request.resource_type
        if resource_type not in {"xhr", "fetch"}:
            return
        self.xhr_fetch_response_count += 1
        if resource_type == "xhr":
            self.xhr_requests += 1
        else:
            self.fetch_requests += 1

        content_type = response.headers.get("content-type", "").lower().strip() or "<missing>"
        self._response_content_type_counts[content_type] += 1
        try:
            body_text = self._decode_response_body(response.body(), content_type)
            self.response_body_read_success_count += 1
        except Exception as exc:
            self.response_body_read_failed_count += 1
            self._append_body_failure(response, exc)
            self._print_response_summary(response, content_type=content_type, body_prefix=None)
            return

        body_prefix = body_text.lstrip()[:120]
        self._print_response_summary(response, content_type=content_type, body_prefix=body_prefix)
        if not self._should_attempt_json(content_type, body_text):
            return
        try:
            payload = json.loads(body_text)
        except (TypeError, ValueError, json.JSONDecodeError):
            self.json_parse_failed_count += 1
            return
        self.json_parse_success_count += 1
        self.json_responses += 1
        self._inspect_payload(
            payload,
            source={
                "source_type": "network_json",
                "url": self._safe_url(response.url),
                "method": response.request.method,
                "http_status": response.status,
                "resource_type": resource_type,
                "content_type": content_type,
            },
        )

    def _append_body_failure(self, response: Response, exc: Exception) -> None:
        """Keep a bounded, non-sensitive reason list for post-run diagnosis."""
        if len(self._response_body_failure_samples) >= 20:
            return
        self._response_body_failure_samples.append(
            {
                "url": self._safe_url(response.url),
                "resource_type": response.request.resource_type,
                "http_status": response.status,
                "error_type": type(exc).__name__,
                "error": str(exc)[:300],
            }
        )

    def snapshot_open_pages(self, context: BrowserContext) -> None:
        """Capture the final visible tab state without manipulating any page."""
        snapshots: list[dict[str, str | bool]] = []
        for page in context.pages:
            try:
                if page.is_closed():
                    continue
                current_url = self._safe_url(page.url)
            except Exception as exc:
                logger.debug("[DONGCHEDI_INSPECT] page snapshot failed: %s", type(exc).__name__)
                continue
            snapshots.append({"url": current_url, "is_about_blank": current_url == "about:blank"})
        self._open_page_snapshots = snapshots

    @staticmethod
    def _should_attempt_json(content_type: str, body_text: str) -> bool:
        body = body_text.lstrip()
        return "application/json" in content_type or "text/json" in content_type or body.startswith(("{", "["))

    @staticmethod
    def _decode_response_body(body: bytes, content_type: str) -> str:
        """Decode bytes without relying on a missing/incorrect HTTP charset."""
        charset_match = re.search(r"charset=([^;\\s]+)", content_type, flags=re.IGNORECASE)
        encodings = [charset_match.group(1).strip("\\\"'")] if charset_match else []
        encodings.append("utf-8")
        for encoding in encodings:
            try:
                return body.decode(encoding)
            except (LookupError, UnicodeDecodeError):
                continue
        return body.decode("utf-8", errors="replace")

    def _print_response_summary(self, response: Response, *, content_type: str, body_prefix: str | None) -> None:
        label = "XHR" if response.request.resource_type == "xhr" else "FETCH"
        print(f"[{label}] status={response.status} content-type={content_type} url={self._safe_url(response.url)}")
        if body_prefix is not None:
            print(f"[{label}] body_prefix={self._safe_body_prefix(body_prefix)}")

    @classmethod
    def _safe_body_prefix(cls, body_prefix: str) -> str:
        if any(part in body_prefix.lower() for part in SENSITIVE_KEY_PARTS):
            return "[REDACTED]"
        return " ".join(body_prefix.split())[:120]

    def inspect_page_initialization_json(self, page: Page) -> None:
        """Inspect JSON script tags and summarize raw script candidates safely."""
        try:
            scripts = page.evaluate("""() => Array.from(document.scripts).map((node, index) => ({index, type: node.type || '', id: node.id || '', text: node.textContent || ''}))""")
        except Exception as exc:
            logger.warning("[DONGCHEDI_INSPECT] initialization script inspection failed: %s", type(exc).__name__)
            return
        for script in scripts:
            text = str(script.get("text", "")).strip()
            if not text:
                continue
            metadata = {"script_index": script.get("index"), "script_type": script.get("type") or None, "script_id": script.get("id") or None, "text_length": len(text)}
            if text[:1] in {"{", "["}:
                try:
                    payload = json.loads(text)
                except json.JSONDecodeError:
                    continue
                matches, _ = self.scan_json(payload)
                if matches:
                    self._initialization_sources.append({"source_type": "page_initialization_json", **metadata, "matched_fields": matches})
                self._inspect_payload(payload, source={"source_type": "page_initialization_json", **metadata})
            else:
                matches = self._scan_text_for_target_fields(text)
                if matches:
                    self._initialization_sources.append({"source_type": "page_initialization_script", **metadata, "matched_fields": matches})

    @classmethod
    def scan_json(cls, payload: Any) -> tuple[list[str], list[dict[str, str]]]:
        """Recursively search JSON keys and scalar values for fields and IDs."""
        matched: set[str] = set()
        ids: list[dict[str, str]] = []
        def walk(value: Any, path: str) -> None:
            if isinstance(value, Mapping):
                for key, nested in value.items():
                    key_text = str(key)
                    child_path = f"{path}.{key_text}" if path else key_text
                    cls._match_target_fields(key_text, matched)
                    if cls._is_id_key(key_text) and cls._is_scalar(nested):
                        ids.append({"path": child_path, "key": key_text, "value": cls._bounded_scalar(nested)})
                    walk(nested, child_path)
            elif isinstance(value, list):
                for index, nested in enumerate(value):
                    walk(nested, f"{path}[{index}]")
            elif cls._is_scalar(value):
                cls._match_target_fields(cls._bounded_scalar(value), matched)
        walk(payload, "")
        return sorted(matched), ids[:100]

    @staticmethod
    def _is_scalar(value: Any) -> bool:
        return value is None or isinstance(value, (str, int, float, bool))

    @staticmethod
    def _bounded_scalar(value: Any) -> str:
        return str(value)[:300]

    @classmethod
    def _match_target_fields(cls, text: str, matched: set[str]) -> None:
        lowered = text.lower()
        for field, aliases in TARGET_FIELD_ALIASES.items():
            if any(alias.lower() in lowered for alias in aliases):
                matched.add(field)

    @classmethod
    def _scan_text_for_target_fields(cls, text: str) -> list[str]:
        matched: set[str] = set()
        cls._match_target_fields(text, matched)
        return sorted(matched)

    @staticmethod
    def _is_id_key(key: str) -> bool:
        normalized = key.replace(" ", "").lower()
        return bool(ID_KEY_PATTERN.search(normalized)) or normalized.endswith("id")

    @staticmethod
    def _safe_url(url: str) -> str:
        """Remove fragments and redact sensitive query parameter values."""
        parts = urlsplit(url)
        query = urlencode([(key, "[REDACTED]" if any(word in key.lower() for word in SENSITIVE_KEY_PARTS) else value) for key, value in parse_qsl(parts.query, keep_blank_values=True)], doseq=True)
        return urlunsplit((parts.scheme, parts.netloc, parts.path, query, ""))

    def _classify_candidate(self, source: Mapping[str, Any], matches: list[str], possible_ids: list[dict[str, str]]) -> list[str]:
        url_text = str(source.get("url", "")).lower()
        categories = [category for category, hints in URL_CATEGORY_HINTS.items() if any(hint.lower() in url_text for hint in hints)]
        if matches and "parameter" not in categories:
            categories.append("parameter")
        if possible_ids and not categories:
            categories.append("model")
        return categories

    def _append_candidate_api(self, category: str, source: Mapping[str, Any], matches: list[str], possible_ids: list[dict[str, str]]) -> None:
        item = {"url": source.get("url"), "method": source.get("method"), "http_status": source.get("http_status"), "resource_type": source.get("resource_type"), "matched_fields": matches, "possible_id_keys": [entry["key"] for entry in possible_ids[:20]], "relationship_status": "candidate"}
        if item not in self._candidate_apis[category]:
            self._candidate_apis[category].append(item)

    def _inspect_payload(self, payload: Any, *, source: dict[str, Any]) -> None:
        matches, possible_ids = self.scan_json(payload)
        self._matched_fields.update(matches)
        self._append_possible_ids(possible_ids)
        categories = self._classify_candidate(source, matches, possible_ids)
        for category in categories:
            self._append_candidate_api(category, source, matches, possible_ids)
        if not (matches or possible_ids or categories):
            return
        file_path = self._save_payload(payload, source=source, matches=matches, possible_ids=possible_ids, categories=categories)
        print("=" * 36)
        print("发现疑似车型参数数据")
        print("=" * 36)
        print(f"URL: {source.get('url', 'page initialization JSON')}")
        print(f"Method: {source.get('method', 'N/A')}")
        print(f"命中字段: {'、'.join(matches) if matches else 'ID/接口结构候选'}")
        print(f"保存: {file_path}")

    def _append_possible_ids(self, entries: list[dict[str, str]]) -> None:
        for entry in entries:
            if entry not in self._possible_ids and len(self._possible_ids) < 200:
                self._possible_ids.append(entry)

    def _save_payload(self, payload: Any, *, source: Mapping[str, Any], matches: list[str], possible_ids: list[dict[str, str]], categories: list[str]) -> Path:
        self._sequence += 1
        file_path = self.output_dir / f"request_{self._sequence:03d}.json"
        record = {"source": dict(source), "matched_target_fields": matches, "possible_ids": possible_ids, "candidate_categories": categories, "relationship_status": "candidate", "json_response": self._sanitize(payload)}
        file_path.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
        return file_path

    @classmethod
    def _sanitize(cls, value: Any) -> Any:
        if isinstance(value, Mapping):
            return {str(key): "[REDACTED]" if any(part in str(key).lower() for part in SENSITIVE_KEY_PARTS) else cls._sanitize(nested) for key, nested in value.items()}
        if isinstance(value, list):
            return [cls._sanitize(item) for item in value]
        return deepcopy(value)

    def write_summary(self) -> Path:
        summary = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "total_requests": self.total_requests,
            "xhr_requests": self.xhr_requests,
            "fetch_requests": self.fetch_requests,
            "xhr_fetch_response_count": self.xhr_fetch_response_count,
            "json_responses": self.json_responses,
            "response_body_read_success_count": self.response_body_read_success_count,
            "response_body_read_failed_count": self.response_body_read_failed_count,
            "json_parse_success_count": self.json_parse_success_count,
            "json_parse_failed_count": self.json_parse_failed_count,
            "response_status_counts": dict(self._response_status_counts),
            "response_content_type_counts": dict(self._response_content_type_counts),
            "response_body_failure_samples": self._response_body_failure_samples,
            "new_page_count": self.new_page_count,
            "navigation_count": self.navigation_count,
            "document_request_failed_count": self.document_request_failed_count,
            "open_page_snapshots": self._open_page_snapshots,
            "candidate_brand_apis": self._candidate_apis["brand"],
            "candidate_series_apis": self._candidate_apis["series"],
            "candidate_model_apis": self._candidate_apis["model"],
            "candidate_parameter_apis": self._candidate_apis["parameter"],
            "matched_target_fields": sorted(self._matched_fields),
            "possible_ids": self._possible_ids,
            "initialization_json_sources": self._initialization_sources,
            "candidate_relationships": [{"category": category, "url": candidate.get("url"), "possible_id_keys": candidate.get("possible_id_keys", []), "relationship_status": "candidate"} for category, candidates in self._candidate_apis.items() for candidate in candidates if candidate.get("possible_id_keys")],
            "relationship_status": "candidate",
        }
        path = self.output_dir / "summary.json"
        path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        return path
