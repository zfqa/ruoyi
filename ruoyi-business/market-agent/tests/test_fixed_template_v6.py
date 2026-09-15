from pathlib import Path
from openpyxl import Workbook

from app.services.parser import parse_file
from app.services.market_analysis import MarketAnalyzer

ROOT = Path(__file__).resolve().parents[1]


def test_yyyymm_fixed_template_is_detected_and_normalized():
    path = ROOT / "data" / "sample_fixed_template_v6.xlsx"
    df, metas, issues = parse_file(path)
    model_meta = next(x for x in metas if x.sheet_name == "车型销量")
    assert model_meta.detected_metric == "sales"
    assert model_meta.detected_dimension == "model"
    assert model_meta.transform_mode == "wide_to_long"
    assert set(df["time_period"].dropna().astype(str)) >= {"2024-01", "2024-02", "2025-01", "2025-02"}
    assert "model" in df.columns
    assert "sales" in df.columns


def test_sheet_name_has_priority_over_conflicting_filename(tmp_path):
    path = tmp_path / "汽车产量数据.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "品牌销量"
    ws.append(["名称", 202401, 202501])
    ws.append(["品牌A", 100, 120])
    wb.save(path)
    df, metas, _ = parse_file(path, source_name=path.name)
    assert metas[0].detected_metric == "sales"
    assert metas[0].detected_dimension == "brand"
    assert "sales" in df.columns
    assert "production" not in df.columns or df["production"].dropna().empty


def test_each_metric_uses_its_own_sheet_and_dimension():
    path = ROOT / "data" / "sample_fixed_template_v6.xlsx"
    df, _, _ = parse_file(path)
    result = MarketAnalyzer(df).run_all()
    assert result.latest_period == "2025-02"
    assert result.summary["sales"] == 270.0
    assert result.summary["production"] == 280.0
    assert result.summary["domestic_sales"] == 205.0
    assert result.summary["export"] == 65.0
    assert result.summary["inventory"] == 53.0
    assert result.metric_sources["sales"]["Sheet"] == "市场销量"
    assert result.metric_sources["production"]["Sheet"] == "市场产量"
    assert result.metric_sources["sales"]["源指标"] == "销量"
    assert result.metric_sources["sales"]["源行范围"]
    assert result.rankings["oem"][0]["对象"] == "OEM-A"
    assert result.rankings["brand"][0]["对象"] == "品牌甲"
    assert result.rankings["model"][0]["对象"] == "车型1"
    assert result.power_share


def test_yoy_and_top_model_trend_are_calculated_from_yyyymm_history():
    path = ROOT / "data" / "sample_fixed_template_v6.xlsx"
    df, _, _ = parse_file(path)
    analyzer = MarketAnalyzer(df)
    charts = analyzer.line_charts()
    titles = [x["title"] for x in charts]
    assert "核心指标同比增速" in titles
    assert "Top车型月度销量趋势" in titles
    anomalies = analyzer.detect_anomalies(0.15)
    assert anomalies  # 2025-02相对2024-02存在明显同比变化
    assert all("原表第" in row["来源"] or "!" in row["来源"] for row in anomalies)
    assert any("当前期：" in row["来源"] and "同期：" in row["来源"] for row in anomalies)


def test_missing_metric_only_skips_that_metric(tmp_path):
    path = tmp_path / "只有销量.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "OEM销量"
    ws.append(["名称", 202401, 202501])
    ws.append(["A", 100, 130])
    ws.append(["B", 80, 90])
    wb.save(path)
    df, _, _ = parse_file(path, source_name=path.name)
    result = MarketAnalyzer(df).run_all()
    assert result.summary["sales"] == 220.0
    assert result.summary["production"] is None
    assert result.rankings["oem"]
    assert any("产量" in w and "跳过" in w for w in result.warnings)


def test_report_fact_pack_keeps_totals_from_separate_metric_sheets():
    from app.services.report_generator import build_report_payload

    path = ROOT / "data" / "sample_fixed_template_v6.xlsx"
    df, _, _ = parse_file(path)
    payload = build_report_payload(df, use_llm=False)
    metrics = payload["market_fact_pack"]["overall_metrics"]
    assert metrics["sales"] == 270.0
    assert metrics["production"] == 280.0
    assert metrics["domestic_sales"] == 205.0
    assert metrics["export"] == 65.0
    assert metrics["inventory"] == 53.0
    assert payload["market_fact_pack"]["selected_metric_sources"]["production"]["Sheet"] == "市场产量"
