"""Deterministic competitive-insight metrics calculated from extracted cell facts."""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation
from typing import Any


TARGET_MAKERS = ("Tianma", "AUO", "CSOT", "BOE")
HISTORY_YEARS = (2022, 2023, 2024, 2025, 2026)
SUMMARY_YEARS = (2024, 2025)
SUMMARY_QUARTERS = {1, 2, 3}
SUMMARY_QUARTERS_FULL_YEAR = {1, 2, 3, 4}
MAKER_ALIASES = {
    "tianma": "Tianma",
    "auo": "AUO",
    "boe": "BOE",
    "china star": "CSOT",
    "china_star": "CSOT",
    "csot": "CSOT",
}


def _detect_summary_mode(rows: list[dict[str, Any]]) -> tuple[set[int], bool, str, int]:
    """按解析后的实际季度完备度自动选择汇总口径。

    规则（优先较新年份）：
    - 某 SUMMARY_YEAR 的 Q1–Q4 全部出现 → 该年全年汇总
    - 否则若 Q1–Q3 齐全 → 该年前三季度汇总
    不依赖文件名；文件名仅用于发布滞后说明与列裁剪。
    """
    by_year: dict[int, set[int]] = {}
    for row in rows:
        year = row.get("year")
        quarter = row.get("quarter")
        if year in SUMMARY_YEARS and quarter in SUMMARY_QUARTERS_FULL_YEAR:
            by_year.setdefault(int(year), set()).add(int(quarter))

    for year in sorted(SUMMARY_YEARS, reverse=True):
        qs = by_year.get(year, set())
        yy = year % 100
        if SUMMARY_QUARTERS_FULL_YEAR <= qs:
            return set(SUMMARY_QUARTERS_FULL_YEAR), True, f"y{yy}_full_year", year
        if SUMMARY_QUARTERS <= qs:
            return set(SUMMARY_QUARTERS), False, f"y{yy}_q1_q3", year

    return set(SUMMARY_QUARTERS), False, "y25_q1_q3", 2025


def calculate_competitive_metrics(tables: list[dict[str, Any]]) -> dict[str, Any]:
    """Calculate all quantitative report inputs without LLM arithmetic."""
    rows = []
    source_tables = []
    has_specification_field = False
    for table in tables:
        records = table.get("records") or []
        if not records or not _has_core_fields(records[0]):
            continue
        source_tables.append({
            "sheet": table.get("sheet"),
            "table": table.get("table"),
            "range": table.get("range"),
            "record_count": len(records),
        })
        for record in records:
            has_specification_field = has_specification_field or _fact(
                record, "original_specification", "specification"
            ) is not None
            normalized = _normalize_record(record, table)
            if normalized is not None:
                rows.append(normalized)

    gaps = []
    if not rows:
        gaps.append("未找到同时包含年份、季度、面板厂和出货量的明细表")
    if source_tables and not has_specification_field:
        gaps.append("源明细缺少Original Specification字段，无法按Automobile monitor口径计算")
    if rows and not any(row.get("size") is not None for row in rows):
        gaps.append("源明细缺少尺寸字段，无法计算尺寸分组指标")
    if rows and not any(row.get("region") for row in rows):
        gaps.append("源明细缺少区域字段，无法计算区域指标")
    if rows and not any(row.get("display_area") is not None for row in rows):
        gaps.append("源明细缺少显示面积字段，无法计算显示面积指标")

    summary_quarters, full_year, summary_mode, summary_target_year = _detect_summary_mode(rows)
    summary_rows = [
        row for row in rows
        if row["year"] in SUMMARY_YEARS and row["quarter"] in summary_quarters
    ]
    market = _group(summary_rows, lambda row: (row["year"],))
    maker = _group(summary_rows, lambda row: (row["maker"], row["year"]))
    technology = _group(summary_rows, lambda row: (row["maker"], row["year"], row["technology"]))
    market_technology = _group(summary_rows, lambda row: (row["year"], row["technology"]))
    clients = _group(summary_rows, lambda row: (row["maker"], row["year"], row["client"] or "未定义"))
    applications = _group(summary_rows, lambda row: (row["maker"], row["year"], row["application"] or "未定义"))
    history_maker = _group(rows, lambda row: (row["maker"], row["year"]))
    sizes = _group(
        [row for row in summary_rows if row.get("size") is not None],
        lambda row: (row["maker"], row["year"], _size_bucket(row["size"])),
    )
    maker_technology_size = _group(
        [row for row in summary_rows if row.get("size") is not None],
        lambda row: (row["maker"], row["year"], row["technology"], _size_bucket(row["size"])),
    )
    market_technology_size = _group(
        [row for row in summary_rows if row.get("size") is not None],
        lambda row: (row["year"], row["technology"], _size_bucket(row["size"])),
    )

    market_metrics = _market_metrics(market, full_year=full_year)
    makers = {}
    for maker_name in TARGET_MAKERS:
        makers[maker_name] = {
            "shipment": _maker_shipment_metrics(maker_name, maker, market, full_year=full_year),
            "historical_shipment": _historical_shipment_metrics(maker_name, history_maker, maker),
            "technology": _dimension_metrics(
                maker_name, technology, maker, "technology", market_groups=market_technology
            ),
            "clients": _dimension_metrics(maker_name, clients, maker, "client", top_n=10),
            "applications": _dimension_metrics(maker_name, applications, maker, "application", top_n=10),
            "sizes": _dimension_metrics(maker_name, sizes, maker, "size_bucket") if sizes else [],
            "technology_sizes": _technology_size_metrics(
                maker_name, maker_technology_size, maker, technology,
                market_technology, market_technology_size,
            ) if maker_technology_size else [],
        }

    return {
        "engine": "python_deterministic_v1",
        "scope": {
            "summary_years": list(SUMMARY_YEARS),
            "historical_years": list(HISTORY_YEARS),
            "quarters": [f"Q{q}" for q in sorted(summary_quarters)],
            "summary_mode": summary_mode,
            "summary_target_year": summary_target_year,
            "full_year": full_year,
            "original_specification": "Automobile monitor",
            "makers": list(TARGET_MAKERS),
            "market_maker_scope": "all_source_makers",
            "source_row_count": len(rows),
            "summary_row_count": len(summary_rows),
            "source_tables": source_tables,
        },
        "formulas": {
            "yoy": "value_2025 / value_2024 - 1",
            "market_share": "maker_shipment / market_shipment",
            "maker_internal_share": "maker_segment_shipment / maker_total_shipment",
            "segment_share": "market_segment_shipment / total_market_shipment",
            "total_market_share": "market_segment_shipment / total_market_shipment",
            "technology_internal_share": "market_segment_shipment / market_technology_shipment",
            "technology_size_maker_internal_share": "maker_segment_shipment / maker_total_shipment",
            "segment_market_share": "maker_segment_shipment / market_same_technology_size_shipment",
            "technology_market_share": "maker_segment_shipment / market_technology_shipment",
            "same_size_market_share": "maker_segment_shipment / market_segment_shipment",
        },
        "market": market_metrics,
        "summary_matrix": _summary_matrix(
            market, maker, technology, market_technology,
            maker_technology_size, market_technology_size,
            full_year=full_year,
        ),
        "makers": makers,
        "data_gaps": gaps,
    }


def _has_core_fields(record: dict[str, Any]) -> bool:
    return (
        _fact(record, "year") is not None
        and _fact(record, "panel_maker", "manufacturer", "maker") is not None
        and _fact(record, "quantity_000", "shipment", "qty_000") is not None
    )


def _normalize_record(record: dict[str, Any], table: dict[str, Any]) -> dict[str, Any] | None:
    year = _integer(_value(_fact(record, "year")))
    quarter = _quarter(_value(_fact(record, "quarter")))
    maker = _maker(_value(_fact(record, "panel_maker", "manufacturer", "maker")))
    quantity_fact = _fact(record, "quantity_000", "shipment", "qty_000")
    quantity = _decimal(_value(quantity_fact))
    specification = str(_value(_fact(record, "original_specification", "specification")) or "").strip()
    application = _text(_value(_fact(record, "application", "product")))
    technology = _technology(_value(_fact(record, "technology")))
    if year not in HISTORY_YEARS or quarter not in {1, 2, 3, 4} or maker is None or quantity is None:
        return None
    if specification.lower() != "automobile monitor":
        return None
    if "automobile monitor" in application.lower() and "others" in application.lower():
        return None
    if technology not in {"LTPS", "a-Si"}:
        return None
    return {
        "year": year,
        "quarter": quarter,
        "maker": maker,
        "quantity": quantity,
        "technology": technology,
        "client": _text(_value(_fact(record, "client", "customer"))),
        "application": application,
        "region": _text(_value(_fact(record, "region", "area"))),
        "size": _decimal(_value(_fact(record, "size", "display_size", "diagonal_size"))),
        "display_area": _decimal(_value(_fact(record, "display_area"))),
        "evidence": {
            "sheet": (quantity_fact or {}).get("source_sheet") or table.get("sheet"),
            "cell": (quantity_fact or {}).get("source_cell"),
            "value": _number(quantity),
        },
    }


def _group(rows, key_function):
    groups = defaultdict(lambda: {"value": Decimal("0"), "cells": [], "sheet": None, "count": 0})
    for row in rows:
        key = key_function(row)
        bucket = groups[key]
        bucket["value"] += row["quantity"]
        bucket["count"] += 1
        evidence = row["evidence"]
        bucket["sheet"] = bucket["sheet"] or evidence.get("sheet")
        cell = evidence.get("cell")
        if cell and len(bucket["cells"]) < 12:
            bucket["cells"].append(cell)
    return groups


def _market_metrics(market, full_year: bool = False):
    values = {year: market.get((year,)) for year in SUMMARY_YEARS}
    suffix = "y25_full_year" if full_year else "y25q1_q3"
    return {
        "metric_id": f"market.shipment.{suffix}",
        "unit": "thousand_units",
        "summary_mode": "full_year" if full_year else "q1_q3",
        "values": {str(year): _metric_value(values[year]) for year in SUMMARY_YEARS},
        "yoy_2025_vs_2024": _ratio(values[2025], values[2024]),
        "evidence": {str(year): _evidence(values[year], "sum") for year in SUMMARY_YEARS},
    }


def _maker_shipment_metrics(maker_name, maker, market, full_year: bool = False):
    values = {year: maker.get((maker_name, year)) for year in SUMMARY_YEARS}
    suffix = "y25_full_year" if full_year else "y25q1_q3"
    return {
        "metric_id": f"maker.{_slug(maker_name)}.shipment.{suffix}",
        "unit": "thousand_units",
        "summary_mode": "full_year" if full_year else "q1_q3",
        "values": {str(year): _metric_value(values[year]) for year in SUMMARY_YEARS},
        "yoy_2025_vs_2024": _ratio(values[2025], values[2024]),
        "market_share": {
            str(year): _share(values[year], market.get((year,))) for year in SUMMARY_YEARS
        },
        "evidence": {str(year): _evidence(values[year], "sum") for year in SUMMARY_YEARS},
    }


def _historical_shipment_metrics(maker_name, history, summary):
    annual = {year: history.get((maker_name, year)) for year in HISTORY_YEARS}
    q1_q3_2025 = summary.get((maker_name, 2025))
    return {
        "metric_id": f"maker.{_slug(maker_name)}.shipment.history",
        "unit": "thousand_units",
        "annual_values": {str(year): _metric_value(annual[year]) for year in HISTORY_YEARS},
        "y25_q1_q3": _metric_value(q1_q3_2025),
        "yoy_2025_vs_2024": _ratio(annual[2025], annual[2024]),
        "evidence": {str(year): _evidence(annual[year], "sum") for year in HISTORY_YEARS},
    }


def _dimension_metrics(
    maker_name, groups, maker_totals, dimension_name, top_n: int | None = None, market_groups=None
):
    dimensions = sorted({key[2] for key in groups if key[0] == maker_name})
    if top_n is not None:
        dimensions.sort(key=lambda item: (groups.get((maker_name, 2025, item)) or {}).get("value", 0), reverse=True)
        dimensions = dimensions[:top_n]
    result = []
    for dimension in dimensions:
        values = {year: groups.get((maker_name, year, dimension)) for year in SUMMARY_YEARS}
        metric = {
            "metric_id": f"maker.{_slug(maker_name)}.{dimension_name}.{_slug(dimension)}",
            dimension_name: dimension,
            "unit": "thousand_units",
            "values": {str(year): _metric_value(values[year]) for year in SUMMARY_YEARS},
            "yoy_2025_vs_2024": _ratio(values[2025], values[2024]),
            "maker_internal_share": {
                str(year): _share(values[year], maker_totals.get((maker_name, year))) for year in SUMMARY_YEARS
            },
            "evidence": {str(year): _evidence(values[year], "sum") for year in SUMMARY_YEARS},
        }
        if market_groups is not None:
            metric["segment_market_share"] = {
                str(year): _share(values[year], market_groups.get((year, dimension))) for year in SUMMARY_YEARS
            }
        result.append(metric)
    return result


def _technology_size_metrics(
    maker_name, groups, maker_totals, maker_technology, market_technology, market_segments
):
    combinations = sorted({(key[2], key[3]) for key in groups if key[0] == maker_name})
    result = []
    for technology, size_bucket in combinations:
        values = {
            year: groups.get((maker_name, year, technology, size_bucket)) for year in SUMMARY_YEARS
        }
        result.append({
            "metric_id": f"maker.{_slug(maker_name)}.technology_size.{_slug(technology)}.{_slug(size_bucket)}",
            "technology": technology,
            "size_bucket": size_bucket,
            "unit": "thousand_units",
            "values": {str(year): _metric_value(values[year]) for year in SUMMARY_YEARS},
            "yoy_2025_vs_2024": _ratio(values[2025], values[2024]),
            "maker_internal_share": {
                str(year): _share(values[year], maker_totals.get((maker_name, year)))
                for year in SUMMARY_YEARS
            },
            "segment_market_share": {
                str(year): _share(values[year], market_segments.get((year, technology, size_bucket)))
                for year in SUMMARY_YEARS
            },
            "technology_market_share": {
                str(year): _share(values[year], market_technology.get((year, technology)))
                for year in SUMMARY_YEARS
            },
            "same_size_market_share": {
                str(year): _share(values[year], market_segments.get((year, technology, size_bucket)))
                for year in SUMMARY_YEARS
            },
            "evidence": {str(year): _evidence(values[year], "sum") for year in SUMMARY_YEARS},
        })
    return result


def _summary_matrix(market, maker, maker_technology, market_technology, maker_segments, market_segments, full_year: bool = False):
    """Build the Y25 Summary matrix (Q1-Q3 or full year depending on data through)."""
    rows = []
    size_labels = (("<8", '8”以下'), ("[8,12)", '8”-12”'), ("[12,15)", '12”-15”'), (">=15", '15”以上'))
    for technology_name in ("LTPS", "a-Si"):
        for size_bucket, size_label in size_labels:
            market_values = {
                year: market_segments.get((year, technology_name, size_bucket)) for year in SUMMARY_YEARS
            }
            maker_values = {
                maker_name: {
                    year: maker_segments.get((maker_name, year, technology_name, size_bucket))
                    for year in SUMMARY_YEARS
                }
                for maker_name in TARGET_MAKERS
            }
            rows.append(_summary_row(
                technology_name, size_bucket, size_label, False,
                market_values, maker_values, market, maker,
                market_technology, maker_technology, market_values,
            ))

        market_values = {
            year: market_technology.get((year, technology_name)) for year in SUMMARY_YEARS
        }
        maker_values = {
            maker_name: {
                year: maker_technology.get((maker_name, year, technology_name))
                for year in SUMMARY_YEARS
            }
            for maker_name in TARGET_MAKERS
        }
        rows.append(_summary_row(
            technology_name, "total", f"{technology_name} Total", True,
            market_values, maker_values, market, maker,
            market_technology, maker_technology, market_values,
        ))

    market_values = {year: market.get((year,)) for year in SUMMARY_YEARS}
    maker_values = {
        maker_name: {year: maker.get((maker_name, year)) for year in SUMMARY_YEARS}
        for maker_name in TARGET_MAKERS
    }
    rows.append(_summary_row(
        "All", "all", "All", True, market_values, maker_values,
        market, maker, market_technology, maker_technology, market_values,
    ))
    suffix = "y25_full_year" if full_year else "y25q1_q3"
    title = "Y25全年 Summary" if full_year else "Y25前三季度 Summary"
    return {
        "metric_id": f"market.{suffix}.summary_matrix",
        "title": title,
        "summary_mode": "full_year" if full_year else "q1_q3",
        "row_order": [row["row_key"] for row in rows],
        "rows": rows,
    }


def _summary_row(
    technology_name, size_bucket, label, is_total, market_values, maker_values,
    market, maker, market_technology, maker_technology, market_segment_values,
):
    row_key = f"{_slug(technology_name)}.{_slug(size_bucket)}"
    market_columns = {
        "metric_id": f"market.summary.{row_key}",
        "values": {str(year): _metric_value(market_values[year]) for year in SUMMARY_YEARS},
        "yoy_2025_vs_2024": _ratio(market_values[2025], market_values[2024]),
        # 最终报告第3页口径：市场尺寸细分 / LTPS+a-Si全市场总量。
        "segment_share": {
            str(year): _share(market_values[year], market.get((year,)))
            for year in SUMMARY_YEARS
        },
        "technology_internal_share": {
            str(year): _share(market_values[year], market_technology.get((year, technology_name)))
            for year in SUMMARY_YEARS
        },
        "total_market_share": {
            str(year): _share(market_values[year], market.get((year,))) for year in SUMMARY_YEARS
        },
        "evidence": {str(year): _evidence(market_values[year], "sum") for year in SUMMARY_YEARS},
    }
    if technology_name == "All":
        market_columns["segment_share"] = {
            str(year): _share(market_values[year], market.get((year,))) for year in SUMMARY_YEARS
        }
        market_columns["technology_internal_share"] = dict(market_columns["segment_share"])

    maker_columns = {}
    for maker_name in TARGET_MAKERS:
        values = maker_values[maker_name]
        # 最终报告“内部占比”以Maker全部技术出货量为分母。
        internal_denominators = {year: maker.get((maker_name, year)) for year in SUMMARY_YEARS}
        maker_columns[maker_name] = {
            "metric_id": f"maker.{_slug(maker_name)}.summary.{row_key}",
            "values": {str(year): _metric_value(values[year]) for year in SUMMARY_YEARS},
            "yoy_2025_vs_2024": _ratio(values[2025], values[2024]),
            "growth_contribution_2025_vs_2024": _growth_contribution(
                values[2025], values[2024],
                internal_denominators[2025], internal_denominators[2024],
            ),
            "internal_share": {
                str(year): _share(values[year], internal_denominators[year]) for year in SUMMARY_YEARS
            },
            "segment_market_share": {
                str(year): _share(values[year], market_segment_values[year])
                for year in SUMMARY_YEARS
            },
            "technology_market_share": {
                str(year): _share(
                    values[year], market.get((year,)) if technology_name == "All"
                    else market_technology.get((year, technology_name))
                ) for year in SUMMARY_YEARS
            },
            "same_size_market_share": {
                str(year): _share(values[year], market_segment_values[year]) for year in SUMMARY_YEARS
            },
            "evidence": {str(year): _evidence(values[year], "sum") for year in SUMMARY_YEARS},
        }
    return {
        "row_key": row_key,
        "technology": technology_name,
        "size_bucket": size_bucket,
        "label": label,
        "is_total": is_total,
        "market": market_columns,
        "makers": maker_columns,
    }


def _fact(record: dict[str, Any], *names: str):
    for name in names:
        fact = record.get(name)
        if isinstance(fact, dict):
            return fact
    return None


def _value(fact):
    return fact.get("value") if isinstance(fact, dict) else None


def _maker(value) -> str | None:
    text = _text(value)
    if not text:
        return None
    # 市场汇总必须保留所有Maker；仅对报告关注厂商做名称归一化。
    return MAKER_ALIASES.get(text.lower(), text)


def _quarter(value) -> int | None:
    text = _text(value).upper().replace("QUARTER", "Q").replace(" ", "")
    for number in {1, 2, 3, 4}:
        if text in {str(number), f"Q{number}", f"{number}Q"}:
            return number
    return None


def _technology(value) -> str:
    text = _text(value).lower()
    if "ltps" in text:
        return "LTPS"
    if "a-si" in text or "a_si" in text or "asi" == text:
        return "a-Si"
    return ""


def _integer(value) -> int | None:
    try:
        return int(float(str(value)))
    except (TypeError, ValueError):
        return None


def _decimal(value) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value).replace(",", "").strip())
    except (InvalidOperation, ValueError):
        return None


def _text(value) -> str:
    return "" if value is None else str(value).strip()


def _metric_value(bucket):
    return None if not bucket else _number(bucket["value"])


def _ratio(current, previous):
    if not current or not previous or previous["value"] == 0:
        return None
    return _number(current["value"] / previous["value"] - Decimal("1"))


def _growth_contribution(current, previous, total_current, total_previous):
    if not current or not previous or not total_current or not total_previous:
        return None
    total_delta = total_current["value"] - total_previous["value"]
    if total_delta == 0:
        return None
    return _number((current["value"] - previous["value"]) / total_delta)


def _share(part, total):
    if not part or not total or total["value"] == 0:
        return None
    return _number(part["value"] / total["value"])


def _evidence(bucket, aggregation):
    if not bucket:
        return None
    return {
        "sheet": bucket["sheet"],
        "cells": bucket["cells"],
        "source_record_count": bucket["count"],
        "aggregation": aggregation,
    }


def _size_bucket(value: Decimal) -> str:
    if value < 8:
        return "<8"
    if value < 12:
        return "[8,12)"
    if value < 15:
        return "[12,15)"
    return ">=15"


def _number(value: Decimal):
    rounded = value.quantize(Decimal("0.000001"))
    return int(rounded) if rounded == rounded.to_integral() else float(rounded)


def _slug(value: str) -> str:
    return "_".join(str(value).lower().replace("/", " ").replace("-", " ").split())
