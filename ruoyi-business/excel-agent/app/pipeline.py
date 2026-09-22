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
from app.report.record_merge import merge_records_by_year_quarter, period_coverage
from app.report.periods import latest_omdia_data_through, parse_omdia_tracker_meta, workbook_overlay_rank
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
    extra_history_file_paths: list[str] | None = None,
    extra_supply_chain_file_paths: list[str] | None = None,
    file_label: str | None = None,
    baseline_file_label: str | None = None,
    supply_chain_file_label: str | None = None,
    extra_history_file_labels: list[str] | None = None,
    extra_supply_chain_file_labels: list[str] | None = None,
) -> dict[str, Any]:
    pivot_cache = extract_shipment_share_cache(file_path)
    if pivot_cache is not None:
        baseline_cache = extract_shipment_share_cache(baseline_file_path) if baseline_file_path else None
        supply_chain_cache = extract_supply_chain_cache(supply_chain_file_path) if supply_chain_file_path else None
        return _parse_shipment_share_cache(
            file_path, pivot_cache, max_records_per_table, use_llm,
            baseline_file_path, baseline_cache, supply_chain_file_path, supply_chain_cache,
            extra_history_file_paths=extra_history_file_paths or [],
            extra_supply_chain_file_paths=extra_supply_chain_file_paths or [],
            file_label=file_label,
            baseline_file_label=baseline_file_label,
            supply_chain_file_label=supply_chain_file_label,
            extra_history_file_labels=extra_history_file_labels or [],
            extra_supply_chain_file_labels=extra_supply_chain_file_labels or [],
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


def _source_workbook_entry(
    workbook_id: str,
    file_name: str,
    role: str,
    original_file_name: str | None = None,
) -> dict[str, Any]:
    display_name = original_file_name or file_name
    entry = {
        "workbook_id": workbook_id,
        "file_name": display_name,
        "stored_file_name": file_name,
        "original_file_name": display_name,
        "role": role,
    }
    meta = parse_omdia_tracker_meta(display_name)
    if meta:
        entry["omdia_publication"] = meta["publication_label"]
        entry["omdia_data_through"] = meta["data_through_label"]
        entry["omdia_lag_quarters"] = meta["lag_quarters"]
    return entry


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
    extra_history_file_paths=None, extra_supply_chain_file_paths=None,
    file_label=None, baseline_file_label=None, supply_chain_file_label=None,
    extra_history_file_labels=None, extra_supply_chain_file_labels=None,
):
    workbook_id = f"sha256:{_file_hash(file_path)}"
    stored_name = os.path.basename(file_path)
    file_name = file_label or stored_name
    primary_records = _pivot_cache_records(cache, file_name, workbook_id)

    history_groups: list[tuple[str, list[dict[str, Any]]]] = []
    history_sources = []
    extra_history_labels = list(extra_history_file_labels or [])
    extra_history_entries = []
    for index, extra_path in enumerate(list(extra_history_file_paths or [])):
        if not extra_path:
            continue
        extra_cache = extract_shipment_share_cache(extra_path)
        if extra_cache is None:
            continue
        extra_id = f"sha256:{_file_hash(extra_path)}"
        extra_stored = os.path.basename(extra_path)
        extra_name = (
            extra_history_labels[index]
            if index < len(extra_history_labels) and extra_history_labels[index]
            else extra_stored
        )
        extra_records = _pivot_cache_records(
            extra_cache, extra_name, extra_id, "Shipment share (extra history)"
        )
        entry = _source_workbook_entry(
            extra_id, extra_stored, "extra history", original_file_name=extra_name,
        )
        extra_history_entries.append((workbook_overlay_rank(extra_name), extra_name, extra_records, entry))
    # Oldest extras first, current last: newer Year/Quarter replaces older.
    extra_history_entries.sort(key=lambda item: item[0])
    for _, extra_name, extra_records, entry in extra_history_entries:
        history_groups.append((extra_name, extra_records))
        history_sources.append(entry)
    history_groups.append((file_name, primary_records))
    records = merge_records_by_year_quarter(history_groups) if len(history_groups) > 1 else primary_records

    baseline_records = []
    baseline_source = None
    if baseline_file_path and baseline_cache:
        baseline_id = f"sha256:{_file_hash(baseline_file_path)}"
        baseline_stored = os.path.basename(baseline_file_path)
        baseline_name = baseline_file_label or baseline_stored
        baseline_records = _pivot_cache_records(
            baseline_cache, baseline_name, baseline_id, "Shipment share (Y22 baseline)"
        )
        baseline_source = _source_workbook_entry(
            baseline_id, baseline_stored, "Y22 baseline", original_file_name=baseline_name,
        )

    supply_groups: list[tuple[str, list[dict[str, Any]]]] = []
    supply_sources = []
    extra_supply_labels = list(extra_supply_chain_file_labels or [])
    extra_supply_entries = []
    for index, extra_path in enumerate(list(extra_supply_chain_file_paths or [])):
        if not extra_path:
            continue
        extra_cache = extract_supply_chain_cache(extra_path)
        if extra_cache is None:
            continue
        extra_id = f"sha256:{_file_hash(extra_path)}"
        extra_stored = os.path.basename(extra_path)
        extra_name = (
            extra_supply_labels[index]
            if index < len(extra_supply_labels) and extra_supply_labels[index]
            else extra_stored
        )
        extra_records = _pivot_cache_records(
            extra_cache, extra_name, extra_id,
            "Panel maker to client pivot", "panel-maker-client-pivot-cache",
        )
        entry = _source_workbook_entry(
            extra_id, extra_stored, "extra supply chain", original_file_name=extra_name,
        )
        extra_supply_entries.append((workbook_overlay_rank(extra_name), extra_name, extra_records, entry))
    extra_supply_entries.sort(key=lambda item: item[0])
    for _, extra_name, extra_records, entry in extra_supply_entries:
        supply_groups.append((extra_name, extra_records))
        supply_sources.append(entry)

    supply_chain_records = []
    supply_chain_source = None
    if supply_chain_file_path and supply_chain_cache:
        supply_chain_id = f"sha256:{_file_hash(supply_chain_file_path)}"
        supply_stored = os.path.basename(supply_chain_file_path)
        supply_chain_name = supply_chain_file_label or supply_stored
        supply_chain_records = _pivot_cache_records(
            supply_chain_cache, supply_chain_name, supply_chain_id,
            "Panel maker to client pivot", "panel-maker-client-pivot-cache",
        )
        supply_chain_source = _source_workbook_entry(
            supply_chain_id, supply_stored, "Supply Chain customer/region",
            original_file_name=supply_chain_name,
        )
        supply_groups.append((supply_chain_name, supply_chain_records))
    if len(supply_groups) > 1:
        supply_chain_records = merge_records_by_year_quarter(supply_groups)
    elif supply_groups:
        supply_chain_records = supply_groups[0][1]

    metric_table = {**cache, "records": records}
    preview_records = records if max_records_per_table is None else records[:max_records_per_table]
    source_workbooks = [item for item in [
        _source_workbook_entry(workbook_id, stored_name, "current history", original_file_name=file_name),
        *history_sources, baseline_source, supply_chain_source, *supply_sources,
    ] if item]
    result = {
        "workbook_id": workbook_id,
        "file_name": file_name,
        "source_workbooks": source_workbooks,
        "period_coverage": period_coverage(records),
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
                "notes": ["从Shipment share内嵌Pivot Cache读取全量明细，多History按Year/Quarter后写覆盖合并"],
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
            "max_column": len((supply_chain_cache or {"fields": []})["fields"]) if supply_chain_cache else (
                len(supply_groups[0][1][0]) if supply_groups and supply_groups[0][1] else 0
            ),
            "effective_range": (supply_chain_cache or {}).get("range"),
            "merged_ranges": [],
            "tables": [{
                "table_id": "panel-maker-client-pivot-cache",
                "table_name": (supply_chain_cache or {"table": "Panel maker to client pivot"}).get("table", "Panel maker to client pivot"),
                "source": {"sheet": "Panel maker to client pivot", "range": (supply_chain_cache or {}).get("range")},
                "header": None,
                "data": {"record_count": len(supply_chain_records)},
                "fields": (
                    [{"name": normalize_field_name(name), "original": name} for name in supply_chain_cache["fields"]]
                    if supply_chain_cache else []
                ),
                "records": supply_chain_records if max_records_per_table is None else supply_chain_records[:max_records_per_table],
                "notes": ["从Panel maker to client pivot内嵌Pivot Cache读取全量明细，多Supply按Year/Quarter后写覆盖合并"],
                "source_notes": [],
                "quality": {
                    "structure_confidence": 1.0, "field_mapping_confidence": 1.0,
                    "cell_lineage_coverage": 1.0, "validation_errors": [],
                    "llm_interpreted": False, "review_required": False,
                },
            }],
        }] if supply_chain_records else []),
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
    through = latest_omdia_data_through(
        [item.get("original_file_name") or item.get("file_name") for item in source_workbooks]
    )
    data_through_year = through.get("data_through_year") if through else None
    data_through_quarter = through.get("data_through_quarter") if through else None
    if through:
        result["omdia_publication_lag"] = through
    result["computed_metrics"] = calculate_competitive_metrics(
        [metric_table],
        data_through_year=data_through_year,
        data_through_quarter=data_through_quarter,
    )
    maker_details = {}
    detail_gaps = []
    maker_names = _report_maker_names(records, supply_chain_records)
    metrics_scope = (result["computed_metrics"].get("scope") or {})
    full_year = bool(metrics_scope.get("full_year"))
    for maker in maker_names:
        detail = {
            "history": calculate_tianma_history_metrics(
                records, baseline_records, maker,
                data_through_year=data_through_year,
                data_through_quarter=data_through_quarter,
                full_year=full_year,
            ),
            "product": calculate_tianma_product_metrics(
                records, baseline_records, maker,
                data_through_year=data_through_year,
                data_through_quarter=data_through_quarter,
                full_year=full_year,
            ),
            "customer": calculate_tianma_customer_metrics(
                supply_chain_records, maker,
                data_through_year=data_through_year,
                data_through_quarter=data_through_quarter,
                full_year=full_year,
            ),
            "application": calculate_tianma_application_metrics(
                records, baseline_records, maker,
                data_through_year=data_through_year,
                data_through_quarter=data_through_quarter,
                full_year=full_year,
            ),
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
