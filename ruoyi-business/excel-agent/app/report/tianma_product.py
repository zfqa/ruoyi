"""Deterministic maker technology and size shipment metrics."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any

from app.report.periods import (
    BASE_PERIODS,
    SUPPORTED_YEARS,
    clip_periods_to_data_through,
    parse_period_key,
    periods_for_year_quarters,
    periods_for_years,
    prior_year_quarter_period,
    quarter_period,
    year_quarters_from_rows,
)


YEARS = SUPPORTED_YEARS
PERIODS = BASE_PERIODS
TECHNOLOGIES = ("LTPS", "a-Si")
SIZE_BUCKETS = (
    ("<8", '8”以下'),
    ("[8,12)", '8”-12”'),
    ("[12,15)", '12”-15”'),
    (">=15", '15”以上'),
)
Q1_Q3 = {1, 2, 3}
FULL_YEAR_QUARTERS = {1, 2, 3, 4}


def _is_full_year_2025(rows, data_through_year=None, data_through_quarter=None) -> bool:
    """有 Omdia 截止季时以其为准；否则才看行内是否齐四季。"""
    if data_through_year is not None and data_through_quarter is not None:
        return (int(data_through_year), int(data_through_quarter)) >= (2025, 4)
    qs = {int(row["quarter"]) for row in rows if int(row.get("year") or 0) == 2025}
    return FULL_YEAR_QUARTERS <= qs


def calculate_tianma_product_metrics(
    current_records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | None = None,
    maker: str = "Tianma",
    data_through_year: int | None = None,
    data_through_quarter: int | None = None,
    full_year: bool | None = None,
) -> dict[str, Any]:
    """Calculate one maker's technology and size section without LLM arithmetic."""
    current = [_normalize(record, "current", maker) for record in current_records]
    baseline = [_normalize(record, "baseline", maker) for record in (baseline_records or [])]
    rows = [row for row in current if row is not None and row["year"] in set(YEARS) - {2022}]
    rows.extend(row for row in baseline if row is not None and row["year"] == 2022)
    active_periods = clip_periods_to_data_through(
        periods_for_year_quarters(year_quarters_from_rows(rows)),
        data_through_year,
        data_through_quarter,
    )
    if full_year is None:
        full_year = _is_full_year_2025(rows, data_through_year, data_through_quarter)
    else:
        full_year = bool(full_year)
    size_quarters = FULL_YEAR_QUARTERS if full_year else Q1_Q3
    maker_key = _slug(maker)
    size_distribution = _exact_size_distribution(rows, maker_key, size_quarters, full_year=full_year)
    # 兼容旧字段名：无论是否全年口径，该键始终只含 Y25 Q1–Q3。
    q1_q3_size_distribution = (
        size_distribution if not full_year
        else _exact_size_distribution(rows, maker_key, Q1_Q3, full_year=False)
    )

    gaps = []
    if not baseline_records:
        gaps.append(f"未提供1Q25 with 4Q24 Results基准文件，{maker}技术别Y22指标暂缺")
    elif not any(row["year"] == 2022 for row in rows):
        gaps.append(f"基准文件Pivot Cache中未找到符合筛选条件的{maker}技术别2022数据")
    if not any(row.get("size") is not None for row in rows):
        gaps.append(f"符合筛选条件的{maker}记录缺少Size，无法生成尺寸分布与尺寸别增长")

    return {
        "engine": "python_deterministic_v1",
        "title": f"{maker}技术别出货与尺寸分布",
        "scope": {
            "source_measure": "Shipments (000s)",
            "original_specification": "Automobile monitor",
            "excluded_application": "Automobile monitor (Others)",
            "maker": maker,
            "technologies": list(TECHNOLOGIES),
            "years": sorted({row["year"] for row in rows}),
            "period_order": list(active_periods),
            "full_year": full_year,
            "primary_period": "Y25F" if full_year else "Y25Q1-Q3",
            "y25q1_q3_quarters": ["Q1", "Q2", "Q3"],
            "summary_quarters": [f"Q{q}" for q in sorted(size_quarters)],
            "y22_source": "baseline_workbook",
            "y23_plus_source": "merged_current_workbooks",
            "source_row_count": len(rows),
        },
        "formulas": {
            "yoy": "current comparable period / previous comparable period - 1",
            "y25f_yoy": "Y25F / Y24 - 1",
            "y25q1_q3_yoy": "Y25 Q1-Q3 / Y24 Q1-Q3 - 1",
            "quarter_yoy": "YxxQn / prior-year same quarter - 1",
            "size_buckets": ["Size < 8", "8 <= Size < 12", "12 <= Size < 15", "Size >= 15"],
        },
        "technology_history": {
            technology: _technology_metric(rows, technology, maker_key, active_periods)
            for technology in TECHNOLOGIES
        },
        "size_distribution": size_distribution,
        "y25q1_q3_size_distribution": q1_q3_size_distribution,
        "technology_size_growth": {
            technology: _technology_size_metric(rows, technology, maker_key) for technology in TECHNOLOGIES
        },
        "data_gaps": gaps,
    }


def _normalize(record, source_role, maker_name):
    year = _integer(_value(_fact(record, "year")))
    quarter = _quarter(_value(_fact(record, "quarter")))
    maker = _text(_value(_fact(record, "panel_maker", "maker")))
    specification = _text(_value(_fact(record, "original_specification", "specification")))
    application = _text(_value(_fact(record, "application", "product")))
    technology = _technology(_value(_fact(record, "technology")))
    shipment_fact = _fact(record, "quantity_000", "shipment")
    shipment = _decimal(_value(shipment_fact))
    size = _decimal(_value(_fact(record, "size", "display_size", "diagonal_size")))
    if year not in YEARS or quarter not in {1, 2, 3, 4} or shipment is None:
        return None
    if not _maker_matches(maker, maker_name) or specification.lower() != "automobile monitor":
        return None
    normalized_application = application.lower().replace("（", "(").replace("）", ")")
    if "automobile monitor" in normalized_application and "others" in normalized_application:
        return None
    if technology not in TECHNOLOGIES:
        return None
    return {
        "year": year,
        "quarter": quarter,
        "technology": technology,
        "size": size,
        "shipment": shipment,
        "source_role": source_role,
        "source_ref": _source_ref(shipment_fact),
    }


def _technology_metric(rows, technology, maker_key, periods):
    selected = [row for row in rows if row["technology"] == technology]
    buckets = _period_buckets(selected, periods)
    prior_q1_q3 = _selected_bucket(selected, 2024, {1, 2, 3})
    prior_q1 = _selected_bucket(selected, 2025, {1})
    return _period_metric(
        buckets, prior_q1_q3, f"{maker_key}.technology.{_slug(technology)}.shipment",
        periods, prior_q1=prior_q1,
    )


def _technology_size_metric(rows, technology, maker_key):
    selected = [row for row in rows if row["technology"] == technology and row["size"] is not None]
    periods = periods_for_year_quarters(year_quarters_from_rows(rows)) or periods_for_years(
        row["year"] for row in rows
    )
    series = []
    for bucket_name, label in SIZE_BUCKETS:
        bucket_rows = [row for row in selected if _size_bucket(row["size"]) == bucket_name]
        buckets = _period_buckets(bucket_rows, periods)
        prior_q1_q3 = _selected_bucket(bucket_rows, 2024, {1, 2, 3})
        prior_q1 = _selected_bucket(bucket_rows, 2025, {1})
        metric = _period_metric(
            buckets,
            prior_q1_q3,
            f"{maker_key}.technology_size.{_slug(technology)}.{_slug(bucket_name)}",
            periods,
            prior_q1=prior_q1,
        )
        metric.update({"size_bucket": bucket_name, "label": label})
        series.append(metric)
    return {
        "metric_id": f"{maker_key}.technology_size.{_slug(technology)}",
        "technology": technology,
        "unit": "thousand_units",
        "period_order": list(periods),
        "series": series,
    }


def _exact_size_distribution(rows, maker_key, quarters=None, full_year: bool = False):
    quarters = set(quarters or Q1_Q3)
    selected = [
        row for row in rows
        if row["year"] == 2025 and row["quarter"] in quarters and row["size"] is not None
    ]
    grouped: dict[Decimal, dict[str, Any]] = {}
    for row in selected:
        bucket = grouped.setdefault(row["size"], {
            "shipment": Decimal("0"),
            "technologies": {technology: Decimal("0") for technology in TECHNOLOGIES},
            "refs": [],
            "count": 0,
        })
        bucket["shipment"] += row["shipment"]
        bucket["technologies"][row["technology"]] += row["shipment"]
        bucket["count"] += 1
        if row["source_ref"] and len(bucket["refs"]) < 12:
            bucket["refs"].append(row["source_ref"])
    suffix = "y25_full_year" if full_year else "y25q1_q3"
    return {
        "metric_id": f"{maker_key}.size_distribution.{suffix}",
        "unit": "thousand_units",
        "primary_period": "Y25F" if full_year else "Y25Q1-Q3",
        "label": "Y25全年尺寸别分布" if full_year else "Y25 Q1-Q3尺寸别分布",
        "points": [{
            "size": _number(size),
            "shipment": _number(bucket["shipment"]),
            "technologies": {
                technology: _number(bucket["technologies"][technology]) for technology in TECHNOLOGIES
            },
            "evidence": {
                "source_roles": ["current"],
                "source_refs": bucket["refs"],
                "source_record_count": bucket["count"],
                "aggregation": "sum",
            },
        } for size, bucket in sorted(grouped.items())],
    }


def _period_metric(buckets, prior_q1_q3, metric_id, periods, prior_q1=None):
    yoy_periods = {
        "Y22": None,
        "Y23": _standard_yoy(buckets.get("Y23"), buckets.get("Y22")),
        "Y24": _standard_yoy(buckets.get("Y24"), buckets.get("Y23")),
        "Y25F": _standard_yoy(buckets.get("Y25F"), buckets.get("Y24")),
        "Y25Q1-Q3": _standard_yoy(buckets.get("Y25Q1-Q3"), prior_q1_q3),
    }
    if "Y26Q1" in periods:
        yoy_periods["Y26Q1"] = _standard_yoy(buckets.get("Y26Q1"), prior_q1)
    return {
        "metric_id": metric_id,
        "unit": "thousand_units",
        "periods": {period: _bucket_value(buckets.get(period)) for period in periods},
        "standard_y25f_yoy": _standard_yoy(buckets.get("Y25F"), buckets.get("Y24")),
        "yoy_periods": yoy_periods,
        "evidence": {period: _bucket_evidence(buckets.get(period)) for period in periods},
    }


def _period_buckets(rows, periods=None):
    periods = periods or PERIODS
    buckets = {period: _empty_bucket() for period in periods}
    for row in rows:
        if row["year"] < 2025:
            period = f"Y{str(row['year'])[-2:]}"
            if period in buckets:
                _add(buckets[period], row)
        elif row["year"] == 2025:
            if "Y25F" in buckets:
                _add(buckets["Y25F"], row)
            if row["quarter"] in {1, 2, 3} and "Y25Q1-Q3" in buckets:
                _add(buckets["Y25Q1-Q3"], row)
        elif row["year"] == 2026 and row["quarter"] == 1 and "Y26Q1" in buckets:
            _add(buckets["Y26Q1"], row)
    return buckets


def _selected_bucket(rows, year, quarters):
    bucket = _empty_bucket()
    for row in rows:
        if row["year"] == year and row["quarter"] in quarters:
            _add(bucket, row)
    return bucket


def _empty_bucket():
    return {"value": Decimal("0"), "count": 0, "refs": [], "sources": set()}


def _add(bucket, row):
    bucket["value"] += row["shipment"]
    bucket["count"] += 1
    bucket["sources"].add(row["source_role"])
    if row["source_ref"] and len(bucket["refs"]) < 12:
        bucket["refs"].append(row["source_ref"])


def _bucket_value(bucket):
    if not bucket or not bucket["count"]:
        return None
    return _number(bucket["value"])


def _bucket_evidence(bucket):
    if not bucket or not bucket["count"]:
        return None
    return {
        "source_roles": sorted(bucket["sources"]),
        "source_refs": bucket["refs"],
        "source_record_count": bucket["count"],
        "aggregation": "sum",
    }


def _standard_yoy(current, previous):
    if not current or not previous or not current["count"] or not previous["count"] or previous["value"] == 0:
        return None
    return _number(current["value"] / previous["value"] - Decimal("1"))


def _size_bucket(size):
    if size < Decimal("8"):
        return "<8"
    if size < Decimal("12"):
        return "[8,12)"
    if size < Decimal("15"):
        return "[12,15)"
    return ">=15"


def _technology(value):
    text = _text(value).lower().replace("_", "-")
    if "ltps" in text:
        return "LTPS"
    if "a-si" in text or "a si" in text or "amorphous silicon" in text:
        return "a-Si"
    return None


def _fact(record, *names):
    for name in names:
        value = record.get(name)
        if isinstance(value, dict):
            return value
    return None


def _value(fact):
    return fact.get("value") if isinstance(fact, dict) else None


def _source_ref(fact):
    return fact.get("source_cell") if isinstance(fact, dict) else None


def _integer(value):
    try:
        return int(float(str(value)))
    except (TypeError, ValueError):
        return None


def _quarter(value):
    text = _text(value).upper().replace("QUARTER", "Q").replace(" ", "")
    for number in (1, 2, 3, 4):
        if text in {str(number), f"Q{number}", f"{number}Q"}:
            return number
    return None


def _decimal(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value).replace(",", "").strip())
    except (InvalidOperation, ValueError):
        return None


def _text(value):
    return "" if value is None else str(value).strip()


def _number(value):
    rounded = value.quantize(Decimal("0.000001"))
    return int(rounded) if rounded == rounded.to_integral() else float(rounded)


def _slug(value):
    return str(value).lower().replace("a-si", "asi").replace("ltps", "ltps").replace("<", "lt").replace(">=", "gte").replace("[", "").replace("]", "").replace(",", "_").replace(")", "")


def _maker_matches(value, maker):
    actual = _text(value).lower().replace("_", " ").replace("-", " ")
    target = _text(maker).lower().replace("_", " ").replace("-", " ")
    return actual == target or (target == "csot" and actual in {"china star", "csot"})
