"""Start a visible standalone browser for one manual Dongchedi login."""
from __future__ import annotations

import argparse
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from dongchedi_service.auth import DongchediAuthManager


def _is_manual_confirmation(value: str) -> bool:
    """Only an explicit affirmative answer authorizes state persistence."""
    return value.strip().lower() in {"y", "yes"}


def main() -> int:
    parser = argparse.ArgumentParser(description="懂车帝人工登录并保存 Playwright 登录状态")
    parser.add_argument("--login-url", help="可选：经人工确认的懂车帝登录页 URL")
    parser.add_argument("--authenticated-selector", help="可选：经人工确认的登录成功标识 CSS selector")
    args = parser.parse_args()

    manager = DongchediAuthManager(login_url=args.login_url, authenticated_selector=args.authenticated_selector)
    session = manager.begin_manual_login()
    try:
        print("请在浏览器中完成登录及验证码，登录成功后回到终端按 Enter 继续。")
        input()
        if manager.authenticated_selector:
            result = manager.save_state_if_authenticated(session)
        else:
            print("未配置自动登录检测 Selector。")
            confirmation = input("请确认你已经在 Chromium 浏览器中成功登录懂车帝。是否确认已经登录成功？ [y/N]: ")
            result = manager.save_state_after_manual_confirmation(
                session,
                confirmed=_is_manual_confirmation(confirmation),
            )
        print(f"status={result.status.value}\nmessage={result.message}")
        if result.verification_mode:
            print(f"verification_mode={result.verification_mode}")
        return 0 if result.status.value == "authenticated" else 1
    finally:
        session.close()


if __name__ == "__main__":
    raise SystemExit(main())
