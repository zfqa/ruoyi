import asyncio
from io import BytesIO
from types import SimpleNamespace

import pandas as pd
from fastapi import UploadFile

from app.api import routes
from app.services.validator import validate_market_data


def test_duplicate_issue_uses_original_excel_rows_and_locations():
    frame = pd.DataFrame([
        {"time_period": "2025-01", "market": "中国", "oem": "甲", "brand": "A", "model": "M", "sales": 1, "source_file": "a.xlsx", "source_sheet": "销量", "source_row": 7, "source_cell": "B7"},
        {"time_period": "2025-01", "market": "中国", "oem": "甲", "brand": "A", "model": "M", "sales": 2, "source_file": "a.xlsx", "source_sheet": "销量", "source_row": 7, "source_cell": "C7"},
        {"time_period": "2025-01", "market": "中国", "oem": "甲", "brand": "A", "model": "M", "sales": 3, "source_file": "a.xlsx", "source_sheet": "销量", "source_row": 8, "source_cell": "B8"},
        {"time_period": "2025-01", "market": "中国", "oem": "甲", "brand": "A", "model": "M", "sales": 4, "source_file": "a.xlsx", "source_sheet": "销量", "source_row": 8, "source_cell": "C8"},
    ])

    issue = next(item for item in validate_market_data(frame) if item.code == "DUPLICATE_ROWS")

    assert issue.source_record_count == 2
    assert issue.standardized_record_count == 4
    assert "2 个原表数据行" in issue.message
    assert {item["source_row"] for item in issue.locations} == {7, 8}
    assert {item["source_cell"] for item in issue.locations} == {"B7", "C7", "B8", "C8"}


def test_multi_file_upload_returns_independent_file_results(tmp_path, monkeypatch):
    monkeypatch.setattr(routes, "get_settings", lambda: SimpleNamespace(upload_dir=tmp_path, max_upload_mb=50))
    monkeypatch.setattr(routes, "save_dataset", lambda *args, **kwargs: None)
    monkeypatch.setattr(routes, "save_sheet_datasets", lambda *args, **kwargs: {})
    monkeypatch.setattr(routes, "load_meta", lambda *args, **kwargs: {})

    csv_a = "时间,市场,品牌,车型,销量\n2025-01,中国,A,M1,10\n"
    csv_b = "时间,市场,品牌,车型,销量\n2025-02,中国,B,M2,20\n"
    files = [
        UploadFile(filename="first.csv", file=BytesIO(csv_a.encode("utf-8-sig"))),
        UploadFile(filename="second.csv", file=BytesIO(csv_b.encode("utf-8-sig"))),
    ]

    result = asyncio.run(routes._upload_market_files(files))

    assert result.source_files == ["first.csv", "second.csv"]
    assert [item.file_name for item in result.file_results] == ["first.csv", "second.csv"]
    assert len(result.file_results) == 2
    assert all(item.parse_summary["file_count"] == 1 for item in result.file_results)
    assert result.file_results[0].preview != result.file_results[1].preview
    assert result.rows == sum(item.rows for item in result.file_results)
