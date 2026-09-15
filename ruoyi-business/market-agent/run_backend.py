import os
import sys
from pathlib import Path

import uvicorn


# Always resolve ``app`` from this checkout.  This prevents a globally installed
# package named ``app`` (or a stale copy from another project) from being loaded
# when the service is launched by VS Code/Start-Process with a different cwd.
SERVICE_ROOT = Path(__file__).resolve().parent
os.chdir(SERVICE_ROOT)
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))


if __name__ == "__main__":
    from app.version import API_CONTRACT_VERSION, APP_VERSION

    print(
        f"Starting market-agent {APP_VERSION} ({API_CONTRACT_VERSION}) "
        f"from {SERVICE_ROOT}",
        flush=True,
    )
    uvicorn.run(
        "app.main:app",
        host=os.getenv("MARKET_AGENT_HOST", "127.0.0.1"),
        port=int(os.getenv("MARKET_AGENT_PORT", "8000")),
        reload=False,
    )
