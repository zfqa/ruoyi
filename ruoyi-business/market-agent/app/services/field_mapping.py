from __future__ import annotations

import re
from typing import Iterable, Any

from app.models.schemas import STANDARD_FIELDS
from app.services.template_registry import (
    EXACT_FIELD_ALIASES,
    exact_field_for_header,
    clean_token,
    IGNORE_HEADERS,
)

# 向后兼容其他模块/测试引用。
FIELD_SYNONYMS: dict[str, list[str]] = {k: list(v) for k, v in EXACT_FIELD_ALIASES.items()}
DIMENSION_FIELDS = {
    "region", "market", "vehicle_type", "oem", "brand", "model",
    "power_type", "size_class", "tech_route",
}


def clean_header(h: object) -> str:
    return clean_token(h)


def map_headers_with_confidence(headers: Iterable[object]) -> tuple[dict[str, str], dict[str, float]]:
    """固定模板字段映射：只接受注册表精确别名。

    命中即为1.0；未命中就是未映射，不再生成0.96/0.92等“看起来接近”的概率值。
    """
    mapping: dict[str, str] = {}
    confidences: dict[str, float] = {}
    used: set[str] = set()
    for raw in headers:
        src = str(raw)
        field = exact_field_for_header(raw)
        if field and field not in used:
            mapping[src] = field
            confidences[src] = 1.0
            used.add(field)
    return mapping, confidences


def map_headers(headers: Iterable[object]) -> dict[str, str]:
    return map_headers_with_confidence(headers)[0]


def is_period_header_name(name: object) -> bool:
    h = clean_header(name)
    return bool(re.fullmatch(r"20\d{2}(?:0[1-9]|1[0-2])(?:\.0)?", h))


def _has_business_value(series) -> bool:
    if series is None:
        return False
    for x in series.tolist():
        if x is None:
            continue
        try:
            import pandas as pd
            if isinstance(x, float) and pd.isna(x):
                continue
        except Exception:
            pass
        if str(x).strip() != "":
            return True
    return False


def deterministic_field_validation(df, mapping: dict[str, str], confidences: dict[str, float]) -> dict[str, Any]:
    """返回可审计的字段确定性校验，不把未知列伪装成95%/99%。

    - 精确注册字段：100%
    - 明确忽略列（序号/备注/单位等）：不进入核心映射分母
    - 年月列：由周期模块独立校验
    - 自动生成的“列32”等无表头列：单独列为辅助未命名列，不冒充已映射字段
    - 其他有数据的命名列：视为未注册字段，核心字段校验不通过
    """
    if df is None or not hasattr(df, "columns"):
        return {
            "score": 0.0, "status": "FAIL", "mapped_columns": [], "unmapped_named_columns": [],
            "unlabeled_data_columns": [], "ignored_columns": [], "details": "无可校验DataFrame",
        }

    mapped: list[str] = []
    unknown_named: list[str] = []
    unlabeled: list[str] = []
    ignored: list[str] = []
    for col in df.columns:
        name = str(col)
        if is_period_header_name(name):
            continue
        if not _has_business_value(df[name]):
            continue
        if re.fullmatch(r"列\d+", name):
            unlabeled.append(name)
            continue
        if clean_header(name) in IGNORE_HEADERS:
            ignored.append(name)
            continue
        if name in mapping and confidences.get(name) == 1.0:
            mapped.append(name)
        else:
            unknown_named.append(name)

    # 核心“已命名业务字段”必须全部精确映射，才是100%。无表头辅助列另行提示，不偷换概念。
    score = 1.0 if not unknown_named and (bool(mapped) or not any(_has_business_value(df[c]) for c in df.columns)) else 0.0
    status = "PASS" if score == 1.0 else "REVIEW"
    return {
        "score": score,
        "status": status,
        "mapped_columns": mapped,
        "mapped_count": len(mapped),
        "unmapped_named_columns": unknown_named,
        "unlabeled_data_columns": unlabeled,
        "ignored_columns": ignored,
        "details": "所有已命名核心业务字段均为注册表精确映射" if score == 1.0 else "存在未注册命名字段，禁止宣称100%",
    }


def field_mapping_confidence(df, mapping: dict[str, str], confidences: dict[str, float]) -> float:
    """向后兼容字段：现在只返回确定性0或1。"""
    return float(deterministic_field_validation(df, mapping, confidences)["score"])


def known_keyword_count(row_values: list[object]) -> int:
    score = 0
    for v in row_values:
        if exact_field_for_header(v):
            score += 1
    return score


def standard_fields() -> list[str]:
    return STANDARD_FIELDS.copy()
