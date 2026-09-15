from __future__ import annotations
from dataclasses import dataclass
from typing import Any
import math
import re
import pandas as pd

from app.services.market_analysis import MarketAnalyzer, DISPLAY_NAME
from app.services.period_service import normalize_period, previous_year_period, period_options
from app.services.core_indicators import (
    AGGREGATIONS,
    COMPARISONS,
    format_indicator_value,
    indicator_capabilities,
    safe_change,
    validate_summary_rows,
    validate_indicator_specs,
    validate_matrix_column_specs,
)
from app.services.power_groups import FUEL_TYPES, NEW_ENERGY_TYPES, classify_power_type, power_group_catalog

NEV_TYPES = NEW_ENERGY_TYPES
ICE_TYPES = FUEL_TYPES

SEGMENT_LABELS = {
    "total_market": "总体市场",
    "passenger_vehicle": "乘用车",
    "commercial_vehicle": "商用车",
    "new_energy_vehicle": "新能源车",
    "ice_vehicle": "燃油车",
}

SEGMENT_MAPPING_GROUPS = {
    "vehicle": {
        "passenger_vehicle": "乘用车",
        "commercial_vehicle": "商用车",
        "unclassified": "未分类",
    },
    "power": {
        "new_energy_vehicle": "新能源车",
        "ice_vehicle": "燃油车",
        "unclassified": "未分类",
    },
}

COMPONENT_LABELS = {
    "market_summary": "市场核心指标表",
    "oem_top10": "OEM销量排名TOP10",
    "brand_top10": "品牌销量排名TOP10",
    "model_top10": "车型销量排名TOP10",
    "market_top10": "市场/车种销量排名TOP10",
    "power_structure": "动力类型结构占比",
    "monthly_trend": "核心指标月度趋势",
    "nev_share_trend": "新能源汽车销量占有率",
    "nev_vs_ice_yoy": "新能源车与燃油车销量同比变化",
    "all_line_charts": "全部可用折线图",
}

STATIC_DASHBOARD_COMPONENTS = [
    {"id": "market_summary", "title": "市场核心指标表", "kind": "table", "group": "市场观察表"},
    {"id": "market_top10", "title": "市场/车种销量排名TOP10", "kind": "ranking", "group": "排名"},
    {"id": "oem_top10", "title": "OEM销量排名TOP10", "kind": "ranking", "group": "排名"},
    {"id": "brand_top10", "title": "品牌销量排名TOP10", "kind": "ranking", "group": "排名"},
    {"id": "model_top10", "title": "车型销量排名TOP10", "kind": "ranking", "group": "排名"},
    {
        "id": "power_structure",
        "title": "动力类型结构占比",
        "kind": "table_and_pie",
        "group": "动力结构/趋势",
        "description": "指定周期的静态动力结构表格和饼图；与月度结构占比折线图是不同组件。",
    },
    {"id": "monthly_trend", "title": "核心指标月度趋势", "kind": "line_chart", "group": "动力结构/趋势"},
    {"id": "nev_share_trend", "title": "新能源汽车销量占有率", "kind": "line_chart", "group": "折线图"},
    {"id": "nev_vs_ice_yoy", "title": "新能源车与燃油车销量同比变化", "kind": "line_chart", "group": "折线图"},
    {"id": "all_line_charts", "title": "全部可用折线图", "kind": "chart_collection", "group": "折线图"},
]

LINE_CHART_COMPONENT_PREFIX = "line_chart:"


def line_chart_component_id(title: str) -> str:
    """Return a stable, human-auditable component id for a dashboard chart."""
    return LINE_CHART_COMPONENT_PREFIX + str(title or "").strip()


def is_line_chart_component(component_id: str) -> bool:
    return str(component_id or "").startswith(LINE_CHART_COMPONENT_PREFIX)


def line_chart_title(component_id: str) -> str:
    return str(component_id or "")[len(LINE_CHART_COMPONENT_PREFIX):].strip()


def component_label(component_id: str) -> str:
    if is_line_chart_component(component_id):
        return line_chart_title(component_id)
    return COMPONENT_LABELS.get(component_id, component_id)


def dashboard_component_catalog(
    charts: list[dict[str, Any]] | None = None,
    available_static_ids: set[str] | None = None,
) -> list[dict[str, Any]]:
    """Return the dashboard/report shared component registry.

    The ID, rather than a fuzzy title, is the contract between dashboard selection,
    chat planning, preview and every export format.
    """
    # The catalog is data-driven.  It contains only items that the current
    # Excel/sheet can actually render, never a global list of theoretical
    # components.  Legacy helper chart IDs are intentionally excluded: every
    # visible chart is addressed by its exact line_chart:<dashboard title> ID.
    allowed = set(available_static_ids or set())
    catalog = [dict(item) for item in STATIC_DASHBOARD_COMPONENTS if item["id"] in allowed]
    existing = {item["id"] for item in catalog}
    existing_titles = {str(item["title"]) for item in catalog}
    for index, chart in enumerate(charts or []):
        title = str(chart.get("title") or f"折线图{index + 1}")
        component_id = line_chart_component_id(title)
        # Special helper components can point at the same chart title. The exact
        # dashboard chart still keeps its own auditable line_chart:* identity.
        if component_id in existing or title in existing_titles:
            continue
        catalog.append({
            "id": component_id,
            "title": title,
            "kind": "line_chart",
            "group": "折线图",
            "source": str(chart.get("source") or ""),
            "series": [str(s.get("name") or "") for s in (chart.get("series") or [])],
        })
        existing.add(component_id)
        existing_titles.add(title)
    return catalog


def apply_scope_filters(df: pd.DataFrame, filters: dict[str, Any] | None) -> pd.DataFrame:
    """Apply report-section filters without guessing unavailable dimensions."""
    out = df.copy()
    for dim, selected in (filters or {}).items():
        if dim not in out.columns:
            continue
        values = selected if isinstance(selected, list) else [selected]
        wanted = {_norm_text(x) for x in values if _norm_text(x)}
        if wanted:
            out = out[out[dim].map(_norm_text).isin(wanted)].copy()
    return out


def _norm_text(v: Any) -> str:
    if v is None:
        return ""
    try:
        if bool(pd.isna(v)):
            return ""
    except (TypeError, ValueError):
        pass
    text = re.sub(r"\s+", " ", str(v).strip()).lower()
    return "" if text in {"nan", "none", "null", "<na>"} else text


def _display_source_value(v: Any) -> str:
    """Render source values without leaking pandas' internal NaN spelling."""
    text = _norm_text(v)
    return str(v).strip() if text else "（空白）"


def _fmt_num(v: float | None) -> str:
    if v is None or pd.isna(v):
        return "缺失"
    return f"{float(v):,.0f}"


def _fmt_pct(v: float | None) -> str:
    if v is None or pd.isna(v):
        return "缺失"
    return f"{float(v):+.1%}"


def _safe_ratio(a: float | None, b: float | None) -> float | None:
    if a is None or b is None or pd.isna(a) or pd.isna(b) or math.isclose(float(b), 0.0):
        return None
    return float(a) / float(b)


def _month_shift(period: str, delta: int) -> str | None:
    try:
        p = pd.Period(period, freq="M") + delta
        return f"{p.year:04d}-{p.month:02d}"
    except Exception:
        return None


@dataclass
class SegmentRule:
    id: str
    label: str
    evidence: str


class MarketComponentEngine:
    """把整车分析能力暴露成可供报告编排器选择的确定性组件。

    LLM只选择组件；本类负责所有销量、同比、环比、占比和图表数据计算。
    """

    def __init__(self, df: pd.DataFrame, analysis_period: dict[str, Any] | None = None):
        self.df = df.copy()
        self.analysis_period = analysis_period or {}
        self.analyzer = MarketAnalyzer(self.df, **self.analysis_period)
        self.metric = self.analyzer.primary_sales_metric()
        self.metric_label = self.analyzer.sales_metric_label()
        self.region = self.analyzer.region_name() or ""

    def capabilities(self, analysis_result: dict[str, Any] | None = None) -> dict[str, Any]:
        """Build the catalog, reusing an analysis payload when the caller already has one."""
        indicator_options = self._indicator_capabilities()
        dims = {}
        for dim in ["region", "market", "vehicle_type", "oem", "brand", "model", "power_type"]:
            if dim in self.df.columns:
                vals = [str(x) for x in self.df[dim].dropna().astype(str).unique().tolist() if str(x).strip()]
                dims[dim] = vals[:200]
        if self.region and not dims.get("region"):
            # Some templates communicate the region through file/Sheet
            # semantics rather than a physical region column.  Keep that
            # inferred, auditable scope available to the dialogue planner.
            dims["region"] = [self.region]
        charts = (analysis_result or {}).get("line_charts") or self.analyzer.line_charts()
        available_static: set[str] = set()
        overview = (analysis_result or {}).get("overview_table") if analysis_result is not None else self.analyzer.overview()
        if (self.metric and overview) or indicator_options.get("metrics"):
            available_static.add("market_summary")
        precomputed_rankings = (analysis_result or {}).get("rankings") or {}
        for dim, component_id in [
            ("market", "market_top10"), ("oem", "oem_top10"),
            ("brand", "brand_top10"), ("model", "model_top10"),
        ]:
            has_ranking = bool(precomputed_rankings.get(dim)) if analysis_result is not None else bool(self.analyzer.ranking(dim, top_n=1))
            if dim in self.df.columns and has_ranking:
                available_static.add(component_id)
        power_share = (analysis_result or {}).get("power_share") if analysis_result is not None else self.analyzer.power_structure()
        if "power_type" in self.df.columns and power_share:
            available_static.add("power_structure")
        catalog = dashboard_component_catalog(charts, available_static)
        supported_components = {str(item["id"]): str(item["title"]) for item in catalog}
        return {
            "region": self.region,
            "period": self.analyzer.period.to_dict(),
            "primary_metric": self.metric,
            "primary_metric_label": self.metric_label,
            "supported_components": supported_components,
            "supported_segments": SEGMENT_LABELS,
            "available_line_charts": [
                {
                    "id": line_chart_component_id(c.get("title", f"折线图{i + 1}")),
                    "title": c.get("title", f"折线图{i + 1}"),
                    "series": [str(s.get("name", "")) for s in (c.get("series") or [])],
                    "source": c.get("source", ""),
                }
                for i, c in enumerate(charts)
            ],
            "dashboard_components": catalog,
            "dimensions": dims,
            "indicator_capabilities": indicator_options,
            "power_group_capabilities": power_group_catalog(self.df),
        }

    def _has_metric_source(self, metric: str, scope: str | None = None) -> bool:
        if metric not in self.df.columns or not pd.to_numeric(self.df[metric], errors="coerce").notna().any():
            return False
        frame = self.df[pd.to_numeric(self.df[metric], errors="coerce").notna()].copy()
        if "source_metric_type" in frame.columns:
            exact = frame[frame["source_metric_type"].astype(str).str.casefold() == metric.casefold()]
            if not exact.empty:
                frame = exact
        if scope:
            if "source_metric_scope" not in frame.columns:
                return False
            scoped = frame[frame["source_metric_scope"].astype(str).str.casefold() == scope.casefold()]
            if scoped.empty:
                return False
            frame = scoped
        return not frame.empty

    def _can_derive_domestic_wholesale(self) -> bool:
        return self._has_metric_source("wholesale") and self._has_metric_source("export")

    def _can_derive_inventory(self) -> bool:
        has_domestic_wholesale = self._has_metric_source("domestic_wholesale") or self._can_derive_domestic_wholesale()
        return has_domestic_wholesale and self._has_metric_source("retail_sales", "domestic")

    def _indicator_capabilities(self) -> dict[str, Any]:
        options = indicator_capabilities(self.df, self.analyzer.period.available_selected_periods)
        metrics = list(options.get("metrics") or [])
        fields = {str(item.get("field")) for item in metrics}
        derived = [
            (
                "domestic_wholesale", "国内批发销量", "sum",
                "总批发销量-出口批发销量", self._can_derive_domestic_wholesale(),
            ),
            (
                "inventory", "库存量", "latest",
                "国内批发销量-中国市场零售销量", self._can_derive_inventory(),
            ),
        ]
        for field, label, aggregation, formula, available in derived:
            if available and field not in fields:
                metrics.append({
                    "field": field,
                    "label": label,
                    "default_aggregation": aggregation,
                    "default_unit": "",
                    "numeric_density": 1.0,
                    "derived": True,
                    "formula": formula,
                })
                fields.add(field)
        metric_order = {name: index for index, name in enumerate([
            "production", "sales", "retail_sales", "wholesale",
            "domestic_sales", "domestic_wholesale", "export", "inventory",
        ])}
        metrics.sort(key=lambda item: (metric_order.get(str(item.get("field")), 999), str(item.get("label", ""))))
        # Expose the exact selection rule alongside each option.  The UI uses
        # this for a read-only audit hint, while calculations below remain the
        # sole source of truth.
        for item in metrics:
            field = str(item.get("field") or "")
            if item.get("derived"):
                item["source_kind"] = "formula"
                item["data_rule"] = str(item.get("formula") or "业务公式")
                continue
            source = self._indicator_source(field)
            files = sorted({str(value) for value in source.get("source_file", pd.Series(dtype=object)).dropna().tolist() if str(value).strip()})
            sheets = sorted({str(value) for value in source.get("source_sheet", pd.Series(dtype=object)).dropna().tolist() if str(value).strip()})
            item["source_kind"] = "excel"
            item["data_rule"] = f"当前Excel字段：{item.get('label') or field}"
            item["source_files"] = files
            item["source_sheets"] = sheets
        options["metrics"] = metrics
        # Keep the metric picker transparent without making unsupported
        # business metrics selectable.  The visible disabled entries explain
        # why a familiar metric cannot be used for *this* workbook; only the
        # ``metrics`` list above is accepted by server-side validation.
        unavailable: list[dict[str, str]] = []
        standard_metrics = [
            ("production", "产量"),
            ("sales", "销量"),
            ("wholesale", "批发销量"),
            ("domestic_wholesale", "国内批发销量"),
            ("export", "出口"),
            ("inventory", "库存量"),
        ]
        available_fields = {str(item.get("field")) for item in metrics}
        for field, label in standard_metrics:
            if field in available_fields:
                continue
            if field == "domestic_wholesale":
                missing = []
                if not self._has_metric_source("wholesale"):
                    missing.append("总批发销量")
                if not self._has_metric_source("export"):
                    missing.append("出口批发销量")
                reason = f"缺少公式依赖：{'、'.join(missing)}" if missing else "当前Excel无可用国内批发销量数据"
            elif field == "inventory":
                missing = []
                if not (self._has_metric_source("domestic_wholesale") or self._can_derive_domestic_wholesale()):
                    missing.append("中国市场批发销量")
                if not self._has_metric_source("retail_sales", "domestic"):
                    missing.append("中国市场零售销量")
                reason = f"缺少公式依赖：{'、'.join(missing)}" if missing else "当前Excel无可用库存量数据"
            else:
                reason = f"当前Excel未识别到“{label}”数值字段"
            unavailable.append({"field": field, "label": label, "reason": reason})
        options["unavailable_metrics"] = unavailable
        dimensions = list(options.get("dimensions") or [])
        if "power_type" in self.df.columns and not any(item.get("field") == "power_group" for item in dimensions):
            dimensions.append({
                "field": "power_group",
                "label": "动力大类",
                "values": ["新能源车", "燃油车", "未分类"],
                "truncated": False,
            })
        options["dimensions"] = dimensions
        segment_capabilities, mapping_items = self.segment_capabilities()
        options["segment_capabilities"] = segment_capabilities
        options["segment_mapping_items"] = mapping_items
        template_ids = [
            ("row_total", "total_market"),
            ("row_passenger", "passenger_vehicle"),
            ("row_commercial", "commercial_vehicle"),
            ("row_nev", "new_energy_vehicle"),
            ("row_ice", "ice_vehicle"),
        ]
        capability_by_id = {item["id"]: item for item in segment_capabilities}
        options["matrix_row_templates"] = [
            {
                "id": row_id,
                "display_name": SEGMENT_LABELS[segment_id],
                "segment": segment_id,
                "filters": [],
            }
            for row_id, segment_id in template_ids
            # Only fully covered scopes belong in the default report template.
            # A review scope remains manually selectable, but must never look
            # like a system-confirmed default before its source values are mapped.
            if capability_by_id.get(segment_id, {}).get("status") == "reliable"
        ]
        options["matrix_column_templates"] = [
            {
                "id": f"column_{item['field']}",
                "display_name": item["label"],
                "metric": item["field"],
                "aggregation": item.get("default_aggregation") or "sum",
                "filters": [],
                "comparisons": ["yoy"],
                "show_share": False,
                "unit": item.get("default_unit") or "",
                "decimals": 0,
            }
            for item in metrics
        ]
        return options

    def _base_source(self) -> pd.DataFrame:
        if not self.metric:
            return pd.DataFrame()
        return self.analyzer._select_source(self.metric, purpose="trend", latest=False).copy()

    def _period_frame(self, period: str) -> pd.DataFrame:
        data = self._base_source()
        if data.empty or "time_period" not in data.columns:
            return pd.DataFrame()
        norm = data["time_period"].map(normalize_period)
        return data[norm == period].copy()

    @staticmethod
    def _mapping_value(mapping_overrides: dict[str, Any] | None, dimension: str, value: Any) -> str | None:
        dimension_mapping = (mapping_overrides or {}).get(dimension) or {}
        if not isinstance(dimension_mapping, dict):
            return None
        normalized = _norm_text(value)
        for source_value, target in dimension_mapping.items():
            if _norm_text(source_value) == normalized:
                return str(target)
        return None

    def _segment_mask(self, frame: pd.DataFrame, segment_id: str,
                      mapping_overrides: dict[str, Any] | None = None) -> tuple[pd.Series, str]:
        if frame.empty:
            return pd.Series(False, index=frame.index), "无可用数据"
        if segment_id == "total_market":
            return pd.Series(True, index=frame.index), "总体市场=当前主销量口径的全部明细"

        if segment_id in {"new_energy_vehicle", "ice_vehicle"}:
            if "power_type" not in frame.columns:
                return pd.Series(False, index=frame.index), "缺少power_type"
            expected_override = segment_id
            groups = frame["power_type"].map(lambda value: (
                self._mapping_value(mapping_overrides, "power_type", value)
                or {"new_energy": "new_energy_vehicle", "fuel": "ice_vehicle"}.get(classify_power_type(value), "unclassified")
            ))
            return groups.eq(expected_override), (
                "新能源车=EV/BEV+PHV/PHEV+FCV"
                if segment_id == "new_energy_vehicle"
                else "燃油车=ICE+HV/HEV+MHV/MHEV"
            )

        dim = "market" if "market" in frame.columns and frame["market"].notna().any() else "vehicle_type" if "vehicle_type" in frame.columns else None
        if not dim:
            return pd.Series(False, index=frame.index), "缺少market/vehicle_type"
        labels = frame[dim].map(_norm_text)
        unique = set(labels.dropna().tolist())
        region = _norm_text(self.region)
        us_profile = ("美国" in region or "united states" in region or region in {"usa", "us"}) and {
            "cars", "light trucks", "medium trucks", "heavy trucks"
        }.issubset(unique)
        override_groups = frame[dim].map(lambda value: self._mapping_value(mapping_overrides, dim, value))
        overridden = override_groups.notna()
        if us_profile:
            if segment_id == "passenger_vehicle":
                base = labels.isin({"cars", "light trucks"})
                return base.where(~overridden, override_groups.eq(segment_id)), "美国轻型车口径：乘用车=Cars+Light Trucks"
            base = labels.isin({"medium trucks", "heavy trucks"})
            return base.where(~overridden, override_groups.eq(segment_id)), "美国商用车口径：商用车=Medium Trucks+Heavy Trucks"

        passenger_tokens = ["cars", "car", "sedan", "hatchback", "suv", "mpv", "passenger", "乘用"]
        commercial_tokens = ["commercial", "商用", "medium truck", "heavy truck", "medium trucks", "heavy trucks", "bus", "中型卡车", "重型卡车", "客车"]
        tokens = passenger_tokens if segment_id == "passenger_vehicle" else commercial_tokens
        mask = labels.map(lambda x: any(t in x for t in tokens))
        mask = mask.where(~overridden, override_groups.eq(segment_id))
        evidence = "按源表市场/车种标签进行确定性关键词归类；未命中对象不自动推断"
        return mask, evidence

    def segment_capabilities(self, mapping_overrides: dict[str, Any] | None = None) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        """Describe which business scopes the selected Excel can actually support."""
        source = self._indicator_source(self.metric) if self.metric else pd.DataFrame()
        selected_periods = set(self.analyzer.period.available_selected_periods or [])
        if selected_periods and not source.empty and "time_period" in source.columns:
            source = source[source["time_period"].map(normalize_period).isin(selected_periods)].copy()
        metric_values = pd.to_numeric(source.get(self.metric, pd.Series(index=source.index, dtype=float)), errors="coerce") if self.metric else pd.Series(index=source.index, dtype=float)
        source = source[metric_values.notna()].copy()
        metric_values = pd.to_numeric(source.get(self.metric, pd.Series(index=source.index, dtype=float)), errors="coerce") if self.metric else pd.Series(index=source.index, dtype=float)
        total_records = int(len(source))
        total_value = float(metric_values.abs().sum()) if not metric_values.empty else 0.0
        source_files = sorted({str(value) for value in source.get("source_file", pd.Series(dtype=object)).dropna().tolist() if str(value).strip()})
        source_sheets = sorted({str(value) for value in source.get("source_sheet", pd.Series(dtype=object)).dropna().tolist() if str(value).strip()})

        def source_row_bounds(mask: pd.Series | None = None) -> tuple[int | None, int | None]:
            if "source_row" not in source.columns:
                return None, None
            rows = pd.to_numeric(source.loc[mask, "source_row"] if mask is not None else source["source_row"], errors="coerce").dropna()
            if rows.empty:
                return None, None
            return int(rows.min()), int(rows.max())

        total_row_start, total_row_end = source_row_bounds()

        capabilities: list[dict[str, Any]] = [{
            "id": "total_market",
            "label": "总体市场",
            "available": bool(total_records),
            "status": "reliable" if total_records else "unavailable",
            "reason": "当前主销量口径全部有效明细；总计/小计行不会与明细重复累加" if total_records else "当前Excel没有可用销量数据",
            "source_dimension": "",
            "source_metric": self.metric or "",
            "source_metric_label": self.metric_label or self.metric or "",
            "source_files": source_files,
            "source_sheets": source_sheets,
            "source_row_start": total_row_start,
            "source_row_end": total_row_end,
            "record_count": total_records,
            "total_record_count": total_records,
            "record_coverage": 1.0 if total_records else 0.0,
            "value_coverage": 1.0 if total_records else 0.0,
            "unclassified_values": [],
            "overlap_count": 0,
        }]
        mapping_items: list[dict[str, Any]] = []

        def append_group(group_name: str, dimension: str | None, segment_ids: list[str]) -> None:
            if not dimension or dimension not in source.columns:
                for segment_id in segment_ids:
                    capabilities.append({
                        "id": segment_id, "label": SEGMENT_LABELS[segment_id], "available": False,
                        "status": "unavailable", "reason": f"当前Excel缺少{dimension or ('power_type' if group_name == 'power' else 'market/vehicle_type')}字段",
                        "source_dimension": dimension or "", "source_files": source_files, "source_sheets": source_sheets,
                        "source_metric": self.metric or "", "source_metric_label": self.metric_label or self.metric or "",
                        "source_row_start": None, "source_row_end": None,
                        "record_count": 0, "total_record_count": total_records, "record_coverage": 0.0,
                        "value_coverage": 0.0, "unclassified_values": [], "overlap_count": 0,
                    })
                return
            masks = {segment_id: self._segment_mask(source, segment_id, mapping_overrides)[0] for segment_id in segment_ids}
            classified = pd.Series(False, index=source.index)
            match_count = pd.Series(0, index=source.index, dtype=int)
            for mask in masks.values():
                classified |= mask
                match_count += mask.astype(int)
            overlap_count = int((match_count > 1).sum())
            classified_records = int(classified.sum())
            classified_value = float(metric_values.loc[classified].abs().sum()) if total_value else 0.0
            record_coverage = classified_records / total_records if total_records else 0.0
            value_coverage = classified_value / total_value if total_value else record_coverage
            unclassified_values = sorted({
                _display_source_value(value)
                for value in source.loc[~classified, dimension].tolist()
            })
            for segment_id in segment_ids:
                mask = masks[segment_id]
                record_count = int(mask.sum())
                row_start, row_end = source_row_bounds(mask)
                _, evidence = self._segment_mask(source, segment_id, mapping_overrides)
                available = record_count > 0 and overlap_count == 0
                reliable = available and record_coverage >= 0.995 and value_coverage >= 0.995
                status = "reliable" if reliable else "review" if available else "unavailable"
                reason = evidence
                if status == "review":
                    reason += f"；仍有{len(unclassified_values)}个原始值未分类"
                if not available:
                    reason = f"字段{dimension}中没有可归入{SEGMENT_LABELS[segment_id]}的数据" if not overlap_count else "分类规则存在重叠，请先修正映射"
                capabilities.append({
                    "id": segment_id, "label": SEGMENT_LABELS[segment_id], "available": available,
                    "status": status, "reason": reason, "source_dimension": dimension,
                    "source_files": source_files, "source_sheets": source_sheets,
                    "source_metric": self.metric or "", "source_metric_label": self.metric_label or self.metric or "",
                    "source_row_start": row_start, "source_row_end": row_end,
                    "record_count": record_count, "total_record_count": total_records,
                    "record_coverage": round(record_coverage, 6), "value_coverage": round(value_coverage, 6),
                    "unclassified_values": unclassified_values, "overlap_count": overlap_count,
                })
            group_options = SEGMENT_MAPPING_GROUPS[group_name]
            for raw_value in sorted({str(value).strip() for value in source[dimension].tolist() if _norm_text(value)}):
                default_segment = "unclassified"
                raw_indexes = source.index[source[dimension].map(_norm_text) == _norm_text(raw_value)]
                for segment_id in segment_ids:
                    if masks[segment_id].loc[raw_indexes].any():
                        default_segment = segment_id
                        break
                mapping_items.append({
                    "group": group_name, "dimension": dimension, "source_value": raw_value,
                    "default_segment": default_segment,
                    "selected_segment": self._mapping_value(mapping_overrides, dimension, raw_value) or default_segment,
                    "requires_review": default_segment == "unclassified",
                    "options": [{"value": key, "label": label} for key, label in group_options.items()],
                })

        vehicle_dimension = "market" if "market" in source.columns and source["market"].notna().any() else "vehicle_type" if "vehicle_type" in source.columns and source["vehicle_type"].notna().any() else None
        append_group("vehicle", vehicle_dimension, ["passenger_vehicle", "commercial_vehicle"])
        append_group("power", "power_type" if "power_type" in source.columns else None, ["new_energy_vehicle", "ice_vehicle"])
        capabilities.append({
            "id": "custom", "label": "自定义筛选", "available": bool(self._indicator_capabilities_dimensions()),
            "status": "reliable" if self._indicator_capabilities_dimensions() else "unavailable",
            "reason": "按当前Excel真实存在的维度字段和值筛选", "source_dimension": "",
            "source_files": source_files, "source_sheets": source_sheets,
            "source_metric": self.metric or "", "source_metric_label": self.metric_label or self.metric or "",
            "source_row_start": total_row_start, "source_row_end": total_row_end,
            "record_count": total_records, "total_record_count": total_records,
            "record_coverage": 1.0 if total_records else 0.0, "value_coverage": 1.0 if total_records else 0.0,
            "unclassified_values": [], "overlap_count": 0,
        })
        return capabilities, mapping_items

    def _indicator_capabilities_dimensions(self) -> list[str]:
        return [dimension for dimension in ["region", "market", "vehicle_type", "oem", "brand", "model", "power_type", "size_class", "tech_route"] if dimension in self.df.columns and self.df[dimension].notna().any()]

    def _sum_segment_period(self, period: str, segment_id: str) -> tuple[float | None, str]:
        frame = self._period_frame(period)
        if frame.empty or not self.metric or self.metric not in frame.columns:
            return None, "当前周期无主销量数据"
        mask, evidence = self._segment_mask(frame, segment_id)
        vals = pd.to_numeric(frame.loc[mask, self.metric], errors="coerce").dropna()
        return (float(vals.sum()) if not vals.empty else None), evidence

    def _sum_segment_window(self, periods: list[str], segment_id: str) -> tuple[float | None, str]:
        values = []
        evidence = ""
        for p in periods:
            v, evidence = self._sum_segment_period(p, segment_id)
            if v is not None:
                values.append(v)
        return (float(sum(values)) if values else None), evidence

    def _selected_periods(self) -> list[str]:
        return list(self.analyzer.period.available_selected_periods or [])

    def _comparison_periods(self) -> list[str]:
        return list(self.analyzer.period.comparison_available_periods or [])

    def _segment_stats(self, segment_id: str) -> dict[str, Any]:
        selected = self._selected_periods()
        comparison = self._comparison_periods()
        value, evidence = self._sum_segment_window(selected, segment_id)
        prev, _ = self._sum_segment_window(comparison, segment_id) if comparison else (None, evidence)
        yoy = (value / prev - 1.0) if value is not None and prev is not None and not math.isclose(prev, 0.0) and (not self.analyzer.period.is_range or (self.analyzer.period.complete and self.analyzer.period.comparison_complete)) else None
        total, _ = self._sum_segment_window(selected, "total_market")
        share = _safe_ratio(value, total)
        mom = None
        mom_period = None
        if not self.analyzer.period.is_range and self.analyzer.period.end_period:
            mom_period = _month_shift(self.analyzer.period.end_period, -1)
            prev_m, _ = self._sum_segment_period(mom_period, segment_id) if mom_period else (None, evidence)
            if value is not None and prev_m is not None and not math.isclose(prev_m, 0.0):
                mom = value / prev_m - 1.0
        return {
            "id": segment_id,
            "label": SEGMENT_LABELS.get(segment_id, segment_id),
            "value": value,
            "yoy": yoy,
            "mom": mom,
            "share": share,
            "evidence": evidence,
            "comparison_period": self.analyzer.period.comparison_label,
            "mom_period": mom_period,
        }

    def market_summary(self, segments: list[str] | None = None) -> dict[str, Any]:
        # With no explicitly requested segment list, reuse the exact dynamic
        # table shown on the current dashboard.  This avoids turning the US POC
        # example (total/passenger/commercial/NEV) into a universal template for
        # unrelated future Excel files.
        if not segments:
            rows = self.analyzer.overview()
            return {
                "id": "market_summary",
                "type": "table",
                "title": "市场核心指标表",
                "rows": rows,
                "facts": rows,
                "source": "当前整车市场观察看板/市场观察表",
            }
        rows = []
        raw = []
        for sid in segments:
            if sid not in SEGMENT_LABELS:
                continue
            s = self._segment_stats(sid)
            raw.append(s)
            parts = []
            if s["value"] is not None:
                parts.append(f"{self.metric_label}{_fmt_num(s['value'])}")
            if s["yoy"] is not None:
                parts.append(f"同比{_fmt_pct(s['yoy'])}")
            if s["mom"] is not None:
                parts.append(f"环比{_fmt_pct(s['mom'])}")
            if s["share"] is not None and sid != "total_market":
                parts.append(f"占总体市场{s['share']:.1%}")
            rows.append({
                "指标": s["label"],
                self.metric_label: s["value"],
                "同比": s["yoy"],
                "环比": s["mom"],
                "占比": s["share"] if sid != "total_market" else 1.0 if s["value"] is not None else None,
                "具体分析": "；".join(parts) + "。" if parts else "当前数据不足，无法计算。",
                "计算口径": s["evidence"],
            })
        return {"id": "market_summary", "type": "table", "title": "市场核心指标表", "rows": rows, "facts": raw}

    @staticmethod
    def _drop_duplicate_summary_rows(frame: pd.DataFrame) -> pd.DataFrame:
        if frame.empty or "source_row_kind" not in frame.columns:
            return frame
        kinds = frame["source_row_kind"].fillna("detail").astype(str).str.casefold()
        detail = frame[~kinds.isin({"summary", "total", "subtotal"})].copy()
        return detail if not detail.empty else frame

    @staticmethod
    def _apply_indicator_filters(frame: pd.DataFrame, filters: list[dict[str, Any]]) -> pd.DataFrame:
        out = frame.copy()
        for condition in filters:
            dimension = str(condition.get("dimension") or "")
            if dimension == "power_group":
                if "power_type" not in out.columns:
                    return out.iloc[0:0].copy()
                wanted_labels = {_norm_text(value) for value in condition.get("values") or [] if _norm_text(value)}
                label_to_group = {"新能源车": "new_energy", "燃油车": "fuel", "未分类": "unclassified", "new_energy": "new_energy", "fuel": "fuel", "unclassified": "unclassified"}
                wanted = {group for label, group in label_to_group.items() if _norm_text(label) in wanted_labels}
                out = out[out["power_type"].map(classify_power_type).isin(wanted)].copy()
                continue
            if dimension not in out.columns:
                return out.iloc[0:0].copy()
            wanted = {_norm_text(value) for value in condition.get("values") or [] if _norm_text(value)}
            if wanted:
                out = out[out[dimension].map(_norm_text).isin(wanted)].copy()
        return out

    def _indicator_source(self, metric: str) -> pd.DataFrame:
        source = self.analyzer._select_source(metric, purpose="snapshot", latest=False)
        return self._drop_duplicate_summary_rows(source)

    def _dependency_source(self, metric: str, scope: str | None = None) -> pd.DataFrame:
        if metric not in self.df.columns:
            return pd.DataFrame()
        frame = self.df[pd.to_numeric(self.df[metric], errors="coerce").notna()].copy()
        if "source_metric_type" in frame.columns:
            exact = frame[frame["source_metric_type"].astype(str).str.casefold() == metric.casefold()]
            if not exact.empty:
                frame = exact
        if scope:
            if "source_metric_scope" not in frame.columns:
                return pd.DataFrame()
            frame = frame[frame["source_metric_scope"].astype(str).str.casefold() == scope.casefold()].copy()
        if frame.empty:
            return frame
        # A metric may occur in several files.  Never add independent sources;
        # choose the single source with the widest period coverage and row set.
        source_columns = [column for column in ["source_file", "source_sheet"] if column in frame.columns]
        if source_columns:
            candidates = []
            for _, group in frame.groupby(source_columns, dropna=False, sort=False):
                periods = group["time_period"].map(normalize_period).nunique() if "time_period" in group.columns else 0
                candidates.append(((int(periods), len(group)), group))
            candidates.sort(key=lambda item: item[0], reverse=True)
            frame = candidates[0][1].copy()
        return self._drop_duplicate_summary_rows(frame)

    def _aggregate_indicator(
        self,
        source: pd.DataFrame,
        metric: str,
        aggregation: str,
        periods: list[str] | None,
        filters: list[dict[str, Any]],
        segment: str = "custom",
        segment_mappings: dict[str, Any] | None = None,
    ) -> float | None:
        frame = source.copy()
        if periods and "time_period" in frame.columns:
            wanted = set(periods)
            frame = frame[frame["time_period"].map(normalize_period).isin(wanted)].copy()
        if segment and segment != "custom":
            mask, _ = self._segment_mask(frame, segment, segment_mappings)
            frame = frame.loc[mask].copy()
        frame = self._apply_indicator_filters(frame, filters)
        if frame.empty:
            return None
        if aggregation == "count":
            return float(len(frame))
        if metric not in frame.columns:
            return None
        frame["__indicator_value"] = pd.to_numeric(
            frame[metric].astype(str).str.replace(",", "", regex=False).str.replace("%", "", regex=False),
            errors="coerce",
        )
        frame = frame.dropna(subset=["__indicator_value"])
        if frame.empty:
            return None
        if aggregation == "latest" and "time_period" in frame.columns:
            frame["__indicator_period"] = frame["time_period"].map(normalize_period)
            available = sorted(x for x in frame["__indicator_period"].dropna().unique().tolist() if x)
            if available:
                frame = frame[frame["__indicator_period"] == available[-1]].copy()
            # “期末值”仍需汇总期末月内的各对象，避免只取任意一行。
            return float(frame["__indicator_value"].sum())
        values = frame["__indicator_value"]
        if aggregation == "average":
            return float(values.mean())
        if aggregation == "max":
            return float(values.max())
        if aggregation == "min":
            return float(values.min())
        return float(values.sum())

    def _calculate_metric(
        self,
        metric: str,
        aggregation: str,
        periods: list[str] | None,
        filters: list[dict[str, Any]],
        segment: str = "custom",
        segment_mappings: dict[str, Any] | None = None,
    ) -> tuple[float | None, dict[str, Any]]:
        effective_periods = list(periods or [])
        if metric == "inventory" and effective_periods:
            effective_periods = [sorted(effective_periods)[-1]]
            aggregation = "latest"
        if metric == "domestic_wholesale" and not self._has_metric_source("domestic_wholesale") and self._can_derive_domestic_wholesale():
            wholesale_source = self._dependency_source("wholesale")
            export_source = self._dependency_source("export")
            wholesale = self._aggregate_indicator(wholesale_source, "wholesale", "sum", effective_periods, filters, segment, segment_mappings)
            exported = self._aggregate_indicator(export_source, "export", "sum", effective_periods, filters, segment, segment_mappings)
            value = wholesale - exported if wholesale is not None and exported is not None else None
            return value, {
                "formula": "中国市场批发销量=总批发销量-出口批发销量",
                "dependencies": {"wholesale": wholesale, "export": exported},
                "status": "complete" if value is not None else "missing_dependency",
            }
        if metric == "inventory" and not self._has_metric_source("inventory") and self._can_derive_inventory():
            domestic, domestic_audit = self._calculate_metric("domestic_wholesale", "sum", effective_periods, filters, segment, segment_mappings)
            retail_source = self._dependency_source("retail_sales", "domestic")
            retail = self._aggregate_indicator(retail_source, "retail_sales", "sum", effective_periods, filters, segment, segment_mappings)
            value = domestic - retail if domestic is not None and retail is not None else None
            return value, {
                "formula": "国内库存量=中国市场批发销量-中国市场零售销量",
                "dependencies": {"domestic_wholesale": domestic, "domestic_retail_sales": retail},
                "upstream": domestic_audit,
                "status": "complete" if value is not None else "missing_dependency",
                "stock_rule": "区间仅取期末月",
            }
        source = self._indicator_source(metric)
        value = self._aggregate_indicator(source, metric, aggregation, effective_periods, filters, segment, segment_mappings)
        files = sorted({str(x) for x in source.get("source_file", pd.Series(dtype=object)).dropna().tolist() if str(x).strip()})
        sheets = sorted({str(x) for x in source.get("source_sheet", pd.Series(dtype=object)).dropna().tolist() if str(x).strip()})
        segment_rule = "自定义Excel字段筛选"
        if segment and segment != "custom":
            _, segment_rule = self._segment_mask(source, segment, segment_mappings)
        return value, {
            "formula": None,
            "source_files": files,
            "source_sheets": sheets,
            "source_metric": metric,
            "segment": segment,
            "segment_rule": segment_rule,
            "status": "complete" if value is not None else "missing_source",
        }

    def custom_indicator_summary(self, specs: list[dict[str, Any]]) -> dict[str, Any]:
        capabilities = self._indicator_capabilities()
        indicators = validate_indicator_specs(specs, capabilities)
        current_periods = list(self.analyzer.period.available_selected_periods or [])
        comparison_periods = list(self.analyzer.period.comparison_available_periods or [])
        rows: list[dict[str, Any]] = []
        facts: list[dict[str, Any]] = []
        for spec in indicators:
            metric = spec["metric"]
            aggregation = spec["aggregation"]
            value, calculation_audit = self._calculate_metric(metric, aggregation, current_periods, spec["filters"])
            comparisons = list(spec.get("comparisons") or [])
            yoy_previous = None
            mom_previous = None
            yoy_change = None
            mom_change = None
            comparison_labels: list[str] = []
            if "yoy" in comparisons:
                yoy_previous, _ = self._calculate_metric(metric, aggregation, comparison_periods, spec["filters"])
                yoy_change = safe_change(value, yoy_previous)
                comparison_labels.append(f"同比:{self.analyzer.period.comparison_label or '去年同期'}")
            if "mom" in comparisons and self.analyzer.period.end_period:
                previous_month = _month_shift(self.analyzer.period.end_period, -1)
                mom_previous, _ = self._calculate_metric(metric, aggregation, [previous_month] if previous_month else [], spec["filters"])
                mom_change = safe_change(value, mom_previous)
                comparison_labels.append(f"环比:{previous_month or '上期'}")
            denominator = None
            share = None
            if spec["show_share"]:
                denominator, _ = self._calculate_metric(metric, aggregation, current_periods, [])
                share = _safe_ratio(value, denominator)

            metric_label = DISPLAY_NAME.get(metric, metric)
            formatted_value = format_indicator_value(value, spec["unit"], spec["decimals"])
            details = [f"{spec['display_name']}{metric_label}为{formatted_value}"] if value is not None else [f"{spec['display_name']}缺少可计算数据"]
            if yoy_change is not None:
                details.append(f"同比{_fmt_pct(yoy_change)}")
            if mom_change is not None:
                details.append(f"环比{_fmt_pct(mom_change)}")
            if share is not None:
                details.append(f"占同口径总体{metric_label}{share:.1%}")
            filter_text = "；".join(
                f"{condition['dimension']}={','.join(condition['values'])}" for condition in spec["filters"]
            ) or "全部数据"
            rows.append({
                "指标": spec["display_name"],
                "指标字段": metric_label,
                "数值": value,
                "显示值": formatted_value,
                "同比": yoy_change,
                "环比": mom_change,
                "占比": share,
                "具体分析": "；".join(details) + "。",
                "计算口径": f"字段={metric}；聚合={AGGREGATIONS[aggregation]}；筛选={filter_text}；比较基期={'；'.join(comparison_labels) or '无'}",
            })
            # Keep the scalar fields for older downstream readers (prefer YoY
            # when both are selected) and expose the complete comparison map.
            legacy_previous = yoy_previous if "yoy" in comparisons else mom_previous
            legacy_change = yoy_change if "yoy" in comparisons else mom_change
            facts.append({
                **spec,
                "value": value,
                "previous": legacy_previous,
                "change": legacy_change,
                "comparison_values": {
                    "yoy": {"previous": yoy_previous, "change": yoy_change} if "yoy" in comparisons else None,
                    "mom": {"previous": mom_previous, "change": mom_change} if "mom" in comparisons else None,
                },
                "share": share,
                "denominator": denominator,
                "calculation_audit": calculation_audit,
            })
        return {
            "id": "market_summary",
            "type": "table",
            "title": "自定义核心指标表",
            "rows": rows,
            "facts": facts,
            "indicator_schema_hash": capabilities.get("schema_hash"),
            "custom_indicators": True,
        }

    def custom_indicator_matrix(
        self,
        row_specs: list[dict[str, Any]],
        column_specs: list[dict[str, Any]],
        segment_mappings: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        capabilities = self._indicator_capabilities()
        rows_config = validate_summary_rows(row_specs, capabilities)
        columns = validate_matrix_column_specs(column_specs, capabilities)
        if not rows_config or not columns:
            raise ValueError("二维市场概览至少需要一个报告行和一个指标列")
        segment_capabilities, _ = self.segment_capabilities(segment_mappings)
        capability_by_segment = {item["id"]: item for item in segment_capabilities}
        current_periods = list(self.analyzer.period.available_selected_periods or [])
        comparison_periods = list(self.analyzer.period.comparison_available_periods or [])
        output_rows: list[dict[str, Any]] = []
        fact_rows: list[dict[str, Any]] = []
        for row_spec in rows_config:
            output: dict[str, Any] = {"指标": row_spec["display_name"]}
            row_facts: dict[str, Any] = {
                "row": row_spec,
                "scope_audit": capability_by_segment.get(str(row_spec.get("segment"))) or {
                    "id": "custom", "label": "自定义筛选", "status": "reliable",
                    "reason": "按当前Excel中明确选择的维度值筛选",
                },
                "cells": {},
            }
            analysis_parts: list[str] = []
            for column in columns:
                metric = column["metric"]
                aggregation = column["aggregation"]
                filters = list(row_spec.get("filters") or []) + list(column.get("filters") or [])
                segment = row_spec.get("segment") or "custom"
                value, audit = self._calculate_metric(metric, aggregation, current_periods, filters, segment, segment_mappings)
                yoy = None
                mom = None
                previous_values: dict[str, Any] = {}
                if "yoy" in column.get("comparisons", []):
                    previous_values["yoy"], _ = self._calculate_metric(metric, aggregation, comparison_periods, filters, segment, segment_mappings)
                    yoy = safe_change(value, previous_values["yoy"])
                if "mom" in column.get("comparisons", []) and self.analyzer.period.end_period:
                    previous_month = _month_shift(self.analyzer.period.end_period, -1)
                    previous_values["mom"], _ = self._calculate_metric(metric, aggregation, [previous_month] if previous_month else [], filters, segment, segment_mappings)
                    mom = safe_change(value, previous_values["mom"])
                display = format_indicator_value(value, column["unit"], column["decimals"])
                comparison_text = []
                if yoy is not None:
                    comparison_text.append(f"同比{_fmt_pct(yoy)}")
                if mom is not None:
                    comparison_text.append(f"环比{_fmt_pct(mom)}")
                output[column["display_name"]] = display + ("\n（" + "，".join(comparison_text) + "）" if comparison_text else "")
                if value is not None:
                    analysis_parts.append(f"{column['display_name']}{display}" + ("，" + "、".join(comparison_text) if comparison_text else ""))
                row_facts["cells"][column["id"]] = {
                    "column": column,
                    "value": value,
                    "yoy": yoy,
                    "mom": mom,
                    "previous": previous_values,
                    "calculation_audit": audit,
                }
            output["具体分析"] = "；".join(analysis_parts) + "。" if analysis_parts else "当前行缺少同粒度Excel数据，未进行推算。"
            output_rows.append(output)
            fact_rows.append(row_facts)
        return {
            "id": "market_summary",
            "type": "table",
            "title": "市场核心指标表",
            "rows": output_rows,
            "facts": fact_rows,
            "matrix": True,
            "matrix_rows": rows_config,
            "matrix_columns": columns,
            "scope_audit": [row["scope_audit"] for row in fact_rows],
            "segment_mappings": segment_mappings or {},
            "indicator_schema_hash": capabilities.get("schema_hash"),
        }

    def dashboard_market_overview(self) -> dict[str, Any]:
        """Return the default dashboard overview from the same configurable engine.

        The dashboard used to render a fixed sales-oriented table assembled by
        ``MarketAnalyzer.overview``.  That hid formula metrics which live in
        different Excel sheets.  The default view is now a one-row matrix using
        every metric discovered for the current workbook.  Users can expand it
        into passenger/commercial/new-energy/custom rows in the report composer.
        """
        capabilities = self._indicator_capabilities()
        columns = list(capabilities.get("matrix_column_templates") or [])
        if not columns:
            return {"rows": [], "summary": {}, "facts": [], "columns": []}
        total_row = {
            "id": "row_total",
            "display_name": "总体市场",
            "segment": "total_market",
            "filters": [],
        }
        component = self.custom_indicator_matrix([total_row], columns)
        summary: dict[str, Any] = {}
        facts = component.get("facts") or []
        cells = facts[0].get("cells", {}) if facts else {}
        for column in columns:
            cell = cells.get(column.get("id"), {})
            summary[str(column.get("metric"))] = cell.get("value")
        return {
            "rows": component.get("rows") or [],
            "summary": summary,
            "facts": facts,
            "columns": columns,
            "indicator_schema_hash": component.get("indicator_schema_hash"),
        }

    def ranking_component(self, dim: str, top_n: int = 10) -> dict[str, Any]:
        rows = self.analyzer.ranking(dim, top_n=top_n)
        cid = {"oem":"oem_top10", "brand":"brand_top10", "model":"model_top10", "market":"market_top10"}.get(dim, f"{dim}_top10")
        return {"id": cid, "type": "ranking", "title": COMPONENT_LABELS.get(cid, f"{dim} TOP{top_n}"), "rows": rows, "dimension": dim}

    def power_component(self) -> dict[str, Any]:
        return {"id":"power_structure", "type":"table", "title":"动力类型结构占比", "rows":self.analyzer.power_structure()}

    def monthly_trend_component(self) -> dict[str, Any]:
        rows = self.analyzer.trend()
        chart = {"title":"核心指标月度趋势", "series":[{"name":self.metric_label, "points":[{"时间":x.get("时间"),"数值":x.get("数值")} for x in rows]}], "y_format":"number", "unit":"源表单位", "source":"time_period + primary metric"}
        return {"id":"monthly_trend", "type":"chart", "title":"核心指标月度趋势", "chart":chart, "rows":rows}

    def _chart_periods(self, lookback_months: int = 12) -> list[str]:
        opts = period_options(self.df)
        all_periods = [str(x) for x in opts.get("available_periods", [])]
        end = self.analyzer.period.end_period
        if self.analyzer.period.is_range:
            return self._selected_periods()
        eligible = [p for p in all_periods if not end or p <= end]
        return eligible[-max(2, int(lookback_months or 12)):]

    def nev_share_chart(self, lookback_months: int = 12) -> dict[str, Any]:
        points=[]
        for p in self._chart_periods(lookback_months):
            nev,_=self._sum_segment_period(p,"new_energy_vehicle")
            total,_=self._sum_segment_period(p,"total_market")
            share=_safe_ratio(nev,total)
            if share is not None:
                points.append({"时间":p,"数值":share})
        chart={"title":"新能源汽车销量占有率","series":[{"name":"新能源销量占有率","points":points}],"y_format":"percent","unit":"%","source":"EV/BEV+PHV/PHEV+FCV ÷ 总体市场"}
        return {"id":"nev_share_trend","type":"chart","title":chart["title"],"chart":chart}

    def nev_vs_ice_yoy_chart(self, lookback_months: int = 12) -> dict[str, Any]:
        series=[]
        for sid,label in [("new_energy_vehicle","新能源销量同比"),("ice_vehicle","燃油车销量同比")]:
            pts=[]
            for p in self._chart_periods(lookback_months):
                cur,_=self._sum_segment_period(p,sid)
                pp=previous_year_period(p)
                prev,_=self._sum_segment_period(pp,sid) if pp else (None,"")
                if cur is not None and prev is not None and not math.isclose(prev,0.0):
                    pts.append({"时间":p,"数值":cur/prev-1.0})
            if pts:
                series.append({"name":label,"points":pts})
        chart={"title":"新能源车与燃油车销量同比变化","series":series,"y_format":"percent","unit":"%","source":"当前月与去年同月的分动力类型确定性计算"}
        return {"id":"nev_vs_ice_yoy","type":"chart","title":chart["title"],"chart":chart}

    def all_line_components(self) -> list[dict[str, Any]]:
        return [
            {
                "id": line_chart_component_id(c.get("title", f"折线图{i + 1}")),
                "type": "chart",
                "title": c.get("title", f"折线图{i + 1}"),
                "chart": c,
            }
            for i, c in enumerate(self.analyzer.line_charts())
        ]

    def build_components(self, plan: dict[str, Any], observation: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        obs = observation or (plan.get("market_observation", {}) if isinstance(plan, dict) else {})
        ids = list(obs.get("components") or [])
        segments = list(obs.get("summary_segments") or [])
        core_indicators = list(obs.get("core_indicators") or [])
        matrix_rows = list(obs.get("market_summary_rows") or [])
        matrix_columns = list(obs.get("market_summary_columns") or [])
        component_options = dict(obs.get("component_options") or {})
        lookback = int(obs.get("lookback_months") or 12)
        out=[]
        for cid in ids:
            if cid == "market_summary":
                if matrix_rows and matrix_columns:
                    market_options = component_options.get("market_summary") if isinstance(component_options.get("market_summary"), dict) else {}
                    out.append(self.custom_indicator_matrix(
                        matrix_rows,
                        matrix_columns,
                        segment_mappings=market_options.get("segment_mappings") or {},
                    ))
                else:
                    out.append(self.custom_indicator_summary(core_indicators) if core_indicators else self.market_summary(segments))
            elif cid == "oem_top10": out.append(self.ranking_component("oem"))
            elif cid == "brand_top10": out.append(self.ranking_component("brand"))
            elif cid == "model_top10": out.append(self.ranking_component("model"))
            elif cid == "market_top10": out.append(self.ranking_component("market"))
            elif cid == "power_structure": out.append(self.power_component())
            elif cid == "monthly_trend": out.append(self.monthly_trend_component())
            elif cid == "nev_share_trend": out.append(self.nev_share_chart(lookback))
            elif cid == "nev_vs_ice_yoy": out.append(self.nev_vs_ice_yoy_chart(lookback))
            elif cid == "all_line_charts": out.extend(self.all_line_components())
            elif is_line_chart_component(cid):
                title = line_chart_title(cid)
                if title == "动力类型月度结构占比":
                    options = component_options.get(cid) if isinstance(component_options.get(cid), dict) else {}
                    chart = self.analyzer.power_group_monthly_chart(
                        selected_groups=options.get("series"),
                        mapping=options.get("power_type_mapping"),
                    )
                else:
                    chart = next((c for c in self.analyzer.line_charts() if str(c.get("title")) == title), None)
                if chart:
                    out.append({"id": cid, "type": "chart", "title": title, "chart": chart})
                else:
                    out.append({
                        "id": cid,
                        "type": "unavailable",
                        "title": title,
                        "warning": f"当前数据范围无法生成图表：{title}",
                    })
        return out

    def component_fact_pack(self, components: list[dict[str, Any]]) -> dict[str, Any]:
        pack={"period":self.analyzer.period.to_dict(),"region":self.region,"primary_metric":self.metric,"primary_metric_label":self.metric_label,"components":[]}
        for c in components:
            item={"id":c.get("id"),"type":c.get("type"),"title":c.get("title")}
            if c.get("rows") is not None: item["rows"]=c.get("rows")
            if c.get("facts") is not None: item["facts"]=c.get("facts")
            if c.get("chart") is not None: item["chart"]=c.get("chart")
            pack["components"].append(item)
        return pack
