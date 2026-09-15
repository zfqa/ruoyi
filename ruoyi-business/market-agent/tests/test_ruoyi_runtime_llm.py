from fastapi.testclient import TestClient

from app.main import app
from app.core.llm import reset_runtime_llm


def test_ruoyi_gateway_configuration_overrides_local_env():
    try:
        with TestClient(app) as client:
            response = client.get(
                "/api/llm/status",
                headers={
                    "X-Market-LLM-Managed": "true",
                    "X-Market-LLM-Api-Url": "https://example.invalid/api/v3/chat/completions",
                    "X-Market-LLM-Model": "test-model",
                    "X-Market-LLM-Api-Key": "test-key",
                },
            )
        assert response.status_code == 200
        payload = response.json()
        assert payload["enabled"] is True
        assert payload["api_key_configured"] is True
        assert payload["base_url"].endswith("/chat/completions")
        assert payload["model"] == "test-model"
        assert payload["configuration_source"] == "ruoyi_runtime"
    finally:
        reset_runtime_llm()
