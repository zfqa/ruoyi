"""Deterministic competitive-insight metrics calculated from extracted cell facts."""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation
from typing import Any

from app.report.periods import (
    MIN_YEAR,
    MAX_YEAR,
    summary_pair_from_data_through,
)


TARGET_MAKERS = ("Tianma", "AUO", "CSOT", "BOE")
MARKET_TECHNOLOGIES = {"LTPS", "a-Si", "Oxide"}
MAKER_TECHNOLOGIES = {"LTPS", "a-Si"}
# Default pair kept for imports/tests that still reference the constant name.
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


def _history_years_for(current_year: int) -> tuple[int, ...]:
    end = max(int(current_year) + 1, 2026)
    return tuple(range(MIN_YEAR, min(end, MAX_YEAR) + 1))


def _detect_summary_mode(
    rows: list[dict[str, Any]],
    data_through_year: int | None = None,
    data_through_quarter: int | None = None,
) -> tuple[set[int], bool, str, int, int, tuple[int, int]]:
    """选择汇总口径，并返回 (quarters, full_year, mode, current, prior, summary_years).

    有 Omdia 文件名截止季时，以截止季为准（例如 with 3Q25 Results → 前三季度；
    1Q26 发布 → 截止 4Q25 → 全年）。Pivot 里可能已有晚于截止季的预测季，不能因此提前切全年。
    """
    if data_through_year is not None and data_through_quarter is not None:
        pair = summary_pair_from_data_through(data_through_year, data_through_quarter)
        current = int(pair["current_year"])
        prior = int(pair["prior_year"])
        summary_years = tuple(pair["summary_years"])
        quarter = int(pair["data_through_quarter"])
        yy = current % 100
        if quarter >= 4:
            return set(SUMMARY_QUARTERS_FULL_YEAR), True, f"y{yy}_full_year", current, prior, summary_years
        if quarter >= 3:
            return set(SUMMARY_QUARTERS), False, f"y{yy}_q1_q3", current, prior, summary_years
        return set(range(1, quarter + 1)), False, f"y{yy}_q1_q{quarter}", current, prior, summary_years

    by_year: dict[int, set[int]] = {}
    for row in rows:
        year = row.get("year")
        quarter = row.get("quarter")
        if year is None or quarter not in SUMMARY_QUARTERS_FULL_YEAR:
            continue
        by_year.setdefault(int(year), set()).add(int(quarter))

    for year in sorted(by_year, reverse=True):
        qs = by_year.get(year, set())
        yy = year % 100
        prior = year - 1
        summary_years = (prior, year)
        if SUMMARY_QUARTERS_FULL_YEAR <= qs:
            return set(SUMMARY_QUARTERS_FULL_YEAR), True, f"y{yy}_full_year", year, prior, summary_years
        if SUMMARY_QUARTERS <= qs:
            return set(SUMMARY_QUARTERS), False, f"y{yy}_q1_q3", year, prior, summary_years

    pair = summary_pair_from_data_through(2025, 3)
    return (
        set(SUMMARY_QUARTERS),
        False,
        "y25_q1_q3",
        int(pair["current_year"]),
        int(pair["prior_year"]),
        tuple(pair["summary_years"]),
    )


def _attach_yoy(metric: dict[str, Any], yoy_value) -> dict[str, Any]:
    """Primary yoy fields + legacy alias so old UI/export keep working."""
    metric["yoy"] = yoy_value
    metric["yoy_current_vs_prior"] = yoy_value
    metric["yoy_2025_vs_2024"] = yoy_value
    return metric


def calculate_competitive_metrics(
    tables: list[dict[str, Any]],
    data_through_year: int | None = None,
    data_through_quarter: int | None = None,
) -> dict[str, Any]:
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

    (
        summary_quarters, full_year, summary_mode, current_year, prior_year, summary_years,
    ) = _detect_summary_mode(rows, data_through_year, data_through_quarter)
    history_years = _history_years_for(current_year)
    pair = summary_pair_from_data_through(
        data_through_year if data_through_year is not None else current_year,
        data_through_quarter if data_through_quarter is not None else (4 if full_year else 3),
    )
    primary_period = pair["primary_period"]
    yy = current_year % 100

    summary_rows = [
        row for row in rows
        if row["year"] in summary_years and row["quarter"] in summary_quarters
    ]
    # 前装市场总量含 Oxide。厂商出货、面积和内部占比仍只计 LTPS 与 a-Si。
    maker_summary_rows = [row for row in summary_rows if row["technology"] in MAKER_TECHNOLOGIES]
    maker_history_rows = [row for row in rows if row["technology"] in MAKER_TECHNOLOGIES]
    market = _group(summary_rows, lambda row: (row["year"],))
    maker = _group(maker_summary_rows, lambda row: (row["maker"], row["year"]))
    technology = _group(maker_summary_rows, lambda row: (row["maker"], row["year"], row["technology"]))
    market_technology = _group(summary_rows, lambda row: (row["year"], row["technology"]))
    clients = _group(maker_summary_rows, lambda row: (row["maker"], row["year"], row["client"] or "未定义"))
    applications = _group(maker_summary_rows, lambda row: (row["maker"], row["year"], row["application"] or "未定义"))
    history_maker = _group(maker_history_rows, lambda row: (row["maker"], row["year"]))
    sizes = _group(
        [row for row in maker_summary_rows if row.get("size") is not None],
        lambda row: (row["maker"], row["year"], _size_bucket(row["size"])),
    )
    maker_technology_size = _group(
        [row for row in maker_summary_rows if row.get("size") is not None],
        lambda row: (row["maker"], row["year"], row["technology"], _size_bucket(row["size"])),
    )
    market_technology_size = _group(
        [row for row in summary_rows if row.get("size") is not None],
        lambda row: (row["year"], row["technology"], _size_bucket(row["size"])),
    )

    year_ctx = {
        "current_year": current_year,
        "prior_year": prior_year,
        "summary_years": summary_years,
        "history_years": history_years,
        "full_year": full_year,
        "yy": yy,
        "primary_period": primary_period,
    }

    market_metrics = _market_metrics(market, year_ctx)
    makers = {}
    for maker_name in TARGET_MAKERS:
        makers[maker_name] = {
            "shipment": _maker_shipment_metrics(maker_name, maker, market, year_ctx),
            "historical_shipment": _historical_shipment_metrics(maker_name, history_maker, maker, year_ctx),
            "technology": _dimension_metrics(
                maker_name, technology, maker, "technology", year_ctx, market_groups=market_technology
            ),
            "clients": _dimension_metrics(maker_name, clients, maker, "client", year_ctx, top_n=10),
            "applications": _dimension_metrics(
                maker_name, applications, maker, "application", year_ctx, top_n=10
            ),
            "sizes": _dimension_metrics(maker_name, sizes, maker, "size_bucket", year_ctx) if sizes else [],
            "technology_sizes": _technology_size_metrics(
                maker_name, maker_technology_size, maker, technology,
                market_technology, market_technology_size, year_ctx,
            ) if maker_technology_size else [],
        }

    horizon_kind = "full_year" if full_year else (
        "q1_q3" if summary_mode.endswith("q1_q3")
        else summary_mode.rsplit("_", 1)[-1] if "_q1_q" in summary_mode else "q1_q3"
    )
    return {
        "engine": "python_deterministic_v1",
        "scope": {
            "summary_years": list(summary_years),
            "historical_years": list(history_years),
            "current_year": current_year,
            "prior_year": prior_year,
            "primary_period": primary_period,
            "full_year_period": pair["full_year_period"],
            "q1_q3_period": pair["q1_q3_period"],
            "quarters": [f"Q{q}" for q in sorted(summary_quarters)],
            "summary_mode": summary_mode,
            "summary_target_year": current_year,
            "full_year": full_year,
            # Compat alias used by older exporters / UI checks.
            "full_year_2025": full_year and current_year == 2025,
            "data_through_year": data_through_year if data_through_year is not None else current_year,
            "data_through_quarter": (
                data_through_quarter if data_through_quarter is not None else (4 if full_year else 3)
            ),
            "report_horizon": pair.get("report_horizon") or horizon_kind,
            "horizon_kind": pair.get("horizon_kind") or horizon_kind,
            "header_period_label": pair.get("title_suffix") or (
                f"Y{yy}全年" if full_year else f"Y{yy}前三季度"
            ),
            "original_specification": "Automobile monitor",
            "makers": list(TARGET_MAKERS),
            "market_maker_scope": "all_source_makers",
            "market_technology_scope": "LTPS, a-Si, Oxide",
            "maker_technology_scope": "LTPS, a-Si",
            "source_row_count": len(rows),
            "summary_row_count": len(summary_rows),
            "source_tables": source_tables,
        },
        "formulas": {
            "yoy": f"value_{current_year} / value_{prior_year} - 1",
            "market_share": "maker_shipment / market_shipment",
            "maker_internal_share": "maker_segment_shipment / maker_total_shipment",
            "segment_share": "market_segment_shipment / (LTPS + a-Si market); Oxide excluded from share denominator",
            "total_market_share": "market_segment_shipment / (LTPS + a-Si market); Oxide excluded from share denominator",
            "technology_internal_share": "market_segment_shipment / market_technology_shipment",
            "technology_size_maker_internal_share": "maker_segment_shipment / maker_total_shipment",
            "segment_market_share": "maker_segment_shipment / market_same_technology_size_shipment",
            "technology_market_share": "maker_segment_shipment / market_technology_shipment (All row: LTPS+a-Si)",
            "same_size_market_share": "maker_segment_shipment / market_segment_shipment (All row: LTPS+a-Si)",
        },
        "market": market_metrics,
        "summary_matrix": _summary_matrix(
            market, maker, technology, market_technology,
            maker_technology_size, market_technology_size,
            year_ctx,
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
    technology = _technology(_value(_fact(record, "technology", "master_technology")))
    if year is None or year < MIN_YEAR or year > MAX_YEAR or quarter not in {1, 2, 3, 4} or maker is None or quantity is None:
        return None
    if specification.lower() != "automobile monitor":
        return None
    if "automobile monitor" in application.lower() and "others" in application.lower():
        return None
    if technology not in MARKET_TECHNOLOGIES:
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


def _group(rows, key_fn):
    groups = {}
    for row in rows:
        key = key_fn(row)
        bucket = groups.get(key)
        if bucket is None:
            bucket = {
                "value": Decimal("0"),
                "cells": [],
                "sheet": None,
                "count": 0,
            }
            groups[key] = bucket
        bucket["value"] += row["quantity"]
        bucket["count"] += 1
        evidence = row["evidence"]
        bucket["sheet"] = bucket["sheet"] or evidence.get("sheet")
        cell = evidence.get("cell")
        if cell and len(bucket["cells"]) < 12:
            bucket["cells"].append(cell)
    return groups


def _market_metrics(market, year_ctx):
    summary_years = year_ctx["summary_years"]
    current, prior = year_ctx["current_year"], year_ctx["prior_year"]
    values = {year: market.get((year,)) for year in summary_years}
    yy = year_ctx["yy"]
    suffix = f"y{yy}_full_year" if year_ctx["full_year"] else f"y{yy}q1_q3"
    metric = {
        "metric_id": f"market.shipment.{suffix}",
        "unit": "thousand_units",
        "summary_mode": "full_year" if year_ctx["full_year"] else "q1_q3",
        "values": {str(year): _metric_value(values[year]) for year in summary_years},
        "evidence": {str(year): _evidence(values[year], "sum") for year in summary_years},
    }
    return _attach_yoy(metric, _ratio(values.get(current), values.get(prior)))


def _maker_shipment_metrics(maker_name, maker, market, year_ctx):
    summary_years = year_ctx["summary_years"]
    current, prior = year_ctx["current_year"], year_ctx["prior_year"]
    values = {year: maker.get((maker_name, year)) for year in summary_years}
    yy = year_ctx["yy"]
    suffix = f"y{yy}_full_year" if year_ctx["full_year"] else f"y{yy}q1_q3"
    metric = {
        "metric_id": f"maker.{_slug(maker_name)}.shipment.{suffix}",
        "unit": "thousand_units",
        "summary_mode": "full_year" if year_ctx["full_year"] else "q1_q3",
        "values": {str(year): _metric_value(values[year]) for year in summary_years},
        "market_share": {
            str(year): _share(values[year], market.get((year,))) for year in summary_years
        },
        "evidence": {str(year): _evidence(values[year], "sum") for year in summary_years},
    }
    return _attach_yoy(metric, _ratio(values.get(current), values.get(prior)))


def _historical_shipment_metrics(maker_name, history, summary, year_ctx):
    history_years = year_ctx["history_years"]
    current, prior = year_ctx["current_year"], year_ctx["prior_year"]
    annual = {year: history.get((maker_name, year)) for year in history_years}
    current_summary = summary.get((maker_name, current))
    metric = {
        "metric_id": f"maker.{_slug(maker_name)}.shipment.history",
        "unit": "thousand_units",
        "annual_values": {str(year): _metric_value(annual[year]) for year in history_years},
        "y25_q1_q3": _metric_value(current_summary),
        "primary_summary": _metric_value(current_summary),
        "evidence": {str(year): _evidence(annual[year], "sum") for year in history_years},
    }
    return _attach_yoy(metric, _ratio(annual.get(current), annual.get(prior)))


def _dimension_metrics(
    maker_name, groups, maker_totals, dimension_name, year_ctx,
    top_n: int | None = None, market_groups=None,
):
    summary_years = year_ctx["summary_years"]
    current, prior = year_ctx["current_year"], year_ctx["prior_year"]
    dimensions = sorted({key[2] for key in groups if key[0] == maker_name})
    if top_n is not None:
        dimensions.sort(
            key=lambda item: (groups.get((maker_name, current, item)) or {}).get("value", 0),
            reverse=True,
        )
        dimensions = dimensions[:top_n]
    result = []
    for dimension in dimensions:
        values = {year: groups.get((maker_name, year, dimension)) for year in summary_years}
        metric = {
            "metric_id": f"maker.{_slug(maker_name)}.{dimension_name}.{_slug(dimension)}",
            dimension_name: dimension,
            "unit": "thousand_units",
            "values": {str(year): _metric_value(values[year]) for year in summary_years},
            "maker_internal_share": {
                str(year): _share(values[year], maker_totals.get((maker_name, year)))
                for year in summary_years
            },
            "evidence": {str(year): _evidence(values[year], "sum") for year in summary_years},
        }
        _attach_yoy(metric, _ratio(values.get(current), values.get(prior)))
        if market_groups is not None:
            metric["segment_market_share"] = {
                str(year): _share(values[year], market_groups.get((year, dimension)))
                for year in summary_years
            }
        result.append(metric)
    return result


def _technology_size_metrics(
    maker_name, groups, maker_totals, maker_technology, market_technology, market_segments, year_ctx,
):
    summary_years = year_ctx["summary_years"]
    current, prior = year_ctx["current_year"], year_ctx["prior_year"]
    combinations = sorted({(key[2], key[3]) for key in groups if key[0] == maker_name})
    result = []
    for technology, size_bucket in combinations:
        values = {
            year: groups.get((maker_name, year, technology, size_bucket)) for year in summary_years
        }
        metric = {
            "metric_id": f"maker.{_slug(maker_name)}.technology_size.{_slug(technology)}.{_slug(size_bucket)}",
            "technology": technology,
            "size_bucket": size_bucket,
            "unit": "thousand_units",
            "values": {str(year): _metric_value(values[year]) for year in summary_years},
            "maker_internal_share": {
                str(year): _share(values[year], maker_totals.get((maker_name, year)))
                for year in summary_years
            },
            "segment_market_share": {
                str(year): _share(values[year], market_segments.get((year, technology, size_bucket)))
                for year in summary_years
            },
            "technology_market_share": {
                str(year): _share(values[year], market_technology.get((year, technology)))
                for year in summary_years
            },
            "same_size_market_share": {
                str(year): _share(values[year], market_segments.get((year, technology, size_bucket)))
                for year in summary_years
            },
            "evidence": {str(year): _evidence(values[year], "sum") for year in summary_years},
        }
        _attach_yoy(metric, _ratio(values.get(current), values.get(prior)))
        result.append(metric)
    return result


def _summary_matrix(
    market, maker, maker_technology, market_technology, maker_segments, market_segments, year_ctx,
):
    """Build Summary matrix for the active current/prior year pair."""
    summary_years = year_ctx["summary_years"]
    current, prior = year_ctx["current_year"], year_ctx["prior_year"]
    yy = year_ctx["yy"]
    rows = []
    # Summary「细分占比 / 细分市场占比」分母只用 LTPS+a-Si（终稿口径）。
    share_market = {
        year: _bucket_sum(
            market_technology.get((year, "LTPS")),
            market_technology.get((year, "a-Si")),
        )
        for year in summary_years
    }
    size_labels = (("<8", '8”以下'), ("[8,12)", '8”-12”'), ("[12,15)", '12”-15”'), (">=15", '15”以上'))
    for technology_name in ("LTPS", "a-Si"):
        for size_bucket, size_label in size_labels:
            market_values = {
                year: market_segments.get((year, technology_name, size_bucket)) for year in summary_years
            }
            maker_values = {
                maker_name: {
                    year: maker_segments.get((maker_name, year, technology_name, size_bucket))
                    for year in summary_years
                }
                for maker_name in TARGET_MAKERS
            }
            rows.append(_summary_row(
                technology_name, size_bucket, size_label, False,
                market_values, maker_values, share_market, maker,
                market_technology, maker_technology, market_values, year_ctx,
            ))

        market_values = {
            year: market_technology.get((year, technology_name)) for year in summary_years
        }
        maker_values = {
            maker_name: {
                year: maker_technology.get((maker_name, year, technology_name))
                for year in summary_years
            }
            for maker_name in TARGET_MAKERS
        }
        rows.append(_summary_row(
            technology_name, "total", f"{technology_name} Total", True,
            market_values, maker_values, share_market, maker,
            market_technology, maker_technology, market_values, year_ctx,
        ))

    market_values = {year: market.get((year,)) for year in summary_years}
    maker_values = {
        maker_name: {year: maker.get((maker_name, year)) for year in summary_years}
        for maker_name in TARGET_MAKERS
    }
    rows.append(_summary_row(
        "All", "all", "All", True, market_values, maker_values,
        share_market, maker, market_technology, maker_technology, share_market, year_ctx,
    ))
    suffix = f"y{yy}_full_year" if year_ctx["full_year"] else f"y{yy}q1_q3"
    title = f"Y{yy}全年 Summary" if year_ctx["full_year"] else f"Y{yy}前三季度 Summary"
    return {
        "metric_id": f"market.{suffix}.summary_matrix",
        "title": title,
        "summary_mode": "full_year" if year_ctx["full_year"] else "q1_q3",
        "current_year": current,
        "prior_year": prior,
        "row_order": [row["row_key"] for row in rows],
        "rows": rows,
    }


def _summary_row(
    technology_name, size_bucket, label, is_total, market_values, maker_values,
    share_market, maker, market_technology, maker_technology, market_segment_values, year_ctx,
):
    summary_years = year_ctx["summary_years"]
    current, prior = year_ctx["current_year"], year_ctx["prior_year"]
    row_key = f"{_slug(technology_name)}.{_slug(size_bucket)}"
    market_columns = {
        "metric_id": f"market.summary.{row_key}",
        "values": {str(year): _metric_value(market_values[year]) for year in summary_years},
        "segment_share": {
            str(year): _share(market_values[year], share_market.get(year))
            for year in summary_years
        },
        "technology_internal_share": {
            str(year): _share(market_values[year], market_technology.get((year, technology_name)))
            for year in summary_years
        },
        "total_market_share": {
            str(year): _share(market_values[year], share_market.get(year)) for year in summary_years
        },
        "evidence": {str(year): _evidence(market_values[year], "sum") for year in summary_years},
    }
    _attach_yoy(market_columns, _ratio(market_values.get(current), market_values.get(prior)))
    if technology_name == "All":
        all_share = {
            str(year): (1.0 if share_market.get(year) else None) for year in summary_years
        }
        market_columns["segment_share"] = dict(all_share)
        market_columns["technology_internal_share"] = dict(all_share)
        market_columns["total_market_share"] = dict(all_share)

    maker_columns = {}
    for maker_name in TARGET_MAKERS:
        values = maker_values[maker_name]
        internal_denominators = {year: maker.get((maker_name, year)) for year in summary_years}
        growth = _growth_contribution(
            values.get(current), values.get(prior),
            internal_denominators.get(current), internal_denominators.get(prior),
        )
        maker_metric = {
            "metric_id": f"maker.{_slug(maker_name)}.summary.{row_key}",
            "values": {str(year): _metric_value(values[year]) for year in summary_years},
            "growth_contribution": growth,
            "growth_contribution_2025_vs_2024": growth,
            "internal_share": {
                str(year): _share(values[year], internal_denominators[year]) for year in summary_years
            },
            "segment_market_share": {
                str(year): _share(values[year], market_segment_values[year])
                for year in summary_years
            },
            "technology_market_share": {
                str(year): _share(
                    values[year],
                    share_market.get(year) if technology_name == "All"
                    else market_technology.get((year, technology_name)),
                ) for year in summary_years
            },
            "same_size_market_share": {
                str(year): _share(values[year], market_segment_values[year]) for year in summary_years
            },
            "evidence": {str(year): _evidence(values[year], "sum") for year in summary_years},
        }
        _attach_yoy(maker_metric, _ratio(values.get(current), values.get(prior)))
        maker_columns[maker_name] = maker_metric
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
    if "oxide" in text:
        return "Oxide"
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


def _bucket_sum(*buckets):
    total = None
    for bucket in buckets:
        if not bucket:
            continue
        if total is None:
            total = {
                "value": Decimal(bucket["value"]),
                "cells": list(bucket.get("cells") or []),
                "sheet": bucket.get("sheet"),
                "count": int(bucket.get("count") or 0),
            }
        else:
            total["value"] += bucket["value"]
            total["cells"].extend(bucket.get("cells") or [])
            total["count"] += int(bucket.get("count") or 0)
    return total


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
