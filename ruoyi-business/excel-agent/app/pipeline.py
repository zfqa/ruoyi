"""End-to-end Excel extraction pipeline."""
from __future__ import annotations

import hashlib
import json
import os
from typing import Any

from openpyxl.utils import get_column_letter

from app.excel.cell_parser import parse_cells
from app.excel.loader import load_workbooks
from app.excel.pivot_cache import extract_shipment_share_cache, extract_supply_chain_cache
from app.extraction.field_mapper import normalize_field_name
from app.extraction.record_builder import build_records
from app.llm.client import ArkChatClient
from app.llm.interpreter import interpret_table
from app.report.metrics import calculate_competitive_metrics
from app.report.tianma_history import calculate_tianma_history_metrics
from app.report.tianma_product import calculate_tianma_product_metrics
from app.report.tianma_growth import calculate_tianma_application_metrics, calculate_tianma_customer_metrics
from app.table.detector import detect_tables
from app.validation.table_validator import validate_table


def parse_workbook(
    file_path: str,
    include_raw_cells: bool = False,
    max_records_per_table: int | None = None,
    raw_cell_mode: str = "non-empty",
    use_llm: bool = False,
    llm_client: ArkChatClient | None = None,
    max_llm_tables: int | None = None,
    baseline_file_path: str | None = None,
    supply_chain_file_path: str | None = None,
) -> dict[str, Any]:
    pivot_cache = extract_shipment_share_cache(file_path)
    if pivot_cache is not None:
        baseline_cache = extract_shipment_share_cache(baseline_file_path) if baseline_file_path else None
        supply_chain_cache = extract_supply_chain_cache(supply_chain_file_path) if supply_chain_file_path else None
        return _parse_shipment_share_cache(
            file_path, pivot_cache, max_records_per_table, use_llm,
            baseline_file_path, baseline_cache, supply_chain_file_path, supply_chain_cache,
        )
    wb_formula, wb_value = load_workbooks(file_path)
    workbook_id = _file_hash(file_path)
    file_name = os.path.basename(file_path)
    client = llm_client or (ArkChatClient() if use_llm else None)
    llm_errors: list[str] = []
    llm_success_count = 0
    llm_fallback_count = 0
    llm_rejected_count = 0
    llm_attempt_count = 0
    llm_skipped_count = 0
    metric_tables: list[dict[str, Any]] = []
    if max_llm_tables is None:
        max_llm_tables = int(os.getenv("ARK_MAX_TABLE_CALLS", "4"))
    max_llm_tables = max(0, max_llm_tables)
    if use_llm and client is not None and not client.available:
        llm_errors.append("LLM API Key未配置，已使用规则解析")

    result = {
        "workbook_id": f"sha256:{workbook_id}",
        "file_name": file_name,
        "sheets": [],
        "quality": {
            "cell_match_rate": 1.0,
            "value_source": "openpyxl",
            "llm_requested": use_llm,
            "llm_used": False,
        },
    }

    for sheet_index, ws_formula in enumerate(wb_formula.worksheets):
        ws_value = wb_value[ws_formula.title]
        effective = _effective_range(ws_formula)
        sheet_result = {
            "sheet_name": ws_formula.title,
            "sheet_index": sheet_index,
            "max_row": ws_formula.max_row,
            "max_column": ws_formula.max_column,
            "effective_range": effective,
            "merged_ranges": [str(rng) for rng in ws_formula.merged_cells.ranges],
            "tables": [],
        }
        if include_raw_cells:
            parsed_cells = parse_cells(ws_formula, ws_value, ws_formula.title)
            if raw_cell_mode == "all":
                sheet_result["cells"] = [cell.to_dict() for cell in parsed_cells]
            else:
                sheet_result["cells"] = [cell.to_dict() for cell in parsed_cells if cell.raw_value is not None or cell.is_merged]

        if _skip_sheet(ws_formula.title):
            result["sheets"].append(sheet_result)
            continue

        tables = detect_tables(ws_value, ws_formula.title)
        if ws_formula.title.strip().lower() == "{table.main}" and tables:
            tables = [max(tables, key=lambda item: item["metrics"]["non_empty_count"])]

        for table in tables:
            interpreted = table
            llm_note = None
            if use_llm and client is not None and client.available and llm_attempt_count < max_llm_tables:
                llm_attempt_count += 1
                try:
                    candidate = interpret_table(client, ws_formula, table)
                    llm_success_count += 1
                    if not candidate.get("llm_is_table", True):
                        if candidate.get("llm_confidence", 0.0) >= 0.7:
                            llm_rejected_count += 1
                            continue
                        llm_note = "LLM低置信度判定非表格，已保留规则结果并标记人工复核"
                        llm_fallback_count += 1
                    else:
                        interpreted = candidate
                except Exception as exc:
                    llm_fallback_count += 1
                    llm_note = f"LLM结构解析失败，已使用规则结果: {str(exc)[:300]}"
                    llm_errors.append(f"{table['candidate_id']}: {str(exc)[:300]}")
            elif use_llm and client is not None and client.available:
                llm_skipped_count += 1
                llm_note = f"已达到LLM候选表调用上限({max_llm_tables})，使用规则解析"

            errors = validate_table(interpreted, ws_value)
            records = build_records(ws_formula, ws_value, interpreted, file_name, result["workbook_id"])
            metric_tables.append({
                "sheet": ws_formula.title,
                "table": interpreted.get("llm_table_name") or _table_name(ws_value, interpreted),
                "range": interpreted["range"],
                "records": records,
            })
            if max_records_per_table is not None:
                records = records[:max_records_per_table]
            notes = [llm_note] if llm_note else []
            confidence = interpreted.get("llm_confidence", _confidence(interpreted, errors))
            semantic_table = {
                "table_id": interpreted["candidate_id"],
                "table_name": interpreted.get("llm_table_name") or _table_name(ws_value, interpreted),
                "source": {
                    "sheet": ws_formula.title,
                    "range": interpreted["range"],
                },
                "header": interpreted.get("header"),
                "data": interpreted.get("data"),
                "fields": interpreted.get("fields", []),
                "records": records,
                "notes": notes,
                "source_notes": _source_notes(ws_value, interpreted),
                "quality": {
                    "structure_confidence": confidence,
                    "field_mapping_confidence": confidence if interpreted.get("fields") else 0.0,
                    "cell_lineage_coverage": 1.0 if records else 0.0,
                    "validation_errors": errors,
                    "llm_interpreted": bool(interpreted.get("llm_is_table")),
                    "review_required": confidence < 0.7 or bool(llm_note),
                },
            }
            sheet_result["tables"].append(semantic_table)
        result["sheets"].append(sheet_result)
    result["computed_metrics"] = calculate_competitive_metrics(metric_tables)
    result["quality"].update({
        "llm_used": llm_success_count > 0,
        "llm_model": client.model if use_llm and client is not None else None,
        "llm_success_count": llm_success_count,
        "llm_attempt_count": llm_attempt_count,
        "llm_call_limit": max_llm_tables,
        "llm_skipped_count": llm_skipped_count,
        "llm_fallback_count": llm_fallback_count,
        "llm_rejected_count": llm_rejected_count,
        "llm_errors": llm_errors[:20],
    })
    return result


def _effective_range(ws) -> str | None:
    min_row = min_col = None
    max_row = max_col = None
    merged = {coord for rng in ws.merged_cells.ranges for row in ws[rng.coord] for coord in [row[0].coordinate]}
    for row in ws.iter_rows():
        for cell in row:
            if cell.value is None and cell.coordinate not in merged:
                continue
            min_row = cell.row if min_row is None else min(min_row, cell.row)
            max_row = cell.row if max_row is None else max(max_row, cell.row)
            min_col = cell.column if min_col is None else min(min_col, cell.column)
            max_col = cell.column if max_col is None else max(max_col, cell.column)
    if min_row is None:
        return None
    return f"{get_column_letter(min_col)}{min_row}:{get_column_letter(max_col)}{max_row}"


def _table_name(ws, table: dict[str, Any]) -> str:
    header_row = (table.get("header") or {}).get("start_row", table["min_row"])
    for row in range(table["min_row"], header_row):
        for col in range(table["min_col"], table["max_col"] + 1):
            value = ws.cell(row, col).value
            if isinstance(value, str) and value.strip():
                return value.strip()
    return table["sheet"]


def _source_notes(ws, table: dict[str, Any]) -> list[dict[str, Any]]:
    notes = []
    for row in range(table["min_row"], table["max_row"] + 1):
        for col in range(table["min_col"], table["max_col"] + 1):
            value = ws.cell(row, col).value
            if isinstance(value, str) and value.strip().lower().startswith("source:"):
                notes.append({"text": value.strip(), "cell": ws.cell(row, col).coordinate})
    return notes


def _confidence(table: dict[str, Any], errors: list[str]) -> float:
    if errors:
        return 0.6
    if table.get("fields") and table["metrics"]["non_empty_count"] > 20:
        return 0.92
    return 0.75


def _file_hash(file_path: str) -> str:
    sha = hashlib.sha256()
    with open(file_path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def to_json(data: dict[str, Any]) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2, default=_json_default)


def _json_default(value):
    if hasattr(value, "isoformat"):
        return value.isoformat(sep=" ")
    return str(value)


def _skip_sheet(sheet_name: str) -> bool:
    return sheet_name.strip().lower() in {"{parameters}", "contents", "definitions", "cognos_office_connection_cache"}


def _pivot_cache_records(
    cache, file_name: str, workbook_id: str, source_sheet: str = "Shipment share",
    table_id: str = "shipment-share-pivot-cache",
) -> list[dict[str, Any]]:
    records = []
    names = [(name, normalize_field_name(name)) for name in cache["fields"]]
    for row_index, source in enumerate(cache["records"], start=1):
        record = {}
        for original, normalized in names:
            value = source.get(original)
            source_ref = f"pivot-cache:r{row_index}:{original}"
            record[normalized] = {
                "value": value,
                "display_value": value,
                "source_cell": source_ref,
                "formula": None,
                "formula_status": None,
                "source_sheet": source_sheet,
                "lineage": {
                    "workbook_id": workbook_id,
                    "file_name": file_name,
                    "sheet_name": source_sheet,
                    "table_id": table_id,
                    "source_range": cache["range"],
                    "source_cell": source_ref,
                    "source_row": row_index,
                    "source_column": original,
                    "original_value": value,
                    "normalized_value": value,
                },
            }
        records.append(record)
    return records


def _parse_shipment_share_cache(
    file_path, cache, max_records_per_table, use_llm,
    baseline_file_path=None, baseline_cache=None,
    supply_chain_file_path=None, supply_chain_cache=None,
):
    workbook_id = f"sha256:{_file_hash(file_path)}"
    file_name = os.path.basename(file_path)
    records = _pivot_cache_records(cache, file_name, workbook_id)
    baseline_records = []
    baseline_source = None
    if baseline_file_path and baseline_cache:
        baseline_id = f"sha256:{_file_hash(baseline_file_path)}"
        baseline_name = os.path.basename(baseline_file_path)
        baseline_records = _pivot_cache_records(
            baseline_cache, baseline_name, baseline_id, "Shipment share (Y22 baseline)"
        )
        baseline_source = {
            "workbook_id": baseline_id,
            "file_name": baseline_name,
            "role": "Y22 baseline",
        }
    supply_chain_records = []
    supply_chain_source = None
    if supply_chain_file_path and supply_chain_cache:
        supply_chain_id = f"sha256:{_file_hash(supply_chain_file_path)}"
        supply_chain_name = os.path.basename(supply_chain_file_path)
        supply_chain_records = _pivot_cache_records(
            supply_chain_cache, supply_chain_name, supply_chain_id,
            "Panel maker to client pivot", "panel-maker-client-pivot-cache",
        )
        supply_chain_source = {
            "workbook_id": supply_chain_id,
            "file_name": supply_chain_name,
            "role": "Supply Chain customer/region",
        }
    metric_table = {**cache, "records": records}
    preview_records = records if max_records_per_table is None else records[:max_records_per_table]
    result = {
        "workbook_id": workbook_id,
        "file_name": file_name,
        "source_workbooks": [item for item in [{
            "workbook_id": workbook_id,
            "file_name": file_name,
            "role": "Y23-Y25 current",
        }, baseline_source, supply_chain_source] if item],
        "sheets": [{
            "sheet_name": "Shipment share",
            "sheet_index": None,
            "max_row": len(records),
            "max_column": len(cache["fields"]),
            "effective_range": cache["range"],
            "merged_ranges": [],
            "tables": [{
                "table_id": "shipment-share-pivot-cache",
                "table_name": cache["table"],
                "source": {"sheet": "Shipment share", "range": cache["range"]},
                "header": None,
                "data": {"record_count": len(records)},
                "fields": [{"name": normalize_field_name(name), "original": name} for name in cache["fields"]],
                "records": preview_records,
                "notes": ["从Shipment share内嵌Pivot Cache读取全量明细"],
                "source_notes": [],
                "quality": {
                    "structure_confidence": 1.0,
                    "field_mapping_confidence": 1.0,
                    "cell_lineage_coverage": 1.0,
                    "validation_errors": [],
                    "llm_interpreted": False,
                    "review_required": False,
                },
            }],
        }] + ([{
            "sheet_name": "Panel maker to client pivot",
            "sheet_index": None,
            "max_row": len(supply_chain_records),
            "max_column": len(supply_chain_cache["fields"]),
            "effective_range": supply_chain_cache["range"],
            "merged_ranges": [],
            "tables": [{
                "table_id": "panel-maker-client-pivot-cache",
                "table_name": supply_chain_cache["table"],
                "source": {"sheet": "Panel maker to client pivot", "range": supply_chain_cache["range"]},
                "header": None,
                "data": {"record_count": len(supply_chain_records)},
                "fields": [{"name": normalize_field_name(name), "original": name} for name in supply_chain_cache["fields"]],
                "records": supply_chain_records if max_records_per_table is None else supply_chain_records[:max_records_per_table],
                "notes": ["从Panel maker to client pivot内嵌Pivot Cache读取全量明细"],
                "source_notes": [],
                "quality": {
                    "structure_confidence": 1.0, "field_mapping_confidence": 1.0,
                    "cell_lineage_coverage": 1.0, "validation_errors": [],
                    "llm_interpreted": False, "review_required": False,
                },
            }],
        }] if supply_chain_cache else []),
        "quality": {
            "cell_match_rate": 1.0,
            "value_source": "ooxml_pivot_cache",
            "llm_requested": use_llm,
            "llm_used": False,
            "llm_model": None,
            "llm_success_count": 0,
            "llm_attempt_count": 0,
            "llm_call_limit": 0,
            "llm_skipped_count": 1 if use_llm else 0,
            "llm_fallback_count": 0,
            "llm_rejected_count": 0,
            "llm_errors": [],
            "structure_note": "Pivot Cache已提供精确字段定义，无需LLM识别表头",
        },
    }
    result["computed_metrics"] = calculate_competitive_metrics([metric_table])
    maker_details = {}
    detail_gaps = []
    maker_names = _report_maker_names(records, supply_chain_records)
    for maker in maker_names:
        detail = {
            "history": calculate_tianma_history_metrics(records, baseline_records, maker),
            "product": calculate_tianma_product_metrics(records, baseline_records, maker),
            "customer": calculate_tianma_customer_metrics(supply_chain_records, maker),
            "application": calculate_tianma_application_metrics(records, baseline_records, maker),
        }
        maker_details[maker] = detail
        for section in detail.values():
            detail_gaps.extend(section.get("data_gaps") or [])
    result["computed_metrics"]["maker_details"] = maker_details
    # Backward-compatible keys retained for existing reports and front-end routes.
    result["computed_metrics"]["tianma_history"] = maker_details["Tianma"]["history"]
    result["computed_metrics"]["tianma_product"] = maker_details["Tianma"]["product"]
    result["computed_metrics"]["tianma_customer"] = maker_details["Tianma"]["customer"]
    result["computed_metrics"]["tianma_application"] = maker_details["Tianma"]["application"]
    result["computed_metrics"]["data_gaps"] = list(dict.fromkeys(
        (result["computed_metrics"].get("data_gaps") or []) + detail_gaps
    ))
    return result


def _report_maker_names(records: list[dict[str, Any]], supply_chain_records: list[dict[str, Any]]) -> list[str]:
    """Return only explicitly configured report makers; market totals still include every maker."""
    aliases = {"tianma": "Tianma", "auo": "AUO", "boe": "BOE", "csot": "CSOT",
               "china star": "CSOT", "china_star": "CSOT", "tcl csot": "CSOT"}
    configured = [item.strip() for item in os.getenv("REPORT_TEMPLATE_MAKERS", "").split(",") if item.strip()]
    requested = configured or ["Tianma", "AUO", "CSOT", "BOE"]
    names = []
    for raw in requested:
        normalized = aliases.get(raw.lower(), raw)
        if normalized not in names:
            names.append(normalized)
    if "Tianma" not in names:
        names.insert(0, "Tianma")
    return names
