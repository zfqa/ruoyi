from __future__ import annotations

import math
import shutil

import pandas as pd
from docx import Document
from fastapi.testclient import TestClient

from app.main import app
from app.services.market_components import MarketComponentEngine, line_chart_component_id
from app.services.power_groups import classify_power_type
from app.services.report_generator import export_report
from app.services.report_planner import load_report_plan
from app.services.storage import processed_base, save_dataset


POWER_VALUES = {
    "EV": (50, 10, 40),
    "PHV": (20, 5, 15),
    "ICE": (80, 8, 70),
    "HV": (30, 4, 20),
    "MHV": (10, 2, 5),
    "N/A": (10, 1, 10),
}


def china_multisheet_frame() -> pd.DataFrame:
    rows = []
    for period, scale in [("2024-01", 0.5), ("2025-01", 1.0)]:
        for power_type, (wholesale, exported, retail) in POWER_VALUES.items():
            common = {
                "time_period": period,
                "power_type": power_type,
                "source_file": "china-market.xlsx",
                "source_row_kind": "detail",
            }
            rows.append({
                **common,
                "wholesale": wholesale * scale,
                "source_sheet": "总批发销量",
                "source_metric_type": "wholesale",
                "source_metric_scope": "total",
            })
            rows.append({
                **common,
                "export": exported * scale,
                "source_sheet": "出口批发销量",
                "source_metric_type": "export",
                "source_metric_scope": "wholesale",
            })
            rows.append({
                **common,
                "retail_sales": retail * scale,
                "source_sheet": "中国市场零售销量",
                "source_metric_type": "retail_sales",
                "source_metric_scope": "domestic",
            })
    return pd.DataFrame(rows)


def test_china_wholesale_inventory_formulas_are_excel_grounded_and_audited():
    engine = MarketComponentEngine(
        china_multisheet_frame(),
        {"period_mode": "single", "end_period": "2025-01"},
    )
    capabilities = engine.capabilities()["indicator_capabilities"]
    metrics = {item["field"]: item for item in capabilities["metrics"]}
    assert metrics["domestic_wholesale"]["formula"] == "总批发销量-出口批发销量"
    assert metrics["inventory"]["formula"] == "国内批发销量-中国市场零售销量"

    columns = [
        item for item in capabilities["matrix_column_templates"]
        if item["metric"] in {"wholesale", "domestic_wholesale", "export", "retail_sales", "inventory"}
    ]
    component = engine.custom_indicator_matrix(
        [{"id": "total", "display_name": "总体市场", "segment": "total_market", "filters": []}],
        columns,
    )
    cells = component["facts"][0]["cells"]
    by_metric = {cell["column"]["metric"]: cell for cell in cells.values()}
    assert by_metric["wholesale"]["value"] == 200
    assert by_metric["export"]["value"] == 30
    assert by_metric["domestic_wholesale"]["value"] == 170
    assert by_metric["retail_sales"]["value"] == 160
    assert by_metric["inventory"]["value"] == 10
    assert by_metric["domestic_wholesale"]["calculation_audit"]["dependencies"] == {
        "wholesale": 200,
        "export": 30,
    }
    assert by_metric["inventory"]["calculation_audit"]["dependencies"] == {
        "domestic_wholesale": 170,
        "domestic_retail_sales": 160,
    }
    assert by_metric["inventory"]["yoy"] == 1.0


def test_matrix_column_header_is_locked_to_selected_excel_metric_or_formula():
    engine = MarketComponentEngine(
        china_multisheet_frame(),
        {"period_mode": "single", "end_period": "2025-01"},
    )
    # These labels simulate a stale plan or a forged client payload.  A title
    # must never be allowed to rename one metric into another business metric.
    component = engine.custom_indicator_matrix(
        [{"id": "total", "display_name": "总体市场", "segment": "total_market", "filters": []}],
        [
            {"id": "wrong_wholesale", "display_name": "销量", "metric": "wholesale", "aggregation": "sum"},
            {"id": "wrong_domestic", "display_name": "批发销量", "metric": "domestic_wholesale", "aggregation": "sum"},
            {"id": "wrong_inventory", "display_name": "销量", "metric": "inventory", "aggregation": "sum"},
        ],
    )
    row = component["rows"][0]
    assert "批发销量" in row and "销量" not in row
    assert "国内批发销量" in row
    assert "库存量" in row
    columns = {item["metric"]: item for item in component["matrix_columns"]}
    assert columns["wholesale"]["display_name"] == "批发销量"
    assert columns["domestic_wholesale"]["display_name"] == "国内批发销量"
    assert columns["inventory"]["display_name"] == "库存量"
    # Stock is a closing balance; even a tampered "sum" aggregation cannot
    # turn it into a period sum.
    assert columns["inventory"]["aggregation"] == "latest"


def test_metric_picker_explains_unavailable_standard_metrics_without_enabling_them():
    sales_only = pd.DataFrame([
        {"time_period": "2025-01", "sales": 100, "source_file": "sales-only.xlsx", "source_sheet": "销量", "source_row_kind": "detail"},
    ])
    capabilities = MarketComponentEngine(sales_only, {"period_mode": "single", "end_period": "2025-01"}).capabilities()["indicator_capabilities"]
    assert [item["field"] for item in capabilities["metrics"]] == ["sales"]
    unavailable = {item["field"]: item["reason"] for item in capabilities["unavailable_metrics"]}
    assert "production" in unavailable and "未识别到“产量”" in unavailable["production"]
    assert "wholesale" in unavailable and "未识别到“批发销量”" in unavailable["wholesale"]
    assert "export" in unavailable
    assert "domestic_wholesale" in unavailable and "总批发销量" in unavailable["domestic_wholesale"]
    assert "inventory" in unavailable and "中国市场零售销量" in unavailable["inventory"]


def test_dashboard_overview_uses_same_formula_engine_as_report_composer():
    engine = MarketComponentEngine(
        china_multisheet_frame(),
        {"period_mode": "single", "end_period": "2025-01"},
    )
    overview = engine.dashboard_market_overview()
    assert len(overview["rows"]) == 1
    assert overview["summary"]["domestic_wholesale"] == 170
    assert overview["summary"]["inventory"] == 10
    assert "国内批发销量" in overview["rows"][0]
    assert "库存量" in overview["rows"][0]


def test_power_groups_are_exact_configurable_and_keep_unknown_in_denominator():
    frame = china_multisheet_frame()
    engine = MarketComponentEngine(frame, {"period_mode": "single", "end_period": "2025-01"})
    assert classify_power_type("BEV") == "new_energy"
    assert classify_power_type("HEV") == "fuel"
    assert classify_power_type("MHV/PHV") == "unclassified"

    chart = engine.analyzer.power_group_monthly_chart()
    assert [item["name"] for item in chart["series"]] == ["新能源车", "燃油车"]
    current = {item["name"]: item["points"][-1]["数值"] for item in chart["series"]}
    # The deterministic primary-sales selector chooses total wholesale for
    # this workbook, so every percentage below is grounded in that same Sheet.
    assert chart["classification_audit"]["metric"] == "wholesale"
    assert math.isclose(current["新能源车"], 70 / 200)
    assert math.isclose(current["燃油车"], 120 / 200)
    assert math.isclose(chart["classification_audit"]["classified_coverage"], 190 / 200)
    assert chart["classification_audit"]["unclassified_values"] == ["N/A"]

    raw_chart = engine.analyzer.power_type_monthly_chart()
    assert [item["name"] for item in raw_chart["series"]] == list(POWER_VALUES)
    raw_current = {item["name"]: item["points"][-1]["数值"] for item in raw_chart["series"]}
    assert math.isclose(raw_current["EV"], 50 / 200)
    assert math.isclose(raw_current["N/A"], 10 / 200)
    assert raw_chart["classification_audit"]["mode"] == "all_power_types"

    component_id = line_chart_component_id("动力类型月度结构占比")
    components = engine.build_components({
        "market_observation": {
            "components": [component_id],
            "component_options": {
                component_id: {
                    "series": ["new_energy"],
                    "power_type_mapping": {"N/A": "new_energy"},
                }
            },
        }
    })
    configured = components[0]["chart"]
    assert [item["name"] for item in configured["series"]] == ["新能源车"]
    assert math.isclose(configured["series"][0]["points"][-1]["数值"], 80 / 200)
    assert configured["classification_audit"]["unclassified_values"] == []


def test_visual_api_persists_matrix_formulas_and_power_series_selection(tmp_path):
    dataset_id = "pytest_china_matrix_v22"
    frame = china_multisheet_frame()
    try:
        save_dataset(dataset_id, frame, {"file_name": "china-market.xlsx"})
        client = TestClient(app)
        dashboard = client.get(
            f"/api/analysis/{dataset_id}",
            params={"period_mode": "single", "start_period": "2025-01", "end_period": "2025-01"},
        )
        assert dashboard.status_code == 200, dashboard.text
        dashboard_body = dashboard.json()
        # The dashboard remains the user's original concise overview.  Formula
        # columns belong to the visual report composer instead.
        assert "国内批发销量" not in dashboard_body["overview_table"][0]
        variants = dashboard_body["power_trend_variants"]
        assert set(variants) >= {"all", "new_energy", "fuel", "both"}
        assert [item["name"] for item in variants["all"]["series"]] == list(POWER_VALUES)
        assert [item["name"] for item in variants["new_energy"]["series"]] == ["新能源车"]
        assert [item["name"] for item in variants["fuel"]["series"]] == ["燃油车"]
        assert [item["name"] for item in variants["both"]["series"]] == ["新能源车", "燃油车"]

        engine = MarketComponentEngine(frame, {"period_mode": "single", "end_period": "2025-01"})
        capabilities = engine.capabilities()["indicator_capabilities"]
        columns = [
            item for item in capabilities["matrix_column_templates"]
            if item["metric"] in {"wholesale", "domestic_wholesale", "export", "inventory"}
        ]
        for column in columns:
            column["display_name"] = "销量"  # stale/mismatched client label
        power_chart_id = line_chart_component_id("动力类型月度结构占比")
        response = client.put(f"/api/report-plan/{dataset_id}/visual", json={
            "observation_title": "2025年1月中国市场观察",
            "component_ids": ["market_summary", power_chart_id],
            "market_summary_rows": [
                {"id": "total", "display_name": "总体市场", "segment": "total_market", "filters": []},
                {"id": "nev", "display_name": "新能源车", "segment": "new_energy_vehicle", "filters": []},
            ],
            "market_summary_columns": columns,
            "component_options": {
                power_chart_id: {"series": ["fuel"], "power_type_mapping": {"N/A": "unclassified"}}
            },
            "period_mode": "single",
            "start_period": "2025-01",
            "end_period": "2025-01",
        })
        assert response.status_code == 200, response.text
        observation = response.json()["plan"]["market_observations"][0]
        assert len(observation["market_summary_rows"]) == 2
        assert {item["metric"] for item in observation["market_summary_columns"]} == {
            "wholesale", "domestic_wholesale", "export", "inventory",
        }
        persisted_labels = {item["metric"]: item["display_name"] for item in observation["market_summary_columns"]}
        assert persisted_labels == {
            "wholesale": "批发销量",
            "domestic_wholesale": "国内批发销量",
            "export": "出口",
            "inventory": "库存量",
        }
        assert observation["component_options"][power_chart_id]["series"] == ["fuel"]

        report = client.get(
            f"/api/report/{dataset_id}",
            params={"use_llm": False, "period_mode": "single", "start_period": "2025-01", "end_period": "2025-01"},
        )
        assert report.status_code == 200, report.text
        components = report.json()["market_observations"][0]["components"]
        summary = components[0]
        assert summary["matrix"] is True
        total_facts = summary["facts"][0]["cells"]
        values = {cell["column"]["metric"]: cell["value"] for cell in total_facts.values()}
        assert values["domestic_wholesale"] == 170
        assert values["inventory"] == 10
        assert [item["name"] for item in components[1]["chart"]["series"]] == ["燃油车"]

        docx_path = export_report(
            frame,
            "docx",
            tmp_path,
            "china_matrix_v22",
            report_plan=load_report_plan(dataset_id),
            analysis_period={"period_mode": "single", "start_period": "2025-01", "end_period": "2025-01"},
        )
        document = Document(docx_path)
        headers = [cell.text for cell in document.tables[0].rows[0].cells]
        assert "国内批发销量" in headers
        assert "库存量" in headers
        assert len(document.inline_shapes) >= 1
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)
