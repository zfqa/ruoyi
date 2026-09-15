from pathlib import Path

import pandas as pd

from app.services.market_analysis import MarketAnalyzer
from app.services.period_service import resolve_analysis_period, period_options
from app.services.report_generator import build_report_payload, export_excel
from app.services.chat_engine import ChatEngine


def _period_df():
    rows = []
    # 2023 Jan-Jun baseline + 2024 Jan-Jun current, two vehicle types.
    for year, factor in [(2023, 1.0), (2024, 1.2)]:
        for month in range(1, 7):
            p = f"{year}-{month:02d}"
            rows.extend([
                {
                    "time_period": p,
                    "vehicle_type": "Cars",
                    "brand": "A",
                    "model": "A1",
                    "power_type": "EV",
                    "sales": 100 * month * factor,
                    "inventory": 1000 + month + (year - 2023) * 100,
                    "source_file": "x.xlsx",
                    "source_sheet": "销量",
                    "source_metric_type": "sales",
                },
                {
                    "time_period": p,
                    "vehicle_type": "SUV",
                    "brand": "B",
                    "model": "B1",
                    "power_type": "PHV",
                    "sales": 50 * month * factor,
                    "inventory": 500 + month + (year - 2023) * 50,
                    "source_file": "x.xlsx",
                    "source_sheet": "销量",
                    "source_metric_type": "sales",
                },
            ])
    return pd.DataFrame(rows)


def test_period_options_and_modes():
    df = _period_df()
    opts = period_options(df)
    assert opts["available_years"] == [2023, 2024]
    assert opts["months_by_year"]["2024"] == [1, 2, 3, 4, 5, 6]
    assert opts["latest_period"] == "2024-06"

    latest = resolve_analysis_period(df, "latest")
    assert latest.start_period == latest.end_period == "2024-06"
    assert latest.trend_uses_all_periods is True

    single = resolve_analysis_period(df, "single", start_period="2024-03")
    assert single.display_label == "2024年3月"
    assert single.comparison_start == "2023-03"

    rng = resolve_analysis_period(df, "range", start_period="2024-01", end_period="2024-06")
    assert rng.display_label == "2024年1-6月累计"
    assert rng.comparison_label == "2023年1-6月累计"
    assert rng.complete and rng.comparison_complete


def test_range_flow_sum_inventory_period_end_and_yoy():
    df = _period_df()
    analyzer = MarketAnalyzer(df, period_mode="range", start_period="2024-01", end_period="2024-06")
    result = analyzer.run_all()

    # current sales = 1.2 * (100+50) * sum(1..6) = 3780
    assert result.summary["sales"] == 3780.0
    # inventory is a stock metric: only 2024-06 period-end, not Jan-Jun sum.
    assert result.summary["inventory"] == (1106 + 556)
    assert round(analyzer._total_yoy("sales"), 6) == 0.2
    assert len(result.monthly_trend) == 6
    assert result.monthly_trend[0]["时间"] == "2024-01"
    assert result.monthly_trend[-1]["时间"] == "2024-06"
    assert result.rankings["brand"][0]["对象"] == "A"
    assert result.rankings["brand"][0]["销量/数值"] == 2520.0
    assert result.analysis_period["aggregation_label"] == "区间累计"


def test_single_month_and_latest_backward_compatibility():
    df = _period_df()
    single = MarketAnalyzer(df, period_mode="single", start_period="2024-03").run_all()
    assert single.summary["sales"] == 540.0
    assert len(single.monthly_trend) == 9
    assert single.monthly_trend[-1]["时间"] == "2024-03"

    latest = MarketAnalyzer(df).run_all()
    assert latest.summary["sales"] == 1080.0
    # 最新月和指定同月都展示截至目标月的全部历史。
    assert len(latest.monthly_trend) == 12


def test_missing_month_is_explicitly_flagged():
    df = _period_df()
    df = df[df["time_period"] != "2024-03"].copy()
    result = MarketAnalyzer(df, period_mode="range", start_period="2024-01", end_period="2024-06").run_all()
    assert result.analysis_period["complete"] is False
    assert result.analysis_period["missing_periods"] == ["2024-03"]
    assert any("缺失：2024-03" in w for w in result.warnings)
    # 区间缺月时可以展示“基于已有月份”的累计值，但不能输出伪完整同比。
    assert MarketAnalyzer(df, period_mode="range", start_period="2024-01", end_period="2024-06")._total_yoy("sales") is None


def test_report_chat_and_export_share_same_period(tmp_path: Path):
    df = _period_df()
    period = {"period_mode": "range", "start_period": "2024-01", "end_period": "2024-06"}
    payload = build_report_payload(df, use_llm=False, analysis_period=period)
    assert payload["period_label"] == "2024年1-6月累计"
    assert payload["comparison_period_label"] == "2023年1-6月累计"
    assert payload["market_fact_pack"]["period_label"] == "2024年1-6月累计"

    chat = ChatEngine(df, analysis_period=period).answer("品牌排名", use_llm=False)
    assert "2024年1-6月累计" in chat.answer

    out = tmp_path / "period_report.xlsx"
    export_excel(df, out, analysis_period=period)
    assert out.exists() and out.stat().st_size > 0
