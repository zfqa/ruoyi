"""Shared year/period helpers for multi-workbook automotive display metrics."""
from __future__ import annotations

import re
from typing import Any, Iterable


# Accept a wide year window; periods beyond the Y25 template are discovered from data.
MIN_YEAR = 2022
MAX_YEAR = 2035
BASE_PERIODS = ("Y22", "Y23", "Y24", "Y25F", "Y25Q1-Q3")
# Kept for callers that still import SUPPORTED_YEARS.
SUPPORTED_YEARS = tuple(range(MIN_YEAR, MAX_YEAR + 1))

# Omdia tracker filenames use the website *publication* quarter.
# Actual updated results always lag by one quarter (e.g. 1Q26 publish → data through 4Q25).
_OMDIA_NQYY = re.compile(r"(?<![0-9A-Za-z])([1-4])\s*Q\s*([0-9]{2})(?![0-9])", re.IGNORECASE)
_OMDIA_YYNQ = re.compile(r"(?<![0-9A-Za-z])([0-9]{2})\s*([1-4])\s*Q(?![0-9A-Za-z])", re.IGNORECASE)
_OMDIA_WITH_RESULTS = re.compile(
    r"with\s+([1-4])\s*Q\s*([0-9]{2})\s+Results",
    re.IGNORECASE,
)


def year_label(year: int) -> str:
    return f"Y{str(int(year))[-2:]}"


def quarter_period(year: int, quarter: int) -> str:
    return f"{year_label(year)}Q{int(quarter)}"


def publication_label(year: int, quarter: int) -> str:
    """Omdia-style publication tag, e.g. 1Q26."""
    return f"{int(quarter)}Q{str(int(year))[-2:]}"


def prior_calendar_quarter(year: int, quarter: int) -> tuple[int, int]:
    year_i, quarter_i = int(year), int(quarter)
    if quarter_i == 1:
        return year_i - 1, 4
    return year_i, quarter_i - 1


def omdia_data_through(publication_year: int, publication_quarter: int) -> tuple[int, int]:
    """Map Omdia publication quarter → last actual/result quarter covered."""
    return prior_calendar_quarter(publication_year, publication_quarter)


def _yy_to_year(yy: str | int) -> int:
    value = int(yy)
    return 2000 + value if value < 100 else value


def _collect_omdia_quarter_tokens(text: str) -> list[tuple[int, int, int, int]]:
    """Return (start, end, year, quarter) tokens found in filename/title text."""
    found: list[tuple[int, int, int, int]] = []
    for match in _OMDIA_NQYY.finditer(text or ""):
        quarter, yy = match.group(1), match.group(2)
        found.append((match.start(), match.end(), _yy_to_year(yy), int(quarter)))
    for match in _OMDIA_YYNQ.finditer(text or ""):
        yy, quarter = match.group(1), match.group(2)
        found.append((match.start(), match.end(), _yy_to_year(yy), int(quarter)))
    found.sort(key=lambda item: item[0])
    deduped: list[tuple[int, int, int, int]] = []
    last_end = -1
    for item in found:
        if item[0] < last_end:
            continue
        deduped.append(item)
        last_end = item[1]
    return deduped


def parse_omdia_tracker_meta(file_name: str | None) -> dict[str, Any] | None:
    """Parse Omdia tracker publication / data-through quarters from a file name.

    Rule (stable going forward): publication quarter on Omdia site lags the
    updated result quarter by one quarter.
    Example: file tagged 1Q26 → data updated through 4Q25.
    """
    text = str(file_name or "")
    if not text.strip():
        return None
    tokens = _collect_omdia_quarter_tokens(text)
    if not tokens:
        return None

    results_match = _OMDIA_WITH_RESULTS.search(text)
    data_through: tuple[int, int] | None = None
    if results_match:
        data_through = (_yy_to_year(results_match.group(2)), int(results_match.group(1)))

    publication: tuple[int, int] | None = None
    if data_through is not None:
        for _, _, year, quarter in tokens:
            if (year, quarter) != data_through:
                publication = (year, quarter)
                break
        if publication is None:
            year, quarter = data_through
            publication = (year + 1, 1) if quarter == 4 else (year, quarter + 1)
    else:
        _, _, year, quarter = tokens[-1]
        publication = (year, quarter)
        data_through = omdia_data_through(year, quarter)

    assert publication is not None and data_through is not None
    return {
        "publication_year": publication[0],
        "publication_quarter": publication[1],
        "publication_label": publication_label(*publication),
        "data_through_year": data_through[0],
        "data_through_quarter": data_through[1],
        "data_through_label": publication_label(*data_through),
        "lag_quarters": 1,
    }


def latest_omdia_data_through(
    file_names: Iterable[str | None],
) -> dict[str, Any] | None:
    """Pick the newest data-through quarter implied by Omdia file names."""
    latest: dict[str, Any] | None = None
    latest_key = (-1, -1)
    for name in file_names:
        meta = parse_omdia_tracker_meta(name)
        if not meta:
            continue
        key = (meta["data_through_year"], meta["data_through_quarter"])
        if key > latest_key:
            latest_key = key
            latest = meta
    return latest


def report_horizon_from_data_through(year: int, quarter: int) -> dict[str, Any]:
    """Map data-through quarter to report framing (前三季度 / 全年 / 截至Qn)."""
    year_i, quarter_i = int(year), int(quarter)
    label = year_label(year_i)
    if quarter_i == 4:
        horizon = f"{label.lower()}_full_year"
        title_suffix = f"{label}全年"
        summary_name = f"{label}全年"
        kind = "full_year"
    elif quarter_i == 3:
        horizon = f"{label.lower()}_q1_q3"
        title_suffix = f"{label}前三季度"
        summary_name = f"{label}前三季度"
        kind = "q1_q3"
    else:
        horizon = f"{label.lower()}_q1_q{quarter_i}"
        title_suffix = f"{label}Q1-Q{quarter_i}"
        summary_name = f"{label}截至Q{quarter_i}"
        kind = f"q1_q{quarter_i}"
    return {
        "report_horizon": horizon,
        "horizon_kind": kind,
        "data_through_year": year_i,
        "data_through_quarter": quarter_i,
        "title_suffix": title_suffix,
        "summary_name": summary_name,
        "full_year": quarter_i == 4,
        "q1_q3": quarter_i == 3,
    }


def period_sort_key(period: str) -> tuple[int, int, int]:
    """Sort key: annual/F/Q1-Q3 before later explicit quarters of same year."""
    parsed = parse_period_key(period)
    if not parsed:
        return (9999, 99, 99)
    year, quarter = parsed
    text = str(period or "").upper()
    if text.endswith("F"):
        return (year, 4, 1)  # full-year sits with Q4 actuals
    if "Q1-Q3" in text:
        return (year, 3, 0)
    if quarter is None:
        return (year, 0, 0)
    return (year, quarter, 2)


def is_period_within_data_through(
    period: str,
    data_through_year: int | None,
    data_through_quarter: int | None,
) -> bool:
    """Keep base annual/Y25F/Y25Q1-Q3; drop explicit quarters after data-through.

    Example: data through 4Q25 → keep Y25F / Y25Q1-Q3, drop Y26Q1 / Y26Q2 forecasts.
    """
    if data_through_year is None or data_through_quarter is None:
        return True
    text = str(period or "").strip().upper()
    if text in BASE_PERIODS or text.endswith("F") or "Q1-Q3" in text:
        # Annual / full-year / Q1-Q3 process columns are always kept for the
        # current Y25 report template when they belong to <= data-through year.
        parsed = parse_period_key(text)
        if not parsed:
            return True
        year, _ = parsed
        return year <= int(data_through_year)
    parsed = parse_period_key(text)
    if not parsed:
        return True
    year, quarter = parsed
    if quarter is None:
        return year <= int(data_through_year)
    return (year, quarter) <= (int(data_through_year), int(data_through_quarter))


def clip_periods_to_data_through(
    periods: Iterable[str] | None,
    data_through_year: int | None,
    data_through_quarter: int | None,
) -> tuple[str, ...]:
    order = list(periods or [])
    clipped = [
        period for period in order
        if is_period_within_data_through(period, data_through_year, data_through_quarter)
    ]
    return tuple(clipped)


def parse_period_key(period: str) -> tuple[int, int | None] | None:
    """Parse Y26Q1 / Y25F / Y25Q1-Q3 / Y24 into (year, quarter_or_None)."""
    text = str(period or "").strip().upper()
    if not text.startswith("Y") or len(text) < 3:
        return None
    body = text[1:]
    if body.endswith("F") and body[:-1].isdigit():
        year = 2000 + int(body[:-1])
        return year, None
    if "Q1-Q3" in body:
        yy = body.split("Q", 1)[0]
        if yy.isdigit():
            return 2000 + int(yy), None
    if "Q" in body:
        yy, _, q = body.partition("Q")
        if yy.isdigit() and q.isdigit() and int(q) in {1, 2, 3, 4}:
            return 2000 + int(yy), int(q)
    if body.isdigit():
        return 2000 + int(body), None
    return None


def prior_year_quarter_period(period: str) -> str | None:
    """YoY peer for dynamic quarter keys, e.g. Y26Q1 -> Y25Q1."""
    parsed = parse_period_key(period)
    if not parsed:
        return None
    year, quarter = parsed
    if quarter is None:
        return None
    return quarter_period(year - 1, quarter)


def periods_for_years(years: Iterable[int]) -> tuple[str, ...]:
    """Backward-compatible helper: years only (no quarters) → base + Q1 for years>=2026."""
    present = sorted({int(year) for year in years if year is not None})
    periods = list(BASE_PERIODS)
    for year in present:
        if year >= 2026:
            periods.append(quarter_period(year, 1))
    return tuple(dict.fromkeys(periods))


def periods_for_year_quarters(pairs: Iterable[tuple[int, int]]) -> tuple[str, ...]:
    """Build report period order from observed (year, quarter) pairs."""
    pairs_set = {
        (int(year), int(quarter))
        for year, quarter in pairs
        if year is not None and quarter in {1, 2, 3, 4} and MIN_YEAR <= int(year) <= MAX_YEAR
    }
    years = {year for year, _ in pairs_set}
    periods = list(BASE_PERIODS)
    for year in sorted(y for y in years if y < 2025):
        label = year_label(year)
        if label not in periods:
            periods.insert(
                max(
                    0,
                    len([p for p in periods if p.startswith("Y") and "Q" not in p and not p.endswith("F")]),
                ),
                label,
            )
    for year, quarter in sorted((y, q) for y, q in pairs_set if y >= 2026):
        key = quarter_period(year, quarter)
        if key not in periods:
            periods.append(key)
    return tuple(periods)


def year_quarters_from_rows(rows: Iterable[dict[str, Any]]) -> list[tuple[int, int]]:
    pairs: list[tuple[int, int]] = []
    for row in rows:
        year = row.get("year")
        quarter = row.get("quarter")
        try:
            year_i = int(year)
            quarter_i = int(quarter)
        except (TypeError, ValueError):
            continue
        if MIN_YEAR <= year_i <= MAX_YEAR and quarter_i in {1, 2, 3, 4}:
            pairs.append((year_i, quarter_i))
    return pairs


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
        if MIN_YEAR <= year <= MAX_YEAR:
            years.add(year)
    return years


def extended_periods(period_order: Iterable[str] | None) -> tuple[str, ...]:
    """Periods beyond the Y25 base template."""
    order = list(period_order or [])
    return tuple(period for period in order if period not in BASE_PERIODS)
