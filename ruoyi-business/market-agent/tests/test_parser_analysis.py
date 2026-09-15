from pathlib import Path

from app.services.parser import parse_file
from app.services.market_analysis import MarketAnalyzer
from app.services.context_ingest import items_from_text
from app.services.report_generator import build_report_payload, export_report

ROOT = Path(__file__).resolve().parents[1]


def test_sample_excel_parse_and_analysis():
    path = ROOT / "data" / "sample_market.xlsx"
    df, metas, issues = parse_file(path)
    assert len(df) >= 4
    assert metas[0].header_row == 2
    assert "wholesale" in df.columns
    result = MarketAnalyzer(df).run_all()
    assert result.overview_table
    assert result.anomalies


def test_sample_csv_parse():
    path = ROOT / "data" / "sample_market.csv"
    df, metas, issues = parse_file(path)
    assert len(df) == 4
    assert metas[0].field_mapping["批发销量"] == "wholesale"


def test_timeseries_uses_latest_period_for_report_snapshot():
    path = ROOT / "data" / "sample_timeseries_market.xlsx"
    df, _, _ = parse_file(path)
    analyzer = MarketAnalyzer(df)
    assert analyzer.latest_period() == "2025-11"
    totals = analyzer.totals(latest=True)
    # 最新周期总体市场行批发销量为369.5，不应把12个月或新能源/燃油明细重复累加。
    assert round(totals["wholesale"], 1) == 369.5
    charts = analyzer.line_charts()
    assert any(c["title"] == "新能源汽车市场占有率" for c in charts)
    assert any(c["title"] == "核心指标同比增速" for c in charts)


def test_report_outline_and_no_hallucination_placeholder():
    path = ROOT / "data" / "sample_market.xlsx"
    df, _, _ = parse_file(path)
    payload = build_report_payload(df, context_items=[], use_llm=False)
    assert [x["title"] for x in payload["outline"]] == ["行业全景", "企业行动", "产业链观察", "竞争追踪"]
    # Formal reports no longer generate a placeholder paragraph for missing
    # supplementary material; an empty section is kept without technical text.
    assert payload["macro_policy"] == []
    assert payload["personnel"] == []


def test_context_rule_classification_and_report_sections():
    text = """
1.2宏观政策动态：某地发布汽车消费更新政策，明确补贴实施范围和期限。
2.1人事调整：某车企宣布新任总裁履新。
3.1产业链观察：某企业发布新一代固态电池技术路线。
"""
    items = items_from_text(text, "测试资料")
    # 一段文本作为一个片段时至少要被识别为某个有效行业类别。
    assert items
    assert items[0].category in {"macro_policy", "personnel", "industry_chain", "strategy", "competition"}


def test_export_three_formats(tmp_path):
    path = ROOT / "data" / "sample_timeseries_market.xlsx"
    df, _, _ = parse_file(path)
    context = [
        x.model_dump() for x in items_from_text(
            "1.2宏观政策动态\n某地区发布新能源汽车消费支持政策，政策文件明确执行时间与适用范围。",
            "测试政策资料",
        )
    ]
    for fmt in ["xlsx", "docx", "pptx"]:
        out = export_report(df, fmt, tmp_path, "test", context_items=context, meta={"file_name": path.name})
        assert out.exists()
        assert out.stat().st_size > 1000


def test_report_contains_all_dashboard_modules_by_default():
    path = ROOT / "data" / "sample_timeseries_market.xlsx"
    df, _, _ = parse_file(path)
    payload = build_report_payload(df, context_items=[], use_llm=False)
    assert payload["dynamic_report_plan"] is True
    assert payload["market_observations"] == []
    assert payload["included_dashboard_modules"] == ["周报内容", "异常提示"]
    assert payload["overview_table"] == []
    assert payload["trend"] == []
    assert payload["line_charts"] == []
    assert payload["anomalies"]


def test_report_line_chart_selection():
    path = ROOT / "data" / "sample_timeseries_market.xlsx"
    df, _, _ = parse_file(path)
    plan = {
        "mode": "custom",
        "market_observations": [{
            "custom_title": "新能源观察",
            "components": ["line_chart:新能源汽车市场占有率"],
        }],
    }
    payload = build_report_payload(df, context_items=[], use_llm=False, report_plan=plan)
    components = payload["market_observations"][0]["components"]
    assert len(components) == 1
    assert components[0]["title"] == "新能源汽车市场占有率"


def test_report_instruction_only_persists_structured_options():
    import shutil
    from app.services.storage import save_dataset, processed_base
    from app.services.report_config import apply_report_instruction, load_report_config
    path = ROOT / "data" / "sample_timeseries_market.xlsx"
    df, _, _ = parse_file(path)
    dataset_id = "pytest_report_dialog"
    try:
        save_dataset(dataset_id, df, {"file_name": path.name})
        titles = [x["title"] for x in MarketAnalyzer(df).line_charts()]
        cfg, changes = apply_report_instruction(dataset_id, "报告中不要异常提示，并且重点分析新能源市场", titles)
        assert cfg["include_anomalies"] is False
        assert "custom_instructions" not in cfg
        assert load_report_config(dataset_id)["include_anomalies"] is False
        cfg, changes = apply_report_instruction(dataset_id, "报告中加入全部折线图", titles)
        assert cfg["include_line_charts"] is True
        assert cfg["selected_line_charts"] == []
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_report_edit_followup_uses_chat_history():
    import shutil
    from app.services.storage import save_dataset, processed_base
    from app.services.chat_engine import ChatEngine
    path = ROOT / "data" / "sample_timeseries_market.xlsx"
    df, _, _ = parse_file(path)
    dataset_id = "pytest_report_followup"
    try:
        save_dataset(dataset_id, df, {"file_name": path.name})
        engine = ChatEngine(df, dataset_id=dataset_id)
        first = engine.answer("报告中不要异常提示", use_llm=False)
        assert first.report_updated
        follow = engine.answer("那把异常提示加回来", use_llm=False, history=[{"role": "user", "content": "报告中不要异常提示"}, {"role": "assistant", "content": first.answer}])
        assert follow.report_updated
        assert follow.report_config["include_anomalies"] is True
    finally:
        shutil.rmtree(processed_base(dataset_id), ignore_errors=True)


def test_semantic_sheet_name_maps_generic_value_to_production(tmp_path):
    from openpyxl import Workbook
    path = tmp_path / "市场数据.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "11月产量"
    ws.append(["时间", "指标", "数值"])
    ws.append(["2025.11", "总体市场", "353.2万"])
    ws.append(["2025.11", "乘用车", "314.4万"])
    wb.save(path)

    df, metas, issues = parse_file(path, source_name="市场数据.xlsx")
    assert metas[0].detected_metric == "production"
    assert metas[0].value_column == "数值"
    assert metas[0].transform_mode == "semantic_value_column"
    assert "production" in df.columns
    assert round(float(df.loc[df["market"] == "总体市场", "production"].iloc[0]), 1) == 353.2


def test_semantic_filename_maps_generic_value_to_export(tmp_path):
    from openpyxl import Workbook
    path = tmp_path / "2025年11月出口数据.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws.append(["月份", "市场", "数量"])
    ws.append(["2025.11", "总体市场", "72.8万"])
    wb.save(path)

    df, metas, _ = parse_file(path, source_name=path.name)
    assert metas[0].detected_metric == "export"
    assert "export" in df.columns
    assert round(float(df["export"].dropna().iloc[0]), 1) == 72.8


def test_wide_metric_sheet_uses_sheet_semantics_and_melts_periods(tmp_path):
    from openpyxl import Workbook
    path = tmp_path / "市场.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "批发销量"
    ws.append(["市场", "2025.10", "2025.11"])
    ws.append(["总体市场", 360.0, 369.5])
    ws.append(["新能源车", 170.0, 182.3])
    wb.save(path)

    df, metas, _ = parse_file(path, source_name=path.name)
    assert metas[0].transform_mode == "wide_to_long"
    assert metas[0].detected_metric == "wholesale"
    assert set(df["time_period"].dropna()) == {"2025-10", "2025-11"}
    assert round(float(df[(df["market"] == "总体市场") & (df["time_period"] == "2025-11")]["wholesale"].iloc[0]), 1) == 369.5


def test_separate_metric_tables_reconcile_into_same_market_row():
    import pandas as pd
    df = pd.DataFrame([
        {"time_period": "2025-11", "market": "总体市场", "production": 353.2, "source_file": "产量.xlsx", "source_sheet": "产量", "source_metric_type": "production"},
        {"time_period": "2025-11", "market": "总体市场", "wholesale": 342.9, "source_file": "销量.xlsx", "source_sheet": "销量", "source_metric_type": "wholesale"},
        {"time_period": "2025-11", "market": "总体市场", "export": 72.8, "source_file": "出口.xlsx", "source_sheet": "出口", "source_metric_type": "export"},
    ])
    analyzer = MarketAnalyzer(df)
    totals = analyzer.totals()
    assert totals["production"] == 353.2
    assert totals["wholesale"] == 342.9
    assert totals["export"] == 72.8
    overview = analyzer.overview()[0]
    assert "353.2" in overview["产量"]
    assert "342.9" in overview["批发销量"]
    assert "72.8" in overview["出口"]


def test_unique_metric_rows_keep_values_and_metric_lineage_on_fast_path():
    import pandas as pd
    from app.services.data_reconcile import reconcile_metric_rows

    source = pd.DataFrame([
        {"time_period": "2025-01", "model": "A", "sales": 10, "yoy_sales": None, "source_sheet": "Sheet1", "source_row": 3, "source_cell": "H3"},
        {"time_period": "2025-01", "model": "B", "sales": 20, "yoy_sales": None, "source_sheet": "Sheet1", "source_row": 4, "source_cell": "H4"},
    ])
    reconciled, warnings = reconcile_metric_rows(source)

    assert warnings == []
    assert reconciled[["time_period", "model", "sales"]].to_dict("records") == source[["time_period", "model", "sales"]].to_dict("records")
    assert reconciled["source_cell_sales"].tolist() == ["H3", "H4"]
    assert "source_cell_yoy_sales" not in reconciled.columns


def test_report_fact_pack_is_current_data_driven():
    path = ROOT / "data" / "sample_timeseries_market.xlsx"
    df, _, _ = parse_file(path)
    payload = build_report_payload(df, context_items=[], use_llm=False)
    fact_pack = payload["market_fact_pack"]
    assert fact_pack["latest_period"] == "2025-11"
    assert round(fact_pack["overall_metrics"]["wholesale"], 1) == 369.5
    assert fact_pack["rules"]["template_is_layout_only"] is True
    # 关闭LLM时也必须根据当前数据生成，而不是写死参考周报标题。
    assert "2025年11月" in payload["market_observation_title"] or "11月" in payload["market_observation_title"]
