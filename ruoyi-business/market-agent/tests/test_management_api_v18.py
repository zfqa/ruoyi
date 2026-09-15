import shutil
import threading
import time
from pathlib import Path

import pandas as pd
from fastapi.testclient import TestClient

from app.main import app
from app.api import routes
from app.core.config import get_settings
from app.services.storage import append_context, load_context, processed_base, save_dataset


def test_context_item_delete_report_config_and_dataset_lifecycle():
    dataset_id = "pytest_v18_management"
    base = processed_base(dataset_id)
    shutil.rmtree(base, ignore_errors=True)
    save_dataset(dataset_id, pd.DataFrame([
        {"time_period": "2025-01", "market": "SUV", "sales": 120, "inventory": None},
        {"time_period": "2025-02", "market": "SUV", "sales": 150},
    ]), {"file_name": "management.xlsx", "source_files": ["management.xlsx"]})
    append_context(dataset_id, [
        {"category": "macro_policy", "title": "政策A", "content": "内容A", "source_name": "来源A", "locator": "第1页"},
        {"category": "competition", "title": "竞争B", "content": "内容B", "source_name": "来源B", "locator": "第2页"},
    ])
    client = TestClient(app)
    try:
        context = client.get(f"/api/context/{dataset_id}")
        assert context.status_code == 200
        items = context.json()["items"]
        assert len(items) == 2
        assert all(item["id"] and item["created_at"] for item in items)

        deleted = client.post(f"/api/context/{dataset_id}/delete-items", json={"item_ids": [items[0]["id"]]})
        assert deleted.status_code == 200
        assert deleted.json()["total"] == 1
        assert client.get(f"/api/context/{dataset_id}").json()["items"][0]["title"] == "竞争B"

        config = client.get(f"/api/report-config/{dataset_id}").json()
        config["custom_title"] = "管理层周报"
        config["ranking_dimensions"] = ["brand"]
        saved = client.put(f"/api/report-config/{dataset_id}", json=config)
        assert saved.status_code == 200
        assert saved.json()["custom_title"] == "管理层周报"
        assert saved.json()["ranking_dimensions"] == ["brand"]

        detail = client.get(f"/api/dataset/{dataset_id}")
        assert detail.status_code == 200
        assert detail.json()["rows"] == 2
        assert detail.json()["preview"][0]["inventory"] is None

        removed = client.delete(f"/api/dataset/{dataset_id}")
        assert removed.status_code == 200
        assert removed.json()["deleted"] is True
        assert not base.exists()
        assert client.get(f"/api/dataset/{dataset_id}").status_code == 404
    finally:
        shutil.rmtree(base, ignore_errors=True)


def test_async_upload_job_reaches_real_parse_result():
    client = TestClient(app)
    source = Path(__file__).parents[1] / "data" / "sample_market.csv"
    with source.open("rb") as handle:
        created = client.post("/api/upload/jobs", files={"files": (source.name, handle, "text/csv")})
    assert created.status_code == 200
    job_id = created.json()["job_id"]
    job = created.json()
    for _ in range(100):
        job = client.get(f"/api/upload/jobs/{job_id}").json()
        if job["status"] in {"success", "failed", "cancelled"}:
            break
        time.sleep(0.05)
    try:
        assert job["status"] == "success", job
        assert job["progress"] == 100
        assert job["result"]["dataset_id"]
        assert job["result"]["rows"] > 0
        removed = client.delete(f"/api/dataset/{job['result']['dataset_id']}")
        assert removed.status_code == 200
    finally:
        shutil.rmtree(get_settings().job_dir / job_id, ignore_errors=True)


def test_context_upload_preserves_original_name_is_idempotent_and_rejects_stale_dataset():
    dataset_id = "pytest_v21_context_upload"
    base = processed_base(dataset_id)
    shutil.rmtree(base, ignore_errors=True)
    save_dataset(dataset_id, pd.DataFrame([{"time_period": "2025-01", "sales": 1}]), {"file_name": "source.csv"})
    client = TestClient(app)
    filename = "中文行业资料.txt"
    body = "2.1 人事调整\n某企业任命新的负责人，原文必须完整保存。".encode("utf-8")
    try:
        first = client.post(
            f"/api/context/{dataset_id}/upload",
            files={"files": (filename, body, "text/plain")},
        )
        assert first.status_code == 200, first.text
        assert first.json()["added"] == 1
        assert first.json()["items"][0]["source_name"] == filename

        second = client.post(
            f"/api/context/{dataset_id}/upload",
            files={"files": (filename, body, "text/plain")},
        )
        assert second.status_code == 200, second.text
        assert second.json()["added"] == 0
        assert second.json()["items"] == []
        assert len(load_context(dataset_id)) == 1

        stale = client.post(
            "/api/context/dataset-does-not-exist/upload",
            files={"files": (filename, body, "text/plain")},
        )
        assert stale.status_code == 404
        assert "重新选择数据集" in stale.json()["detail"]
    finally:
        shutil.rmtree(base, ignore_errors=True)


def test_async_upload_job_cancel_and_retry(monkeypatch):
    """真实覆盖取消状态落盘、取消后重试以及重试后的完整解析结果。"""
    client = TestClient(app)
    source = Path(__file__).parents[1] / "data" / "sample_market.csv"
    started = threading.Event()
    release = threading.Event()
    real_process = routes._process_upload_job

    def delayed_process(job_id, staged_files):
        started.set()
        release.wait(timeout=5)
        real_process(job_id, staged_files)

    monkeypatch.setattr(routes, "_process_upload_job", delayed_process)
    with source.open("rb") as handle:
        created = client.post("/api/upload/jobs", files={"files": (source.name, handle, "text/csv")})
    assert created.status_code == 200
    job_id = created.json()["job_id"]
    assert started.wait(timeout=2)

    cancelled = client.post(f"/api/upload/jobs/{job_id}/cancel")
    assert cancelled.status_code == 200
    release.set()
    for _ in range(100):
        cancelled_job = client.get(f"/api/upload/jobs/{job_id}").json()
        if cancelled_job["status"] == "cancelled":
            break
        time.sleep(0.05)
    assert cancelled_job["status"] == "cancelled"

    monkeypatch.setattr(routes, "_process_upload_job", real_process)
    retried = client.post(f"/api/upload/jobs/{job_id}/retry")
    assert retried.status_code == 200
    retry_id = retried.json()["job_id"]
    retry_job = retried.json()
    for _ in range(100):
        retry_job = client.get(f"/api/upload/jobs/{retry_id}").json()
        if retry_job["status"] in {"success", "failed", "cancelled"}:
            break
        time.sleep(0.05)
    try:
        assert retry_job["status"] == "success", retry_job
        assert retry_job["progress"] == 100
        assert retry_job["retry_of"] == job_id
        removed = client.delete(f"/api/dataset/{retry_job['result']['dataset_id']}")
        assert removed.status_code == 200
    finally:
        shutil.rmtree(get_settings().job_dir / job_id, ignore_errors=True)
        shutil.rmtree(get_settings().job_dir / retry_id, ignore_errors=True)
