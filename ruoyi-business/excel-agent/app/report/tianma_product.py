"""Deterministic maker technology and size shipment metrics."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any


YEARS = (2022, 2023, 2024, 2025)
PERIODS = ("Y22", "Y23", "Y24", "Y25F", "Y25Q1-Q3")
TECHNOLOGIES = ("LTPS", "a-Si")
SIZE_BUCKETS = (
    ("<8", '8”以下'),
    ("[8,12)", '8”-12”'),
    ("[12,15)", '12”-15”'),
    (">=15", '15”以上'),
)


def calculate_tianma_product_metrics(
    current_records: list[dict[str, Any]],
    baseline_records: list[dict[str, Any]] | None = None,
    maker: str = "Tianma",
) -> dict[str, Any]:
    """Calculate one maker's technology and size section without LLM arithmetic."""
    current = [_normalize(record, "current", maker) for record in current_records]
    baseline = [_normalize(record, "baseline", maker) for record in (baseline_records or [])]
    rows = [row for row in current if row is not None and row["year"] in {2023, 2024, 2025}]
    rows.extend(row for row in baseline if row is not None and row["year"] == 2022)

    gaps = []
    if not baseline_records:
        gaps.append(f"未提供1Q25 with 4Q24 Results基准文件，{maker}技术别Y22指标暂缺")
    elif not any(row["year"] == 2022 for row in rows):
        gaps.append(f"基准文件Pivot Cache中未找到符合筛选条件的{maker}技术别2022数据")
    if not any(row.get("size") is not None for row in rows):
        gaps.append(f"符合筛选条件的{maker}记录缺少Size，无法生成尺寸分布与尺寸别增长")

    maker_key = _slug(maker)

    return {
        "engine": "python_deterministic_v1",
        "title": f"{maker}技术别出货与尺寸分布",
        "scope": {
            "source_measure": "Shipments (000s)",
            "original_specification": "Automobile monitor",
            "excluded_application": "Automobile monitor (Others)",
            "maker": maker,
            "technologies": list(TECHNOLOGIES),
            "years": list(YEARS),
            "period_order": list(PERIODS),
            "y25q1_q3_quarters": ["Q1", "Q2", "Q3"],
            "y22_source": "baseline_workbook",
            "y23_y25_source": "current_workbook",
            "source_row_count": len(rows),
        },
        "formulas": {
            "yoy": "current comparable period / previous comparable period - 1",
            "y25f_yoy": "Y25F / Y24 - 1",
            "y25q1_q3_yoy": "Y25 Q1-Q3 / Y24 Q1-Q3 - 1",
            "size_buckets": ["Size < 8", "8 <= Size < 12", "12 <= Size < 15", "Size >= 15"],
        },
        "technology_history": {
            technology: _technology_metric(rows, technology, maker_key) for technology in TECHNOLOGIES
        },
        "y25q1_q3_size_distribution": _exact_size_distribution(rows, maker_key),
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


def _technology_metric(rows, technology, maker_key):
    selected = [row for row in rows if row["technology"] == technology]
    buckets = _period_buckets(selected)
    prior_q1_q3 = _selected_bucket(selected, 2024, {1, 2, 3})
    return _period_metric(
        buckets, prior_q1_q3, f"{maker_key}.technology.{_slug(technology)}.shipment"
    )


def _technology_size_metric(rows, technology, maker_key):
    selected = [row for row in rows if row["technology"] == technology and row["size"] is not None]
    series = []
    for bucket_name, label in SIZE_BUCKETS:
        bucket_rows = [row for row in selected if _size_bucket(row["size"]) == bucket_name]
        buckets = _period_buckets(bucket_rows)
        prior_q1_q3 = _selected_bucket(bucket_rows, 2024, {1, 2, 3})
        metric = _period_metric(
            buckets,
            prior_q1_q3,
            f"{maker_key}.technology_size.{_slug(technology)}.{_slug(bucket_name)}",
        )
        metric.update({"size_bucket": bucket_name, "label": label})
        series.append(metric)
    return {
        "metric_id": f"{maker_key}.technology_size.{_slug(technology)}",
        "technology": technology,
        "unit": "thousand_units",
        "period_order": list(PERIODS),
        "series": series,
    }


def _exact_size_distribution(rows, maker_key):
    selected = [
        row for row in rows
        if row["year"] == 2025 and row["quarter"] in {1, 2, 3} and row["size"] is not None
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
    return {
        "metric_id": f"{maker_key}.size_distribution.y25q1_q3",
        "unit": "thousand_units",
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


def _period_metric(buckets, prior_q1_q3, metric_id):
    return {
        "metric_id": metric_id,
        "unit": "thousand_units",
        "periods": {period: _bucket_value(buckets[period]) for period in PERIODS},
        "standard_y25f_yoy": _standard_yoy(buckets["Y25F"], buckets["Y24"]),
        "yoy_periods": {
            "Y22": None,
            "Y23": _standard_yoy(buckets["Y23"], buckets["Y22"]),
            "Y24": _standard_yoy(buckets["Y24"], buckets["Y23"]),
            "Y25F": _standard_yoy(buckets["Y25F"], buckets["Y24"]),
            "Y25Q1-Q3": _standard_yoy(buckets["Y25Q1-Q3"], prior_q1_q3),
        },
        "evidence": {period: _bucket_evidence(buckets[period]) for period in PERIODS},
    }


def _period_buckets(rows):
    buckets = {period: _empty_bucket() for period in PERIODS}
    for row in rows:
        period = f"Y{str(row['year'])[-2:]}" if row["year"] < 2025 else "Y25F"
        _add(buckets[period], row)
        if row["year"] == 2025 and row["quarter"] in {1, 2, 3}:
            _add(buckets["Y25Q1-Q3"], row)
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


def _standard_yoy(current, previous):
    if not current["count"] or not previous["count"] or previous["value"] == 0:
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
