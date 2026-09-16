"""On-demand brand → series → selected-series detail service."""
from __future__ import annotations

from dataclasses import dataclass
import logging
from time import monotonic
from typing import Any
import uuid

from playwright.sync_api import BrowserContext, Response

from dongchedi_service.auth import AuthStatus, DongchediAuthManager

from .discovery import BrandDiscoveryResult, DongchediSeriesDiscovery
from .parser import DongchediSeriesParser
from .price_client import DongchediPriceClient


TARGET_BRANDS = ("比亚迪", "蔚来", "长城", "吉利", "长安", "理想", "奇瑞")

logger = logging.getLogger(__name__)


class VehicleServiceError(RuntimeError):
    def __init__(self, code: str, message: str, *, diagnostic_id: str | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.diagnostic_id = diagnostic_id


@dataclass
class _CacheItem:
    expires_at: float
    result: BrandDiscoveryResult


class VehicleSelectionService:
    """Provides on-demand details; it never pre-fetches every series or brand."""

    def __init__(
        self,
        *,
        cache_ttl_seconds: int = 1800,
        auth_manager: DongchediAuthManager | None = None,
        discovery: DongchediSeriesDiscovery | None = None,
        parser: DongchediSeriesParser | None = None,
    ) -> None:
        self.cache_ttl_seconds = cache_ttl_seconds
        self.auth_manager = auth_manager or DongchediAuthManager()
        self.discovery = discovery or DongchediSeriesDiscovery()
        self.parser = parser or DongchediSeriesParser()
        self._series_cache: dict[str, _CacheItem] = {}

    @staticmethod
    def list_brands() -> list[dict[str, str]]:
        return [{"name": brand} for brand in TARGET_BRANDS]

    def list_series(self, brand: str) -> BrandDiscoveryResult:
        self._validate_brand(brand)
        started_at = monotonic()
        cached = self._series_cache.get(brand)
        if cached is not None and cached.expires_at > monotonic():
            logger.info("[series-discovery] start brand=%s cache=hit", brand)
            logger.info(
                "[series-discovery] discovery finished brand=%s cache=hit count=%s elapsed=%dms",
                brand,
                len(cached.result.series),
                (monotonic() - started_at) * 1000,
            )
            return cached.result
        logger.info("[series-discovery] start brand=%s cache=miss", brand)
        try:
            result = self._discover(brand)
        except Exception as exc:
            logger.warning(
                "[series-discovery] discovery failed brand=%s cache=miss error_type=%s elapsed=%dms",
                brand,
                type(exc).__name__,
                (monotonic() - started_at) * 1000,
            )
            raise
        self._series_cache[brand] = _CacheItem(monotonic() + self.cache_ttl_seconds, result)
        logger.info(
            "[series-discovery] discovery finished brand=%s cache=miss count=%s elapsed=%dms",
            brand,
            len(result.series),
            (monotonic() - started_at) * 1000,
        )
        return result

    def get_series_details(self, series_id: str, *, brand: str | None = None, series_name: str | None = None) -> dict[str, Any]:
        if brand is not None:
            self._validate_brand(brand)
            discovery = self.list_series(brand)
            selected = next((item for item in discovery.series if item.series_id == series_id), None)
            if selected is None and series_name:
                selected = next((item for item in discovery.series if item.series_name == series_name), None)
            if selected is None:
                raise VehicleServiceError("series_not_found", "指定车系不属于当前品牌或不存在。")
        else:
            selected = self._find_in_cached_series(series_id)
            if selected is None:
                raise VehicleServiceError("series_not_found", "请先查询品牌车系列表，或在详情请求中提供 brand。")
        return self._parse_selected_series(selected.parameter_url)

    def get_series_details_by_name(self, brand: str, series_name: str) -> dict[str, Any]:
        """Resolve a user-facing series name once, then continue by stable ID."""
        self._validate_brand(brand)
        discovery = self.list_series(brand)
        selected = next((item for item in discovery.series if item.series_name == series_name), None)
        if selected is None:
            raise VehicleServiceError("series_not_found", "当前品牌下未找到指定车系。")
        return self._parse_selected_series(selected.parameter_url)

    def _discover(self, brand: str) -> BrandDiscoveryResult:
        def operation(context: BrowserContext) -> BrandDiscoveryResult:
            logger.info("[series-discovery] stage brand=%s stage=open_page", brand)
            page = context.new_page()
            try:
                return self.discovery.discover(page, brand)
            except LookupError as exc:
                raise VehicleServiceError("upstream_error", str(exc)) from exc
            except VehicleServiceError:
                raise
            except Exception as exc:
                raise VehicleServiceError("upstream_error", f"车系列表获取失败：{type(exc).__name__}") from exc
            finally:
                page.close()
        return self._with_context(operation)

    def _parse_selected_series(self, parameter_url: str) -> dict[str, Any]:
        def operation(context: BrowserContext) -> dict[str, Any]:
            payloads: list[dict[str, Any]] = []
            page = context.new_page()
            try:
                page.on("response", lambda response: self._collect_price_payload(payloads, response))
                try:
                    page.goto(parameter_url, wait_until="domcontentloaded", timeout=60_000)
                    page.wait_for_timeout(6_000)
                except Exception as exc:
                    raise VehicleServiceError("page_load_failed", f"参数页加载失败：{type(exc).__name__}") from exc
                body = page.locator("body").inner_text(timeout=5_000)
                if self._is_auth_required(page.url, body):
                    raise VehicleServiceError("auth_required", "登录状态失效或页面要求验证，请重新人工登录。")
                try:
                    return self.parser.parse(page, payloads).to_dict()
                except ValueError as exc:
                    raise VehicleServiceError("parse_failed", str(exc)) from exc
                except Exception as exc:
                    raise VehicleServiceError("parse_failed", f"参数页解析失败：{type(exc).__name__}") from exc
            finally:
                page.close()
        return self._with_context(operation)

    def _with_context(self, operation: Any) -> Any:
        logger.info("[series-discovery] stage stage=load_auth_state")
        status = self.auth_manager.state_file_status()
        if not self.auth_manager.state_path.exists() or status.status == AuthStatus.AUTH_STATE_INVALID:
            raise VehicleServiceError(
                "auth_required",
                "未找到有效登录状态，请先在 agent-service 目录执行 python scripts/dongchedi_login.py 完成人工登录。",
            )
        playwright = browser = context = None
        try:
            # Real-site validation established that this target must use a
            # visible browser. No fingerprint modification or headless bypass.
            logger.info("[series-discovery] stage stage=launch_browser")
            playwright, browser = self.auth_manager._start_browser(headless=False)
            logger.info("[series-discovery] stage stage=load_auth_state")
            context = self.auth_manager.create_context(browser, use_saved_state=True)
            return operation(context)
        except VehicleServiceError:
            raise
        except Exception as exc:
            diagnostic_id = uuid.uuid4().hex[:8]
            operation_name = getattr(operation, "__qualname__", getattr(operation, "__name__", "unknown"))
            logger.exception(
                "Dongchedi upstream error diagnostic_id=%s operation=%s error_type=%s error=%s",
                diagnostic_id,
                operation_name,
                type(exc).__name__,
                str(exc),
            )
            raise VehicleServiceError(
                "upstream_error",
                f"懂车帝上游访问失败：{type(exc).__name__}",
                diagnostic_id=diagnostic_id,
            ) from exc
        finally:
            if context is not None:
                context.close()
            if browser is not None:
                browser.close()
            if playwright is not None:
                playwright.stop()

    @staticmethod
    def _collect_price_payload(payloads: list[dict[str, Any]], response: Response) -> None:
        if not DongchediPriceClient.is_price_api(response.url):
            return
        try:
            payload = response.json()
        except Exception:
            return
        if isinstance(payload, dict):
            payloads.append(payload)

    def _find_in_cached_series(self, series_id: str):
        for cache in self._series_cache.values():
            if cache.expires_at <= monotonic():
                continue
            for series in cache.result.series:
                if series.series_id == series_id:
                    return series
        return None

    @staticmethod
    def _validate_brand(brand: str) -> None:
        if brand not in TARGET_BRANDS:
            raise VehicleServiceError("unsupported_brand", "当前仅支持指定的 7 个业务品牌。")

    @staticmethod
    def _is_auth_required(url: str, body: str) -> bool:
        return "login-required" in url.lower() or "请登录" in body or "验证码" in body
