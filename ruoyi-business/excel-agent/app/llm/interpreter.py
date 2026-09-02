"""Validate LLM structure output and convert it into deterministic extraction coordinates."""
from __future__ import annotations

import re
from typing import Any

from openpyxl.utils.cell import column_index_from_string

from app.extraction.field_mapper import normalize_field_name
from app.llm.client import ArkChatClient, LlmError
from app.llm.prompts import SYSTEM_PROMPT, build_table_prompt


def interpret_table(client: ArkChatClient, ws, table: dict[str, Any]) -> dict[str, Any]:
    raw = client.complete_json(SYSTEM_PROMPT, build_table_prompt(ws, table))
    return validate_and_apply(raw, ws, table)


def validate_and_apply(result: dict[str, Any], ws, table: dict[str, Any]) -> dict[str, Any]:
    if result.get("candidate_id") not in {None, table["candidate_id"]}:
        raise LlmError("LLM candidate_id与请求不一致")
    confidence = _confidence(result.get("confidence"))
    if not isinstance(result.get("is_table"), bool):
        raise LlmError("LLM is_table必须为布尔值")
    if not result["is_table"]:
        return {**table, "llm_is_table": False, "llm_confidence": confidence}

    header = _row_range(result.get("header"), table, "header")
    data = _row_range(result.get("data"), table, "data")
    if data["start_row"] <= header["end_row"]:
        raise LlmError("LLM数据区域与表头区域重叠")

    fields = _fields(result.get("columns"), ws, table, header)
    if len(fields) < 2:
        raise LlmError("LLM有效字段少于2列")
    footer_rows = _rows(result.get("footer_rows", []), table)
    title_cells = _cells(result.get("title_cells", []), table)
    title_values = [_cell_text(ws, cell) for cell in title_cells]
    table_name = " / ".join(value for value in title_values if value)

    refined = dict(table)
    refined.update({
        "header": header,
        "data": data,
        "fields": fields,
        "footer_rows": footer_rows,
        "title_cells": title_cells,
        "llm_is_table": True,
        "llm_confidence": confidence,
        "llm_table_name": table_name[:500] or None,
    })
    return refined


def _row_range(value: Any, table: dict[str, Any], label: str) -> dict[str, int]:
    if not isinstance(value, dict):
        raise LlmError(f"LLM {label}必须为对象")
    try:
        start = int(value["start_row"])
        end = int(value["end_row"])
    except (KeyError, TypeError, ValueError) as exc:
        raise LlmError(f"LLM {label}行号无效") from exc
    if start < table["min_row"] or end > table["max_row"] or start > end:
        raise LlmError(f"LLM {label}超出候选区域")
    return {"start_row": start, "end_row": end}


def _fields(columns: Any, ws, table: dict[str, Any], header: dict[str, int]) -> list[dict[str, Any]]:
    if not isinstance(columns, list):
        raise LlmError("LLM columns必须为数组")
    used: set[str] = set()
    seen_columns: set[str] = set()
    fields = []
    for item in columns:
        if not isinstance(item, dict):
            continue
        column = str(item.get("column") or "").strip().upper()
        try:
            col_index = column_index_from_string(column)
        except ValueError as exc:
            raise LlmError(f"LLM列坐标无效: {column}") from exc
        if col_index < table["min_col"] or col_index > table["max_col"] or column in seen_columns:
            raise LlmError(f"LLM列坐标越界或重复: {column}")
        seen_columns.add(column)
        source_header = _header_cells(item.get("source_header", []), column, header)
        header_path = [_cell_text(ws, cell) for cell in source_header]
        header_path = [value for index, value in enumerate(header_path) if value and value not in header_path[:index]]
        original = " / ".join(header_path) or column
        requested_name = _safe_field_name(str(item.get("standard_name") or ""))
        name = _dedupe_name(requested_name, used) if requested_name else normalize_field_name(original, used)
        fields.append({
            "name": name,
            "display_name": original,
            "header_path": header_path or [original],
            "column": column,
            "source_cell": source_header[-1],
            "source_header": source_header,
        })
    return fields


def _header_cells(values: Any, column: str, header: dict[str, int]) -> list[str]:
    cells = [str(value).strip().upper() for value in values] if isinstance(values, list) else []
    if not cells:
        cells = [f"{column}{row}" for row in range(header["start_row"], header["end_row"] + 1)]
    expected = {f"{column}{row}" for row in range(header["start_row"], header["end_row"] + 1)}
    if any(cell not in expected for cell in cells):
        raise LlmError(f"LLM表头坐标不属于列{column}的表头区域")
    return cells


def _rows(values: Any, table: dict[str, Any]) -> list[int]:
    if not isinstance(values, list):
        raise LlmError("LLM footer_rows必须为数组")
    rows = sorted({int(value) for value in values})
    if any(row < table["min_row"] or row > table["max_row"] for row in rows):
        raise LlmError("LLM footer_rows超出候选区域")
    return rows


def _cells(values: Any, table: dict[str, Any]) -> list[str]:
    if not isinstance(values, list):
        return []
    cells = []
    for value in values:
        cell = str(value).strip().upper()
        match = re.fullmatch(r"([A-Z]+)([1-9]\d*)", cell)
        if not match:
            raise LlmError(f"LLM标题坐标无效: {cell}")
        col = column_index_from_string(match.group(1))
        row = int(match.group(2))
        if col < table["min_col"] or col > table["max_col"] or row < table["min_row"] or row > table["max_row"]:
            raise LlmError(f"LLM标题坐标越界: {cell}")
        cells.append(cell)
    return cells


def _safe_field_name(value: str) -> str:
    return re.sub(r"[^0-9a-zA-Z_]+", "_", value.lower()).strip("_")


def _dedupe_name(name: str, used: set[str]) -> str:
    candidate = name
    index = 2
    while candidate in used:
        candidate = f"{name}_{index}"
        index += 1
    used.add(candidate)
    return candidate


def _cell_text(ws, coordinate: str) -> str:
    cell = ws[coordinate]
    value = cell.value
    if value is None:
        for merged_range in ws.merged_cells.ranges:
            if coordinate in merged_range:
                value = ws.cell(merged_range.min_row, merged_range.min_col).value
                break
    return "" if value is None else str(value).strip()


def _confidence(value: Any) -> float:
    try:
        confidence = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, confidence))
