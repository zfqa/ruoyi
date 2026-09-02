"""Deterministic table candidate detection.

The detector intentionally returns coordinates only. It never invents cell
values; downstream extraction rereads every value from the workbook.
"""
from __future__ import annotations

from dataclasses import asdict
from typing import Any

from openpyxl.utils import get_column_letter

from app.extraction.field_mapper import normalize_field_name
from app.models.table import TableRegion


HEADER_KEYWORDS = {
    "year",
    "quarter",
    "qy",
    "panel maker",
    "client",
    "original specification",
    "product",
    "technology",
    "qty",
}


def detect_tables(ws, sheet_name: str) -> list[dict[str, Any]]:
    """Detect table regions in a sheet using row/column occupancy rules."""
    if _is_metadata_sheet(sheet_name):
        return []

    rows = _non_empty_rows(ws)
    if not rows:
        return []

    bands = _row_bands(rows)
    regions: list[dict[str, Any]] = []
    for band_index, (min_row, max_row) in enumerate(bands, start=1):
        cols = _non_empty_cols(ws, min_row, max_row)
        if not cols:
            continue
        col_bands = _col_bands(cols)
        for col_index, (min_col, max_col) in enumerate(col_bands, start=1):
            region = _build_region(ws, sheet_name, band_index, col_index, min_row, max_row, min_col, max_col)
            if region["metrics"]["non_empty_count"] < 4:
                continue
            if len(region.get("fields", [])) < 2:
                continue
            regions.append(region)
    return regions


def _is_metadata_sheet(sheet_name: str) -> bool:
    return sheet_name.strip().lower() in {"{parameters}", "contents", "definitions", "cognos_office_connection_cache"}


def _non_empty_rows(ws) -> list[int]:
    rows = []
    for row in ws.iter_rows():
        if any(cell.value is not None for cell in row):
            rows.append(row[0].row)
    return rows


def _row_bands(rows: list[int], max_gap: int = 2) -> list[tuple[int, int]]:
    bands = []
    start = prev = rows[0]
    for row in rows[1:]:
        if row - prev > max_gap:
            bands.append((start, prev))
            start = row
        prev = row
    bands.append((start, prev))
    return bands


def _non_empty_cols(ws, min_row: int, max_row: int) -> list[int]:
    cols = []
    for col in range(1, ws.max_column + 1):
        found = False
        for row in range(min_row, max_row + 1):
            if ws.cell(row, col).value is not None:
                found = True
                break
        if found:
            cols.append(col)
    return cols


def _col_bands(cols: list[int], max_gap: int = 2) -> list[tuple[int, int]]:
    bands = []
    start = prev = cols[0]
    for col in cols[1:]:
        if col - prev > max_gap:
            bands.append((start, prev))
            start = col
        prev = col
    bands.append((start, prev))
    return bands


def _build_region(ws, sheet_name: str, band_index: int, col_index: int, min_row: int, max_row: int, min_col: int, max_col: int) -> dict[str, Any]:
    values = []
    non_empty = 0
    text_count = 0
    number_count = 0
    for row in range(min_row, max_row + 1):
        for col in range(min_col, max_col + 1):
            value = ws.cell(row, col).value
            values.append(value)
            if value is not None:
                non_empty += 1
                if isinstance(value, (int, float)):
                    number_count += 1
                else:
                    text_count += 1

    total = max(1, len(values))
    region = TableRegion(
        candidate_id=f"{_safe_id(sheet_name)}_T{band_index:02d}_{col_index:02d}",
        sheet=sheet_name,
        min_row=min_row,
        max_row=max_row,
        min_col=min_col,
        max_col=max_col,
        density=non_empty / total,
        text_ratio=text_count / max(1, non_empty),
        number_ratio=number_count / max(1, non_empty),
        merge_ratio=_merge_ratio(ws, min_row, max_row, min_col, max_col),
    )
    header = infer_header(ws, min_row, max_row, min_col, max_col)
    data_start = header["end_row"] + 1 if header else min_row
    return {
        **asdict(region),
        "range": region.range_str(),
        "metrics": {"non_empty_count": non_empty},
        "header": header,
        "data": {"start_row": data_start, "end_row": max_row},
        "fields": build_fields(ws, header, min_col, max_col),
    }


def infer_header(ws, min_row: int, max_row: int, min_col: int, max_col: int) -> dict[str, int] | None:
    best_row = None
    best_score = -1
    scan_end = min(max_row, min_row + 20)
    for row in range(min_row, scan_end + 1):
        values = [ws.cell(row, col).value for col in range(min_col, max_col + 1)]
        text_values = [str(v).strip().lower() for v in values if isinstance(v, str) and str(v).strip()]
        score = len(text_values)
        score += sum(2 for v in text_values if any(k in v for k in HEADER_KEYWORDS))
        next_row_numbers = 0
        if row < max_row:
            next_row_numbers = sum(1 for col in range(min_col, max_col + 1) if isinstance(ws.cell(row + 1, col).value, (int, float)))
        score += min(next_row_numbers, 3)
        if score > best_score:
            best_row = row
            best_score = score
    if best_row is None or best_score <= 0:
        return None
    return {"start_row": best_row, "end_row": best_row}


def build_fields(ws, header: dict[str, int] | None, min_col: int, max_col: int) -> list[dict[str, Any]]:
    if not header:
        return []
    used: set[str] = set()
    fields = []
    row = header["end_row"]
    for col in range(min_col, max_col + 1):
        value = ws.cell(row, col).value
        if value is None or str(value).strip() == "":
            continue
        column_letter = get_column_letter(col)
        fields.append({
            "name": normalize_field_name(str(value), used),
            "display_name": str(value),
            "column": column_letter,
            "source_cell": f"{column_letter}{row}",
        })
    return fields


def _merge_ratio(ws, min_row: int, max_row: int, min_col: int, max_col: int) -> float:
    merged_cells = 0
    total = (max_row - min_row + 1) * (max_col - min_col + 1)
    for merged_range in ws.merged_cells.ranges:
        for row in range(min_row, max_row + 1):
            for col in range(min_col, max_col + 1):
                if ws.cell(row, col).coordinate in merged_range:
                    merged_cells += 1
    return merged_cells / max(1, total)


def _safe_id(value: str) -> str:
    return "".join(ch if ch.isalnum() else "_" for ch in value).strip("_") or "sheet"
