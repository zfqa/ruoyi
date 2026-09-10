"""Deterministic maker front-install shipment, area, and share history."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any


YEARS = (2022, 2023, 2024, 2025)
PERIODS = ("Y22", "Y23", "Y24", "Y25F", "Y25Q1-Q3")
EXCLUDED_MAKER_TECHNOLOGIES = ("oxide",)


def calculate_tianma_history_metrics(
    current_records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | None = None,
    maker: str = "Tianma",
) -> dict[str, Any]:
    current = [_normalize(record, "current") for record in current_records]
    baseline = [_normalize(record, "baseline") for record in (baseline_records or [])]
    rows = [row for row in current if row is not None and row["year"] in {2023, 2024, 2025}]
    rows.extend(row for row in baseline if row is not None and row["year"] == 2022)
    maker_measure_rows = [row for row in rows if not row["excluded_from_maker_metrics"]]

    gaps = []
    if not baseline_records:
        gaps.append(f"未提供1Q25 with 4Q24 Results基准文件，{maker} Y22指标暂缺")
    elif not any(row["year"] == 2022 for row in rows):
        gaps.append("基准文件Pivot Cache中未找到符合筛选条件的2022数据")

    maker_key = _slug(maker)
    shipment = _measure_metric(maker_measure_rows, maker, "shipment", "thousand_units", f"{maker_key}.front_install.shipment")
    display_area = _measure_metric(maker_measure_rows, maker, "display_area", "square_meters", f"{maker_key}.front_install.display_area")
    shipment_share = _share_metric(
        maker_measure_rows, rows, maker, "shipment", f"{maker_key}.front_install.shipment_share"
    )
    display_area_share = _share_metric(
        maker_measure_rows, rows, maker, "display_area", f"{maker_key}.front_install.display_area_share"
    )
    return {
        "engine": "python_deterministic_v1",
        "title": f"{maker}前装出货、面积及市占率",
        "scope": {
            "original_specification": "Automobile monitor",
            "excluded_application": "Automobile monitor (Others)",
            "maker": maker,
            "years": list(YEARS),
            "period_order": list(PERIODS),
            "source_row_count": len(rows),
            "y22_source": "baseline_workbook",
            "y23_y25_source": "current_workbook",
            "maker_technology_scope": "exclude Oxide",
            "share_market_denominator_technology_scope": "all technologies",
        },
        "formulas": {
            "yoy": "current comparable period / previous comparable period - 1",
            "y25f_yoy": "Y25F / Y24 - 1",
            "y25q1_q3_yoy": "Y25 Q1-Q3 / Y24 Q1-Q3 - 1",
            "shipment_share": f"{maker} shipment / market shipment",
            "display_area_share": f"{maker} display area / market display area",
        },
        "shipment": shipment,
        "display_area": display_area,
        "shipment_share": shipment_share,
        "display_area_share": display_area_share,
        "data_gaps": gaps,
    }


def _normalize(record, source_role):
    year = _integer(_value(_fact(record, "year")))
    quarter = _quarter(_value(_fact(record, "quarter")))
    maker = _text(_value(_fact(record, "panel_maker", "maker")))
    specification = _text(_value(_fact(record, "original_specification", "specification")))
    application = _text(_value(_fact(record, "application", "product")))
    technology = _text(_value(_fact(record, "technology", "master_technology")))
    shipment_fact = _fact(record, "quantity_000", "shipment")
    area_fact = _fact(record, "display_area")
    shipment = _decimal(_value(shipment_fact))
    display_area = _decimal(_value(area_fact))
    if year not in YEARS or quarter not in {1, 2, 3, 4}:
        return None
    if specification.lower() != "automobile monitor":
        return None
    normalized_application = application.lower().replace("（", "(").replace("）", ")")
    if "automobile monitor" in normalized_application and "others" in normalized_application:
        return None
    return {
        "year": year,
        "quarter": quarter,
        "maker": maker,
        "shipment": shipment,
        "display_area": display_area,
        "source_role": source_role,
        "shipment_ref": _source_ref(shipment_fact),
        "display_area_ref": _source_ref(area_fact),
        "excluded_from_maker_metrics": _is_excluded_maker_technology(technology),
    }


def _measure_metric(rows, maker, measure, unit, metric_id):
    maker_rows = [row for row in rows if _maker_matches(row["maker"], maker)]
    buckets = _period_buckets(maker_rows, measure)
    prior_q1_q3 = _selected_bucket(maker_rows, measure, 2024, {1, 2, 3})
    return {
        "metric_id": metric_id,
        "unit": unit,
        "periods": {period: _bucket_value(buckets[period]) for period in PERIODS},
        "comparison_periods": {
            "Y24Q1-Q3": _bucket_value(prior_q1_q3),
            "Y25Q1-Q3": _bucket_value(buckets["Y25Q1-Q3"]),
        },
        "standard_y25f_yoy": _standard_yoy(buckets["Y25F"], buckets["Y24"]),
        "yoy_2025_q1_q3_vs_2024_q1_q3": _standard_yoy(
            buckets["Y25Q1-Q3"], prior_q1_q3
        ),
        "forecast_completion_y25_q1_q3": _completion(
            buckets["Y25Q1-Q3"], buckets["Y25F"]
        ),
        "yoy_periods": {
            "Y22": None,
            "Y23": _standard_yoy(buckets["Y23"], buckets["Y22"]),
            "Y24": _standard_yoy(buckets["Y24"], buckets["Y23"]),
            "Y25F": _standard_yoy(buckets["Y25F"], buckets["Y24"]),
            "Y25Q1-Q3": _standard_yoy(buckets["Y25Q1-Q3"], prior_q1_q3),
        },
        "evidence": {period: _bucket_evidence(buckets[period]) for period in PERIODS},
        "comparison_evidence": {
            "Y24Q1-Q3": _bucket_evidence(prior_q1_q3),
            "Y25Q1-Q3": _bucket_evidence(buckets["Y25Q1-Q3"]),
        },
    }


def _share_metric(maker_measure_rows, market_rows, maker, measure, metric_id):
    maker_buckets = _period_buckets(
        [row for row in maker_measure_rows if _maker_matches(row["maker"], maker)], measure
    )
    market_buckets = _period_buckets(market_rows, measure)
    return {
        "metric_id": metric_id,
        "unit": "ratio",
        "periods": {
            period: _share(maker_buckets[period], market_buckets[period]) for period in PERIODS
        },
        "evidence": {
            period: {
                "numerator": _bucket_evidence(maker_buckets[period]),
                "denominator": _bucket_evidence(market_buckets[period]),
            } for period in PERIODS
        },
    }


def _period_buckets(rows, measure):
    buckets = {period: _empty_bucket() for period in PERIODS}
    for row in rows:
        value = row.get(measure)
        if value is None:
            continue
        period = f"Y{str(row['year'])[-2:]}" if row["year"] < 2025 else "Y25F"
        _add(buckets[period], value, row.get(f"{measure}_ref"), row["source_role"])
        if row["year"] == 2025 and row["quarter"] in {1, 2, 3}:
            _add(buckets["Y25Q1-Q3"], value, row.get(f"{measure}_ref"), row["source_role"])
    return buckets


def _selected_bucket(rows, measure, year, quarters):
    bucket = _empty_bucket()
    for row in rows:
        value = row.get(measure)
        if row["year"] == year and row["quarter"] in quarters and value is not None:
            _add(bucket, value, row.get(f"{measure}_ref"), row["source_role"])
    return bucket


def _empty_bucket():
    return {"value": Decimal("0"), "count": 0, "refs": [], "sources": set()}


def _add(bucket, value, source_ref, source_role):
    bucket["value"] += value
    bucket["count"] += 1
    bucket["sources"].add(source_role)
    if source_ref and len(bucket["refs"]) < 12:
        bucket["refs"].append(source_ref)


def _bucket_value(bucket):
    return None if not bucket["count"] else _number(bucket["value"])


def _bucket_evidence(bucket):
    if not bucket["count"]:
        return None
    return {
        "source_roles": sorted(bucket["sources"]),
        "source_refs": bucket["refs"],
        "source_record_count": bucket["count"],
        "aggregation": "sum",
    }


def _share(part, total):
    if not part["count"] or not total["count"] or total["value"] == 0:
        return None
    return _number(part["value"] / total["value"])


def _standard_yoy(current, previous):
    if not previous["count"] or not current["count"] or previous["value"] == 0:
        return None
    return _number(current["value"] / previous["value"] - Decimal("1"))


def _completion(actual, forecast):
    if not actual["count"] or not forecast["count"] or forecast["value"] == 0:
        return None
    return _number(actual["value"] / forecast["value"])


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


def _is_excluded_maker_technology(value):
    normalized = _text(value).lower().replace("_", " ").replace("-", " ")
    return any(name in normalized for name in EXCLUDED_MAKER_TECHNOLOGIES)


def _number(value):
    rounded = value.quantize(Decimal("0.000001"))
    return int(rounded) if rounded == rounded.to_integral() else float(rounded)


def _maker_matches(value, maker):
    actual = _text(value).lower().replace("_", " ").replace("-", " ")
    target = _text(maker).lower().replace("_", " ").replace("-", " ")
    return actual == target or (target == "csot" and actual in {"china star", "csot"})


def _slug(value):
    return "".join(character.lower() if character.isalnum() else "_" for character in str(value)).strip("_")
