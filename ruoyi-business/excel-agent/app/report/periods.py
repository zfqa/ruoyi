"""Shared year/period helpers for multi-workbook automotive display metrics."""
from __future__ import annotations

from typing import Iterable


SUPPORTED_YEARS = (2022, 2023, 2024, 2025, 2026)
# Keep legacy Y25-centric periods and append Y26Q1 when 2026 data is present.
BASE_PERIODS = ("Y22", "Y23", "Y24", "Y25F", "Y25Q1-Q3")


def year_label(year: int) -> str:
    return f"Y{str(year)[-2:]}"


def periods_for_years(years: Iterable[int]) -> tuple[str, ...]:
    present = {int(year) for year in years if year is not None}
    periods = list(BASE_PERIODS)
    if 2026 in present:
        periods.append("Y26Q1")
    return tuple(periods)


def current_years_from_records(records: list[dict], year_key: str = "year") -> set[int]:
    years: set[int] = set()
    for record in records:
        value = record.get(year_key)
        if isinstance(value, dict):
            value = value.get("value")
        try:
            year = int(float(str(value)))
        except (TypeError, ValueError):
            continue
        if year in SUPPORTED_YEARS:
            years.add(year)
    return years
