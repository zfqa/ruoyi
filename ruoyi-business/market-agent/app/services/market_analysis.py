from __future__ import annotations
from dataclasses import dataclass
from typing import Any
import copy
import math
import pandas as pd

from app.services.data_reconcile import reconcile_metric_rows
from app.services.period_service import (
    AnalysisPeriod, FLOW_METRICS, STOCK_METRICS, resolve_analysis_period, period_options,
    filter_period_frame, stock_frame, normalize_period, previous_year_period,
)
from app.services.power_groups import (
    POWER_GROUP_LABELS,
    classify_power_type,
    power_group_catalog,
)

NUM_COLS = ["production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export", "inventory"]
YOY_COLS = ["yoy_production", "yoy_sales", "yoy_retail_sales", "yoy_wholesale", "yoy_domestic_sales", "yoy_domestic_wholesale", "yoy_export"]
YOY_BY_METRIC = {
    "production": "yoy_production",
    "sales": "yoy_sales",
    "retail_sales": "yoy_retail_sales",
    "wholesale": "yoy_wholesale",
    "domestic_sales": "yoy_domestic_sales",
    "domestic_wholesale": "yoy_domestic_wholesale",
    "export": "yoy_export",
}
DISPLAY_NAME = {
    "production": "产量", "sales": "销量", "retail_sales": "零售销量", "wholesale": "批发销量",
    "domestic_sales": "国内销量", "domestic_wholesale": "国内批发销量",
    "export": "出口", "inventory": "库存量", "yoy_production": "产量同比",
    "yoy_sales": "销量同比", "yoy_retail_sales": "零售销量同比", "yoy_wholesale": "批发销量同比",
    "yoy_domestic_sales": "国内销量同比", "yoy_domestic_wholesale": "国内批发销量同比", "yoy_export": "出口同比",
}
TOTAL_LABELS = {
    "总体市场", "总体", "整体", "总计", "合计", "总市场", "全部",
    "all", "total", "grandtotal", "grand total",
}
DIMENSIONS = ["market", "vehicle_type", "oem", "brand", "model", "power_type", "size_class", "tech_route", "region"]
SOURCE_KEYS = ["source_file", "source_sheet"]


def fmt_num(v: Any) -> str:
    if v is None or pd.isna(v):
        return "缺失"
    # POC保持源表数量级，不擅自把“辆”和“万辆”互转。旧模板常用“万”，因此这里只展示数值。
    val = float(v)
    if abs(val) >= 10000:
        return f"{val:,.0f}"
    return f"{val:.1f}"


def fmt_pct(v: Any) -> str:
    if v is None or pd.isna(v):
        return "缺失"
    return f"{float(v) * 100:+.1f}%"


def value_with_yoy(value: Any, yoy: Any) -> str:
    if value is None or pd.isna(value):
        return "缺失"
    return f"{fmt_num(value)}\n（同比{fmt_pct(yoy)}）" if yoy is not None and not pd.isna(yoy) else fmt_num(value)


def _period_sort_value(v: Any) -> Any:
    if pd.isna(v):
        return pd.Timestamp.min
    text = str(v).strip()
    for fmt in ["%Y.%m", "%Y-%m", "%Y/%m", "%Y%m"]:
        try:
            return pd.to_datetime(text, format=fmt)
        except Exception:
            pass
    try:
        return pd.to_datetime(text)
    except Exception:
        return text


def _previous_year_period(period: Any) -> str | None:
    text = str(period).strip()
    try:
        dt = pd.to_datetime(text, format="%Y-%m")
        return f"{dt.year - 1:04d}-{dt.month:02d}"
    except Exception:
        return None


def _safe_mode(series: pd.Series) -> str | None:
    vals = series.dropna().astype(str)
    vals = vals[~vals.str.fullmatch(r"nan|none|", case=False)]
    return vals.mode().iat[0] if not vals.empty else None


def _normalized_label(value: Any) -> str:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return ""
    text = str(value).strip().lower()
    return "".join(text.split())


def _is_total_label(value: Any) -> bool:
    """只把完整等值的“总计/All/Total”视为汇总行。

    v7使用str.contains("all")会误删 Small Cars、Allion、Include all models 等合法业务对象。
    """
    normalized = _normalized_label(value)
    labels = {_normalized_label(x) for x in TOTAL_LABELS}
    return bool(normalized and normalized in labels)


def _total_mask(series: pd.Series) -> pd.Series:
    return series.map(_is_total_label).fillna(False)


def _prepare_dimension(series: pd.Series) -> pd.Series:
    """保留字面值N/A；真正空白统一显示为“未分类(空白)”。"""
    def conv(v: Any) -> str:
        if v is None or (isinstance(v, float) and pd.isna(v)):
            return "未分类(空白)"
        text = str(v).strip()
        return text if text else "未分类(空白)"
    return series.map(conv)


@dataclass
class AnalysisResult:
    summary: dict[str, Any]
    overview_table: list[dict[str, Any]]
    rankings: dict[str, list[dict[str, Any]]]
    power_share: list[dict[str, Any]]
    monthly_trend: list[dict[str, Any]]
    line_charts: list[dict[str, Any]]
    anomalies: list[dict[str, Any]]
    warnings: list[str]
    metric_sources: dict[str, Any] | None = None
    dimension_integrity: dict[str, Any] | None = None
    latest_period: str | None = None
    region: str | None = None
    analysis_period: dict[str, Any] | None = None
    period_options: dict[str, Any] | None = None
    calculation_audit: dict[str, Any] | None = None


class MarketAnalyzer:
    """整车市场统计引擎。

    v6关键点：销量/产量/库存等可以来自不同Sheet、不同粒度。每个指标独立选择最可信来源，
    不再因为“车型销量表存在”就用车型粒度去错误汇总产量表。
    """

    def __init__(
        self,
        df: pd.DataFrame,
        period_mode: str = "latest",
        start_period: str | None = None,
        end_period: str | None = None,
        year: int | str | None = None,
        analysis_period: AnalysisPeriod | None = None,
    ):
        self.raw_df = df.copy()
        for c in NUM_COLS + YOY_COLS:
            if c in self.raw_df.columns:
                self.raw_df[c] = pd.to_numeric(self.raw_df[c], errors="coerce")
        self.period = analysis_period or resolve_analysis_period(
            self.raw_df, mode=period_mode, start_period=start_period, end_period=end_period, year=year
        )
        reconciled, reconcile_warnings = reconcile_metric_rows(self.raw_df)
        self.df = reconciled.copy()
        self.reconcile_warnings = reconcile_warnings
        for c in NUM_COLS + YOY_COLS:
            if c in self.df.columns:
                self.df[c] = pd.to_numeric(self.df[c], errors="coerce")
        self._derive_yoy_from_history()
        self.source_warnings: list[str] = []
        self._source_cache: dict[tuple[Any, ...], pd.DataFrame] = {}
        self._total_cache: dict[tuple[str, bool], float | None] = {}
        self._trend_cache: dict[tuple[str, bool], pd.DataFrame] = {}
        self._yoy_series_cache: dict[str, pd.DataFrame] = {}
        self._ranking_cache: dict[tuple[str, str, bool], list[dict[str, Any]]] = {}
        self._power_structure_cache: list[dict[str, Any]] | None = None
        self._line_charts_cache: list[dict[str, Any]] | None = None

    def source_metric_coverage(self) -> list[str]:
        if "source_metric_type" not in self.raw_df.columns:
            return []
        vals = self.raw_df["source_metric_type"].dropna().astype(str)
        return list(dict.fromkeys(v for v in vals if v and v.lower() not in {"none", "nan"}))

    def primary_sales_metric(self) -> str | None:
        """选择当前分析对象的主数量口径。

        单Sheet出口表只有 ``export`` 数值时，出口本身就是该Sheet要排名/做趋势的销量口径，
        因此必须允许 export 进入排名、动力结构和车型趋势。综合视图同时含批发/出口时，
        仍优先使用 sales/wholesale/retail_sales，避免把出口误当整个市场主销量。
        """
        available = [m for m in ["sales", "retail_sales", "wholesale", "export"] if m in self.raw_df.columns and self.raw_df[m].notna().any()]
        if not available:
            return None
        if "sales" in available:
            return "sales"
        if "wholesale" in available:
            return "wholesale"
        if "retail_sales" in available:
            return "retail_sales"
        return "export"

    def primary_domestic_metric(self) -> str | None:
        for m in ["domestic_sales", "domestic_wholesale"]:
            if m in self.raw_df.columns and self.raw_df[m].notna().any():
                return m
        return None

    def sales_metric_label(self) -> str:
        m = self.primary_sales_metric()
        if not m:
            return "销量"
        if "source_metric_label" in self.raw_df.columns:
            label = _safe_mode(self.raw_df["source_metric_label"])
            if label and label != "综合业务公式":
                return label
        return DISPLAY_NAME.get(m, "销量")

    def data_latest_period(self) -> str | None:
        opts = period_options(self.raw_df)
        return opts.get("latest_period")

    def latest_period(self) -> str | None:
        """兼容旧接口：返回当前分析周期的结束月份，而不是永远锁死数据最新月份。"""
        return self.period.end_period

    def analysis_frame(self, comparison: bool = False) -> pd.DataFrame:
        return filter_period_frame(self.df, self.period, comparison=comparison, for_trend=False)

    def latest_frame(self) -> pd.DataFrame:
        """兼容旧命名。v13下表示“当前分析周期frame”，区间模式可包含多个月。"""
        return self.analysis_frame(comparison=False)

    def region_name(self) -> str | None:
        data = self.latest_frame()
        return _safe_mode(data["region"]) if "region" in data.columns else None


    def _overall_row(self, frame: pd.DataFrame) -> pd.Series | None:
        """兼容报告模块：从给定frame中选择最完整的明确总体市场行。"""
        for col in ["market", "vehicle_type"]:
            if col in frame.columns:
                rows = frame[_total_mask(frame[col])].copy()
                if not rows.empty:
                    fields = [c for c in NUM_COLS + YOY_COLS if c in rows.columns]
                    rows["__score"] = rows[fields].notna().sum(axis=1) if fields else 0
                    return rows.sort_values("__score", ascending=False).iloc[0]
        return None

    def _derive_yoy_from_history(self) -> None:
        """若固定模板只有月度绝对值，则按同粒度去年同月自动计算同比。

        这是确定性计算，不使用大模型；已有源表同比值不会被覆盖。
        """
        if self.df.empty or "time_period" not in self.df.columns:
            return
        key_dims = [c for c in DIMENSIONS if c in self.df.columns]
        sentinel = "__NULL__"
        keys: list[tuple[str, ...]] = []
        for _, row in self.df.iterrows():
            keys.append(tuple(sentinel if pd.isna(row.get(c)) else str(row.get(c)) for c in key_dims))
        periods = self.df["time_period"].astype(str).tolist()

        for metric, yoy_col in YOY_BY_METRIC.items():
            if metric not in self.df.columns:
                continue
            if yoy_col not in self.df.columns:
                self.df[yoy_col] = pd.NA
            lookup: dict[tuple[str, tuple[str, ...]], tuple[float, int]] = {}
            for i, val in enumerate(pd.to_numeric(self.df[metric], errors="coerce")):
                if pd.notna(val):
                    lookup[(periods[i], keys[i])] = (float(val), i)
            for i, current in enumerate(pd.to_numeric(self.df[metric], errors="coerce")):
                if pd.isna(current) or pd.notna(self.df.at[self.df.index[i], yoy_col]):
                    continue
                prev_period = _previous_year_period(periods[i])
                if not prev_period:
                    continue
                previous = lookup.get((prev_period, keys[i]))
                if previous is not None and not math.isclose(previous[0], 0.0):
                    prev, prev_position = previous
                    self.df.at[self.df.index[i], yoy_col] = float(current) / prev - 1.0
                    self.df.at[
                        self.df.index[i], f"source_comparison_{yoy_col}"
                    ] = self._format_record_source(self.df.iloc[prev_position], metric)
            self.df[yoy_col] = pd.to_numeric(self.df[yoy_col], errors="coerce")

    @staticmethod
    def _format_record_source(row: pd.Series, metric: str | None = None) -> str:
        """把标准化记录还原为甲方可核验的原Excel位置。"""
        suffixes = [metric, None] if metric else [None]
        for suffix in suffixes:
            key = lambda name: f"{name}_{suffix}" if suffix else name
            sheet = row.get(key("source_sheet"))
            cell = row.get(key("source_cell"))
            source_row = row.get(key("source_row"))
            column = row.get(key("source_column"))
            sheet_text = "" if pd.isna(sheet) else str(sheet).strip()
            cell_text = "" if pd.isna(cell) else str(cell).strip()
            column_text = "" if pd.isna(column) else str(column).strip()
            if cell_text and cell_text.lower() != "nan":
                source = f"{sheet_text}!{cell_text}" if sheet_text else cell_text
                if column_text and column_text.lower() != "nan":
                    source += f"（{column_text}列）"
                return source
            if pd.notna(source_row) and str(source_row).strip().lower() != "nan":
                row_text = str(source_row).strip()
                return f"{sheet_text} 原表第{row_text}行" if sheet_text else f"原表第{row_text}行"
        return ""

    def _source_groups(self, data: pd.DataFrame):
        keys = [c for c in SOURCE_KEYS if c in data.columns]
        if not keys:
            yield ("__all__",), data
            return
        for key, group in data.groupby(keys, dropna=False, sort=False):
            if not isinstance(key, tuple):
                key = (key,)
            yield key, group.copy()

    def _has_total_row(self, data: pd.DataFrame) -> bool:
        for col in ["market", "vehicle_type"]:
            if col in data.columns and _total_mask(data[col]).any():
                return True
        return False

    def _primary_dimension(self, data: pd.DataFrame, preferred: str | None = None) -> str | None:
        if preferred and preferred in data.columns and data[preferred].notna().any():
            return preferred
        if "source_dimension" in data.columns:
            dim = _safe_mode(data["source_dimension"])
            if dim in DIMENSIONS and dim in data.columns and data[dim].notna().any():
                return dim
        for dim in ["model", "brand", "oem", "power_type", "market", "vehicle_type"]:
            if dim in data.columns and data[dim].notna().any():
                return dim
        return None

    def _group_total_single_period(self, data: pd.DataFrame, metric: str) -> float | None:
        if metric not in data.columns:
            return None
        work = data[data[metric].notna()].copy()
        if work.empty:
            return None
        for col in ["market", "vehicle_type"]:
            if col in work.columns:
                total_rows = work[_total_mask(work[col])]
                if not total_rows.empty:
                    vals = pd.to_numeric(total_rows[metric], errors="coerce").dropna()
                    if not vals.empty:
                        return float(vals.iloc[0])
        dim = self._primary_dimension(work)
        if dim:
            detail = work.dropna(subset=[dim]).copy()
            detail = detail[~_total_mask(detail[dim])]
            if not detail.empty:
                return float(detail.groupby(dim)[metric].sum().sum())
        vals = pd.to_numeric(work[metric], errors="coerce").dropna()
        return float(vals.sum()) if not vals.empty else None

    def _group_total(self, data: pd.DataFrame, metric: str) -> float | None:
        """按指标类型聚合当前时间窗口。

        流量指标（销量/产量/出口）按月总量累计；库存是时点指标，区间只取期末月。
        """
        if metric not in data.columns or data.empty:
            return None
        work = data[data[metric].notna()].copy()
        if work.empty:
            return None
        if "time_period" not in work.columns:
            return self._group_total_single_period(work, metric)
        work["__period"] = work["time_period"].map(normalize_period)
        work = work[work["__period"].notna()].copy()
        if work.empty:
            return self._group_total_single_period(data, metric)
        periods = sorted(work["__period"].unique(), key=_period_sort_value)
        if metric in STOCK_METRICS:
            target = self.period.stock_period
            if target not in periods:
                target = periods[-1]
            return self._group_total_single_period(work[work["__period"] == target].drop(columns=["__period"]), metric)
        totals = []
        for p in periods:
            v = self._group_total_single_period(work[work["__period"] == p].drop(columns=["__period"]), metric)
            if v is not None:
                totals.append(v)
        return float(sum(totals)) if totals else None

    def _source_score(self, data: pd.DataFrame, metric: str, purpose: str, preferred_dim: str | None = None) -> float:
        score = 0.0
        if self._has_total_row(data):
            score += 1000 if purpose == "snapshot" else 180
        if "source_metric_type" in data.columns and (data["source_metric_type"].astype(str) == metric).any():
            score += 300
        if preferred_dim:
            if "source_dimension" in data.columns and (data["source_dimension"].astype(str) == preferred_dim).any():
                score += 600
            elif preferred_dim in data.columns and data[preferred_dim].notna().any():
                score += 150
        if "source_semantic_confidence" in data.columns:
            conf = pd.to_numeric(data["source_semantic_confidence"], errors="coerce").max()
            if pd.notna(conf):
                score += float(conf) * 80
        if purpose == "trend" and "time_period" in data.columns:
            score += min(60, data["time_period"].dropna().astype(str).nunique()) * 40
        score += min(100, len(data)) * 0.2
        return score

    def _select_source(self, metric: str, purpose: str = "snapshot", preferred_dim: str | None = None, latest: bool = False) -> pd.DataFrame:
        # latest=True 保留旧参数名，但v13含义是“当前分析周期”。
        cache_key = (metric, purpose, preferred_dim, latest, self.period.start_period, self.period.end_period, self.period.mode)
        cached = self._source_cache.get(cache_key)
        if cached is not None:
            return cached.copy()
        if metric not in self.raw_df.columns:
            self._source_cache[cache_key] = pd.DataFrame()
            return pd.DataFrame()
        data = self.raw_df[self.raw_df[metric].notna()].copy()
        if latest:
            data = filter_period_frame(data, self.period, comparison=False, for_trend=False)
        if preferred_dim and preferred_dim in data.columns:
            dim_data = data[data[preferred_dim].notna()].copy()
            if not dim_data.empty:
                data = dim_data
        candidates: list[tuple[float, pd.DataFrame]] = []
        for _, group in self._source_groups(data):
            if group.empty:
                continue
            if preferred_dim and (preferred_dim not in group.columns or group[preferred_dim].notna().sum() == 0):
                continue
            candidates.append((self._source_score(group, metric, purpose, preferred_dim), group))
        if not candidates:
            selected = data.copy()
        else:
            candidates.sort(key=lambda x: x[0], reverse=True)
            selected = candidates[0][1].copy()
        self._source_cache[cache_key] = selected.copy()
        return selected

    def _metric_total(self, metric: str, latest: bool = True) -> float | None:
        key = (metric, latest)
        if key in self._total_cache:
            return self._total_cache[key]
        data = self._select_source(metric, purpose="snapshot", latest=latest)
        value = None if data.empty else self._group_total(data, metric)
        self._total_cache[key] = value
        return value

    def _comparison_metric_total(self, metric: str) -> float | None:
        source = self._select_source(metric, purpose="snapshot", latest=False)
        if source.empty:
            return None
        data = filter_period_frame(source, self.period, comparison=True, for_trend=False)
        if data.empty:
            return None
        # comparison库存不参与同比；这里仍按同期末月处理以保持通用性。
        if metric in STOCK_METRICS and "time_period" in data.columns:
            target = self.period.comparison_end
            normalized = data["time_period"].map(normalize_period)
            hit = data[normalized == target] if target else data.iloc[0:0]
            if hit.empty:
                ps = sorted({normalize_period(x) for x in data["time_period"].dropna() if normalize_period(x)}, key=_period_sort_value)
                hit = data[normalized == ps[-1]] if ps else data.iloc[0:0]
            return self._group_total_single_period(hit, metric) if not hit.empty else None
        # 对比区间是流量累计，不使用self.period.stock_period。
        if "time_period" in data.columns:
            totals=[]
            for _, group in data.groupby(data["time_period"].map(normalize_period), dropna=True):
                v=self._group_total_single_period(group, metric)
                if v is not None:
                    totals.append(v)
            return float(sum(totals)) if totals else None
        return self._group_total_single_period(data, metric)

    def metric_source_map(self) -> dict[str, Any]:
        """返回每个核心指标当前分析周期实际采用的Sheet/文件。"""
        out: dict[str, Any] = {}
        for metric in NUM_COLS:
            data = self._select_source(metric, purpose="snapshot", latest=True)
            if data.empty:
                continue
            source_rows = pd.to_numeric(data.get("source_row", pd.Series(dtype=float)), errors="coerce").dropna()
            out[metric] = {
                "指标": DISPLAY_NAME.get(metric, metric),
                "文件": _safe_mode(data["source_file"]) if "source_file" in data.columns else None,
                "Sheet": _safe_mode(data["source_sheet"]) if "source_sheet" in data.columns else None,
                "源指标": _safe_mode(data["source_metric_label"]) if "source_metric_label" in data.columns else DISPLAY_NAME.get(metric, metric),
                "源行范围": [int(source_rows.min()), int(source_rows.max())] if not source_rows.empty else None,
                "识别维度": _safe_mode(data["source_dimension"]) if "source_dimension" in data.columns else None,
                "表类型": _safe_mode(data["source_table_type"]) if "source_table_type" in data.columns else None,
                "语义置信度": float(pd.to_numeric(data["source_semantic_confidence"], errors="coerce").max()) if "source_semantic_confidence" in data.columns and pd.to_numeric(data["source_semantic_confidence"], errors="coerce").notna().any() else None,
                "分析周期": self.period.display_label,
                "聚合规则": "期末值" if metric in STOCK_METRICS and self.period.is_range else ("区间累计" if self.period.is_range else "单月值"),
            }
        return out

    def totals(self, latest: bool = True) -> dict[str, Any]:
        # 每个核心指标独立选源，解决销量、产量、库存位于不同Sheet/不同粒度的问题。
        return {metric: self._metric_total(metric, latest=latest) for metric in NUM_COLS}

    def _total_trend_series(self, metric: str, apply_period: bool = True) -> pd.DataFrame:
        key = (metric, apply_period)
        if key in self._trend_cache:
            return self._trend_cache[key].copy()
        data = self._select_source(metric, purpose="trend", latest=False)
        if apply_period:
            data = filter_period_frame(data, self.period, comparison=False, for_trend=True)
        if data.empty or "time_period" not in data.columns:
            out = pd.DataFrame(columns=["time_period", metric])
            self._trend_cache[key] = out
            return out.copy()
        rows = []
        for period, group in data.dropna(subset=["time_period"]).groupby("time_period", sort=False):
            total = self._group_total_single_period(group, metric)
            if total is not None:
                rows.append({"time_period": normalize_period(period) or str(period), metric: total})
        out = pd.DataFrame(rows)
        if not out.empty:
            out["__sort"] = out["time_period"].map(_period_sort_value)
            out = out.sort_values("__sort").drop(columns=["__sort"])
        self._trend_cache[key] = out.copy()
        return out

    def _total_yoy_series(self, metric: str) -> pd.DataFrame:
        """月度同比序列。使用全历史寻找去年同月，再按当前趋势窗口裁剪。"""
        if metric in self._yoy_series_cache:
            return self._yoy_series_cache[metric].copy()
        trend_all = self._total_trend_series(metric, apply_period=False)
        if trend_all.empty:
            out = pd.DataFrame(columns=["time_period", "yoy"])
            self._yoy_series_cache[metric] = out
            return out.copy()
        yoy_col = YOY_BY_METRIC.get(metric)
        explicit: dict[str, float] = {}
        if yoy_col and yoy_col in self.raw_df.columns:
            data = self._select_source(metric, purpose="trend", latest=False)
            if not data.empty and "time_period" in data.columns:
                for period, group in data.groupby("time_period", sort=False):
                    overall = None
                    for col in ["market", "vehicle_type"]:
                        if col in group.columns:
                            hit = group[_total_mask(group[col])]
                            if not hit.empty:
                                overall = hit
                                break
                    vals = pd.to_numeric((overall if overall is not None else group)[yoy_col], errors="coerce").dropna()
                    if not vals.empty:
                        explicit[normalize_period(period) or str(period)] = float(vals.iloc[0])
        lookup = {str(r["time_period"]): float(r[metric]) for _, r in trend_all.iterrows() if pd.notna(r[metric])}
        rows = []
        for _, r in trend_all.iterrows():
            period = str(r["time_period"])
            if period in explicit:
                yoy = explicit[period]
            else:
                prev_p = previous_year_period(period)
                current = float(r[metric])
                prev = lookup.get(prev_p) if prev_p else None
                yoy = current / prev - 1.0 if prev is not None and not math.isclose(prev, 0.0) else None
            rows.append({"time_period": period, "yoy": yoy})
        out = pd.DataFrame(rows)
        if not out.empty:
            out = out[out["time_period"].isin(set(self.period.trend_available_periods))].copy()
        self._yoy_series_cache[metric] = out.copy()
        return out

    def _total_yoy(self, metric: str) -> float | None:
        if metric not in YOY_BY_METRIC:
            return None
        # 区间/全年必须当前期和去年同期月份都完整，才输出累计同比；
        # 缺月时仍允许展示已有月份累计值，但禁止把不完整区间冒充完整同比。
        if self.period.is_range and (not self.period.complete or not self.period.comparison_complete):
            return None
        # 单月/最新月优先保留源表明确同比，否则用去年同月。
        if not self.period.is_range:
            p = self.period.end_period
            s = self._total_yoy_series(metric)
            hit = s[s["time_period"].astype(str) == str(p)] if not s.empty and p else pd.DataFrame()
            if not hit.empty and pd.notna(hit.iloc[0]["yoy"]):
                return float(hit.iloc[0]["yoy"])
        current = self._metric_total(metric, latest=True)
        previous = self._comparison_metric_total(metric)
        if current is None or previous is None or math.isclose(previous, 0.0):
            return None
        return float(current) / float(previous) - 1.0

    def _dimension_metric_yoy(self, dim: str, value: str, metric: str) -> float | None:
        """当前分析周期与去年同期同维度累计同比。"""
        if dim not in self.raw_df.columns or metric not in self.raw_df.columns:
            return None
        if self.period.is_range and (not self.period.complete or not self.period.comparison_complete):
            return None
        source = self._select_source(metric, purpose="trend", preferred_dim=dim, latest=False)
        if source.empty or "time_period" not in source.columns or dim not in source.columns:
            return None
        current = filter_period_frame(source, self.period, comparison=False, for_trend=False)
        previous = filter_period_frame(source, self.period, comparison=True, for_trend=False)
        current = current[current[dim].astype(str) == str(value)] if not current.empty else current
        previous = previous[previous[dim].astype(str) == str(value)] if not previous.empty else previous
        def agg(frame: pd.DataFrame) -> float | None:
            if frame.empty:
                return None
            if metric in STOCK_METRICS:
                sf = stock_frame(frame, self.period, comparison=False)
                vals = pd.to_numeric(sf[metric], errors="coerce").dropna() if not sf.empty else pd.Series(dtype=float)
                return float(vals.sum()) if not vals.empty else None
            vals = pd.to_numeric(frame[metric], errors="coerce").dropna()
            return float(vals.sum()) if not vals.empty else None
        cur = agg(current)
        # 同比口径只用于流量指标；库存不做默认同比累计。
        if metric in STOCK_METRICS:
            return None
        prev_vals = pd.to_numeric(previous[metric], errors="coerce").dropna() if not previous.empty else pd.Series(dtype=float)
        prev = float(prev_vals.sum()) if not prev_vals.empty else None
        if cur is None or prev is None or math.isclose(prev, 0.0):
            return None
        return cur / prev - 1.0

    def _group_metric_value(self, group: pd.DataFrame, metric: str) -> float | None:
        if metric not in group.columns:
            return None
        work = group[pd.to_numeric(group[metric], errors="coerce").notna()].copy()
        if work.empty:
            return None
        if metric in STOCK_METRICS and "time_period" in work.columns:
            target = self.period.stock_period
            norm = work["time_period"].map(normalize_period)
            hit = work[norm == target] if target else work.iloc[0:0]
            if hit.empty:
                periods = sorted({p for p in norm.dropna().tolist()}, key=_period_sort_value)
                hit = work[norm == periods[-1]] if periods else work.iloc[0:0]
            vals = pd.to_numeric(hit[metric], errors="coerce").dropna()
            return float(vals.sum()) if not vals.empty else None
        vals = pd.to_numeric(work[metric], errors="coerce").dropna()
        return float(vals.sum()) if not vals.empty else None

    def overview(self) -> list[dict[str, Any]]:
        frame = self.analysis_frame()
        group_dim = None
        if "market" in frame.columns and frame["market"].notna().any():
            group_dim = "market"
        elif "vehicle_type" in frame.columns and frame["vehicle_type"].notna().any():
            group_dim = "vehicle_type"

        sales_metric = self.primary_sales_metric()
        domestic_metric = self.primary_domestic_metric()
        display_metrics = [m for m in ["production", sales_metric, domestic_metric, "export", "inventory"] if m]
        display_metrics = list(dict.fromkeys(display_metrics))

        if group_dim:
            data = frame.copy()
            data[group_dim] = _prepare_dimension(data[group_dim])
            rows = []
            for name, group in data.groupby(group_dim, dropna=False, sort=False):
                name = str(name)
                values: dict[str, Any] = {}
                yoys: dict[str, Any] = {}
                for metric in display_metrics:
                    values[metric] = self._group_metric_value(group, metric)
                    yoys[metric] = self._dimension_metric_yoy(group_dim, name, metric) if metric in YOY_BY_METRIC else None
                # 仅单月模式允许直接使用源表该行明确同比；区间必须使用同期累计计算。
                if not self.period.is_range and len(group) == 1:
                    r0 = group.iloc[0]
                    for metric in display_metrics:
                        yoy_col = YOY_BY_METRIC.get(metric)
                        if yoy_col and yoy_col in group.columns and pd.notna(r0.get(yoy_col)):
                            yoys[metric] = float(r0.get(yoy_col))

                fake_data = {group_dim: name, **values}
                for metric, yoy in yoys.items():
                    yoy_col = YOY_BY_METRIC.get(metric)
                    if yoy_col:
                        fake_data[yoy_col] = yoy
                fake = pd.Series(fake_data)
                item = {"指标": name}
                for metric in display_metrics:
                    item[DISPLAY_NAME.get(metric, metric)] = value_with_yoy(values.get(metric), yoys.get(metric))
                item["具体分析"] = self._row_insight(fake)
                rows.append(item)
            if rows:
                return rows

        totals = self.totals()
        item = {"指标": "总体市场（程序汇总）"}
        for metric in display_metrics:
            item[DISPLAY_NAME.get(metric, metric)] = value_with_yoy(totals.get(metric), self._total_yoy(metric) if metric in YOY_BY_METRIC else None)
        item["具体分析"] = f"当前统计口径为{self.period.display_label}；各指标分别从已验证的对应Sheet/文件独立汇总，未跨不同粒度重复相加。"
        return [item]

    def _row_insight(self, r: pd.Series) -> str:
        parts: list[str] = []
        name = r.get("market") or r.get("vehicle_type") or r.get("brand") or r.get("oem") or "该项"
        sales_metric = self.primary_sales_metric()
        domestic_metric = self.primary_domestic_metric()
        sales_val = r.get(sales_metric) if sales_metric else None
        domestic = r.get(domestic_metric) if domestic_metric else None
        export = r.get("export")
        if domestic_metric and sales_metric and pd.notna(domestic) and pd.notna(sales_val) and float(sales_val) != 0:
            parts.append(f"{name}{DISPLAY_NAME.get(domestic_metric)}占{DISPLAY_NAME.get(sales_metric)}约{float(domestic) / float(sales_val):.1%}")
        if sales_metric and pd.notna(export) and pd.notna(sales_val) and float(sales_val) != 0:
            parts.append(f"出口占{DISPLAY_NAME.get(sales_metric)}约{float(export) / float(sales_val):.1%}")
        for metric in ["production", sales_metric, domestic_metric, "export"]:
            if not metric:
                continue
            yoy_col = YOY_BY_METRIC.get(metric)
            yoy = r.get(yoy_col) if yoy_col else None
            if pd.notna(yoy):
                direction = "增长" if float(yoy) >= 0 else "下降"
                parts.append(f"{DISPLAY_NAME.get(metric, metric)}同比{direction}{abs(float(yoy)):.1%}")
        return "；".join(parts[:4]) + "。" if parts else "当前行缺少可用于结构和同比分析的字段，不自动推断。"

    def ranking(self, dim: str, metric: str | None = None, top_n: int | None = None, latest: bool = True) -> list[dict[str, Any]]:
        metric = metric or self.primary_sales_metric()
        if not metric:
            return []
        cache_key = (dim, metric, latest)
        if cache_key in self._ranking_cache:
            cached = self._ranking_cache[cache_key]
            selected = cached if top_n is None else cached[:top_n]
            return [dict(row) for row in selected]

        def finish(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
            self._ranking_cache[cache_key] = [dict(row) for row in rows]
            selected = rows if top_n is None else rows[:top_n]
            return [dict(row) for row in selected]

        data = self._select_source(metric, purpose="snapshot", preferred_dim=dim, latest=latest)
        if data.empty or dim not in data.columns:
            return finish([])
        # 缺失维度不允许自动制造“未分类”排名。若当前Sheet该维度整列为空，明确返回空。
        raw_dim = data[dim]
        usable_dim = raw_dim.notna() & raw_dim.astype(str).str.strip().ne("")
        if not usable_dim.any():
            return finish([])
        data = data.loc[usable_dim].copy()
        data[dim] = _prepare_dimension(data[dim])
        data = data[~_total_mask(data[dim])]
        if data.empty:
            return finish([])
        g = data.groupby(dim, as_index=False, dropna=False)[metric].sum().sort_values(metric, ascending=False)
        total_metric = self.totals(latest=latest).get(metric)
        if total_metric is None or total_metric == 0:
            total_metric = float(data.groupby(dim)[metric].sum().sum())
        g["排名"] = range(1, len(g) + 1)
        g["占比"] = g[metric] / total_metric if total_metric else 0
        rows = g.rename(columns={dim: "对象", metric: "销量/数值"})[["排名", "对象", "销量/数值", "占比"]].to_dict("records")
        return finish(rows)

    def power_structure(self) -> list[dict[str, Any]]:
        if self._power_structure_cache is not None:
            return copy.deepcopy(self._power_structure_cache)
        metric = self.primary_sales_metric()
        if not metric:
            return []
        data = self._select_source(metric, purpose="snapshot", preferred_dim="power_type", latest=True)
        if data.empty or "power_type" not in data.columns:
            return []
        raw_power = data["power_type"]
        usable_power = raw_power.notna() & raw_power.astype(str).str.strip().ne("")
        if not usable_power.any():
            return []
        base = data.loc[usable_power].copy()
        base["power_type"] = _prepare_dimension(base["power_type"])
        base = base[~_total_mask(base["power_type"])]
        g = base.groupby("power_type", as_index=False, dropna=False)[metric].sum().sort_values(metric, ascending=False)
        total = g[metric].sum()
        g["占比"] = g[metric] / total if total else 0
        out = g.rename(columns={"power_type": "动力类型", metric: "销量/数值"})
        out["销量口径"] = DISPLAY_NAME.get(metric, metric)
        rows = out.to_dict("records")
        self._power_structure_cache = copy.deepcopy(rows)
        return rows

    def trend(self, metric: str | None = None) -> list[dict[str, Any]]:
        metric = metric or self.primary_sales_metric()
        if not metric:
            return []
        g = self._total_trend_series(metric)
        if g.empty:
            return []
        return g.rename(columns={"time_period": "时间", metric: "数值"}).to_dict("records")

    def _dimension_trend_wide(self, dim: str, metric: str, top_values: list[str] | None = None) -> pd.DataFrame:
        data = self._select_source(metric, purpose="trend", preferred_dim=dim, latest=False)
        data = filter_period_frame(data, self.period, comparison=False, for_trend=True)
        if data.empty or "time_period" not in data.columns or dim not in data.columns:
            return pd.DataFrame()
        data = data.dropna(subset=["time_period"]).copy()
        data["time_period"] = data["time_period"].map(lambda x: normalize_period(x) or str(x))
        data[dim] = _prepare_dimension(data[dim])
        data = data[~_total_mask(data[dim])]
        if top_values is not None:
            data = data[data[dim].astype(str).isin([str(x) for x in top_values])]
        g = data.groupby(["time_period", dim], as_index=False)[metric].sum()
        if g.empty:
            return pd.DataFrame()
        return g.pivot_table(index="time_period", columns=dim, values=metric, aggfunc="sum", fill_value=0).reset_index().rename(columns={"time_period": "时间"})

    def _chart_from_wide(self, title: str, wide: pd.DataFrame, unit: str, y_format: str, source: str, max_series: int = 6) -> dict[str, Any] | None:
        if wide.empty or len(wide.columns) < 2:
            return None
        wide = wide.copy()
        wide["__sort"] = wide["时间"].map(_period_sort_value)
        wide = wide.sort_values("__sort").drop(columns=["__sort"])
        series = []
        for col in [c for c in wide.columns if c != "时间"][:max_series]:
            vals = pd.to_numeric(wide[col], errors="coerce")
            if vals.notna().sum() == 0:
                continue
            points = [{"时间": str(x), "数值": float(y)} for x, y in zip(wide["时间"], vals) if pd.notna(y)]
            if points:
                series.append({"name": str(col), "points": points})
        return None if not series else {
            "title": title, "x_field": "时间", "y_field": "数值", "unit": unit,
            "y_format": y_format, "source": source, "series": series,
        }

    def _market_share_series(self, metric: str, label: str) -> pd.Series | None:
        data = self._select_source(metric, purpose="trend", preferred_dim="market", latest=False)
        data = filter_period_frame(data, self.period, comparison=False, for_trend=True)
        if data.empty or "time_period" not in data.columns or "market" not in data.columns:
            return None
        rows = []
        for period, group in data.groupby("time_period", sort=False):
            total_rows = group[_total_mask(group["market"])]
            nev_rows = group[group["market"].astype(str).str.contains("新能源", regex=True, na=False)]
            if total_rows.empty or nev_rows.empty:
                continue
            total = pd.to_numeric(total_rows[metric], errors="coerce").dropna()
            nev = pd.to_numeric(nev_rows[metric], errors="coerce").dropna()
            if total.empty or nev.empty or math.isclose(float(total.iloc[0]), 0.0):
                continue
            rows.append((normalize_period(period) or str(period), float(nev.iloc[0]) / float(total.iloc[0])))
        if not rows:
            return None
        return pd.Series({p: v for p, v in rows}, name=label)

    def power_group_monthly_chart(
        self,
        selected_groups: list[str] | None = None,
        mapping: dict[str, str] | None = None,
    ) -> dict[str, Any] | None:
        """Build the grouped power mix chart from the selected Excel source.

        The denominator always contains every non-total power-type record.  An
        unclassified source label therefore cannot silently inflate either the
        new-energy or fuel share; coverage is exposed in the chart audit.
        """
        metric = self.primary_sales_metric()
        if not metric:
            return None
        data = self._select_source(metric, purpose="trend", preferred_dim="power_type", latest=False)
        data = filter_period_frame(data, self.period, comparison=False, for_trend=True)
        if data.empty or "time_period" not in data.columns or "power_type" not in data.columns:
            return None
        work = data.dropna(subset=["time_period"]).copy()
        raw_power = work["power_type"].astype(str).str.strip()
        work = work[raw_power.ne("")].copy()
        work["power_type"] = work["power_type"].astype(str).str.strip()
        work = work[~_total_mask(work["power_type"])].copy()
        work["__value"] = pd.to_numeric(work[metric], errors="coerce")
        work = work.dropna(subset=["__value"])
        if work.empty:
            return None
        work["__period"] = work["time_period"].map(normalize_period)
        work = work.dropna(subset=["__period"])
        work["__power_group"] = work["power_type"].map(lambda value: classify_power_type(value, mapping))
        denominator = work.groupby("__period", sort=False)["__value"].sum()
        grouped = work.groupby(["__period", "__power_group"], sort=False)["__value"].sum()
        requested = [
            value for value in (selected_groups or ["new_energy", "fuel"])
            if value in {"new_energy", "fuel"}
        ]
        requested = list(dict.fromkeys(requested)) or ["new_energy", "fuel"]
        series: list[dict[str, Any]] = []
        for group in requested:
            points: list[dict[str, Any]] = []
            for period in sorted(denominator.index.tolist(), key=_period_sort_value):
                total = float(denominator.get(period, 0.0))
                value = float(grouped.get((period, group), 0.0))
                if not math.isclose(total, 0.0):
                    points.append({"时间": str(period), "数值": value / total})
            if points:
                series.append({"name": POWER_GROUP_LABELS[group], "points": points})
        if not series:
            return None
        classified_value = float(work.loc[work["__power_group"].isin({"new_energy", "fuel"}), "__value"].sum())
        total_value = float(work["__value"].sum())
        catalog = power_group_catalog(work)
        return {
            "title": "动力类型月度结构占比",
            "x_field": "时间",
            "y_field": "数值",
            "unit": "%",
            "y_format": "percent",
            "source": f"Excel动力类型{DISPLAY_NAME.get(metric, metric)}；新能源=EV/BEV+PHV/PHEV+FCV；燃油=ICE+HV/HEV+MHV/MHEV",
            "series": series,
            "classification_audit": {
                "metric": metric,
                "denominator": "每月全部非汇总动力类型记录",
                "classified_coverage": (classified_value / total_value if not math.isclose(total_value, 0.0) else None),
                "unclassified_values": [
                    item["source_value"] for item in catalog["mappings"]
                    if classify_power_type(item["source_value"], mapping) == "unclassified"
                ],
                "mapping": {
                    item["source_value"]: classify_power_type(item["source_value"], mapping)
                    for item in catalog["mappings"]
                },
            },
        }

    def power_type_monthly_chart(self) -> dict[str, Any] | None:
        """Return the unmerged power-type monthly mix for the dashboard.

        This is deliberately separate from ``power_group_monthly_chart``.
        The dashboard setting "所有动力类型" must show the labels that occur
        in the selected Excel rather than pretending they are new-energy or
        fuel.  Each month's denominator remains all non-summary power rows.
        """
        metric = self.primary_sales_metric()
        if not metric:
            return None
        data = self._select_source(metric, purpose="trend", preferred_dim="power_type", latest=False)
        data = filter_period_frame(data, self.period, comparison=False, for_trend=True)
        if data.empty or "time_period" not in data.columns or "power_type" not in data.columns:
            return None
        work = data.dropna(subset=["time_period"]).copy()
        work["power_type"] = work["power_type"].astype(str).str.strip()
        work = work[work["power_type"].ne("")].copy()
        work = work[~_total_mask(work["power_type"])].copy()
        work["__value"] = pd.to_numeric(work[metric], errors="coerce")
        work = work.dropna(subset=["__value"])
        work["__period"] = work["time_period"].map(normalize_period)
        work = work.dropna(subset=["__period"])
        if work.empty:
            return None
        denominator = work.groupby("__period", sort=False)["__value"].sum()
        grouped = work.groupby(["__period", "power_type"], sort=False)["__value"].sum()
        periods = sorted(denominator.index.tolist(), key=_period_sort_value)
        # Preserve the source's first appearance order so Excel users see
        # familiar labels, while keeping it deterministic across refreshes.
        power_types = list(dict.fromkeys(work["power_type"].tolist()))
        series: list[dict[str, Any]] = []
        for power_type in power_types:
            points: list[dict[str, Any]] = []
            for period in periods:
                total = float(denominator.get(period, 0.0))
                value = float(grouped.get((period, power_type), 0.0))
                if not math.isclose(total, 0.0):
                    points.append({"时间": str(period), "数值": value / total})
            if points:
                series.append({"name": power_type, "points": points})
        return None if not series else {
            "title": "动力类型月度结构占比",
            "x_field": "时间",
            "y_field": "数值",
            "unit": "%",
            "y_format": "percent",
            "source": f"Excel动力类型{DISPLAY_NAME.get(metric, metric)}；按原始动力类型分别计算月度结构占比",
            "series": series,
            "classification_audit": {
                "metric": metric,
                "mode": "all_power_types",
                "denominator": "每月全部非汇总动力类型记录",
                "source_power_types": power_types,
            },
        }

    def line_charts(self) -> list[dict[str, Any]]:
        if self._line_charts_cache is not None:
            return copy.deepcopy(self._line_charts_cache)
        charts: list[dict[str, Any]] = []

        # 1) 核心指标月度趋势：每个指标独立从其最可信Sheet计算后再合并。
        trend_parts = []
        for metric in NUM_COLS:
            t = self._total_trend_series(metric)
            if not t.empty:
                trend_parts.append(t.set_index("time_period")[metric].rename(DISPLAY_NAME[metric]))
        if trend_parts:
            wide = pd.concat(trend_parts, axis=1).reset_index().rename(columns={"time_period": "时间"})
            chart = self._chart_from_wide("核心指标月度趋势", wide, "源表单位", "number", "按指标分别选择Sheet/文件并按月份汇总")
            if chart:
                charts.append(chart)

        # 2) 同比增速：优先使用固定月度数据自动计算去年同月同比。
        yoy_parts = []
        for metric in ["production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export"]:
            y = self._total_yoy_series(metric)
            if not y.empty and y["yoy"].notna().any():
                yoy_parts.append(y.set_index("time_period")["yoy"].rename(DISPLAY_NAME[YOY_BY_METRIC[metric]]))
        if yoy_parts:
            wide = pd.concat(yoy_parts, axis=1).reset_index().rename(columns={"time_period": "时间"})
            chart = self._chart_from_wide("核心指标同比增速", wide, "%", "percent", "当前月与去年同月的确定性计算")
            if chart:
                charts.append(chart)

        # 3) 动力类型月度结构占比：按业务口径归并为新能源/燃油，
        # 未识别类型只进入分母并在审计中披露，不擅自归类。
        sales_metric = self.primary_sales_metric()
        power_group_chart = self.power_group_monthly_chart()
        if power_group_chart:
            charts.append(power_group_chart)

        # 4) Top车型月度销量趋势：最新月Top5，再回看全部月份。
        latest_rank = self.ranking("model", top_n=5)
        top_models = [str(x.get("对象")) for x in latest_rank if x.get("对象")]
        model_wide = self._dimension_trend_wide("model", sales_metric, top_models) if top_models and sales_metric else pd.DataFrame()
        if not model_wide.empty:
            chart = self._chart_from_wide("Top车型月度销量趋势", model_wide, "源表单位", "number", "最新月Top5车型 + 全部月份车型销量", max_series=5)
            if chart:
                charts.append(chart)

        # 5) 新能源市场占有率，只在明确市场汇总Sheet同时存在总体/新能源行时计算。
        pieces = []
        for metric, label in [(m, f"新能源汽车{DISPLAY_NAME.get(m, m)}市占率") for m in [self.primary_domestic_metric(), self.primary_sales_metric()] if m]:
            s = self._market_share_series(metric, label)
            if s is not None:
                pieces.append(s)
        if pieces:
            wide = pd.concat(pieces, axis=1).reset_index().rename(columns={"index": "时间"})
            chart = self._chart_from_wide("新能源汽车市场占有率", wide, "%", "percent", "明确市场汇总Sheet中的总体市场与新能源车行")
            if chart:
                charts.insert(0, chart)
        self._line_charts_cache = copy.deepcopy(charts)
        return charts

    def calculation_audit(
        self,
        rankings: dict[str, list[dict[str, Any]]],
        power_share: list[dict[str, Any]],
        line_charts: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """生成可供前端和验收测试核对的确定性计算血缘。

        这里不调用LLM，也不重新生成任何业务数字；只描述本次已经执行的
        Pandas筛选、分组、求和和除法口径，并对输出做基本恒等式校验。
        """
        metric = self.primary_sales_metric()

        def sources(frame: pd.DataFrame) -> dict[str, Any]:
            files = sorted({str(x) for x in frame.get("source_file", pd.Series(dtype=object)).dropna().tolist() if str(x).strip()})
            sheets = sorted({str(x) for x in frame.get("source_sheet", pd.Series(dtype=object)).dropna().tolist() if str(x).strip()})
            rows = pd.to_numeric(frame.get("source_row", pd.Series(dtype=float)), errors="coerce").dropna()
            return {
                "源文件": files,
                "源Sheet": sheets,
                "源行范围": ([int(rows.min()), int(rows.max())] if not rows.empty else None),
                "参与标准化记录数": int(len(frame)),
                "源数值字段": _safe_mode(frame["source_metric_label"]) if "source_metric_label" in frame.columns else None,
                "标准数值字段": metric,
                "源维度字段": _safe_mode(frame["source_dimension"]) if "source_dimension" in frame.columns else None,
            }

        snapshot = self._select_source(metric, purpose="snapshot", latest=True) if metric else self.df.iloc[0:0]
        trend = self._select_source(metric, purpose="trend", latest=False) if metric else self.df.iloc[0:0]
        snapshot = filter_period_frame(snapshot, self.period, comparison=False, for_trend=False)
        trend = filter_period_frame(trend, self.period, comparison=False, for_trend=True)
        snapshot_source = sources(snapshot)
        trend_source = sources(trend)
        total = self.totals(latest=True).get(metric) if metric else None

        modules: list[dict[str, Any]] = [{
            "模块ID": "market_summary",
            "模块": "市场观察表",
            "周期": self.period.display_label,
            "计算": "按标准字段独立选源；流量求和，库存取期末值",
            **snapshot_source,
        }]
        dim_labels = {"market": "市场排名", "oem": "OEM排名", "brand": "品牌排名", "model": "车型排名"}
        for dim, label in dim_labels.items():
            rows = rankings.get(dim, [])
            dim_source = self._select_source(metric, purpose="snapshot", preferred_dim=dim, latest=True) if metric else self.df.iloc[0:0]
            dim_source = filter_period_frame(dim_source, self.period, comparison=False, for_trend=False)
            share_ok = True
            if total not in (None, 0):
                share_ok = all(abs(float(r.get("占比") or 0) - float(r.get("销量/数值") or 0) / float(total)) < 1e-9 for r in rows)
            modules.append({
                "模块ID": f"ranking:{dim}", "模块": label, "周期": self.period.display_label,
                "维度标准字段": dim, "计算": f"groupby({dim})后求和并降序；占比=对象数值/市场总量",
                "占比分母": total, "输出对象数": len(rows), "占比恒等式通过": share_ok, **sources(dim_source),
            })
        power_sum = sum(float(r.get("占比") or 0) for r in power_share)
        power_source = self._select_source(metric, purpose="snapshot", preferred_dim="power_type", latest=True) if metric else self.df.iloc[0:0]
        power_source = filter_period_frame(power_source, self.period, comparison=False, for_trend=False)
        modules.append({
            "模块ID": "power_structure", "模块": "动力类型结构占比", "周期": self.period.display_label,
            "维度标准字段": "power_type", "计算": "groupby(power_type)求和；占比=动力类型数值/全部动力类型数值",
            "占比分母": sum(float(r.get("销量/数值") or 0) for r in power_share),
            "占比合计": power_sum, "占比合计校验通过": (not power_share or abs(power_sum - 1.0) < 1e-9),
            "输出分类数": len(power_share), **sources(power_source),
        })
        for chart in line_charts:
            points = [p for s in chart.get("series", []) for p in s.get("points", [])]
            periods = sorted({str(p.get("时间")) for p in points if p.get("时间")})
            modules.append({
                "模块ID": "line_chart:" + str(chart.get("title") or "折线图"), "模块": chart.get("title"),
                "周期": self.period.trend_scope_label, "计算": chart.get("source"),
                "趋势起始月": periods[0] if periods else None, "趋势截止月": periods[-1] if periods else None,
                "输出点数": len(points), "未包含截止月之后数据": all(p <= str(self.period.trend_end_period) for p in periods) if self.period.trend_end_period else True,
                **trend_source,
            })
        return {
            "计算引擎": "Python/Pandas确定性计算",
            "LLM参与数值计算": False,
            "数据原则": "所有销量、排名、占比、同比和趋势均来自当前上传文件的标准化记录；LLM仅解释文字和编排组件。",
            "快照周期": self.period.display_label,
            "趋势周期": self.period.trend_scope_label,
            "可用趋势月份": list(self.period.trend_available_periods),
            "缺失趋势月份": list(self.period.trend_missing_periods),
            "模块": modules,
        }

    def detect_anomalies(self, threshold: float = 0.15) -> list[dict[str, Any]]:
        rows: list[dict[str, Any]] = []
        # 区间/全年必须基于“当前累计 vs 去年同期累计”，不能把区间内单月同比混入结果。
        if self.period.is_range:
            group_dim = None
            frame = self.analysis_frame()
            if "market" in frame.columns and frame["market"].notna().any():
                group_dim = "market"
            elif "vehicle_type" in frame.columns and frame["vehicle_type"].notna().any():
                group_dim = "vehicle_type"
            sales_metric = self.primary_sales_metric()
            if group_dim and sales_metric:
                labels = _prepare_dimension(frame[group_dim].dropna())
                for subject in sorted(set(labels[~_total_mask(labels)].tolist())):
                    yoy = self._dimension_metric_yoy(group_dim, subject, sales_metric)
                    if yoy is not None and abs(yoy) >= threshold:
                        rows.append({
                            "主体": subject, "指标": DISPLAY_NAME.get(YOY_BY_METRIC.get(sales_metric, ""), "同比"),
                            "同比": yoy, "阈值": threshold, "来源": f"{self.period.display_label} vs {self.period.comparison_label}",
                            "提示": "区间累计同比绝对变化超过基础阈值，需要人工复核原因与口径",
                        })
            for metric in ["production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export"]:
                yoy = self._total_yoy(metric)
                if yoy is not None and abs(yoy) >= threshold:
                    rows.append({
                        "主体": "总体市场", "指标": DISPLAY_NAME[YOY_BY_METRIC[metric]], "同比": yoy, "阈值": threshold,
                        "来源": f"{self.period.display_label} vs {self.period.comparison_label}",
                        "提示": "区间累计同比绝对变化超过基础阈值，需要人工复核原因与口径",
                    })
            return rows[:100]

        frame = self.analysis_frame()
        for yoy_col in [c for c in YOY_COLS if c in frame.columns]:
            vals = pd.to_numeric(frame[yoy_col], errors="coerce")
            data = frame[vals.abs() >= threshold]
            for _, r in data.iterrows():
                subject = r.get("market") or r.get("oem") or r.get("brand") or r.get("model") or r.get("power_type") or f"第{int(r.name)+1}行"
                value_metric = next((metric for metric, column in YOY_BY_METRIC.items() if column == yoy_col), None)
                source = self._format_record_source(r, yoy_col) or self._format_record_source(r, value_metric)
                comparison_source = r.get(f"source_comparison_{yoy_col}")
                if pd.notna(comparison_source) and str(comparison_source).strip():
                    source = f"当前期：{source}；同期：{str(comparison_source).strip()}"
                if not source:
                    source = "原始文件"
                rows.append({
                    "主体": subject, "指标": DISPLAY_NAME.get(yoy_col, yoy_col), "同比": float(r[yoy_col]), "阈值": threshold,
                    "来源": source,
                    "提示": "同比绝对变化超过基础阈值，需要人工复核原因与口径",
                })
        if not rows:
            for metric in ["production", "sales", "retail_sales", "wholesale", "domestic_sales", "domestic_wholesale", "export"]:
                yoy = self._total_yoy(metric)
                if yoy is not None and abs(yoy) >= threshold:
                    rows.append({
                        "主体": "总体市场", "指标": DISPLAY_NAME[YOY_BY_METRIC[metric]], "同比": yoy, "阈值": threshold,
                        "来源": "按当前选择月份与去年同月确定性计算", "提示": "同比绝对变化超过基础阈值，需要人工复核原因与口径",
                    })
        return rows[:100]

    def dimension_integrity(self) -> dict[str, Any]:
        """区分全周期对象、当前选择周期有值对象、排名输出对象。"""
        result: dict[str, Any] = {}
        current_frame = self.analysis_frame()
        for dim in ["market", "vehicle_type", "oem", "brand", "model", "power_type"]:
            if dim not in self.raw_df.columns:
                continue
            raw_dim = self.raw_df[dim]
            usable_dim = raw_dim.notna() & raw_dim.astype(str).str.strip().ne("")
            if not usable_dim.any():
                continue
            all_labels = _prepare_dimension(self.raw_df.loc[usable_dim, dim])
            all_labels = all_labels[~_total_mask(all_labels)]
            all_unique = set(all_labels.tolist())

            period_data = current_frame.copy()
            sales_metric = self.primary_sales_metric()
            if sales_metric and sales_metric in period_data.columns:
                period_data = period_data[pd.to_numeric(period_data[sales_metric], errors="coerce").notna()]
            if not period_data.empty:
                current_raw_dim = period_data[dim]
                current_usable = current_raw_dim.notna() & current_raw_dim.astype(str).str.strip().ne("")
                current_labels = _prepare_dimension(period_data.loc[current_usable, dim])
            else:
                current_labels = pd.Series(dtype=str)
            if not current_labels.empty:
                current_labels = current_labels[~_total_mask(current_labels)]
            current_unique = set(current_labels.tolist())

            ranked = self.ranking("market" if dim == "market" else dim, top_n=None)
            ranked_unique = {str(x.get("对象")) for x in ranked}
            missing_in_ranking = sorted(current_unique - ranked_unique)
            result[dim] = {
                "全周期标准化对象数": len(all_unique),
                "当前分析周期有值对象数": len(current_unique),
                "排名/分析输出对象数": len(ranked_unique),
                "当前分析周期未进入输出": missing_in_ranking[:30],
                # 兼容v12前端/调用方
                "最新周期有值对象数": len(current_unique),
                "最新周期未进入输出": missing_in_ranking[:30],
                "状态": "完整" if not missing_in_ranking else "有缺失",
            }
        return result

    def run_all(self) -> AnalysisResult:
        warnings = list(self.reconcile_warnings) + list(self.period.warnings)
        label_map = {m: DISPLAY_NAME.get(m, m) for m in NUM_COLS}
        for metric, label in label_map.items():
            if metric not in self.raw_df.columns or self.raw_df[metric].dropna().empty:
                warnings.append(f"未发现可用于计算‘{label}’的Sheet/文件；仅跳过该指标，不影响其他指标分析。")
        coverage = self.source_metric_coverage()
        if coverage:
            labels = [label_map.get(x, x) for x in coverage]
            warnings.append("已通过Sheet名优先、文件名其次识别的数据表类型：" + "、".join(labels) + "。")
        warnings.append(
            f"当前分析周期：{self.period.display_label}；同比基期：{self.period.comparison_label}。"
            + ("流量指标按区间累计，库存等时点指标采用期末值；趋势图仅展示所选区间。" if self.period.is_range else "当前为单月快照口径。")
        )
        if "time_period" in self.raw_df.columns and self.raw_df["time_period"].astype(str).str.fullmatch(r"20\d{2}-\d{2}", na=False).any():
            warnings.append("检测到标准月度序列；单月同比按去年同月、区间同比按去年同期累计确定性计算，不调用大模型算数。")
        warnings.append("v13确定性规则：时间范围由统一Period Service控制；市场观察、排名、动力结构、趋势、AI问答、周报和导出共享同一分析周期。缺月会显式提示，不静默冒充完整累计。")
        rankings = {
            "market": self.ranking("market", top_n=None) or self.ranking("vehicle_type", top_n=None),
            "oem": self.ranking("oem", top_n=None),
            "brand": self.ranking("brand", top_n=None),
            "model": self.ranking("model", top_n=None),
        }
        power_share = self.power_structure()
        line_charts = self.line_charts()
        return AnalysisResult(
            summary=self.totals(latest=True),
            overview_table=self.overview(),
            rankings=rankings,
            power_share=power_share,
            monthly_trend=self.trend(),
            line_charts=line_charts,
            anomalies=self.detect_anomalies(),
            warnings=list(dict.fromkeys(warnings)),
            metric_sources=self.metric_source_map(),
            dimension_integrity=self.dimension_integrity(),
            latest_period=self.period.end_period,
            region=self.region_name(),
            analysis_period=self.period.to_dict(),
            period_options=period_options(self.raw_df),
            calculation_audit=self.calculation_audit(rankings, power_share, line_charts),
        )
