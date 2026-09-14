"""Playwright-backed crawler which reuses the static article parser."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Callable

from news_service.crawler.browser_manager import BrowserManager, DynamicBrowserError
from news_service.crawler.static_crawler import FetchError, FetchResult, StaticNewsCrawler

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class DynamicPaginationResult:
    pages: list[FetchResult]
    stop_reason: str


class DynamicCrawler(StaticNewsCrawler):
    """Fetch JavaScript-rendered HTML while retaining StaticNewsCrawler.parse_article."""

    def __init__(self, manager: BrowserManager | None = None, *, timeout_seconds: float = 30.0) -> None:
        super().__init__(retries=1, timeout_seconds=timeout_seconds)
        self.manager = manager or BrowserManager.shared()

    _MAX_PAGE_ATTEMPTS = 3

    def fetch(self, url: str, *, wait_time_seconds: float = 0) -> FetchResult:
        # A Chromium process can occasionally fail to start while an ASGI
        # reload/shutdown has just released an earlier instance.  Retry that
        # narrow, transient case once with a freshly started browser.  This is
        # deliberately not a retry for page parsing, site blocks or timeouts.
        def render() -> tuple[str, str, int]:
            return self.manager.render(
                requested_url,
                timeout_seconds=self.timeout_seconds,
                wait_time_seconds=wait_time_seconds,
            )

        try:
            # Pydantic configuration may expose URLs as HttpUrl objects.  The
            # Playwright protocol only accepts a JSON-serialisable string.
            requested_url = str(url)
            for attempt in range(1, self._MAX_PAGE_ATTEMPTS + 1):
                try:
                    html, final_url, status = render()
                    if attempt > 1:
                        logger.info("[DYNAMIC_CRAWLER][RETRY_SUCCESS] url=%s attempt=%s", requested_url, attempt)
                    return FetchResult(html=html, requested_url=final_url, http_status=status, retry_count=attempt - 1)
                except DynamicBrowserError as exc:
                    if exc.error_type not in {"dynamic_browser_error", "dynamic_timeout"} or attempt == self._MAX_PAGE_ATTEMPTS:
                        raise
                    logger.warning("[DYNAMIC_CRAWLER][RETRY] url=%s attempt=%s error_type=%s", requested_url, attempt, exc.error_type)
                    # A failed navigation can leave the shared Chromium
                    # transport unusable for the next independent public URL.
                    # Retry only with a fresh process; this is generic for all
                    # dynamic sources and remains bounded by _MAX_PAGE_ATTEMPTS.
                    self.manager.shutdown()
        except DynamicBrowserError as exc:
            logger.warning("[DYNAMIC_CRAWLER] url=%s error_type=%s message=%s", url, exc.error_type, exc)
            raise FetchError(str(exc), http_status=None, retry_count=0, error_type=exc.error_type) from exc

    def fetch_paginated(
        self,
        url: str,
        *,
        next_page_selector: str,
        max_pages: int,
        wait_time_seconds: float = 0,
        should_continue: Callable[[str], bool] | None = None,
    ) -> DynamicPaginationResult:
        requested_url = str(url)
        try:
            for attempt in range(1, self._MAX_PAGE_ATTEMPTS + 1):
                try:
                    rendered = self.manager.render_paginated(
                        requested_url,
                        next_page_selector=next_page_selector,
                        max_pages=max_pages,
                        timeout_seconds=self.timeout_seconds,
                        wait_time_seconds=wait_time_seconds,
                        should_continue=should_continue,
                    )
                    return DynamicPaginationResult(
                        pages=[FetchResult(html, final_url, status, attempt - 1) for html, final_url, status in rendered.pages],
                        stop_reason=rendered.stop_reason,
                    )
                except DynamicBrowserError as exc:
                    if exc.error_type not in {"dynamic_browser_error", "dynamic_timeout"} or attempt == self._MAX_PAGE_ATTEMPTS:
                        raise
                    logger.warning("[DYNAMIC_CRAWLER][RETRY] url=%s attempt=%s error_type=%s", requested_url, attempt, exc.error_type)
                    self.manager.shutdown()
            raise AssertionError("unreachable")  # pragma: no cover
        except DynamicBrowserError as exc:
            raise FetchError(str(exc), http_status=None, retry_count=self._MAX_PAGE_ATTEMPTS - 1, error_type=exc.error_type) from exc

    def fetch_public_request(
        self,
        *,
        page_url: str,
        request_url: str,
        method: str,
        request_body: dict[str, object] | None,
        request_headers: dict[str, str] | None,
        request_encoding: str,
        wait_time_seconds: float = 0,
    ) -> FetchResult:
        """Fetch a public structured endpoint in its ordinary page context."""
        try:
            for attempt in range(1, self._MAX_PAGE_ATTEMPTS + 1):
                try:
                    text, final_url, status = self.manager.request_from_page_context(
                        str(page_url),
                        str(request_url),
                        method=method,
                        request_body=request_body,
                        request_headers=request_headers,
                        request_encoding=request_encoding,
                        timeout_seconds=self.timeout_seconds,
                        wait_time_seconds=wait_time_seconds,
                    )
                    return FetchResult(text, final_url, status, attempt - 1)
                except DynamicBrowserError as exc:
                    if exc.error_type not in {"dynamic_browser_error", "dynamic_timeout"} or attempt == self._MAX_PAGE_ATTEMPTS:
                        raise
                    logger.warning("[DYNAMIC_CRAWLER][RETRY] public_endpoint=%s attempt=%s error_type=%s", request_url, attempt, exc.error_type)
                    self.manager.shutdown()
            raise AssertionError("unreachable")  # pragma: no cover
        except DynamicBrowserError as exc:
            raise FetchError(str(exc), http_status=None, retry_count=self._MAX_PAGE_ATTEMPTS - 1, error_type=exc.error_type) from exc

    def fetch_scrolled(self, url: str, *, max_pages: int, wait_time_seconds: float = 0) -> DynamicPaginationResult:
        """Fetch successive public infinite-scroll list states with bounded retries."""
        requested_url = str(url)
        try:
            for attempt in range(1, self._MAX_PAGE_ATTEMPTS + 1):
                try:
                    rendered = self.manager.render_scrolled(
                        requested_url,
                        max_pages=max_pages,
                        timeout_seconds=self.timeout_seconds,
                        wait_time_seconds=wait_time_seconds,
                    )
                    return DynamicPaginationResult(
                        pages=[FetchResult(html, final_url, status, attempt - 1) for html, final_url, status in rendered.pages],
                        stop_reason=rendered.stop_reason,
                    )
                except DynamicBrowserError as exc:
                    if exc.error_type not in {"dynamic_browser_error", "dynamic_timeout"} or attempt == self._MAX_PAGE_ATTEMPTS:
                        raise
                    logger.warning("[DYNAMIC_CRAWLER][RETRY] url=%s attempt=%s error_type=%s", requested_url, attempt, exc.error_type)
                    self.manager.shutdown()
            raise AssertionError("unreachable")  # pragma: no cover
        except DynamicBrowserError as exc:
            raise FetchError(str(exc), http_status=None, retry_count=self._MAX_PAGE_ATTEMPTS - 1, error_type=exc.error_type) from exc


