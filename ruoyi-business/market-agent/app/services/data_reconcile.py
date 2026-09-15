from __future__ import annotations

from typing import Any
import math
import pandas as pd

DIMENSIONS = [
    "time_period", "region", "market", "vehicle_type", "oem", "brand", "model",
    "power_type", "size_class", "tech_route",
]
MEASURES = [
    "production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export", "inventory",
    "yoy_production", "yoy_sales", "yoy_retail_sales", "yoy_wholesale", "yoy_domestic_sales", "yoy_domestic_wholesale", "yoy_export",
]


def _same_numbers(vals: list[float], tol: float = 1e-9) -> bool:
    if len(vals) <= 1:
        return True
    first = vals[0]
    return all(math.isclose(first, x, rel_tol=1e-7, abs_tol=tol) for x in vals[1:])


def reconcile_metric_rows(df: pd.DataFrame) -> tuple[pd.DataFrame, list[str]]:
    """把来自“销量表/产量表/库存表”等分表的同粒度行合并成统一数据模型。

    例：
      2025-11 + 总体市场 + production=353.2
      2025-11 + 总体市场 + wholesale=342.9
    会合并为同一行，供市场观察表同时展示。

    冲突值不会求和或猜测，保留首个值并产生警告。
    """
    if df.empty:
        return df.copy(), []
    work = df.copy()
    present_dims = [c for c in DIMENSIONS if c in work.columns]
    # 只让确实含有数值的指标参与合并。宽表标准化时可能保留一个全空的
    # yoy_*列；逐组处理这种空列既没有业务意义，也会把大表耗时放大。
    present_measures: list[str] = []
    for column in [c for c in MEASURES if c in work.columns]:
        numeric = pd.to_numeric(work[column], errors="coerce")
        if numeric.notna().any():
            work[column] = numeric
            present_measures.append(column)
    if not present_measures:
        return work, []

    # 没有维度时不做行折叠，避免把不同来源明细误合并。
    if not present_dims:
        return work, []

    lineage_cols = [c for c in [
        "source_file", "source_sheet", "source_row", "source_column",
        "source_column_index", "source_cell", "source_metric_type",
    ] if c in work.columns]

    # 如果每一行的业务粒度本来就是唯一的，合并不会改变任何数字或血缘。
    # 这类单Sheet明细表直接返回，避免为上万条记录创建上万个小DataFrame。
    # duplicated会把同位置的空维度视为相同，因而不会漏掉需要合并的空值行。
    if not work.duplicated(subset=present_dims, keep=False).any():
        # 与常规合并路径保持同样的“按指标血缘”契约，但使用整列赋值，
        # 保证快速通道不会牺牲来源准确性。
        for metric in present_measures:
            metric_mask = work[metric].notna()
            for lineage in lineage_cols:
                mask = metric_mask & work[lineage].notna() & work[lineage].astype(str).str.strip().ne("")
                work.loc[mask, f"{lineage}_{metric}"] = work.loc[mask, lineage].astype(str)
        return work, []

    warnings: list[str] = []
    sentinel = "__NULL_DIM__"
    for c in present_dims:
        work[c] = work[c].where(work[c].notna(), sentinel).astype(str)

    rows: list[dict[str, Any]] = []

    grouped = work.groupby(present_dims, dropna=False, sort=False)
    for keys, group in grouped:
        if not isinstance(keys, tuple):
            keys = (keys,)
        row: dict[str, Any] = {c: (None if v == sentinel else v) for c, v in zip(present_dims, keys)}
        for m in present_measures:
            metric_mask = group[m].notna()
            metric_rows = group[metric_mask]
            vals = metric_rows[m].dropna().tolist()
            if not vals:
                row[m] = None
            else:
                unique_vals: list[float] = []
                for v in vals:
                    fv = float(v)
                    if not any(math.isclose(fv, x, rel_tol=1e-7, abs_tol=1e-9) for x in unique_vals):
                        unique_vals.append(fv)
                row[m] = unique_vals[0]
                if len(unique_vals) > 1:
                    key_text = ", ".join(f"{c}={row.get(c)}" for c in present_dims if row.get(c)) or "无维度"
                    warnings.append(f"同一粒度的{m}出现冲突值{unique_vals[:4]}（{key_text}），系统未求和，保留首个值并要求人工复核。")
                # 同一业务粒度可能来自多个指标Sheet。除保留合并后的通用血缘外，
                # 还要保留该指标自己的原始单元格，避免销量异常误指向产量列。
                for lc in lineage_cols:
                    metric_lineage = [str(x) for x in metric_rows[lc].dropna().tolist() if str(x).strip()]
                    if metric_lineage:
                        row[f"{lc}_{m}"] = " | ".join(dict.fromkeys(metric_lineage))[:1000]
        for lc in lineage_cols:
            vals = [str(x) for x in group[lc].dropna().tolist() if str(x).strip()]
            if vals:
                row[lc] = " | ".join(dict.fromkeys(vals))[:1000]
        rows.append(row)

    out = pd.DataFrame(rows)
    # 保留存在但未参与合并的普通列，仅在单值时带回，避免丢失明显标签。
    other_cols = [c for c in work.columns if c not in present_dims + present_measures + lineage_cols]
    if other_cols:
        # 不做复杂回填，原始df仍可通过存储文件追溯。
        pass
    return out, list(dict.fromkeys(warnings))
