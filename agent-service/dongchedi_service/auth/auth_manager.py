"""Standalone Playwright authentication-state management for Dongchedi.

This module deliberately does not contain credentials, captcha logic, or any
anti-bot bypass.  Login-state selectors are configurable because they must be
verified manually against the real site before production use.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import json
import logging
import os
from pathlib import Path
from typing import Any

from playwright.sync_api import Browser, BrowserContext, Page, Playwright, sync_playwright

logger = logging.getLogger(__name__)


class AuthStatus(str, Enum):
    """Stable authentication states exposed to future Dongchedi clients."""

    AUTHENTICATED = "authenticated"
    AUTH_REQUIRED = "auth_required"
    AUTH_EXPIRED = "auth_expired"
    AUTH_STATE_INVALID = "auth_state_invalid"
    LOGIN_FAILED = "login_failed"


@dataclass(frozen=True)
class AuthResult:
    """Safe authentication result; never includes cookies or credentials."""

    status: AuthStatus
    message: str
    verification_mode: str | None = None


@dataclass
class ManualLoginSession:
    """Resources for one visible, standalone manual-login browser session."""

    playwright: Playwright
    browser: Browser
    context: BrowserContext
    page: Page

    def close(self) -> None:
        """Close resources in reverse order without masking an earlier error."""
        try:
            self.context.close()
        finally:
            try:
                self.browser.close()
            finally:
                self.playwright.stop()


class DongchediAuthManager:
    """Manage a standalone Chromium session and Playwright storage state.

    `DONGCHEDI_AUTHENTICATED_SELECTOR` is intentionally optional.  It must be
    set only after manually confirming a selector that is visible for a logged
    in user and absent for a logged out user.  The code does not claim any
    default selector is verified.
    """

    DEFAULT_LOGIN_URL = "https://www.dongchedi.com/login"

    def __init__(
        self,
        state_path: str | Path | None = None,
        *,
        login_url: str | None = None,
        authenticated_selector: str | None = None,
        username_selector: str | None = None,
        password_selector: str | None = None,
    ) -> None:
        project_root = Path(__file__).resolve().parents[2]
        self.state_path = Path(state_path) if state_path is not None else project_root / "data" / "dongchedi_auth.json"
        self.login_url = login_url or os.getenv("DONGCHEDI_LOGIN_URL", self.DEFAULT_LOGIN_URL)
        self.authenticated_selector = authenticated_selector or os.getenv("DONGCHEDI_AUTHENTICATED_SELECTOR")
        self.username_selector = username_selector or os.getenv("DONGCHEDI_USERNAME_SELECTOR")
        self.password_selector = password_selector or os.getenv("DONGCHEDI_PASSWORD_SELECTOR")

    @staticmethod
    def _read_optional_credentials() -> tuple[str | None, str | None]:
        """Read credentials only at runtime and never log their values."""
        return os.getenv("DONGCHEDI_USERNAME"), os.getenv("DONGCHEDI_PASSWORD")

    def state_file_status(self) -> AuthResult:
        """Validate the local state file structurally, without a network call."""
        if not self.state_path.exists():
            return AuthResult(AuthStatus.AUTH_REQUIRED, "未找到登录状态文件，请先执行人工登录。")

        try:
            raw: Any = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return AuthResult(AuthStatus.AUTH_STATE_INVALID, "登录状态文件无效，可重新人工登录覆盖。")

        if not isinstance(raw, dict) or not isinstance(raw.get("cookies"), list) or not isinstance(raw.get("origins"), list):
            return AuthResult(AuthStatus.AUTH_STATE_INVALID, "登录状态文件格式无效，可重新人工登录覆盖。")
        if not raw["cookies"] and not raw["origins"]:
            return AuthResult(AuthStatus.AUTH_REQUIRED, "登录状态文件存在，但其中没有可复用的登录状态。")
        return AuthResult(AuthStatus.AUTH_REQUIRED, "已找到登录状态文件；需访问目标页面后验证是否仍有效。")

    def _start_browser(self, *, headless: bool) -> tuple[Playwright, Browser]:
        try:
            playwright = sync_playwright().start()
            browser = playwright.chromium.launch(headless=headless)
            return playwright, browser
        except Exception as exc:
            logger.exception("[DONGCHEDI_AUTH] Chromium startup failed")
            raise RuntimeError("Chromium 浏览器启动失败，请确认已执行 playwright install chromium。") from exc

    def create_context(self, browser: Browser, *, use_saved_state: bool = False) -> BrowserContext:
        """Create an isolated context, optionally loading the persisted state."""
        if use_saved_state:
            state = self.state_file_status()
            if state.status == AuthStatus.AUTH_STATE_INVALID:
                raise ValueError(state.message)
            if not self.state_path.exists():
                raise FileNotFoundError("未找到登录状态文件")
            return browser.new_context(storage_state=str(self.state_path), locale="zh-CN")
        return browser.new_context(locale="zh-CN")

    def begin_manual_login(self) -> ManualLoginSession:
        """Open a visible standalone browser at the configurable login URL."""
        playwright, browser = self._start_browser(headless=False)
        context = browser.new_context(locale="zh-CN")
        page = context.new_page()
        try:
            page.goto(self.login_url, wait_until="domcontentloaded", timeout=60_000)
            self.fill_credentials_if_configured(page)
            logger.info("[DONGCHEDI_AUTH] visible manual login session opened")
            return ManualLoginSession(playwright, browser, context, page)
        except Exception:
            context.close()
            browser.close()
            playwright.stop()
            raise

    def fill_credentials_if_configured(self, page: Page) -> bool:
        """Optionally prefill only when credentials *and* verified selectors exist.

        Login submission, captcha and SMS verification remain entirely manual.
        """
        username, password = self._read_optional_credentials()
        if not (username and password and self.username_selector and self.password_selector):
            return False
        try:
            page.locator(self.username_selector).fill(username)
            page.locator(self.password_selector).fill(password)
            logger.info("[DONGCHEDI_AUTH] credentials prefilled using configured selectors")
            return True
        except Exception:
            logger.warning("[DONGCHEDI_AUTH] configured credential selectors did not match; continue manually")
            return False

    def page_auth_status(self, page: Page) -> AuthResult:
        """Determine login state with a manually verified configured selector.

        A selector is deliberately required: merely possessing cookies is not
        evidence that a logged-in session is still valid.
        """
        if not self.authenticated_selector:
            return AuthResult(
                AuthStatus.LOGIN_FAILED,
                "未配置 DONGCHEDI_AUTHENTICATED_SELECTOR，无法确认登录成功；不会保存状态。",
            )
        try:
            visible = page.locator(self.authenticated_selector).first.is_visible(timeout=5_000)
        except Exception:
            visible = False
        if visible:
            return AuthResult(AuthStatus.AUTHENTICATED, "已确认登录状态有效。")
        return AuthResult(AuthStatus.AUTH_EXPIRED, "未检测到已登录标识；请重新人工登录。")

    def save_state_if_authenticated(self, session: ManualLoginSession) -> AuthResult:
        """Persist state only after automatic selector-based verification."""
        result = self.page_auth_status(session.page)
        if result.status != AuthStatus.AUTHENTICATED:
            return AuthResult(
                AuthStatus.LOGIN_FAILED,
                "自动登录状态检测失败，认证状态未保存。",
                verification_mode="automatic",
            )
        return self._persist_state(session, verification_mode="automatic")

    def save_state_after_manual_confirmation(self, session: ManualLoginSession, *, confirmed: bool) -> AuthResult:
        """Persist a visible manual-login session after an explicit user confirmation.

        This method is intentionally for the standalone manual-login tool only.
        If an automatic selector is configured, its result remains authoritative
        and a manual confirmation can never override a failed detection.
        """
        if self.authenticated_selector:
            return self.save_state_if_authenticated(session)
        if not confirmed:
            return AuthResult(AuthStatus.LOGIN_FAILED, "登录未确认，认证状态未保存。", verification_mode="manual")
        return self._persist_state(
            session,
            verification_mode="manual",
            message="人工确认登录成功，认证状态已保存。",
        )

    def _persist_state(
        self,
        session: ManualLoginSession,
        *,
        verification_mode: str,
        message: str = "登录状态已保存，可供后续采集复用。",
    ) -> AuthResult:
        """Write Playwright storage state without exposing its sensitive values."""
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        session.context.storage_state(path=str(self.state_path))
        logger.info("[DONGCHEDI_AUTH] login state saved mode=%s path=%s", verification_mode, self.state_path)
        return AuthResult(AuthStatus.AUTHENTICATED, message, verification_mode=verification_mode)

    def verify_saved_login(self, target_url: str) -> AuthResult:
        """Load persisted state into a fresh context and verify it on a target page."""
        file_result = self.state_file_status()
        if file_result.status == AuthStatus.AUTH_STATE_INVALID:
            return file_result
        if not self.state_path.exists():
            return file_result
        try:
            saved_state: Any = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            return AuthResult(AuthStatus.AUTH_STATE_INVALID, "登录状态文件无效，可重新人工登录覆盖。")
        # An empty state is structurally valid Playwright JSON but cannot carry
        # an authenticated session.  Do not open a target page in that case.
        if not saved_state.get("cookies") and not saved_state.get("origins"):
            return AuthResult(AuthStatus.AUTH_REQUIRED, "登录状态文件中没有可复用的登录状态，请人工登录。")
        if not self.authenticated_selector:
            return AuthResult(AuthStatus.AUTH_REQUIRED, "未配置已登录判定 selector，无法验证已保存状态。")

        playwright: Playwright | None = None
        browser: Browser | None = None
        context: BrowserContext | None = None
        try:
            playwright, browser = self._start_browser(headless=True)
            context = self.create_context(browser, use_saved_state=True)
            page = context.new_page()
            page.goto(target_url, wait_until="domcontentloaded", timeout=60_000)
            return self.page_auth_status(page)
        except ValueError:
            return AuthResult(AuthStatus.AUTH_STATE_INVALID, "登录状态文件无效，可重新人工登录覆盖。")
        except Exception as exc:
            logger.warning("[DONGCHEDI_AUTH] saved-state verification failed: %s", type(exc).__name__)
            return AuthResult(AuthStatus.LOGIN_FAILED, "登录状态验证失败，请检查网络或重新人工登录。")
        finally:
            if context is not None:
                context.close()
            if browser is not None:
                browser.close()
            if playwright is not None:
                playwright.stop()
