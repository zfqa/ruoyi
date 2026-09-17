"""Merge multi-workbook History / Supply Chain records by Year+Quarter."""
from __future__ import annotations

from typing import Any


def _year_quarter(record: dict[str, Any]) -> tuple[int, int] | None:
    year_raw = record.get("year")
    quarter_raw = record.get("quarter")
    year_value = year_raw.get("value") if isinstance(year_raw, dict) else year_raw
    quarter_value = quarter_raw.get("value") if isinstance(quarter_raw, dict) else quarter_raw
    try:
        year = int(float(str(year_value)))
    except (TypeError, ValueError):
        return None
    text = "" if quarter_value is None else str(quarter_value).strip().upper().replace("QUARTER", "Q").replace(" ", "")
    quarter = None
    for number in (1, 2, 3, 4):
        if text in {str(number), f"Q{number}", f"{number}Q"}:
            quarter = number
            break
    if quarter is None:
        try:
            quarter = int(float(str(quarter_value)))
        except (TypeError, ValueError):
            return None
    if quarter not in {1, 2, 3, 4}:
        return None
    return year, quarter


def merge_records_by_year_quarter(
    groups: list[tuple[str, list[dict[str, Any]]]],
) -> list[dict[str, Any]]:
    """Merge record groups ordered oldest → newest.

    For each (Year, Quarter) present in a later workbook, that workbook fully
    replaces earlier records for the same period (no double counting).
    """
    by_period: dict[tuple[int, int], list[dict[str, Any]]] = {}
    period_source: dict[tuple[int, int], str] = {}
    for file_name, records in groups:
        periods_in_file: dict[tuple[int, int], list[dict[str, Any]]] = {}
        for record in records:
            key = _year_quarter(record)
            if key is None:
                continue
            periods_in_file.setdefault(key, []).append(record)
        for key, period_records in periods_in_file.items():
            by_period[key] = period_records
            period_source[key] = file_name
    merged: list[dict[str, Any]] = []
    for key in sorted(by_period):
        merged.extend(by_period[key])
    return merged


def period_coverage(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    counts: dict[tuple[int, int], int] = {}
    for record in records:
        key = _year_quarter(record)
        if key is None:
            continue
        counts[key] = counts.get(key, 0) + 1
    return [
        {"year": year, "quarter": quarter, "record_count": counts[(year, quarter)]}
        for year, quarter in sorted(counts)
    ]
