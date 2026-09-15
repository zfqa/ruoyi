from __future__ import annotations

import pandas as pd
import pytest
import shutil
from fastapi.testclient import TestClient

from app.main import app
from app.services.core_indicators import indicator_capabilities
from app.services.market_components import MarketComponentEngine
from app.services.report_planner import normalize_report_plan
from app.services.storage import processed_base, save_dataset


def _automotive_frame() -> pd.DataFrame:
    return pd.DataFrame([
        {"time_period": "2024-01", "power_type": "ICE", "sales": 100, "source_file": "a.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
        {"time_period": "2024-01", "power_type": "EV", "sales": 20, "source_file": "a.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
        {"time_period": "2025-01", "power_type": "ICE", "sales": 90, "source_file": "a.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
        {"time_period": "2025-01", "power_type": "EV", "sales": 40, "source_file": "a.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
    ])


def _finance_frame() -> pd.DataFrame:
    return pd.DataFrame([
        {"time_period": "2024-01", "department": "华东", "revenue_amount": 100.0, "cost_amount": 60.0, "source_file": "finance.xlsx", "source_sheet": "经营数据", "source_row_kind": "detail"},
        {"time_period": "2024-01", "department": "华南", "revenue_amount": 50.0, "cost_amount": 35.0, "source_file": "finance.xlsx", "source_sheet": "经营数据", "source_row_kind": "detail"},
        {"time_period": "2024-12", "department": "华东", "revenue_amount": 110.0, "cost_amount": 65.0, "source_file": "finance.xlsx", "source_sheet": "经营数据", "source_row_kind": "detail"},
        {"time_period": "2024-12", "department": "华南", "revenue_amount": 70.0, "cost_amount": 45.0, "source_file": "finance.xlsx", "source_sheet": "经营数据", "source_row_kind": "detail"},
        {"time_period": "2025-01", "department": "华东", "revenue_amount": 120.0, "cost_amount": 70.0, "source_file": "finance.xlsx", "source_sheet": "经营数据", "source_row_kind": "detail"},
        {"time_period": "2025-01", "department": "华南", "revenue_amount": 80.0, "cost_amount": 50.0, "source_file": "finance.xlsx", "source_sheet": "经营数据", "source_row_kind": "detail"},
    ])


def test_capabilities_change_with_each_excel_schema():
    auto = indicator_capabilities(_automotive_frame(), ["2025-01"])
    finance = indicator_capabilities(_finance_frame(), ["2025-01"])

    assert {item["field"] for item in auto["metrics"]} == {"sales"}
    assert {item["field"] for item in finance["metrics"]} == {"revenue_amount", "cost_amount"}
    assert any(item["field"] == "power_type" for item in auto["dimensions"])
    assert any(item["field"] == "department" for item in finance["dimensions"])
    assert auto["schema_hash"] != finance["schema_hash"]
    assert {item["display_name"] for item in finance["suggested_indicators"]} >= {"总体cost amount", "华东", "华南"}


def test_custom_indicator_calculation_supports_yoy_and_mom_together():
    engine = MarketComponentEngine(
        _finance_frame(),
        {"period_mode": "single", "start_period": "2025-01", "end_period": "2025-01"},
    )
    specs = [{
        "id": "east_revenue",
        "display_name": "华东收入",
        "metric": "revenue_amount",
        "aggregation": "sum",
        "filters": [{"dimension": "department", "values": ["华东"]}],
        "comparisons": ["yoy", "mom"],
        "show_share": True,
        "unit": "万元",
        "decimals": 1,
    }]
    component = engine.custom_indicator_summary(specs)
    row = component["rows"][0]
    assert row["数值"] == 120.0
    assert row["显示值"] == "120.0万元"
    assert row["同比"] == pytest.approx(0.20)
    assert row["环比"] == pytest.approx(120.0 / 110.0 - 1.0)
    assert row["占比"] == pytest.approx(0.60)
    assert "同比" in row["具体分析"] and "环比" in row["具体分析"]
    assert "department=华东" in row["计算口径"]


def test_custom_indicators_reject_fields_or_values_not_in_current_excel():
    engine = MarketComponentEngine(_finance_frame(), {"period_mode": "latest"})
    with pytest.raises(ValueError, match="不存在"):
        engine.custom_indicator_summary([{
            "display_name": "错误指标", "metric": "sales", "aggregation": "sum",
            "filters": [{"dimension": "department", "values": ["不存在地区"]}],
        }])


def test_plan_keeps_dynamic_indicators_and_clears_legacy_fixed_segments():
    plan = normalize_report_plan({
        "market_observations": [{
            "components": ["market_summary"],
            "summary_segments": ["total_market", "new_energy_vehicle"],
            "core_indicators": [{"display_name": "华东收入", "metric": "revenue_amount", "aggregation": "sum"}],
        }],
    })
    observation = plan["market_observations"][0]
    assert plan["version"] == 5
    assert observation["summary_segments"] == []
    assert observation["core_indicators"][0]["metric"] == "revenue_amount"


def test_legacy_single_comparison_is_migrated_to_comparison_list():
    plan = normalize_report_plan({
        "market_observations": [{
            "components": ["market_summary"],
            "core_indicators": [{
                "display_name": "华东收入", "metric": "revenue_amount",
                "aggregation": "sum", "comparison": "yoy",
            }],
        }],
    })
    indicator = plan["market_observations"][0]["core_indicators"][0]
    assert indicator["comparisons"] == ["yoy"]
    assert "comparison" not in indicator


def test_visual_api_persists_and_renders_excel_specific_indicators():
    dataset_id = "pytest_dynamic_indicator_v21"
    try:
        save_dataset(dataset_id, _finance_frame(), {"file_name": "finance.xlsx"})
        client = TestClient(app)
        catalog = client.get(
            f"/api/dashboard-components/{dataset_id}",
            params={"period_mode": "single", "start_period": "2025-01", "end_period": "2025-01"},
        )
        assert catalog.status_code == 200, catalog.text
        assert {item["field"] for item in catalog.json()["indicator_capabilities"]["metrics"]} == {"revenue_amount", "cost_amount"}

        response = client.put(f"/api/report-plan/{dataset_id}/visual", json={
            "observation_title": "2025年1月经营观察",
            "component_ids": ["market_summary"],
            "core_indicators": [{
                "display_name": "华东收入", "metric": "revenue_amount", "aggregation": "sum",
                "filters": [{"dimension": "department", "values": ["华东"]}],
                "comparisons": ["yoy", "mom"], "show_share": True, "unit": "万元", "decimals": 1,
            }],
            "period_mode": "single", "start_period": "2025-01", "end_period": "2025-01",
        })
        assert response.status_code == 200, response.text
        observation = response.json()["plan"]["market_observations"][0]
        assert observation["summary_segments"] == []
        assert observation["core_indicators"][0]["display_name"] == "华东收入"
        assert observation["core_indicators"][0]["comparisons"] == ["yoy", "mom"]

        report = client.get(
            f"/api/report/{dataset_id}",
            params={"use_llm": False, "period_mode": "single", "start_period": "2025-01", "end_period": "2025-01"},
        )
        assert report.status_code == 200, report.text
        rows = report.json()["market_observations"][0]["components"][0]["rows"]
        assert rows[0]["显示值"] == "120.0万元"
        assert rows[0]["同比"] == pytest.approx(0.20)
        assert rows[0]["环比"] == pytest.approx(120.0 / 110.0 - 1.0)
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)
