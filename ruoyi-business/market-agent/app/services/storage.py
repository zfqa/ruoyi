from __future__ import annotations
import hashlib
import json
import re
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
import pandas as pd
from app.core.config import get_settings
from app.models.schemas import ContextItem

COMPREHENSIVE_SHEET_NAME = "综合视图"
_CONTEXT_UPLOAD_PREFIX = re.compile(r"^context_[0-9a-f]{8}_", re.IGNORECASE)


def _context_source_identity(value: Any) -> str:
    """Treat old temporary upload prefixes as the same original source."""
    return _CONTEXT_UPLOAD_PREFIX.sub("", str(value or ""))


def processed_base(dataset_id: str) -> Path:
    return get_settings().processed_dir / dataset_id


def save_dataset(dataset_id: str, df: pd.DataFrame, meta: dict[str, Any]) -> None:
    base = processed_base(dataset_id)
    base.mkdir(parents=True, exist_ok=True)
    df.to_csv(base / "data.csv", index=False, encoding="utf-8-sig")
    (base / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    if not (base / "context.json").exists():
        (base / "context.json").write_text("[]", encoding="utf-8")


def _read_csv(path: Path) -> pd.DataFrame:
    # Preserve literal business category N/A; only real empty strings become NA.
    return pd.read_csv(path, keep_default_na=False, na_values=[""])


def load_dataset(dataset_id: str) -> pd.DataFrame:
    path = processed_base(dataset_id) / "data.csv"
    if not path.exists():
        raise FileNotFoundError(f"dataset_id 不存在：{dataset_id}")
    return _read_csv(path)


def load_meta(dataset_id: str) -> dict[str, Any]:
    path = processed_base(dataset_id) / "meta.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def _write_meta(dataset_id: str, meta: dict[str, Any]) -> None:
    path = processed_base(dataset_id) / "meta.json"
    path.write_text(json.dumps(meta, ensure_ascii=False, indent=2, default=str), encoding="utf-8")


def load_context(dataset_id: str) -> list[dict[str, Any]]:
    path = processed_base(dataset_id) / "context.json"
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            return []
        changed = False
        for item in data:
            if not item.get("id"):
                item["id"] = uuid.uuid4().hex
                changed = True
            if not item.get("created_at"):
                item["created_at"] = datetime.now(timezone.utc).isoformat()
                changed = True
            content_hash = hashlib.sha256(str(item.get("content") or "").encode("utf-8")).hexdigest()
            if item.get("content_sha256") != content_hash:
                item["content_sha256"] = content_hash
                changed = True
        if changed:
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        return data
    except Exception:
        return []


def append_context(dataset_id: str, items: list[ContextItem | dict[str, Any]]) -> list[dict[str, Any]]:
    base = processed_base(dataset_id)
    if not (base / "data.csv").exists():
        raise FileNotFoundError(f"dataset_id 不存在：{dataset_id}")
    current = load_context(dataset_id)
    for item in items:
        record = item.model_dump() if hasattr(item, "model_dump") else dict(item)
        record.setdefault("id", uuid.uuid4().hex)
        record.setdefault("created_at", datetime.now(timezone.utc).isoformat())
        record["content_sha256"] = hashlib.sha256(str(record.get("content") or "").encode("utf-8")).hexdigest()
        signature = (_context_source_identity(record.get("source_name")), record.get("locator"), record.get("title"), record.get("content"))
        if not any((_context_source_identity(x.get("source_name")), x.get("locator"), x.get("title"), x.get("content")) == signature for x in current):
            current.append(record)
    (base / "context.json").write_text(json.dumps(current, ensure_ascii=False, indent=2), encoding="utf-8")
    return current


def clear_context(dataset_id: str) -> None:
    base = processed_base(dataset_id)
    if not (base / "data.csv").exists():
        raise FileNotFoundError(f"dataset_id 不存在：{dataset_id}")
    (base / "context.json").write_text("[]", encoding="utf-8")


def delete_context_items(dataset_id: str, item_ids: list[str]) -> list[dict[str, Any]]:
    """Delete only explicitly selected context records and persist the result."""
    base = processed_base(dataset_id)
    if not (base / "data.csv").exists():
        raise FileNotFoundError(f"dataset_id 不存在：{dataset_id}")
    selected = {str(x) for x in item_ids if str(x).strip()}
    current = load_context(dataset_id)
    remaining = [item for item in current if str(item.get("id")) not in selected]
    (base / "context.json").write_text(json.dumps(remaining, ensure_ascii=False, indent=2), encoding="utf-8")
    return remaining


def delete_dataset(dataset_id: str) -> dict[str, int]:
    """Remove one dataset and only files recorded as belonging to it.

    Every resolved path is checked against a configured storage root before removal.
    """
    base = processed_base(dataset_id)
    if not (base / "data.csv").exists():
        raise FileNotFoundError(f"dataset_id 不存在：{dataset_id}")
    settings = get_settings()
    meta = load_meta(dataset_id)
    removed_uploads = 0
    upload_root = settings.upload_dir.resolve()
    for value in meta.get("upload_paths") or []:
        path = Path(value).resolve()
        if path.parent == upload_root and path.is_file():
            path.unlink()
            removed_uploads += 1
    removed_reports = 0
    report_root = settings.report_dir.resolve()
    for path in report_root.glob(f"*{dataset_id}*"):
        resolved = path.resolve()
        if resolved.parent == report_root and resolved.is_file():
            resolved.unlink()
            removed_reports += 1
    processed_root = settings.processed_dir.resolve()
    resolved_base = base.resolve()
    if resolved_base.parent != processed_root:
        raise ValueError("数据集存储路径校验失败")
    shutil.rmtree(resolved_base)
    removed_jobs = 0
    job_root = settings.job_dir.resolve()
    for job_file in job_root.glob("*/job.json"):
        try:
            job = json.loads(job_file.read_text(encoding="utf-8"))
            if (job.get("result") or {}).get("dataset_id") != dataset_id:
                continue
            job_base = job_file.parent.resolve()
            if job_base.parent == job_root:
                shutil.rmtree(job_base)
                removed_jobs += 1
        except Exception:
            continue
    return {"removed_uploads": removed_uploads, "removed_reports": removed_reports, "removed_jobs": removed_jobs}


def _sheet_file_name(label: str) -> str:
    safe = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff._-]+", "_", str(label)).strip("._") or "sheet"
    digest = hashlib.sha1(str(label).encode("utf-8")).hexdigest()[:8]
    return f"sheet_{safe[:60]}_{digest}.csv"


def save_sheet_datasets(dataset_id: str, df: pd.DataFrame) -> dict[str, str]:
    """Save each file+Sheet pair independently; never mix identical Sheet1 names."""
    base = processed_base(dataset_id)
    result: dict[str, str] = {}
    sheet_col = "source_sheet_name" if "source_sheet_name" in df.columns else "source_sheet" if "source_sheet" in df.columns else None
    if not sheet_col:
        return result
    file_col = "source_file" if "source_file" in df.columns else None
    if file_col:
        pairs = df[[file_col, sheet_col]].drop_duplicates().copy()
        counts = pairs[sheet_col].astype(str).value_counts().to_dict()
        for (source_file, sheet), part in df.groupby([file_col, sheet_col], dropna=False, sort=False):
            sf, sn = str(source_file), str(sheet)
            label = sn if int(counts.get(sn, 0)) == 1 else f"{sf} :: {sn}"
            fn = _sheet_file_name(label)
            part.drop(columns=["source_sheet_name"], errors="ignore").to_csv(base / fn, index=False, encoding="utf-8-sig")
            result[label] = fn
    else:
        for sheet, part in df.groupby(sheet_col, dropna=False, sort=False):
            sn = str(sheet)
            fn = _sheet_file_name(sn)
            part.drop(columns=["source_sheet_name"], errors="ignore").to_csv(base / fn, index=False, encoding="utf-8-sig")
            result[sn] = fn
    return result


def sheet_selector_meta(dataset_id: str) -> list[dict[str, Any]]:
    meta = load_meta(dataset_id)
    metas = meta.get("sheet_meta") or []
    out: list[dict[str, Any]] = []
    for label in (meta.get("sheet_files") or {}):
        candidates = []
        for m in metas:
            sheet, source = str(m.get("sheet_name", "")), str(m.get("source_file", ""))
            if label == sheet or label == f"{source} :: {sheet}":
                candidates.append(m)
        item = dict(candidates[0]) if len(candidates) == 1 else {"sheet_name": label}
        item["analysis_key"] = label
        item["display_name"] = label
        out.append(item)
    return out


def load_sheet_dataset(dataset_id: str, sheet_name: str) -> pd.DataFrame:
    if sheet_name == COMPREHENSIVE_SHEET_NAME:
        return build_comprehensive_dataset(dataset_id)
    meta = load_meta(dataset_id)
    fn = (meta.get("sheet_files") or {}).get(sheet_name) or _sheet_file_name(sheet_name)
    path = processed_base(dataset_id) / fn
    if not path.exists():
        raise FileNotFoundError(f"Sheet数据不存在：{sheet_name}")
    return _read_csv(path)


def _can_build_comprehensive(meta: dict[str, Any]) -> bool:
    rows = meta.get("sheet_meta") or []
    metrics = {(x.get("detected_metric"), x.get("metric_scope")) for x in rows}
    return any(m == "wholesale" for m, _ in metrics) and (any(m == "export" for m, _ in metrics) or any(m == "retail_sales" and s == "domestic" for m, s in metrics))


def available_sheets(dataset_id: str, include_comprehensive: bool = True) -> list[str]:
    meta = load_meta(dataset_id)
    sheets = list((meta.get("sheet_files") or {}).keys())
    if include_comprehensive and len(sheets) >= 2 and _can_build_comprehensive(meta):
        sheets.append(COMPREHENSIVE_SHEET_NAME)
    return sheets


def _period_total(df: pd.DataFrame, metric: str) -> pd.Series:
    if df.empty or metric not in df.columns or "time_period" not in df.columns:
        return pd.Series(dtype=float)
    work = df.copy()
    if "source_row_kind" in work.columns:
        work = work[work["source_row_kind"].fillna("detail").astype(str) != "summary"]
    vals = pd.to_numeric(work[metric], errors="coerce")
    work = work[vals.notna()].copy()
    work[metric] = vals[vals.notna()]
    return work.groupby(work["time_period"].astype(str), sort=False)[metric].sum() if not work.empty else pd.Series(dtype=float)


def build_comprehensive_dataset(dataset_id: str) -> pd.DataFrame:
    """Formula view. Never blindly adds Sheet values.

    domestic_wholesale = total_wholesale - export_wholesale
    inventory = domestic_wholesale - domestic_retail_sales
    """
    meta = load_meta(dataset_id)
    sheet_meta = meta.get("sheet_meta") or []
    total_wholesale = pd.Series(dtype=float); export = pd.Series(dtype=float); domestic_retail = pd.Series(dtype=float)
    selector = sheet_selector_meta(dataset_id)
    for item in selector:
        key = item.get("analysis_key")
        if not key:
            continue
        try:
            sdf = load_sheet_dataset(dataset_id, key)
        except Exception:
            continue
        metric, scope = item.get("detected_metric"), item.get("metric_scope")
        if metric == "wholesale" and total_wholesale.empty:
            total_wholesale = _period_total(sdf, "wholesale")
        elif metric == "export" and export.empty:
            export = _period_total(sdf, "export")
        elif metric == "retail_sales" and scope == "domestic" and domestic_retail.empty:
            domestic_retail = _period_total(sdf, "retail_sales")
    periods = sorted(set(total_wholesale.index) | set(export.index) | set(domestic_retail.index))
    rows: list[dict[str, Any]] = []
    for period in periods:
        row: dict[str, Any] = {
            "time_period": period, "market": "总体市场", "source_file": meta.get("file_name", ""),
            "source_sheet": COMPREHENSIVE_SHEET_NAME, "source_sheet_name": COMPREHENSIVE_SHEET_NAME,
            "source_table_type": "formula_comprehensive_view", "source_parser_id": "formula_comprehensive_view",
            "source_metric_type": "multi_metric", "source_metric_label": "综合业务公式",
            "source_semantic_confidence": 1.0, "source_row_kind": "detail",
        }
        tw = total_wholesale.get(period) if period in total_wholesale.index else None
        ex = export.get(period) if period in export.index else None
        retail = domestic_retail.get(period) if period in domestic_retail.index else None
        if tw is not None and pd.notna(tw): row["wholesale"] = float(tw)
        if ex is not None and pd.notna(ex): row["export"] = float(ex)
        if retail is not None and pd.notna(retail): row["retail_sales"] = float(retail)
        if tw is not None and pd.notna(tw) and ex is not None and pd.notna(ex): row["domestic_wholesale"] = float(tw) - float(ex)
        if row.get("domestic_wholesale") is not None and retail is not None and pd.notna(retail): row["inventory"] = float(row["domestic_wholesale"]) - float(retail)
        rows.append(row)
    return pd.DataFrame(rows)
