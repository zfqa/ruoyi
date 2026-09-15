from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services.market_components import MarketComponentEngine, line_chart_component_id
from app.services.parser import parse_file
from app.services.report_generator import build_report_payload
from app.services.report_planner import (
    apply_report_plan_instruction,
    load_report_plan,
    reset_report_plan,
)
from app.services.storage import processed_base, save_dataset


ROOT = Path(__file__).resolve().parents[1]
POWER_MONTHLY = line_chart_component_id("动力类型月度结构占比")
CORE_MONTHLY = line_chart_component_id("核心指标月度趋势")
NEV_SHARE = line_chart_component_id("新能源汽车市场占有率")


def _df():
    frame, _, _ = parse_file(ROOT / "data" / "sample_timeseries_market.xlsx")
    return frame


def _save(dataset_id: str):
    frame = _df()
    save_dataset(dataset_id, frame, {"file_name": "sample_timeseries_market.xlsx"})
    return frame


def _cleanup(*dataset_ids: str):
    for dataset_id in dataset_ids:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_new_excel_has_no_hardcoded_11_section_or_components():
    dataset_id = "pytest_v17_blank_initial"
    try:
        frame = _save(dataset_id)
        plan = load_report_plan(dataset_id)
        assert plan["mode"] == "custom"
        assert plan["market_observations"] == []
        assert plan["market_observation"] == {}
        payload = build_report_payload(frame, use_llm=False, report_plan=plan)
        assert payload["dynamic_report_plan"] is True
        assert payload["market_observations"] == []
    finally:
        _cleanup(dataset_id)


def test_dialogue_can_create_title_scope_and_period_without_injecting_components():
    dataset_id = "pytest_v17_title_scope_only"
    try:
        frame = _save(dataset_id)
        plan, _ = apply_report_plan_instruction(
            dataset_id,
            "生成美国市场11月观察，标题为美国新能源汽车市场观察",
            frame,
            use_llm=False,
        )
        observation = plan["market_observations"][0]
        assert observation["custom_title"] == "美国新能源汽车市场观察"
        assert observation["analysis_period"]["start_period"] == "2025-11"
        assert observation["components"] == []
    finally:
        _cleanup(dataset_id)


def test_only_explicit_dashboard_components_are_added_in_spoken_order():
    dataset_id = "pytest_v17_explicit_components"
    try:
        frame = _save(dataset_id)
        plan, _ = apply_report_plan_instruction(
            dataset_id,
            "生成美国市场11月观察，标题为美国新能源汽车市场观察，加入市场核心指标表、新能源汽车市场占有率图和动力类型月度结构占比图",
            frame,
            use_llm=False,
        )
        observation = plan["market_observations"][0]
        assert observation["components"] == ["market_summary", NEV_SHARE, POWER_MONTHLY]
        # "新能源" appears in chart/title text; it must not silently filter data.
        assert observation["filters"] == {}
        assert observation["summary_segments"] == []
    finally:
        _cleanup(dataset_id)


def test_selected_dashboard_item_is_context_only_until_dialogue_adds_it():
    dataset_id = "pytest_v17_selected_context"
    try:
        frame = _save(dataset_id)
        assert load_report_plan(dataset_id)["market_observations"] == []
        plan, _ = apply_report_plan_instruction(
            dataset_id,
            "把这张图加入1.1",
            frame,
            use_llm=False,
            selected_component_id=POWER_MONTHLY,
        )
        assert plan["market_observations"][0]["components"] == [POWER_MONTHLY]
    finally:
        _cleanup(dataset_id)


def test_mixed_delete_and_selected_add_updates_exact_existing_components():
    dataset_id = "pytest_v17_mixed_delete_add"
    try:
        frame = _save(dataset_id)
        apply_report_plan_instruction(
            dataset_id,
            "生成市场观察，加入市场核心指标表、OEM销量排名TOP10、动力类型结构占比和核心指标月度趋势图",
            frame,
            use_llm=False,
        )
        plan, _ = apply_report_plan_instruction(
            dataset_id,
            "将周报中的核心指标月度趋势和OME排名的图表删除，然后把这张图加入到周报中",
            frame,
            use_llm=False,
            selected_component_id=POWER_MONTHLY,
        )
        assert plan["market_observations"][0]["components"] == [
            "market_summary", "power_structure", POWER_MONTHLY,
        ]
        assert plan["last_change_set"]["removed"] == ["oem_top10", CORE_MONTHLY]
        assert plan["last_change_set"]["added"] == [POWER_MONTHLY]
    finally:
        _cleanup(dataset_id)


def test_dialogue_can_move_one_dashboard_component_relative_to_another():
    dataset_id = "pytest_v17_relative_order"
    try:
        frame = _save(dataset_id)
        apply_report_plan_instruction(
            dataset_id,
            "生成市场观察，加入动力类型月度结构占比图、市场核心指标表",
            frame,
            use_llm=False,
        )
        plan, _ = apply_report_plan_instruction(
            dataset_id,
            "把动力类型月度结构占比图放到市场核心指标表后面",
            frame,
            use_llm=False,
        )
        assert plan["market_observations"][0]["components"] == ["market_summary", POWER_MONTHLY]
    finally:
        _cleanup(dataset_id)


def test_clear_plan_never_restores_default_components_and_keeps_revision_monotonic():
    dataset_id = "pytest_v17_clear"
    try:
        frame = _save(dataset_id)
        plan, _ = apply_report_plan_instruction(
            dataset_id, "生成市场观察，加入动力类型结构占比", frame, use_llm=False,
        )
        cleared = reset_report_plan(dataset_id)
        assert cleared["revision"] == plan["revision"] + 1
        assert cleared["market_observations"] == []
        assert cleared["market_observation"] == {}
        assert cleared["last_change_set"]["removed"] == ["power_structure"]
    finally:
        _cleanup(dataset_id)


def test_two_uploaded_datasets_have_independent_plans():
    first = "pytest_v17_isolation_a"
    second = "pytest_v17_isolation_b"
    try:
        frame_a = _save(first)
        _save(second)
        apply_report_plan_instruction(
            first, "生成市场观察，加入动力类型结构占比", frame_a, use_llm=False,
        )
        assert load_report_plan(first)["market_observations"]
        assert load_report_plan(second)["market_observations"] == []
    finally:
        _cleanup(first, second)


def test_component_catalog_contains_only_real_dashboard_ids_not_legacy_helpers():
    capabilities = MarketComponentEngine(_df()).capabilities()
    ids = {item["id"] for item in capabilities["dashboard_components"]}
    assert POWER_MONTHLY in ids
    assert CORE_MONTHLY in ids
    assert "power_structure" in ids
    assert "nev_share_trend" not in ids
    assert "all_line_charts" not in ids
    assert ids == set(capabilities["supported_components"])


def test_unknown_chart_request_is_blocked_instead_of_fabricated():
    dataset_id = "pytest_v17_unknown_component"
    try:
        frame = _save(dataset_id)
        with pytest.raises(ValueError, match="没有识别到"):
            apply_report_plan_instruction(
                dataset_id, "把火星汽车销量图加入周报", frame, use_llm=False,
            )
        assert not (processed_base(dataset_id) / "report_plan.json").exists()
    finally:
        _cleanup(dataset_id)


def test_v18_api_contract_is_explicit():
    health = TestClient(app).get("/api/health")
    assert health.status_code == 200
    body = health.json()
    assert body["app_version"] == "21.0"
    assert body["api_contract"] == "dynamic-indicator-content-fidelity-v1"
    assert body["report_export_version"] == "21.1"
    assert body["service_root"].endswith("ruoyi-business\\market-agent") or body["service_root"].endswith("ruoyi-business/market-agent")
    assert "empty_dialogue_composed_observation" in body["features"]
    assert "period_consistent_trend_windows" in body["features"]
    assert "dashboard_excel_lineage" in body["features"]
