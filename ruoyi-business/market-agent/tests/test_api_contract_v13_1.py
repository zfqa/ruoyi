from io import BytesIO

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from app.main import app
from app.api.routes import _persist_upload
from app.version import API_CONTRACT_VERSION


def test_health_exposes_dynamic_period_contract():
    client = TestClient(app)
    r = client.get('/api/health')
    assert r.status_code == 200
    data = r.json()
    assert data['api_contract'] == API_CONTRACT_VERSION
    assert 'dynamic_period_range' in data['features']


def test_upload_storage_failure_returns_actionable_503(tmp_path):
    blocked_parent = tmp_path / "not-a-directory"
    blocked_parent.write_text("occupied", encoding="utf-8")

    with pytest.raises(HTTPException) as exc_info:
        _persist_upload(BytesIO(b"xlsx"), blocked_parent / "sample.xlsx", "sample.xlsx")

    assert exc_info.value.status_code == 503
    assert "无法保存到服务器上传目录" in exc_info.value.detail
    assert "STORAGE_DIR" in exc_info.value.detail
