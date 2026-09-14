"""Reusable Chromium lifecycle management for dynamic news sources."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from threading import RLock, local
from typing import Callable

logger = logging.getLogger(__name__)


class DynamicBrowserError(RuntimeError):
    def __init__(self, message: str, *, error_type: str) -> None:
        super().__init__(message)
        self.error_type = error_type


@dataclass(frozen=True)
class PaginatedRenderResult:
    pages: list[tuple[str, str, int]]
    stop_reason: str


class BrowserManager:
    """Own one headless Chromium process and create isolated contexts per URL."""

    _shared: "BrowserManager | None" = None
    _shared_lock = RLock()

    def __init__(self) -> None:
        self._lock = RLock()
        # Playwright's synchronous API binds its greenlet transport to the
        # thread that created it. FastAPI may execute separate synchronous
        # crawl requests on different worker threads, so one process-wide
        # browser object produces ``Cannot switch to a different thread``.
        # Keep the manager shared for configuration and locking, while making
        # the actual sync-Playwright state thread-local.
        self._thread_state = local()

    @property
    def _playwright(self):
        return getattr(self._thread_state, "playwright", None)

    @_playwright.setter
    def _playwright(self, value) -> None:
        self._thread_state.playwright = value

    @property
    def _browser(self):
        return getattr(self._thread_state, "browser", None)

    @_browser.setter
    def _browser(self, value) -> None:
        self._thread_state.browser = value

    @staticmethod
    def _close_context(context) -> None:
        """Best-effort context cleanup must never mask the crawl result."""
        try:
            context.close()
        except Exception:
            # A failed navigation or concurrent browser teardown can already
            # have closed this context.  Its close error is not a second crawl
            # failure and must not bypass DynamicCrawler's bounded retry.
            logger.debug("[DYNAMIC_CRAWLER] context already closed during cleanup", exc_info=True)

    @classmethod
    def shared(cls) -> "BrowserManager":
        with cls._shared_lock:
            if cls._shared is None:
                cls._shared = cls()
            return cls._shared

    def _ensure_browser(self) -> None:
        if self._browser is not None:
            return
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:
            raise DynamicBrowserError("未安装 Playwright，请执行 pip install playwright 并运行 playwright install chromium", error_type="dynamic_browser_error") from exc
        try:
            self._playwright = sync_playwright().start()
            self._browser = self._playwright.chromium.launch(headless=True)
            logger.info("[DYNAMIC_CRAWLER] browser=started")
        except Exception as exc:
            # Keep the low-level Playwright detail in server logs for
            # troubleshooting, while callers still receive the stable,
            # user-safe dynamic_browser_error classification.
            logger.exception("[DYNAMIC_CRAWLER] Chromium launch failed: %s", exc)
            self.shutdown()
            raise DynamicBrowserError("Chromium 浏览器启动失败", error_type="dynamic_browser_error") from exc

    def render(self, url: str, *, timeout_seconds: float, wait_time_seconds: float) -> tuple[str, str, int]:
        """Return rendered HTML, final URL and HTTP status without retaining page state."""
        timeout_ms = max(1, int(timeout_seconds * 1000))
        with self._lock:
            self._ensure_browser()
            context = self._browser.new_context(locale="zh-CN")
            page = context.new_page()
            try:
                # ``commit`` confirms that a document response was received.
                # A few public sites continue loading third-party assets long
                # after useful document HTML exists, and may never fire the
                # later lifecycle event within our bounded timeout.
                response = page.goto(url, wait_until="commit", timeout=timeout_ms)
                try:
                    page.wait_for_load_state("domcontentloaded", timeout=timeout_ms)
                except Exception as exc:
                    try:
                        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                    except ImportError:  # pragma: no cover - handled by _ensure_browser
                        PlaywrightTimeoutError = ()  # type: ignore[assignment]
                    if not isinstance(exc, PlaywrightTimeoutError):
                        raise
                    logger.info(
                        "[DYNAMIC_CRAWLER] dom_content_loaded_timeout url=%s; using committed document",
                        url,
                    )
                # Some otherwise usable public pages keep analytics, consent, or
                # similar third-party connections active indefinitely.  The DOM
                # has already loaded at this point, so a network-idle timeout is
                # not by itself a page-load failure.  Keep the optimisation when
                # it succeeds, then safely fall back to DOM-ready HTML when it
                # does not.
                try:
                    page.wait_for_load_state("networkidle", timeout=timeout_ms)
                except Exception as exc:
                    try:
                        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                    except ImportError:  # pragma: no cover - handled by _ensure_browser
                        PlaywrightTimeoutError = ()  # type: ignore[assignment]
                    if not isinstance(exc, PlaywrightTimeoutError):
                        raise
                    logger.info(
                        "[DYNAMIC_CRAWLER] network_idle_timeout url=%s; using DOM-ready page",
                        url,
                    )
                if wait_time_seconds:
                    page.wait_for_timeout(int(wait_time_seconds * 1000))
                html = page.content()
                if not html.strip():
                    raise DynamicBrowserError("页面渲染后为空", error_type="dynamic_empty_page")
                status = response.status if response is not None else 200
                logger.info("[DYNAMIC_CRAWLER] url=%s page_loaded=true html_length=%s", url, len(html))
                return html, page.url, status
            except DynamicBrowserError:
                raise
            except Exception as exc:
                # Import lazily so importing the static crawler never requires Playwright.
                try:
                    from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                except ImportError:  # pragma: no cover - handled above when unavailable
                    PlaywrightTimeoutError = ()  # type: ignore[assignment]
                error_type = "dynamic_timeout" if isinstance(exc, PlaywrightTimeoutError) else "dynamic_browser_error"
                message = "page load timeout" if error_type == "dynamic_timeout" else "动态页面加载失败"
                raise DynamicBrowserError(message, error_type=error_type) from exc
            finally:
                self._close_context(context)

    def request_from_page_context(
        self,
        page_url: str,
        request_url: str,
        *,
        method: str,
        request_body: dict[str, object] | None,
        request_headers: dict[str, str] | None,
        request_encoding: str,
        timeout_seconds: float,
        wait_time_seconds: float,
    ) -> tuple[str, str, int]:
        """Make a normal public same-origin request after opening its list page.

        This is for public CMS list endpoints whose own page supplies ordinary
        session context.  It intentionally has no support for credentials,
        private tokens, browser fingerprint changes, or cross-origin calls.
        """
        timeout_ms = max(1, int(timeout_seconds * 1000))
        with self._lock:
            self._ensure_browser()
            context = self._browser.new_context(locale="zh-CN")
            page = context.new_page()
            try:
                response = page.goto(page_url, wait_until="commit", timeout=timeout_ms)
                try:
                    page.wait_for_load_state("domcontentloaded", timeout=timeout_ms)
                except Exception as exc:
                    try:
                        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                    except ImportError:  # pragma: no cover
                        PlaywrightTimeoutError = ()  # type: ignore[assignment]
                    if not isinstance(exc, PlaywrightTimeoutError):
                        raise
                if wait_time_seconds:
                    page.wait_for_timeout(int(wait_time_seconds * 1000))
                payload = page.evaluate(
                    """async ({url, method, body, headers, encoding}) => {
                        const requestHeaders = {...(headers || {})};
                        let encodedBody;
                        if (method !== 'GET' && body) {
                            if (encoding === 'json') {
                                requestHeaders['Content-Type'] ||= 'application/json';
                                encodedBody = JSON.stringify(body);
                            } else {
                                requestHeaders['Content-Type'] ||= 'application/x-www-form-urlencoded; charset=UTF-8';
                                encodedBody = new URLSearchParams(body).toString();
                            }
                        }
                        const result = await fetch(url, {
                            method,
                            headers: requestHeaders,
                            body: encodedBody,
                            credentials: 'same-origin',
                        });
                        return {status: result.status, finalUrl: result.url, text: await result.text()};
                    }""",
                    {
                        "url": request_url,
                        "method": method,
                        "body": request_body,
                        "headers": request_headers,
                        "encoding": request_encoding,
                    },
                )
                status = int(payload["status"])
                if status >= 400:
                    raise DynamicBrowserError(f"公开列表请求失败: HTTP {status}", error_type="dynamic_browser_error")
                text = str(payload["text"])
                if not text.strip():
                    # An empty structured response is a valid end-of-pages
                    # marker.  Return it unchanged for StructuredDiscovery.
                    logger.info("[DYNAMIC_CRAWLER] public_endpoint_empty url=%s", request_url)
                return text, str(payload.get("finalUrl") or request_url), status
            except DynamicBrowserError:
                raise
            except Exception as exc:
                try:
                    from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                except ImportError:  # pragma: no cover
                    PlaywrightTimeoutError = ()  # type: ignore[assignment]
                error_type = "dynamic_timeout" if isinstance(exc, PlaywrightTimeoutError) else "dynamic_browser_error"
                raise DynamicBrowserError(
                    "page load timeout" if error_type == "dynamic_timeout" else "动态公开列表请求失败",
                    error_type=error_type,
                ) from exc
            finally:
                self._close_context(context)

    def render_paginated(
        self,
        url: str,
        *,
        next_page_selector: str,
        max_pages: int,
        timeout_seconds: float,
        wait_time_seconds: float,
        should_continue: Callable[[str], bool] | None = None,
    ) -> PaginatedRenderResult:
        """Render successive client-side list states by clicking a configured control."""
        timeout_ms = max(1, int(timeout_seconds * 1000))
        with self._lock:
            self._ensure_browser()
            context = self._browser.new_context(locale="zh-CN")
            page = context.new_page()
            try:
                # Keep client-side pagination consistent with ``render``:
                # a committed public document is usable even when analytics or
                # long-polling prevents the later DOM lifecycle event from
                # settling within the navigation timeout.
                response = page.goto(url, wait_until="commit", timeout=timeout_ms)
                try:
                    page.wait_for_load_state("domcontentloaded", timeout=timeout_ms)
                except Exception as exc:
                    try:
                        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                    except ImportError:  # pragma: no cover
                        PlaywrightTimeoutError = ()  # type: ignore[assignment]
                    if not isinstance(exc, PlaywrightTimeoutError):
                        raise
                    logger.info("[DYNAMIC_CRAWLER] paginated_dom_content_loaded_timeout url=%s; using committed document", url)
                # Match normal dynamic fetching: interactive controls are
                # often initialised after DOMContentLoaded.  Give ordinary
                # public resources a bounded chance to settle before clicking
                # a configured pagination control; a timeout is harmless once
                # a document exists.
                try:
                    page.wait_for_load_state("networkidle", timeout=timeout_ms)
                except Exception as exc:
                    try:
                        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                    except ImportError:  # pragma: no cover
                        PlaywrightTimeoutError = ()  # type: ignore[assignment]
                    if not isinstance(exc, PlaywrightTimeoutError):
                        raise
                    logger.info("[DYNAMIC_CRAWLER] paginated_network_idle_timeout url=%s; using DOM-ready page", url)
                if wait_time_seconds:
                    page.wait_for_timeout(int(wait_time_seconds * 1000))
                status = response.status if response is not None else 200
                pages = [(page.content(), page.url, status)]
                stop_reason = "max_pages"
                # For a source that explicitly declares descending list dates,
                # the caller can safely stop after the newly appended batch is
                # wholly older than the requested time range.  The callback is
                # intentionally generic: BrowserManager never knows a source,
                # domain, selector, or date format.
                if should_continue is not None and not should_continue(pages[-1][0]):
                    stop_reason = "publish_time_before_start"
                for _ in range(1, max_pages):
                    if stop_reason == "publish_time_before_start":
                        break
                    control = page.locator(next_page_selector).first
                    if control.count() == 0:
                        stop_reason = "no_next_page"
                        break
                    disabled = control.evaluate(
                        "(element) => element.hasAttribute('disabled') || "
                        "element.getAttribute('aria-disabled') === 'true' || "
                        "element.classList.contains('disabled')"
                    )
                    if disabled:
                        stop_reason = "no_next_page"
                        break
                    before = pages[-1][0]
                    # Public "Show more" controls are frequently rendered
                    # below the viewport.  Make generic configured clicks
                    # work for those controls without any source-specific
                    # scrolling rule.
                    control.scroll_into_view_if_needed(timeout=timeout_ms)
                    # A load-more button normally mutates the current DOM,
                    # rather than navigating.  Do not wait for a navigation
                    # that may never occur; the bounded DOM check below is
                    # the authoritative success condition.
                    control.click(timeout=timeout_ms, no_wait_after=True)
                    # A public "load more" handler may append one batch, or
                    # chain several ordinary page requests before it hides
                    # itself.  A fixed one-second snapshot sees only the
                    # first batch on the latter kind of page.  Wait until the
                    # DOM first changes, then until it remains unchanged for
                    # a short bounded settle period.  This is deliberately
                    # selector- and source-agnostic; it only observes the
                    # document that the configured control changed.
                    poll_ms = max(
                        250,
                        min(1000, int(wait_time_seconds * 1000) if wait_time_seconds else 350),
                    )
                    settle_ms = max(750, poll_ms * 2)
                    elapsed_ms = 0
                    changed = False
                    stable_ms = 0
                    html = before
                    while elapsed_ms < timeout_ms:
                        page.wait_for_timeout(poll_ms)
                        elapsed_ms += poll_ms
                        current_html = page.content()
                        if current_html != html:
                            html = current_html
                            changed = True
                            stable_ms = 0
                            continue
                        if changed:
                            stable_ms += poll_ms
                            if stable_ms >= settle_ms:
                                break
                    if not changed:
                        stop_reason = "no_new_page"
                        break
                    pages.append((html, page.url, status))
                    if should_continue is not None and not should_continue(html):
                        stop_reason = "publish_time_before_start"
                        break
                logger.info("[DYNAMIC_CRAWLER] url=%s pages=%s stop_reason=%s", url, len(pages), stop_reason)
                return PaginatedRenderResult(pages=pages, stop_reason=stop_reason)
            except DynamicBrowserError:
                raise
            except Exception as exc:
                try:
                    from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                except ImportError:  # pragma: no cover
                    PlaywrightTimeoutError = ()  # type: ignore[assignment]
                error_type = "dynamic_timeout" if isinstance(exc, PlaywrightTimeoutError) else "dynamic_browser_error"
                raise DynamicBrowserError(
                    "page load timeout" if error_type == "dynamic_timeout" else "动态页面加载失败",
                    error_type=error_type,
                ) from exc
            finally:
                self._close_context(context)

    def render_scrolled(
        self,
        url: str,
        *,
        max_pages: int,
        timeout_seconds: float,
        wait_time_seconds: float,
    ) -> PaginatedRenderResult:
        """Render batches appended by a public infinite-scroll listing."""
        timeout_ms = max(1, int(timeout_seconds * 1000))
        with self._lock:
            self._ensure_browser()
            context = self._browser.new_context(locale="zh-CN")
            page = context.new_page()
            try:
                response = page.goto(url, wait_until="commit", timeout=timeout_ms)
                try:
                    page.wait_for_load_state("domcontentloaded", timeout=timeout_ms)
                except Exception as exc:
                    try:
                        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                    except ImportError:  # pragma: no cover
                        PlaywrightTimeoutError = ()  # type: ignore[assignment]
                    if not isinstance(exc, PlaywrightTimeoutError):
                        raise
                    logger.info("[DYNAMIC_CRAWLER] scroll_dom_content_loaded_timeout url=%s; using committed document", url)
                try:
                    page.wait_for_load_state("networkidle", timeout=timeout_ms)
                except Exception as exc:
                    try:
                        from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                    except ImportError:  # pragma: no cover
                        PlaywrightTimeoutError = ()  # type: ignore[assignment]
                    if not isinstance(exc, PlaywrightTimeoutError):
                        raise
                    logger.info("[DYNAMIC_CRAWLER] scroll_network_idle_timeout url=%s; using DOM-ready page", url)
                if wait_time_seconds:
                    page.wait_for_timeout(int(wait_time_seconds * 1000))
                status = response.status if response is not None else 200
                pages = [(page.content(), page.url, status)]
                stop_reason = "max_pages"
                for _ in range(1, max_pages):
                    before_html = pages[-1][0]
                    before_height = page.evaluate("document.body.scrollHeight")
                    # News feeds often attach their observer to ordinary user
                    # scrolling, not to a single programmatic jump.  Mimic a
                    # bounded sequence of mouse-wheel events until the bottom
                    # is reached.  This remains generic: it neither knows the
                    # source nor assumes a particular card or button selector.
                    step_wait_ms = max(
                        250,
                        min(1000, int((wait_time_seconds * 1000) / 8) if wait_time_seconds else 350),
                    )
                    for _step in range(24):
                        at_bottom = page.evaluate(
                            "window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2"
                        )
                        if at_bottom:
                            break
                        page.mouse.wheel(0, 750)
                        page.wait_for_timeout(step_wait_ms)
                    # Some feeds observe scroll-position changes but ignore
                    # synthetic wheel events once a focused child element has
                    # consumed them.  Explicitly place the document at its
                    # public bottom edge as the generic final trigger.
                    page.evaluate("window.scrollTo(0, document.documentElement.scrollHeight)")
                    # Some public feeds keep the document itself fixed and
                    # render cards in an overflow container.  Advance every
                    # visible, materially scrollable container as well; this
                    # is a generic DOM operation and does not depend on a
                    # source name, domain, or component selector.
                    page.evaluate(
                        """() => {
                            for (const node of document.querySelectorAll('body *')) {
                                const style = getComputedStyle(node);
                                const scrollable = /auto|scroll/.test(style.overflowY)
                                    && node.clientHeight >= 160
                                    && node.scrollHeight > node.clientHeight + 8;
                                if (scrollable) {
                                    node.scrollTop = node.scrollHeight;
                                    node.dispatchEvent(new Event('scroll', { bubbles: true }));
                                }
                            }
                        }"""
                    )
                    # A final scroll event at the edge covers pages whose
                    # sentinel is exactly below the last visible row.
                    page.mouse.wheel(0, 750)
                    # The observer behind an infinite feed often starts an
                    # asynchronous public request only after the bottom-edge
                    # scroll event.  A single immediate snapshot can therefore
                    # wrongly report "no new page" even though the next batch
                    # is about to be appended.  Wait a bounded amount for an
                    # actual DOM/height mutation, then take the new state.
                    # This is intentionally generic: it observes only a
                    # document change and has no source or selector knowledge.
                    wait_limit_ms = min(timeout_ms, max(2500, step_wait_ms * 8))
                    elapsed_ms = 0
                    html = before_html
                    height = before_height
                    while elapsed_ms < wait_limit_ms:
                        page.wait_for_timeout(step_wait_ms)
                        elapsed_ms += step_wait_ms
                        html = page.content()
                        height = page.evaluate("document.body.scrollHeight")
                        if html != before_html or height != before_height:
                            break
                    if html == before_html and height == before_height:
                        stop_reason = "no_new_page"
                        break
                    pages.append((html, page.url, status))
                logger.info("[DYNAMIC_CRAWLER] url=%s scroll_batches=%s stop_reason=%s", url, len(pages), stop_reason)
                return PaginatedRenderResult(pages=pages, stop_reason=stop_reason)
            except DynamicBrowserError:
                raise
            except Exception as exc:
                try:
                    from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
                except ImportError:  # pragma: no cover
                    PlaywrightTimeoutError = ()  # type: ignore[assignment]
                error_type = "dynamic_timeout" if isinstance(exc, PlaywrightTimeoutError) else "dynamic_browser_error"
                raise DynamicBrowserError(
                    "page load timeout" if error_type == "dynamic_timeout" else "动态页面加载失败",
                    error_type=error_type,
                ) from exc
            finally:
                self._close_context(context)

    def shutdown(self) -> None:
        with self._lock:
            if self._browser is not None:
                try:
                    self._browser.close()
                finally:
                    self._browser = None
            if self._playwright is not None:
                try:
                    self._playwright.stop()
                finally:
                    self._playwright = None
            logger.info("[DYNAMIC_CRAWLER] browser=stopped")

    @classmethod
    def shutdown_shared(cls) -> None:
        with cls._shared_lock:
            if cls._shared is not None:
                cls._shared.shutdown()
                cls._shared = None


