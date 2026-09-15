from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from docx import Document
from fastapi.testclient import TestClient

from app.main import app
from app.services.chat_engine import ChatEngine
from app.services.market_components import MarketComponentEngine, line_chart_component_id
from app.services.parser import parse_file
from app.services.report_generator import build_report_payload, export_report
from app.services.report_planner import _rule_patch, apply_report_plan_instruction
from app.services.storage import processed_base, save_dataset


ROOT = Path(__file__).resolve().parents[1]
MIXED_INSTRUCTION = "帮我把周报中的动力类型结构占比去掉，然后将折线图中的动力类型月度结构占比的图加入到周报中"
TARGET_LINE_ID = line_chart_component_id("动力类型月度结构占比")


def _df():
    frame, _, _ = parse_file(ROOT / "data" / "sample_timeseries_market.xlsx")
    return frame


def test_mixed_remove_and_add_produces_two_component_scoped_operations():
    frame = _df()
    patch = _rule_patch(MIXED_INSTRUCTION, MarketComponentEngine(frame).capabilities())
    assert patch["operations"] == [
        {"action": "remove", "component_id": "power_structure"},
        {"action": "add", "component_id": TARGET_LINE_ID},
    ]


def test_mixed_edit_is_atomic_and_preserves_unnamed_components():
    frame = _df()
    dataset_id = "pytest_v16_atomic_mixed_edit"
    try:
        save_dataset(dataset_id, frame, {"file_name": "sample_timeseries_market.xlsx"})
        plan, changes = apply_report_plan_instruction(dataset_id, MIXED_INSTRUCTION, frame, use_llm=False)
        components = plan["market_observations"][0]["components"]
        # v17 starts from a blank 1.1. Removing a component that was never
        # added is a no-op; the explicitly requested chart is the only result.
        assert components == [TARGET_LINE_ID]
        assert plan["last_change_set"]["removed"] == []
        assert plan["last_change_set"]["added"] == [TARGET_LINE_ID]
        assert plan["last_change_set"]["preserved"] == []
        assert plan["revision"] == 1
        assert any("已删除：无" in item for item in changes)
        assert any("已添加：动力类型月度结构占比" in item for item in changes)
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_selected_component_id_resolves_this_chart_without_title_guessing():
    frame = _df()
    capabilities = MarketComponentEngine(frame).capabilities()
    patch = _rule_patch("把这张图加入周报", capabilities, selected_component_id=TARGET_LINE_ID)
    assert patch["operations"] == [{"action": "add", "component_id": TARGET_LINE_ID}]


def test_chat_routes_static_component_removal_to_dynamic_plan():
    frame = _df()
    dataset_id = "pytest_v16_chat_static_remove"
    try:
        save_dataset(dataset_id, frame, {"file_name": "sample_timeseries_market.xlsx"})
        apply_report_plan_instruction(dataset_id, "生成市场观察，加入动力类型结构占比", frame, use_llm=False)
        response = ChatEngine(frame, dataset_id=dataset_id).answer(
            "把周报中的动力类型结构占比去掉",
            use_llm=False,
        )
        assert response.report_updated is True
        assert "power_structure" not in response.report_plan["market_observations"][0]["components"]
        assert response.report_plan["last_change_set"]["removed"] == ["power_structure"]
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_missing_requested_month_does_not_fall_back_or_mutate_plan():
    frame = _df()
    dataset_id = "pytest_v16_missing_month"
    try:
        save_dataset(dataset_id, frame, {"file_name": "sample_timeseries_market.xlsx"})
        with pytest.raises(ValueError):
            apply_report_plan_instruction(dataset_id, "生成美国市场2023年6月观察", frame, use_llm=False)
        # The failed transaction must not create report_plan.json or a revision.
        assert not (processed_base(dataset_id) / "report_plan.json").exists()
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_single_observation_period_drives_cover_preview_and_docx(tmp_path):
    frame = _df()
    dataset_id = "pytest_v16_period_docx"
    try:
        save_dataset(dataset_id, frame, {"file_name": "sample_timeseries_market.xlsx"})
        plan, _ = apply_report_plan_instruction(
            dataset_id,
            "生成2025年10月市场观察，标题为美国10月市场观察，加入市场核心指标表、OEM销量排名TOP10、动力类型结构占比和核心指标月度趋势图",
            frame,
            use_llm=False,
        )
        plan, _ = apply_report_plan_instruction(dataset_id, MIXED_INSTRUCTION, frame, use_llm=False)
        payload = build_report_payload(frame, use_llm=False, report_plan=plan)
        assert payload["period_label"] == "2025年10月"
        assert payload["market_observations"][0]["period_label"] == "2025年10月"
        assert [x["id"] for x in payload["market_observations"][0]["components"]] == [
            "market_summary", "oem_top10", "line_chart:核心指标月度趋势", TARGET_LINE_ID
        ]
        path = export_report(frame, "docx", tmp_path, "v16_period", report_plan=plan)
        doc = Document(path)
        paragraph_text = [p.text.strip() for p in doc.paragraphs]
        assert any(text.startswith("分析周期：2025年10月") for text in paragraph_text)
        assert "动力类型月度结构占比" in paragraph_text
        assert "动力类型结构占比" not in paragraph_text
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_dashboard_component_catalog_api_exposes_exact_ids():
    frame = _df()
    dataset_id = "pytest_v16_component_catalog"
    try:
        save_dataset(dataset_id, frame, {"file_name": "sample_timeseries_market.xlsx"})
        response = TestClient(app).get(f"/api/dashboard-components/{dataset_id}")
        assert response.status_code == 200
        components = {x["id"]: x for x in response.json()["components"]}
        assert components["power_structure"]["kind"] == "table_and_pie"
        assert components[TARGET_LINE_ID]["kind"] == "line_chart"
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)
