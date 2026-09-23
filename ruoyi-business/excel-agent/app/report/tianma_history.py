"""Deterministic maker front-install shipment, area, and share history."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any

from app.report.periods import (
    BASE_PERIODS,
    MIN_YEAR,
    SUPPORTED_YEARS,
    clip_periods_to_data_through,
    full_year_period,
    is_full_year_through,
    parse_period_key,
    periods_for_year_quarters,
    prior_year_quarter_period,
    q1_q3_period,
    quarter_period,
    summary_pair_from_data_through,
    year_label,
    year_quarters_from_rows,
)


YEARS = SUPPORTED_YEARS
PERIODS = BASE_PERIODS
EXCLUDED_MAKER_TECHNOLOGIES = ("oxide",)


def calculate_tianma_history_metrics(
    current_records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | None = None,
    maker: str = "Tianma",
    data_through_year: int | None = None,
    data_through_quarter: int | None = None,
    full_year: bool | None = None,
) -> dict[str, Any]:
    current = [_normalize(record, "current") for record in current_records]
    baseline = [_normalize(record, "baseline") for record in (baseline_records or [])]
    rows = [row for row in current if row is not None and row["year"] in set(YEARS) - {2022}]
    rows.extend(row for row in baseline if row is not None and row["year"] == 2022)
    maker_measure_rows = [row for row in rows if not row["excluded_from_maker_metrics"]]
    pair = summary_pair_from_data_through(data_through_year, data_through_quarter)
    current_year = int(pair["current_year"])
    prior_year = int(pair["prior_year"])
    fy_key = pair["full_year_period"]
    q13_key = pair["q1_q3_period"]
    if full_year is None:
        full_year = is_full_year_through(
            data_through_year, data_through_quarter, rows=rows, current_year=current_year,
        )
    else:
        full_year = bool(full_year)
    primary_period = fy_key if full_year else q13_key
    through_year = data_through_year if data_through_year is not None else current_year
    through_quarter = data_through_quarter if data_through_quarter is not None else (4 if full_year else 3)
    active_periods = clip_periods_to_data_through(
        periods_for_year_quarters(year_quarters_from_rows(rows), current_year=current_year),
        through_year,
        through_quarter,
    )

    gaps = []
    if not baseline_records:
        gaps.append(f"未提供1Q25 with 4Q24 Results基准文件，{maker} Y22指标暂缺")
    elif not any(row["year"] == 2022 for row in rows):
        gaps.append("基准文件Pivot Cache中未找到符合筛选条件的2022数据")

    maker_key = _slug(maker)
    shipment = _measure_metric(
        maker_measure_rows, maker, "shipment", "thousand_units",
        f"{maker_key}.front_install.shipment", active_periods,
        current_year=current_year, prior_year=prior_year, fy_key=fy_key, q13_key=q13_key,
    )
    display_area = _measure_metric(
        maker_measure_rows, maker, "display_area", "square_meters",
        f"{maker_key}.front_install.display_area", active_periods,
        current_year=current_year, prior_year=prior_year, fy_key=fy_key, q13_key=q13_key,
    )
    shipment_share = _share_metric(
        maker_measure_rows, rows, maker, "shipment",
        f"{maker_key}.front_install.shipment_share", active_periods,
        current_year=current_year,
    )
    display_area_share = _share_metric(
        maker_measure_rows, rows, maker, "display_area",
        f"{maker_key}.front_install.display_area_share", active_periods,
        current_year=current_year,
    )
    return {
        "engine": "python_deterministic_v1",
        "title": f"{maker}前装出货、面积及市占率",
        "scope": {
            "original_specification": "Automobile monitor",
            "excluded_application": "Automobile monitor (Others)",
            "maker": maker,
            "years": sorted({row["year"] for row in rows}),
            "period_order": list(active_periods),
            "full_year": full_year,
            "current_year": current_year,
            "prior_year": prior_year,
            "primary_period": primary_period,
            "full_year_period": fy_key,
            "q1_q3_period": q13_key,
            "source_row_count": len(rows),
            "y22_source": "baseline_workbook",
            "y23_plus_source": "merged_current_workbooks",
            "maker_technology_scope": "exclude Oxide",
            "share_market_denominator_technology_scope": "all technologies",
        },
        "formulas": {
            "yoy": "current comparable period / previous comparable period - 1",
            "y25f_yoy": f"{fy_key} / {year_label(prior_year)} - 1",
            "y25q1_q3_yoy": f"{q13_key} / prior-year Q1-Q3 - 1",
            "quarter_yoy": "YxxQn / prior-year same quarter - 1",
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


def _measure_metric(
    rows, maker, measure, unit, metric_id, periods,
    current_year=2025, prior_year=2024, fy_key="Y25F", q13_key="Y25Q1-Q3",
):
    maker_rows = [row for row in rows if _maker_matches(row["maker"], maker)]
    buckets = _period_buckets(maker_rows, measure, periods, current_year=current_year, fy_key=fy_key, q13_key=q13_key)
    prior_q1_q3 = _selected_bucket(maker_rows, measure, prior_year, {1, 2, 3})
    prior_annual = year_label(prior_year)
    yoy_periods = {
        year_label(year): (
            None if year <= MIN_YEAR
            else _standard_yoy(buckets.get(year_label(year)), buckets.get(year_label(year - 1)))
        )
        for year in range(MIN_YEAR, current_year)
    }
    yoy_periods[fy_key] = _standard_yoy(buckets.get(fy_key), buckets.get(prior_annual))
    yoy_periods[q13_key] = _standard_yoy(buckets.get(q13_key), prior_q1_q3)
    comparison = {
        f"Y{str(prior_year)[-2:]}Q1-Q3": _bucket_value(prior_q1_q3),
        q13_key: _bucket_value(buckets.get(q13_key)),
    }
    for period in periods:
        if period in yoy_periods:
            continue
        peer = prior_year_quarter_period(period)
        if peer is None:
            continue
        prior_bucket = buckets.get(peer)
        if prior_bucket is None or not prior_bucket["count"]:
            parsed = parse_period_key(peer)
            if parsed and parsed[1] is not None:
                prior_bucket = _selected_bucket(maker_rows, measure, parsed[0], {parsed[1]})
        yoy_periods[period] = _standard_yoy(buckets.get(period), prior_bucket)
        comparison[period] = _bucket_value(buckets.get(period))
        if peer:
            comparison[peer] = _bucket_value(prior_bucket)
    fy_yoy = _standard_yoy(buckets.get(fy_key), buckets.get(prior_annual))
    q13_yoy = _standard_yoy(buckets.get(q13_key), prior_q1_q3)
    return {
        "metric_id": metric_id,
        "unit": unit,
        "periods": {period: _bucket_value(buckets.get(period)) for period in periods},
        "comparison_periods": comparison,
        "standard_y25f_yoy": fy_yoy,
        "yoy_primary": fy_yoy if fy_key in (periods or []) else q13_yoy,
        "yoy_2025_q1_q3_vs_2024_q1_q3": q13_yoy,
        "forecast_completion_y25_q1_q3": _completion(buckets.get(q13_key), buckets.get(fy_key)),
        "forecast_completion_primary": _completion(buckets.get(q13_key), buckets.get(fy_key)),
        "yoy_periods": yoy_periods,
        "evidence": {period: _bucket_evidence(buckets.get(period)) for period in periods},
        "comparison_evidence": {
            f"Y{str(prior_year)[-2:]}Q1-Q3": _bucket_evidence(prior_q1_q3),
            q13_key: _bucket_evidence(buckets.get(q13_key)),
        },
    }


def _share_metric(maker_measure_rows, market_rows, maker, measure, metric_id, periods, current_year=2025):
    fy_key = full_year_period(current_year)
    q13_key = q1_q3_period(current_year)
    maker_buckets = _period_buckets(
        [row for row in maker_measure_rows if _maker_matches(row["maker"], maker)],
        measure, periods, current_year=current_year, fy_key=fy_key, q13_key=q13_key,
    )
    market_buckets = _period_buckets(
        market_rows, measure, periods, current_year=current_year, fy_key=fy_key, q13_key=q13_key,
    )
    return {
        "metric_id": metric_id,
        "unit": "ratio",
        "periods": {
            period: _share(maker_buckets.get(period), market_buckets.get(period)) for period in periods
        },
        "evidence": {
            period: {
                "numerator": _bucket_evidence(maker_buckets.get(period)),
                "denominator": _bucket_evidence(market_buckets.get(period)),
            } for period in periods
        },
    }


def _period_buckets(rows, measure, periods, current_year=2025, fy_key="Y25F", q13_key="Y25Q1-Q3"):
    buckets = {period: _empty_bucket() for period in periods}
    for row in rows:
        value = row.get(measure)
        if value is None:
            continue
        if row["year"] < current_year:
            period = year_label(row["year"])
            if period in buckets:
                _add(buckets[period], value, row.get(f"{measure}_ref"), row["source_role"])
        elif row["year"] == current_year:
            if fy_key in buckets:
                _add(buckets[fy_key], value, row.get(f"{measure}_ref"), row["source_role"])
            if row["quarter"] in {1, 2, 3} and q13_key in buckets:
                _add(buckets[q13_key], value, row.get(f"{measure}_ref"), row["source_role"])
        else:
            period = quarter_period(row["year"], row["quarter"])
            if period in buckets:
                _add(buckets[period], value, row.get(f"{measure}_ref"), row["source_role"])
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


def _share(part, total):
    if not part or not total or not part["count"] or not total["count"] or total["value"] == 0:
        return None
    return _number(part["value"] / total["value"])


def _standard_yoy(current, previous):
    if not previous or not current or not previous["count"] or not current["count"] or previous["value"] == 0:
        return None
    return _number(current["value"] / previous["value"] - Decimal("1"))


def _completion(actual, forecast):
    if not actual or not forecast or not actual["count"] or not forecast["count"] or forecast["value"] == 0:
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
