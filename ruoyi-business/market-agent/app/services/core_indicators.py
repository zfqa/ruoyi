from __future__ import annotations

import hashlib
import json
import math
import re
from typing import Any

import pandas as pd

from app.services.market_analysis import DISPLAY_NAME
from app.services.period_service import normalize_period


AGGREGATIONS = {
    "sum": "求和",
    "average": "平均值",
    "latest": "期末值",
    "max": "最大值",
    "min": "最小值",
    "count": "记录数",
}

COMPARISONS = {
    "none": "不比较",
    "yoy": "同比",
    "mom": "环比",
}

DIMENSION_LABELS = {
    "region": "地区",
    "market": "市场/车种",
    "vehicle_type": "车辆类型",
    "oem": "车企",
    "brand": "品牌",
    "model": "车型",
    "power_type": "动力类型",
    "power_group": "动力大类",
    "size_class": "级别",
    "tech_route": "技术路线",
    "manufacturer": "制造商",
    "origin_type": "来源类型",
}

SUMMARY_SEGMENTS = {
    "total_market": "总体市场",
    "passenger_vehicle": "乘用车",
    "commercial_vehicle": "商用车",
    "new_energy_vehicle": "新能源车",
    "ice_vehicle": "燃油车",
    "custom": "自定义筛选",
}

KNOWN_METRIC_ORDER = [
    "production", "sales", "retail_sales", "wholesale", "domestic_sales",
    "domestic_wholesale", "export", "inventory",
]

_METADATA_COLUMNS = {
    "time_period", "source_file", "source_sheet", "source_sheet_name", "source_row",
    "source_column", "source_column_index", "source_cell",
    "source_metric_type", "source_metric_scope", "source_metric_label", "source_dimension",
    "source_table_type", "source_parser_id", "source_semantic_confidence", "source_row_kind",
}


def _clean_text(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "").strip())


def _normal_text(value: Any) -> str:
    return _clean_text(value).casefold()


def _safe_id(value: Any, fallback: str) -> str:
    raw = re.sub(r"[^0-9A-Za-z_-]+", "_", str(value or "")).strip("_")
    return raw[:80] or fallback


def _metric_label(field: str) -> str:
    return DISPLAY_NAME.get(field, field.replace("_", " "))


def _numeric_series(series: pd.Series) -> pd.Series:
    if pd.api.types.is_numeric_dtype(series):
        return pd.to_numeric(series, errors="coerce")
    cleaned = series.astype(str).str.strip().str.replace(",", "", regex=False)
    cleaned = cleaned.str.replace("%", "", regex=False)
    return pd.to_numeric(cleaned, errors="coerce")


def discover_metric_catalog(df: pd.DataFrame) -> list[dict[str, Any]]:
    """Discover usable measures from the uploaded data instead of a fixed template.

    A column must contain numeric data in at least 60 percent of its non-empty cells.
    Identifier, provenance and pre-calculated comparison columns are deliberately excluded.
    """
    candidates: list[dict[str, Any]] = []
    for column in df.columns:
        field = str(column)
        if field in _METADATA_COLUMNS or field in DIMENSION_LABELS or field.startswith("yoy_") or field.startswith("source_"):
            continue
        source = df[column]
        present = source[~source.isna() & source.astype(str).str.strip().ne("")]
        if present.empty:
            continue
        numeric = _numeric_series(present.head(5000))
        density = float(numeric.notna().mean())
        if density < 0.60 or numeric.notna().sum() == 0:
            continue
        lower = field.casefold()
        if lower in {"year", "month", "id", "index", "序号", "编号"} or lower.endswith("_id"):
            continue
        default_aggregation = "latest" if field == "inventory" else "average" if any(x in lower for x in ["rate", "ratio", "share", "percent", "占比", "比例", "率"]) else "sum"
        unit = "%" if default_aggregation == "average" and any(x in lower for x in ["rate", "ratio", "share", "percent", "占比", "比例", "率"]) else ""
        candidates.append({
            "field": field,
            "label": _metric_label(field),
            "default_aggregation": default_aggregation,
            "default_unit": unit,
            "numeric_density": round(density, 4),
        })
    order = {name: index for index, name in enumerate(KNOWN_METRIC_ORDER)}
    candidates.sort(key=lambda item: (order.get(item["field"], len(order)), item["label"].casefold()))
    return candidates


def discover_dimension_catalog(df: pd.DataFrame, metric_fields: set[str] | None = None) -> list[dict[str, Any]]:
    metrics = metric_fields or {item["field"] for item in discover_metric_catalog(df)}
    rows: list[dict[str, Any]] = []
    for column in df.columns:
        field = str(column)
        if field in metrics or field in _METADATA_COLUMNS or field.startswith("yoy_") or field.startswith("source_") or field == "time_period":
            continue
        values = [_clean_text(x) for x in df[column].dropna().drop_duplicates().head(501).tolist()]
        values = list(dict.fromkeys(x for x in values if x))
        if not values:
            continue
        # Numeric columns with many distinct values are measures, not filters.
        if _numeric_series(pd.Series(values)).notna().mean() >= 0.60 and len(values) > 24:
            continue
        rows.append({
            "field": field,
            "label": DIMENSION_LABELS.get(field, field.replace("_", " ")),
            "values": values[:500],
            "truncated": len(values) > 500,
        })
    priority = {name: index for index, name in enumerate(DIMENSION_LABELS)}
    rows.sort(key=lambda item: (priority.get(item["field"], len(priority)), item["label"].casefold()))
    return rows


def normalize_indicator_specs(specs: Any) -> list[dict[str, Any]]:
    """Normalize persisted/user supplied indicator definitions without executing expressions."""
    if not isinstance(specs, list):
        return []
    out: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw in enumerate(specs[:50], 1):
        if not isinstance(raw, dict):
            continue
        metric = _clean_text(raw.get("metric"))
        if not metric:
            continue
        indicator_id = _safe_id(raw.get("id"), f"indicator_{index}")
        base_id = indicator_id
        suffix = 2
        while indicator_id in seen:
            indicator_id = f"{base_id}_{suffix}"
            suffix += 1
        seen.add(indicator_id)
        aggregation = str(raw.get("aggregation") or "sum")
        # v21.1 uses a list because one metric can expose YoY and MoM at the
        # same time.  Persisted v21 plans used the scalar ``comparison`` key;
        # keep accepting it so opening an existing report automatically
        # migrates the value without losing the user's selection.
        raw_comparisons = raw.get("comparisons") if "comparisons" in raw else raw.get("comparison")
        comparison_values = raw_comparisons if isinstance(raw_comparisons, list) else [raw_comparisons]
        comparisons: list[str] = []
        for value in comparison_values:
            comparison = str(value or "none")
            if comparison in COMPARISONS and comparison != "none" and comparison not in comparisons:
                comparisons.append(comparison)
        filters: list[dict[str, Any]] = []
        for item in raw.get("filters") or []:
            if not isinstance(item, dict):
                continue
            dimension = _clean_text(item.get("dimension"))
            values = item.get("values") if isinstance(item.get("values"), list) else [item.get("value")]
            values = list(dict.fromkeys(_clean_text(x) for x in values if _clean_text(x)))
            if dimension and values:
                filters.append({"dimension": dimension, "values": values[:200]})
        out.append({
            "id": indicator_id,
            "display_name": _clean_text(raw.get("display_name") or raw.get("name") or _metric_label(metric))[:100],
            "metric": metric,
            "aggregation": aggregation if aggregation in AGGREGATIONS else "sum",
            "filters": filters[:10],
            "comparisons": comparisons,
            "show_share": bool(raw.get("show_share", False)),
            "unit": _clean_text(raw.get("unit"))[:20],
            "decimals": max(0, min(6, int(raw.get("decimals", 0) or 0))),
        })
    return out


def normalize_summary_rows(rows: Any) -> list[dict[str, Any]]:
    """Normalize the independently editable row axis of a matrix summary."""
    if not isinstance(rows, list):
        return []
    out: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw in enumerate(rows[:30], 1):
        if not isinstance(raw, dict):
            continue
        row_id = _safe_id(raw.get("id"), f"summary_row_{index}")
        base_id = row_id
        suffix = 2
        while row_id in seen:
            row_id = f"{base_id}_{suffix}"
            suffix += 1
        seen.add(row_id)
        segment = str(raw.get("segment") or "custom")
        if segment not in SUMMARY_SEGMENTS:
            segment = "custom"
        filters: list[dict[str, Any]] = []
        for item in raw.get("filters") or []:
            if not isinstance(item, dict):
                continue
            dimension = _clean_text(item.get("dimension"))
            values = item.get("values") if isinstance(item.get("values"), list) else [item.get("value")]
            values = list(dict.fromkeys(_clean_text(value) for value in values if _clean_text(value)))
            if dimension and values:
                filters.append({"dimension": dimension, "values": values[:200]})
        out.append({
            "id": row_id,
            "display_name": _clean_text(raw.get("display_name") or SUMMARY_SEGMENTS[segment])[:100],
            "segment": segment,
            "filters": filters[:10],
        })
    return out


def validate_summary_rows(rows: Any, capabilities: dict[str, Any]) -> list[dict[str, Any]]:
    normalized = normalize_summary_rows(rows)
    dimensions = {
        str(item.get("field")): {_normal_text(value) for value in item.get("values") or []}
        for item in capabilities.get("dimensions") or []
    }
    errors: list[str] = []
    for index, row in enumerate(normalized, 1):
        for condition in row["filters"]:
            dimension = condition["dimension"]
            if dimension not in dimensions:
                errors.append(f"第{index}个概览行引用了当前Excel不存在的筛选字段：{dimension}")
                continue
            unavailable = [
                value for value in condition["values"]
                if _normal_text(value) not in dimensions[dimension]
            ]
            if unavailable:
                errors.append(f"第{index}个概览行筛选值不在当前Excel中：{dimension}={','.join(unavailable)}")
    if rows and not normalized:
        errors.append("市场概览行定义为空或格式无效")
    if errors:
        raise ValueError("；".join(errors))
    return normalized


def indicator_capabilities(df: pd.DataFrame, selected_periods: list[str] | None = None) -> dict[str, Any]:
    metrics = discover_metric_catalog(df)
    metric_fields = {item["field"] for item in metrics}
    dimensions = discover_dimension_catalog(df, metric_fields)
    schema_payload = {
        "metrics": [item["field"] for item in metrics],
        "dimensions": [{"field": item["field"], "values": item["values"]} for item in dimensions],
    }
    schema_hash = hashlib.sha256(json.dumps(schema_payload, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()[:16]
    suggestions: list[dict[str, Any]] = []
    if metrics:
        metric = metrics[0]
        suggestions.append({
            "id": "indicator_total",
            "display_name": f"总体{metric['label']}",
            "metric": metric["field"],
            "aggregation": metric["default_aggregation"],
            "filters": [],
            "comparisons": ["yoy"],
            "show_share": False,
            "unit": metric["default_unit"],
            "decimals": 0,
        })
        group = next((x for x in dimensions if x["field"] in {"market", "vehicle_type", "power_type", "region"}), dimensions[0] if dimensions else None)
        if group:
            frame = df.copy()
            if selected_periods and "time_period" in frame.columns:
                selected = set(selected_periods)
                frame = frame[frame["time_period"].map(normalize_period).isin(selected)].copy()
            if not frame.empty and metric["field"] in frame.columns and group["field"] in frame.columns:
                frame["__metric"] = _numeric_series(frame[metric["field"]])
                ranked = frame.dropna(subset=["__metric"]).groupby(group["field"], dropna=True)["__metric"].sum().sort_values(ascending=False)
                for position, value in enumerate(ranked.index.tolist()[:5], 1):
                    label = _clean_text(value)
                    if not label:
                        continue
                    suggestions.append({
                        "id": f"indicator_{_safe_id(group['field'], 'dimension')}_{position}",
                        "display_name": label,
                        "metric": metric["field"],
                        "aggregation": metric["default_aggregation"],
                        "filters": [{"dimension": group["field"], "values": [label]}],
                        "comparisons": ["yoy"],
                        "show_share": True,
                        "unit": metric["default_unit"],
                        "decimals": 0,
                    })
    return {
        "schema_hash": schema_hash,
        "metrics": metrics,
        "dimensions": dimensions,
        "aggregations": [{"value": key, "label": value} for key, value in AGGREGATIONS.items()],
        # An empty selection means "不比较".  Only real comparison dimensions
        # are returned as choices, preventing contradictory combinations such
        # as "不比较 + 同比" in a multi-select control.
        "comparisons": [{"value": key, "label": value} for key, value in COMPARISONS.items() if key != "none"],
        "suggested_indicators": suggestions,
        "max_indicators": 50,
    }


def validate_indicator_specs(specs: Any, capabilities: dict[str, Any]) -> list[dict[str, Any]]:
    normalized = normalize_indicator_specs(specs)
    metric_fields = {str(x.get("field")) for x in capabilities.get("metrics") or []}
    dimensions = {str(x.get("field")): {_normal_text(v) for v in x.get("values") or []} for x in capabilities.get("dimensions") or []}
    errors: list[str] = []
    for index, spec in enumerate(normalized, 1):
        if spec["metric"] not in metric_fields:
            errors.append(f"第{index}个核心指标引用了当前Excel不存在或不可计算的数值字段：{spec['metric']}")
        for condition in spec["filters"]:
            dimension = condition["dimension"]
            if dimension not in dimensions:
                errors.append(f"第{index}个核心指标引用了当前Excel不存在的筛选字段：{dimension}")
                continue
            unavailable = [value for value in condition["values"] if _normal_text(value) not in dimensions[dimension]]
            if unavailable:
                errors.append(f"第{index}个核心指标的筛选值不在当前Excel中：{dimension}={','.join(unavailable)}")
    if specs and not normalized:
        errors.append("核心指标定义为空或格式无效")
    if errors:
        raise ValueError("；".join(errors))
    return normalized


def validate_matrix_column_specs(specs: Any, capabilities: dict[str, Any]) -> list[dict[str, Any]]:
    """Validate the metric axis of a market-summary matrix.

    Matrix columns are business *metric* columns rather than arbitrary report
    prose.  Their header must therefore always equal the label of the selected
    Excel field or approved formula.  This prevents a configuration such as
    ``display_name=批发销量`` combined with ``metric=sales`` from being saved
    or rendered.  The rule is applied server-side so old plans and manually
    submitted API payloads cannot bypass the UI.
    """
    normalized = validate_indicator_specs(specs, capabilities)
    metric_catalog = {
        str(item.get("field")): item
        for item in capabilities.get("metrics") or []
        if str(item.get("field") or "")
    }
    for column in normalized:
        metric = metric_catalog[column["metric"]]
        # The display label is a data-contract label, not a free-text alias.
        # For unknown but numeric Excel fields ``label`` is generated directly
        # from that field; for recognised fields it is the canonical Chinese
        # business label (for example wholesale -> 批发销量).
        column["display_name"] = _clean_text(metric.get("label") or _metric_label(column["metric"]))[:100]
        # Formula columns and inventory have non-negotiable calculation
        # semantics.  A range of monthly inventory records is still a closing
        # balance, not a sum; formulas have the aggregation defined by their
        # dependency equation.
        if metric.get("derived") or column["metric"] == "inventory":
            column["aggregation"] = str(metric.get("default_aggregation") or "latest")
        column["label_locked"] = True
    return normalized


def format_indicator_value(value: float | None, unit: str, decimals: int) -> str:
    if value is None or pd.isna(value):
        return "缺失"
    number = float(value)
    if unit == "%":
        return f"{number:.{decimals}f}%"
    return f"{number:,.{decimals}f}{unit}"


def safe_change(current: float | None, previous: float | None) -> float | None:
    if current is None or previous is None or pd.isna(current) or pd.isna(previous) or math.isclose(float(previous), 0.0):
        return None
    return float(current) / float(previous) - 1.0
