from __future__ import annotations
from typing import Any
import pandas as pd
from app.models.schemas import ParseIssue

CORE_METRICS = ["production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export"]
SALES_FIELDS = ["sales", "retail_sales", "wholesale"]
CORE_DIMENSIONS = ["market", "oem", "brand", "model", "power_type", "vehicle_type"]

def _primary_sales(df: pd.DataFrame) -> str | None:
    for f in ["sales", "retail_sales", "wholesale"]:
        if f in df.columns and df[f].dropna().any():
            return f
    return None

MAX_ISSUE_LOCATIONS = 100


def _plain_value(value: Any) -> Any:
    if value is None or (not isinstance(value, (list, dict)) and pd.isna(value)):
        return None
    if hasattr(value, "item"):
        value = value.item()
    return value


def _source_locations(frame: pd.DataFrame) -> tuple[list[dict[str, Any]], int, int, bool]:
    """Return original workbook locations, never transformed DataFrame indexes."""
    location_columns = [
        column for column in
        ["source_file", "source_sheet", "source_row", "source_column", "source_column_index", "source_cell"]
        if column in frame.columns
    ]
    if not location_columns:
        return [], int(len(frame)), int(len(frame)), False

    locations: list[dict[str, Any]] = []
    location_keys: set[tuple[Any, ...]] = set()
    source_row_keys: set[tuple[Any, ...]] = set()
    for _, record in frame[location_columns].iterrows():
        location = {column: _plain_value(record.get(column)) for column in location_columns}
        location = {key: value for key, value in location.items() if value is not None and value != ""}
        if not location:
            continue
        row_key = (
            location.get("source_file"), location.get("source_sheet"), location.get("source_row")
        )
        if location.get("source_row") is not None:
            source_row_keys.add(row_key)
        location_key = (
            location.get("source_file"), location.get("source_sheet"), location.get("source_row"),
            location.get("source_cell"), location.get("source_column"), location.get("source_column_index"),
        )
        if location_key not in location_keys:
            location_keys.add(location_key)
            locations.append(location)

    source_count = len(source_row_keys) if source_row_keys else len(location_keys) or int(len(frame))
    location_count = len(locations)
    return locations[:MAX_ISSUE_LOCATIONS], source_count, location_count, location_count > MAX_ISSUE_LOCATIONS


def _located_issue(level: str, code: str, message: str, frame: pd.DataFrame,
                   column: str | None = None, source_file: str | None = None) -> ParseIssue:
    locations, source_count, location_count, truncated = _source_locations(frame)
    files = {str(item["source_file"]) for item in locations if item.get("source_file")}
    sheets = {str(item["source_sheet"]) for item in locations if item.get("source_sheet")}
    rows = {int(item["source_row"]) for item in locations if item.get("source_row") is not None}
    return ParseIssue(
        level=level,
        code=code,
        message=message,
        source_file=source_file or (next(iter(files)) if len(files) == 1 else None),
        sheet=next(iter(sheets)) if len(sheets) == 1 else None,
        row=next(iter(rows)) if len(rows) == 1 else None,
        column=column,
        locations=locations,
        source_record_count=source_count,
        standardized_record_count=int(len(frame)),
        location_count=location_count,
        locations_truncated=truncated,
    )


def validate_market_data(df: pd.DataFrame, source_file: str | None = None,
                         check_required_metrics: bool = True) -> list[ParseIssue]:
    issues: list[ParseIssue] = []
    if df.empty:
        return [ParseIssue(level="error", code="NO_DATA", message="未解析到有效数据", source_file=source_file)]
    if check_required_metrics and _primary_sales(df) is None:
        issues.append(ParseIssue(level="warning", code="MISSING_SALES_METRIC", message="未获得销量/零售销量/批发销量任一可用口径；不自动推断。", source_file=source_file))
    for metric in [c for c in CORE_METRICS + ["inventory"] if c in df.columns]:
        bad = df[df[metric].notna() & ~pd.to_numeric(df[metric], errors="coerce").notna()]
        if not bad.empty:
            locations, source_count, _, _ = _source_locations(bad)
            message = f"字段 {metric} 在 {source_count} 个原表数据行中存在无法转换为数值的内容"
            issues.append(_located_issue("warning", "TYPE_ERROR", message, bad, column=metric, source_file=source_file))
    key_cols = [c for c in ["time_period", "market", "oem", "brand", "model", "source_sheet"] if c in df.columns]
    if key_cols:
        dup = df[df.duplicated(key_cols, keep=False)]
        if not dup.empty:
            _, source_count, _, _ = _source_locations(dup)
            message = (
                f"发现 {source_count} 个原表数据行可能重复"
                f"（对应 {len(dup)} 条标准化记录），依据字段：{', '.join(key_cols)}"
            )
            issues.append(_located_issue("warning", "DUPLICATE_ROWS", message, dup, source_file=source_file))
    return issues

def missing_required_for(question_type: str, df: pd.DataFrame) -> list[str]:
    sales = _primary_sales(df)
    required = {
        "inventory": ["inventory"],
        "ranking_oem": ["oem"] + ([sales] if sales else ["sales/retail_sales/wholesale"]),
        "ranking_brand": ["brand"] + ([sales] if sales else ["sales/retail_sales/wholesale"]),
        "ranking_model": ["model"] + ([sales] if sales else ["sales/retail_sales/wholesale"]),
        "power_share": ["power_type"] + ([sales] if sales else ["sales/retail_sales/wholesale"]),
        "trend": ["time_period"] + ([sales] if sales else ["sales/retail_sales/wholesale"]),
    }.get(question_type, [])
    return [f for f in required if f not in df.columns or (f in df.columns and df[f].dropna().empty)]
