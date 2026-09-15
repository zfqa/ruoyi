from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any, Iterable

import pandas as pd
from openpyxl.utils import get_column_letter

from app.services.template_registry import (
    clean_token, match_metric_from_text, match_dimension_from_text,
    GENERIC_VALUE_HEADERS, GENERIC_DIMENSION_HEADERS,
)
from app.services.value_parser import normalize_text, parse_numeric, parse_date_like

PERIOD_HEADER_RE = re.compile(
    r"^(?:20\d{2}(?:0[1-9]|1[0-2])(?:\.0)?|20\d{2}[.\-/年]\d{1,2}(?:月)?|20\d{2}[Qq][1-4]|20\d{2}年[1-4]季度|20\d{2})$"
)


@dataclass
class SheetSemanticHint:
    metric: str | None
    dimension: str | None
    table_type: str
    confidence: float
    dimension_confidence: float
    evidence: list[str]
    metric_status: str = "REVIEW"
    dimension_status: str = "REVIEW"
    conflicts: list[str] | None = None


def classify_sheet_semantics(
    file_name: str,
    sheet_name: str,
    headers: Iterable[Any],
    sample_rows: list[list[Any]] | None = None,
) -> SheetSemanticHint:
    """固定模板确定性识别：Sheet名 > 文件名 > 明确指标表头。

    这里只给0/1，不再用0.92/0.96/0.99。出现冲突或多义时直接REVIEW，禁止伪装成100%。
    """
    evidence: list[str] = []
    conflicts: list[str] = []

    sm, skw, samb = match_metric_from_text(sheet_name)
    fm, fkw, famb = match_metric_from_text(file_name)
    if samb:
        conflicts.append(f"Sheet名同时命中多个指标词：{skw}")
    if famb:
        conflicts.append(f"文件名同时命中多个指标词：{fkw}")

    metric: str | None = None
    metric_status = "REVIEW"
    if sm and not samb:
        metric = sm
        metric_status = "PASS"
        evidence.append(f"Sheet名“{sheet_name}”确定性命中指标“{sm}”（{skw}）")
        if fm and fm != sm:
            compatible_generic = fm == "sales" and sm in {"retail_sales", "wholesale", "domestic_sales", "domestic_wholesale"}
            if compatible_generic:
                evidence.append(f"文件名仅给出泛化销量口径{fm}，与Sheet具体口径{sm}兼容，Sheet具体口径优先")
            else:
                conflicts.append(f"Sheet名指标{sm}与文件名指标{fm}冲突；保持Sheet名但整表进入人工复核")
                metric_status = "REVIEW"
    elif fm and not famb:
        metric = fm
        metric_status = "PASS"
        evidence.append(f"Sheet名未明确；文件名“{file_name}”确定性命中指标“{fm}”（{fkw}）")

    # 若名称均未说明指标，只有明确指标字段表头时才允许确定性识别；多指标表按multi_metric处理。
    if metric is None:
        header_metric_fields = []
        from app.services.template_registry import exact_field_for_header, registered_metric_keys
        metric_keys = set(registered_metric_keys())
        for h in headers:
            f = exact_field_for_header(h)
            if f in metric_keys:
                header_metric_fields.append(f)
        distinct = list(dict.fromkeys(header_metric_fields))
        if len(distinct) == 1:
            metric = distinct[0]
            metric_status = "PASS"
            evidence.append(f"Sheet/文件名未说明指标；表头精确字段唯一确定指标“{metric}”")
        elif len(distinct) > 1:
            metric = None
            metric_status = "PASS_MULTI"
            evidence.append(f"表头精确包含多个指标{distinct}，按多指标长表处理")

    sd, sdkw, sdamb = match_dimension_from_text(sheet_name)
    fd, fdkw, fdamb = match_dimension_from_text(file_name)
    dimension: str | None = None
    dimension_status = "REVIEW"
    if sdamb:
        evidence.append(f"Sheet名包含多个维度词“{sdkw}”，不强行指定单一维度")
    elif sd:
        dimension = sd
        dimension_status = "PASS"
        evidence.append(f"Sheet名确定性命中维度“{sd}”（{sdkw}）")
    elif fdamb:
        evidence.append(f"文件名包含多个维度词“{fdkw}”，等待表头字段确定实际维度")
    elif fd:
        dimension = fd
        dimension_status = "PASS"
        evidence.append(f"Sheet名未明确；文件名确定性命中维度“{fd}”（{fdkw}）")

    table_type = f"metric_{metric}" if metric else "unknown_table"
    if conflicts:
        evidence.extend(["冲突：" + x for x in conflicts])
    return SheetSemanticHint(
        metric=metric,
        dimension=dimension,
        table_type=table_type,
        confidence=1.0 if metric_status in {"PASS", "PASS_MULTI"} else 0.0,
        dimension_confidence=1.0 if dimension_status == "PASS" else 0.0,
        evidence=evidence,
        metric_status=metric_status,
        dimension_status=dimension_status,
        conflicts=conflicts,
    )


def is_period_header(value: Any) -> bool:
    text = normalize_text(value).replace(" ", "")
    return bool(text and PERIOD_HEADER_RE.match(text))


def normalize_period_header(value: Any) -> str:
    parsed = parse_date_like(value)
    return parsed or normalize_text(value)


def detect_period_columns(columns: Iterable[Any]) -> list[str]:
    return [str(c) for c in columns if is_period_header(c)]


def _numeric_ratio(series: pd.Series) -> float:
    if series.empty:
        return 0.0
    vals = series.dropna()
    if vals.empty:
        return 0.0
    parsed = vals.map(parse_numeric)
    return float(parsed.notna().mean())


def _text_ratio(series: pd.Series) -> float:
    vals = series.dropna()
    if vals.empty:
        return 0.0
    valid = 0
    for v in vals:
        text = normalize_text(v)
        if not text:
            continue
        if re.search(r"[A-Za-z\u4e00-\u9fff]", text):
            valid += 1
            continue
        compact = text.replace(",", "")
        if not re.fullmatch(r"[+-]?\d+(?:\.\d+)?(?:%|万|亿|辆|台)?", compact):
            valid += 1
    return valid / max(len(vals), 1)


def choose_semantic_dimension_column(df: pd.DataFrame, desired_dimension: str, mapped_source_columns: set[str]) -> tuple[str | None, str]:
    """确定性泛化对象列：只有“唯一泛化文本列”才允许按Sheet维度赋义。"""
    if df.empty:
        return None, "空表"
    generic_candidates = []
    other_text_candidates = []
    for idx, col in enumerate(df.columns):
        col_s = str(col)
        if col_s in mapped_source_columns or is_period_header(col_s):
            continue
        tr = _text_ratio(df[col_s])
        if tr < 0.75:
            continue
        if clean_token(col_s) in GENERIC_DIMENSION_HEADERS:
            generic_candidates.append(col_s)
        else:
            other_text_candidates.append(col_s)
    if len(generic_candidates) == 1:
        return generic_candidates[0], "唯一注册泛化对象列 + Sheet/文件维度明确"
    if len(generic_candidates) == 0 and len(other_text_candidates) == 1:
        return other_text_candidates[0], "唯一高文本占比未映射对象列 + Sheet/文件维度明确"
    return None, "对象列不唯一，禁止自动推断"


def choose_semantic_value_column(df: pd.DataFrame, mapped_source_columns: set[str]) -> tuple[str | None, str]:
    """确定性泛化数值列：优先唯一“数值/数量/本期”注册泛化列。"""
    if df.empty:
        return None, "空表"
    generic = []
    numeric = []
    for col in df.columns:
        col_s = str(col)
        if col_s in mapped_source_columns or is_period_header(col_s):
            continue
        ratio = _numeric_ratio(df[col_s])
        if ratio < 0.9:
            continue
        if clean_token(col_s) in GENERIC_VALUE_HEADERS:
            generic.append(col_s)
        else:
            numeric.append(col_s)
    if len(generic) == 1:
        return generic[0], "唯一注册泛化数值列"
    if len(generic) == 0 and len(numeric) == 1:
        return numeric[0], "唯一可解析数值列"
    return None, "数值列不唯一，禁止自动推断"


def reshape_wide_metric_table(
    df: pd.DataFrame,
    metric: str,
    header_mapping: dict[str, str],
    source_rows: list[int] | None = None,
    source_column_indexes: dict[str, int] | None = None,
) -> tuple[pd.DataFrame | None, list[str]]:
    period_cols = detect_period_columns(df.columns)
    if len(period_cols) < 2:
        return None, []
    measure_fields = {
        "production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export", "inventory",
        "yoy_production", "yoy_sales", "yoy_retail_sales", "yoy_wholesale", "yoy_domestic_sales", "yoy_domestic_wholesale", "yoy_export",
    }
    rename_dims = {src: std for src, std in header_mapping.items() if src in df.columns and std not in measure_fields}
    base = df.rename(columns=rename_dims).copy()
    if source_rows is not None and len(source_rows) == len(base):
        base["__source_excel_row"] = source_rows
    id_cols = [c for c in base.columns if c not in period_cols]
    melted = base.melt(id_vars=id_cols, value_vars=period_cols, var_name="time_period", value_name=metric)
    # 宽表的一条原始记录会展开为多个标准化记录。这里必须在周期表头被
    # 归一化前保存原Excel坐标，不能再用展开后的DataFrame序号冒充源行号。
    raw_period_headers = melted["time_period"].map(lambda value: str(value))
    if "__source_excel_row" in melted.columns:
        melted["source_row"] = pd.to_numeric(melted.pop("__source_excel_row"), errors="coerce").astype("Int64")
    melted["source_column"] = raw_period_headers
    if source_column_indexes:
        melted["source_column_index"] = raw_period_headers.map(source_column_indexes).astype("Int64")
        if "source_row" in melted.columns:
            melted["source_cell"] = [
                f"{get_column_letter(int(column))}{int(row)}"
                if pd.notna(column) and pd.notna(row) else None
                for column, row in zip(melted["source_column_index"], melted["source_row"])
            ]
    melted["time_period"] = melted["time_period"].map(normalize_period_header)
    melted[metric] = melted[metric].map(parse_numeric)
    melted = melted[melted[metric].notna()].copy()
    return melted, [
        f"确定性识别{len(period_cols)}个周期列并宽转长",
        f"周期写入time_period；数值按已验证表类型写入{metric}",
    ]
