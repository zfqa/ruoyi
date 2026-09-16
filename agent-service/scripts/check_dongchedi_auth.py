"""Check a persisted Dongchedi authentication state without exposing secrets."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from dongchedi_service.auth import DongchediAuthManager


def main() -> int:
    parser = argparse.ArgumentParser(description="检查懂车帝 Playwright 登录状态")
    parser.add_argument("--check-url", help="验证已登录状态的目标页 URL；省略时只检查本地状态文件")
    parser.add_argument("--authenticated-selector", help="经人工确认的登录成功标识 CSS selector")
    args = parser.parse_args()

    manager = DongchediAuthManager(authenticated_selector=args.authenticated_selector)
    check_url = args.check_url or os.getenv("DONGCHEDI_AUTH_CHECK_URL")
    result = manager.verify_saved_login(check_url) if check_url else manager.state_file_status()
    print(f"status={result.status.value}\nmessage={result.message}")
    return 0 if result.status.value == "authenticated" else 1


if __name__ == "__main__":
    raise SystemExit(main())
