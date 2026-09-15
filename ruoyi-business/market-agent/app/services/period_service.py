from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any, Iterable
import re
import pandas as pd

FLOW_METRICS = {
    "production", "sales", "retail_sales", "wholesale",
    "domestic_sales", "domestic_wholesale", "export",
}
STOCK_METRICS = {"inventory"}
VALID_MODES = {"latest", "single", "range", "year"}


def normalize_period(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    m = re.fullmatch(r"(20\d{2})[-./]?(\d{1,2})", text)
    if m:
        month = int(m.group(2))
        if 1 <= month <= 12:
            return f"{int(m.group(1)):04d}-{month:02d}"
    try:
        dt = pd.to_datetime(text)
        return f"{dt.year:04d}-{dt.month:02d}"
    except Exception:
        return None


def period_key(value: Any) -> tuple[int, int]:
    p = normalize_period(value)
    if not p:
        return (-1, -1)
    return int(p[:4]), int(p[5:7])


def previous_year_period(value: str | None) -> str | None:
    p = normalize_period(value)
    if not p:
        return None
    return f"{int(p[:4]) - 1:04d}-{p[5:7]}"


def month_range(start_period: str, end_period: str) -> list[str]:
    start = normalize_period(start_period)
    end = normalize_period(end_period)
    if not start or not end:
        return []
    sy, sm = period_key(start)
    ey, em = period_key(end)
    if (sy, sm) > (ey, em):
        return []
    out: list[str] = []
    y, m = sy, sm
    while (y, m) <= (ey, em):
        out.append(f"{y:04d}-{m:02d}")
        m += 1
        if m == 13:
            y += 1
            m = 1
    return out


def available_periods(df: pd.DataFrame) -> list[str]:
    if "time_period" not in df.columns:
        return []
    vals = {normalize_period(v) for v in df["time_period"].dropna().tolist()}
    vals.discard(None)
    return sorted(vals, key=period_key)


def period_options(df: pd.DataFrame) -> dict[str, Any]:
    periods = available_periods(df)
    years = sorted({int(p[:4]) for p in periods})
    months_by_year: dict[str, list[int]] = {}
    for year in years:
        months_by_year[str(year)] = sorted({int(p[5:7]) for p in periods if p.startswith(f"{year:04d}-")})
    return {
        "available_periods": periods,
        "available_years": years,
        "months_by_year": months_by_year,
        "latest_period": periods[-1] if periods else None,
        "earliest_period": periods[0] if periods else None,
        "supported_modes": ["latest", "single", "range", "year"],
    }


def _display_period(period: str | None) -> str:
    p = normalize_period(period)
    if not p:
        return "无有效周期"
    return f"{p[:4]}年{int(p[5:7])}月"


@dataclass(frozen=True)
class AnalysisPeriod:
    mode: str
    start_period: str | None
    end_period: str | None
    comparison_start: str | None
    comparison_end: str | None
    display_label: str
    comparison_label: str
    expected_periods: list[str]
    available_selected_periods: list[str]
    missing_periods: list[str]
    comparison_expected_periods: list[str]
    comparison_available_periods: list[str]
    comparison_missing_periods: list[str]
    complete: bool
    comparison_complete: bool
    trend_uses_all_periods: bool
    trend_start_period: str | None
    trend_end_period: str | None
    trend_expected_periods: list[str]
    trend_available_periods: list[str]
    trend_missing_periods: list[str]
    trend_scope_label: str
    stock_period: str | None
    warnings: list[str]

    @property
    def is_range(self) -> bool:
        return bool(self.start_period and self.end_period and self.start_period != self.end_period)

    @property
    def aggregation_label(self) -> str:
        if self.mode == "latest":
            return "最新月份快照"
        if self.mode == "single":
            return "指定月份快照"
        if self.mode == "year":
            return "全年累计"
        return "区间累计"

    def to_dict(self) -> dict[str, Any]:
        out = asdict(self)
        out["aggregation_label"] = self.aggregation_label
        out["is_range"] = self.is_range
        return out


def resolve_analysis_period(
    df: pd.DataFrame,
    mode: str | None = "latest",
    start_period: str | None = None,
    end_period: str | None = None,
    year: int | str | None = None,
) -> AnalysisPeriod:
    options = period_options(df)
    periods = options["available_periods"]
    period_set = set(periods)
    mode = str(mode or "latest").strip().lower()
    if mode not in VALID_MODES:
        raise ValueError(f"不支持的分析周期模式：{mode}")

    warnings: list[str] = []
    if not periods:
        return AnalysisPeriod(
            mode=mode, start_period=None, end_period=None,
            comparison_start=None, comparison_end=None,
            display_label="当前数据无有效年月", comparison_label="无可比同期",
            expected_periods=[], available_selected_periods=[], missing_periods=[],
            comparison_expected_periods=[], comparison_available_periods=[], comparison_missing_periods=[],
            complete=False, comparison_complete=False, trend_uses_all_periods=(mode == "latest"),
            trend_start_period=None, trend_end_period=None,
            trend_expected_periods=[], trend_available_periods=[], trend_missing_periods=[],
            trend_scope_label="无可用趋势月份",
            stock_period=None, warnings=["当前数据没有可识别的标准年月 time_period，无法执行时间范围分析。"],
        )

    if mode == "latest":
        start = end = periods[-1]
    elif mode == "single":
        start = normalize_period(start_period or end_period)
        if not start:
            raise ValueError("指定月份模式需要 start_period，例如 2024-06")
        end = start
    elif mode == "year":
        try:
            y = int(year if year is not None else str(start_period or "")[:4])
        except Exception as exc:
            raise ValueError("全年模式需要 year，例如 2024") from exc
        if y < 2000 or y > 2100:
            raise ValueError("year 超出支持范围")
        start, end = f"{y:04d}-01", f"{y:04d}-12"
    else:
        start = normalize_period(start_period)
        end = normalize_period(end_period)
        if not start or not end:
            raise ValueError("累计区间模式需要 start_period 与 end_period，例如 2024-01 到 2024-06")
        if period_key(start) > period_key(end):
            raise ValueError("开始月份不能晚于结束月份")

    expected = month_range(start, end)
    selected = [p for p in expected if p in period_set]
    missing = [p for p in expected if p not in period_set]
    comp_start = previous_year_period(start)
    comp_end = previous_year_period(end)
    comp_expected = month_range(comp_start, comp_end) if comp_start and comp_end else []
    comp_available = [p for p in comp_expected if p in period_set]
    comp_missing = [p for p in comp_expected if p not in period_set]

    if mode in {"range", "year"}:
        if mode == "year":
            display_label = f"{start[:4]}年全年累计"
            comparison_label = f"{comp_start[:4]}年全年累计" if comp_start else "无可比同期"
        elif start[:4] == end[:4]:
            display_label = f"{start[:4]}年{int(start[5:7])}-{int(end[5:7])}月累计"
            comparison_label = f"{comp_start[:4]}年{int(comp_start[5:7])}-{int(comp_end[5:7])}月累计" if comp_start and comp_end else "无可比同期"
        else:
            display_label = f"{_display_period(start)}至{_display_period(end)}累计"
            comparison_label = f"{_display_period(comp_start)}至{_display_period(comp_end)}累计" if comp_start and comp_end else "无可比同期"
    else:
        display_label = _display_period(end)
        comparison_label = _display_period(comp_end) if comp_end else "无可比同期"

    if missing:
        warnings.append(
            f"当前选择周期期望 {len(expected)} 个月，但有效月份仅 {len(selected)} 个；缺失：{'、'.join(missing)}。"
            "累计结果仅基于已有月份，不应视为完整期间累计。"
        )
    if comp_expected and comp_missing:
        warnings.append(
            f"同比基期 {comparison_label} 数据不完整；缺失：{'、'.join(comp_missing)}。"
            "对应同比将仅在当前期和同期口径均可计算时输出。"
        )
    if not selected:
        warnings.append(f"当前选择周期 {display_label} 没有可用数据。")

    # 时点库存采用区间末月；若末月没有记录，退化到区间内最后一个实际有数据月，并明确提示。
    stock_period = end if end in period_set else (selected[-1] if selected else None)
    if stock_period and stock_period != end:
        warnings.append(f"选择区间末月 {end} 无数据；库存等时点指标暂采用区间内最后有效月 {stock_period}。")

    # 快照口径和趋势口径必须分开：最新月/指定月的表格只看目标月，趋势则从
    # 数据最早月份展示到目标月；区间/全年趋势严格限制在选择窗口内。
    # 这样“最新 2025-12”和“指定 2025-12”会得到完全相同的趋势数据，
    # 而“指定 2025-09”不会泄漏 10-12 月的未来数据。
    if mode in {"latest", "single"}:
        trend_start = periods[0] if periods and end else None
        trend_end = end
        trend_expected = month_range(trend_start, trend_end) if trend_start and trend_end else []
        trend_available = [p for p in periods if trend_start and trend_end and trend_start <= p <= trend_end]
        trend_scope_label = f"截至{_display_period(trend_end)}的全部可用月份" if trend_end else "无可用趋势月份"
    else:
        trend_start, trend_end = start, end
        trend_expected = list(expected)
        trend_available = list(selected)
        trend_scope_label = f"{display_label}逐月"
    trend_missing = [p for p in trend_expected if p not in period_set]

    return AnalysisPeriod(
        mode=mode,
        start_period=start,
        end_period=end,
        comparison_start=comp_start,
        comparison_end=comp_end,
        display_label=display_label,
        comparison_label=comparison_label,
        expected_periods=expected,
        available_selected_periods=selected,
        missing_periods=missing,
        comparison_expected_periods=comp_expected,
        comparison_available_periods=comp_available,
        comparison_missing_periods=comp_missing,
        complete=bool(expected) and not missing,
        comparison_complete=bool(comp_expected) and not comp_missing,
        trend_uses_all_periods=(mode == "latest"),
        trend_start_period=trend_start,
        trend_end_period=trend_end,
        trend_expected_periods=trend_expected,
        trend_available_periods=trend_available,
        trend_missing_periods=trend_missing,
        trend_scope_label=trend_scope_label,
        stock_period=stock_period,
        warnings=warnings,
    )


def filter_period_frame(df: pd.DataFrame, period: AnalysisPeriod, comparison: bool = False, for_trend: bool = False) -> pd.DataFrame:
    if df.empty or "time_period" not in df.columns:
        return df.copy()
    allowed = (
        period.trend_available_periods
        if for_trend and not comparison
        else (period.comparison_available_periods if comparison else period.available_selected_periods)
    )
    if not allowed:
        return df.iloc[0:0].copy()
    normalized = df["time_period"].map(normalize_period)
    return df[normalized.isin(set(allowed))].copy()


def stock_frame(df: pd.DataFrame, period: AnalysisPeriod, comparison: bool = False) -> pd.DataFrame:
    if df.empty or "time_period" not in df.columns:
        return df.copy()
    target = period.comparison_end if comparison else period.stock_period
    if comparison:
        available = period.comparison_available_periods
        target = target if target in set(available) else (available[-1] if available else None)
    if not target:
        return df.iloc[0:0].copy()
    normalized = df["time_period"].map(normalize_period)
    return df[normalized == target].copy()


def aggregate_metric_values(values: Iterable[Any], metric: str) -> float | None:
    s = pd.to_numeric(pd.Series(list(values)), errors="coerce").dropna()
    if s.empty:
        return None
    return float(s.sum())
