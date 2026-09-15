from __future__ import annotations

from typing import Any
import pandas as pd

from app.services.market_analysis import MarketAnalyzer, NUM_COLS, YOY_COLS, DISPLAY_NAME


def _safe_float(v: Any) -> float | None:
    try:
        if v is None or pd.isna(v):
            return None
        return float(v)
    except Exception:
        return None


def _latest_market_rows(analyzer: MarketAnalyzer, max_rows: int = 12) -> list[dict[str, Any]]:
    frame = analyzer.latest_frame()
    rows: list[dict[str, Any]] = []
    label_col = "market" if "market" in frame.columns else "vehicle_type" if "vehicle_type" in frame.columns else None
    if not label_col:
        return rows
    data = frame.dropna(subset=[label_col]).copy()
    preferred = data[data[label_col].astype(str).str.contains(r"总体|整体|总计|乘用|商用|新能源|燃油", regex=True, na=False)]
    if not preferred.empty:
        data = preferred
    seen = set()
    for _, r in data.iterrows():
        label = str(r.get(label_col) or "")
        if not label or label in seen:
            continue
        seen.add(label)
        item: dict[str, Any] = {"indicator": label}
        for c in NUM_COLS + YOY_COLS:
            if c in r.index:
                item[c] = _safe_float(r.get(c))
        rows.append(item)
        if len(rows) >= max_rows:
            break
    return rows


def _chart_fact(chart: dict[str, Any]) -> dict[str, Any]:
    out = {"title": chart.get("title"), "series": []}
    for series in chart.get("series", [])[:8]:
        points = series.get("points", [])
        if not points:
            continue
        vals = [(str(p.get("时间")), _safe_float(p.get("数值"))) for p in points]
        vals = [(t, v) for t, v in vals if v is not None]
        if not vals:
            continue
        numbers = [v for _, v in vals]
        latest_t, latest_v = vals[-1]
        prev = vals[-2][1] if len(vals) >= 2 else None
        out["series"].append({
            "name": series.get("name"),
            "latest_period": latest_t,
            "latest_value": latest_v,
            "previous_value": prev,
            "change_vs_previous": (latest_v - prev) if prev is not None else None,
            "peak": max(numbers),
            "trough": min(numbers),
            "points": [{"period": t, "value": v} for t, v in vals[-18:]],
        })
    return out


def build_market_fact_pack(analyzer: MarketAnalyzer, result) -> dict[str, Any]:
    # 核心总量必须使用“每个指标独立选源”的结果，不能因为某一张市场汇总Sheet只含销量
    # 就让产量/库存等其他Sheet的事实从LLM上下文中消失。
    overall_metrics: dict[str, Any] = {k: _safe_float(v) for k, v in result.summary.items()}
    for metric, yoy_col in {
        "production": "yoy_production",
        "sales": "yoy_sales",
        "retail_sales": "yoy_retail_sales",
        "wholesale": "yoy_wholesale",
        "domestic_sales": "yoy_domestic_sales",
        "domestic_wholesale": "yoy_domestic_wholesale",
        "export": "yoy_export",
    }.items():
        overall_metrics[yoy_col] = _safe_float(analyzer._total_yoy(metric))

    ranking_facts: dict[str, Any] = {}
    for dim, rows in result.rankings.items():
        rows = rows[:10]
        ranking_facts[dim] = {
            "top10": rows,
            "top5_share": sum(float(x.get("占比") or 0) for x in rows[:5]) if rows else None,
            "top10_share": sum(float(x.get("占比") or 0) for x in rows[:10]) if rows else None,
        }

    metric_coverage = analyzer.source_metric_coverage()
    raw = analyzer.raw_df
    source_tables = []
    if {"source_file", "source_sheet"}.issubset(raw.columns):
        cols = [c for c in ["source_file", "source_sheet", "source_metric_type", "source_dimension", "source_table_type", "source_semantic_confidence"] if c in raw.columns]
        source_tables = raw[cols].drop_duplicates().where(raw[cols].drop_duplicates().notna(), None).to_dict("records")[:100]

    return {
        "latest_period": result.latest_period,
        "analysis_period": result.analysis_period or {},
        "period_label": (result.analysis_period or {}).get("display_label") or result.latest_period,
        "comparison_period_label": (result.analysis_period or {}).get("comparison_label"),
        "region": result.region,
        "overall_metrics": overall_metrics,
        # v8：市场观察使用完整车种/市场列表，不再只给LLM前12条。
        "market_rows": result.overview_table[:60],
        "rankings": ranking_facts,
        "power_structure": result.power_share[:15],
        "monthly_trend": result.monthly_trend[-24:],
        "chart_facts": [_chart_fact(c) for c in result.line_charts],
        "anomalies": result.anomalies[:30],
        "available_metric_types": metric_coverage,
        "source_tables": source_tables,
        "selected_metric_sources": result.metric_sources or {},
        "primary_sales_metric": analyzer.primary_sales_metric(),
        "primary_sales_label": analyzer.sales_metric_label(),
        "dimension_integrity": result.dimension_integrity or {},
        "data_quality_warnings": result.warnings[:30],
        "rules": {
            "numbers_are_program_calculated": True,
            "missing_fields_must_not_be_inferred": True,
            "template_is_layout_only": True,
        },
    }
