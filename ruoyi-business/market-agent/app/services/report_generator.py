from __future__ import annotations
from collections import defaultdict
from datetime import date
from pathlib import Path
from typing import Any
import hashlib
import json
import re
import tempfile

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

import pandas as pd
from openpyxl import Workbook, load_workbook
from openpyxl.chart import LineChart, Reference
from openpyxl.styles import Alignment, Font, PatternFill, Border, Side
from openpyxl.utils import get_column_letter
from docx import Document
from docx.shared import Inches, Pt, RGBColor as DocxRGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from pptx import Presentation
from pptx.chart.data import ChartData
from pptx.enum.chart import XL_CHART_TYPE, XL_LEGEND_POSITION, XL_MARKER_STYLE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.xmlchemy import OxmlElement as PptxOxmlElement
from pptx.util import Inches as PInches, Pt as PPt
from pptx.dml.color import RGBColor

from app.core.llm import LLMClient, REPORT_SYSTEM_PROMPT, MARKET_ANALYSIS_SYSTEM_PROMPT
from app.services.market_analysis import MarketAnalyzer, fmt_num, fmt_pct
from app.services.report_config import normalize_report_config
from app.services.report_fact_builder import build_market_fact_pack
from app.services.market_components import MarketComponentEngine, component_label
from app.services.report_planner import normalize_report_plan

BLUE = "0B63B6"
DARK = "1F2937"
MUTED = "667085"
LIGHT_BLUE = "EAF3FC"
LIGHT_GRAY = "F5F7FA"
RED = "C62828"
ORANGE = "E69500"
FONT_CN = "Microsoft YaHei"

SECTION_LABELS = {
    "macro_policy": "1.2 宏观政策动态",
    "personnel": "2.1 人事调整",
    "strategy": "2.2 战略调整与布局",
    "industry_chain": "3.1 产业链观察",
    "competition": "4.1 竞争追踪",
}


def _json_safe(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {k: _json_safe(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_json_safe(v) for v in obj]
    if isinstance(obj, float):
        return None if pd.isna(obj) or obj in {float("inf"), float("-inf")} else obj
    if isinstance(obj, (str, int, bool)) or obj is None:
        return obj
    try:
        if pd.isna(obj):
            return None
    except Exception:
        pass
    try:
        if hasattr(obj, "item"):
            return obj.item()
    except Exception:
        pass
    return str(obj)


def _extract_json_object(text: str) -> dict[str, Any] | None:
    if not text or text.startswith("[LLM调用失败"):
        return None
    match = re.search(r"\{.*\}", text, flags=re.S)
    if not match:
        return None
    try:
        return json.loads(match.group(0))
    except Exception:
        return None


def _latest_period_display(period: str | None) -> str:
    if not period:
        return "本期"
    m = re.match(r"(20\d{2})[-./](\d{1,2})", str(period))
    if m:
        return f"{m.group(1)}年{int(m.group(2))}月"
    return str(period)


def _category_groups(context_items: list[dict[str, Any]] | None) -> dict[str, list[dict[str, Any]]]:
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in context_items or []:
        category = item.get("category", "other")
        if category in SECTION_LABELS:
            groups[category].append(item)
    return groups


def _context_source(item: dict[str, Any]) -> str:
    source = str(item.get("source_name") or "补充资料")
    locator = str(item.get("locator") or "")
    return f"{source} {locator}".strip()


def _context_page_number(item: dict[str, Any]) -> str:
    match = re.search(r"第\s*(\d+)\s*页", str(item.get("locator") or ""))
    return match.group(1) if match else ""


def _source_context_entries(items: list[dict[str, Any]], category: str) -> list[dict[str, str]]:
    """Return every supplied source item without summarising or truncating it."""
    parent_label = SECTION_LABELS[category]
    parent_name = re.sub(r"^\d+(?:\.\d+)*\s*", "", parent_label).strip()
    parent_number = parent_label.split()[0]
    parent_compact = re.sub(r"\s+", "", parent_label)
    out: list[dict[str, str]] = []
    for item in items:
        raw = str(item.get("content") or "").replace("\r\n", "\n").replace("\r", "\n")
        lines = [line.strip() for line in raw.split("\n")]
        while lines and not lines[0]:
            lines.pop(0)

        title_suffix = ""
        if lines:
            compact_first = re.sub(r"\s+", "", lines[0])
            if compact_first == parent_compact:
                lines.pop(0)
            elif compact_first.startswith(parent_compact):
                first = lines.pop(0)
                title_suffix = re.sub(
                    rf"^\s*{re.escape(parent_number)}\s*{re.escape(parent_name)}\s*[：:]?\s*",
                    "",
                    first,
                ).strip()

        # A locator-matching naked page number is presentation furniture, not
        # supplied business content.
        page_number = _context_page_number(item)
        if lines and page_number and lines[0] == page_number:
            lines.pop(0)
        while lines and not lines[0]:
            lines.pop(0)
        while lines and not lines[-1]:
            lines.pop()

        content = "\n".join(lines).strip()
        meaningful = [line for line in lines if line]
        if title_suffix:
            event_title = title_suffix
        elif len(meaningful) >= 2 and len(meaningful[0]) <= 24:
            event_title = f"{meaningful[0]} {meaningful[1]}"
        elif meaningful:
            event_title = meaningful[0]
        else:
            source_title = str(item.get("title") or "").strip()
            event_title = source_title if re.sub(r"\s+", "", source_title) != parent_compact else "补充资料"

        out.append({
            "title": event_title,
            "content": content,
            # Kept for API compatibility; this is complete source text rather
            # than a generated summary.
            "summary": content,
            "analysis": "",
            "source": _context_source(item),
            "source_id": str(item.get("id") or ""),
            "source_content_sha256": str(item.get("content_sha256") or hashlib.sha256(raw.encode("utf-8")).hexdigest()),
            "export_content_sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        })
    return out


def _market_fallback(analyzer: MarketAnalyzer, result) -> dict[str, Any]:
    period_text = (result.analysis_period or {}).get("display_label") or _latest_period_display(result.latest_period)
    region = result.region or "整车"
    title = f"{region}市场{period_text}观察"
    facts: list[str] = []
    sales_metric = analyzer.primary_sales_metric()
    domestic_metric = analyzer.primary_domestic_metric()
    if sales_metric:
        yoy = analyzer._total_yoy(sales_metric)
        if yoy is not None:
            direction = "增长" if yoy >= 0 else "下降"
            title += f"：{analyzer.sales_metric_label()}同比{direction}{abs(yoy):.1%}"
    fields = [(analyzer.sales_metric_label(), sales_metric)] if sales_metric else []
    if domestic_metric:
        fields.append(({"domestic_sales":"国内销量","domestic_wholesale":"国内批发销量"}.get(domestic_metric, domestic_metric), domestic_metric))
    fields += [("出口", "export"), ("产量", "production"), ("库存", "inventory")]
    for label, field in fields:
        if not field:
            continue
        value = (result.summary or {}).get(field)
        if value is None or pd.isna(value):
            continue
        text = f"{label}{fmt_num(value)}"
        yoy = analyzer._total_yoy(field) if field in analyzer._yoy_series_cache or field in {"sales","retail_sales","wholesale","domestic_sales","domestic_wholesale","export","production"} else None
        if yoy is not None:
            text += f"，同比{fmt_pct(yoy)}"
        facts.append(text + "。")
    if not facts:
        facts.append("当前数据未提供可确认的核心总量指标，程序不会自动推断缺失值。")
    if result.anomalies:
        facts.append(f"当前分析周期共有{len(result.anomalies)}条同比波动达到±15%基础异常阈值，建议逐项复核口径与业务原因。")
    facts.append("零售销量、批发销量和泛化销量分别保留原始口径，不互相改名；各指标按对应Sheet/文件独立选源。")
    facts.append("未提供外部事件资料时，市场变化原因不自动推断。")
    return {"title": title, "key_insights": facts[:6], "row_analysis": {}}

def _ai_market_analysis(fact_pack: dict[str, Any], report_config: dict[str, Any]) -> dict[str, Any] | None:
    """第一阶段：让LLM只分析本次上传表格，不负责套周报格式。"""
    llm = LLMClient()
    if not llm.enabled:
        return None
    requested = {
        "headline": "仅基于当前数据的一句话市场判断",
        "key_findings": ["3-6条数据洞察；必须引用当前事实，不能引用模板旧内容"],
        "row_analysis": {"当前市场指标名称": "逐项分析当前行的规模/同比/结构；无原因证据时不解释原因"},
        "competition_findings": ["2-5条基于当前OEM/品牌/车型排名和集中度的事实分析"],
        "trend_findings": ["2-5条基于当前趋势/折线图数据的事实分析"],
        "data_quality_notes": ["字段缺失、冲突、口径风险"],
    }
    messages = [
        {"role": "system", "content": MARKET_ANALYSIS_SYSTEM_PROMPT},
        {"role": "user", "content": "请先独立分析这一次上传的表格。输出字段：" + json.dumps(requested, ensure_ascii=False) +
         "\n本次current_fact_pack：" + json.dumps(_json_safe(fact_pack), ensure_ascii=False, default=str)},
    ]
    return _extract_json_object(llm.chat(messages, temperature=0.1, max_tokens=3000) or "")


def _ai_report_content(
    base_payload: dict[str, Any],
    fact_pack: dict[str, Any],
    market_analysis: dict[str, Any] | None,
) -> dict[str, Any] | None:
    """第二阶段：只把本次数据分析结果组织成参考周报的结构，不复刻参考周报事实。"""
    llm = LLMClient()
    if not llm.enabled:
        return None
    compact = {
        "current_fact_pack": fact_pack,
        "current_market_analysis": market_analysis or {},
    }
    requested = {
        "market_title": "针对当前数据重新生成的一句话市场观察标题",
        "market_key_insights": ["2-6条当前市场核心洞察，每条不超过110字"],
        "row_analysis": {"当前指标名称": "每项1段当前数据分析，不超过140字"},
        "competition_insights": ["2-5条当前OEM/品牌/车型竞争格局分析"],
    }
    messages = [
        {"role": "system", "content": REPORT_SYSTEM_PROMPT},
        {"role": "user", "content": "这是第二阶段。请只把本次表格分析组织为固定周报结构。严格JSON，不要Markdown。没有对应行业资料的新闻章节返回空数组。\n输出字段：" +
         json.dumps(requested, ensure_ascii=False) + "\n输入：" + json.dumps(_json_safe(compact), ensure_ascii=False, default=str)},
    ]
    return _extract_json_object(llm.chat(messages, temperature=0.12, max_tokens=4600) or "")


def _apply_ai_row_analysis(overview: list[dict[str, Any]], row_analysis: dict[str, Any]) -> list[dict[str, Any]]:
    if not isinstance(row_analysis, dict):
        return overview
    out = []
    for row in overview:
        item = dict(row)
        name = str(item.get("指标") or "")
        text = row_analysis.get(name)
        if isinstance(text, str) and text.strip():
            item["具体分析"] = text.strip()
        out.append(item)
    return out


def _component_analysis_lines(component: dict[str, Any]) -> list[str]:
    """只根据组件事实生成可审计的图表/排名说明，LLM 不参与算数。"""
    component_type = str(component.get("type") or "")
    rows = component.get("rows") or []
    if component.get("id") == "market_summary":
        return [str(row.get("具体分析")) for row in rows if row.get("具体分析")][:6]
    if component_type == "ranking" and rows:
        leaders = [str(row.get("对象")) for row in rows[:3] if row.get("对象")]
        values = [row.get("销量/数值") for row in rows[:3]]
        detail = "、".join(
            f"{name}（{fmt_num(values[index])}）" if index < len(values) else name
            for index, name in enumerate(leaders)
        )
        return [f"{component.get('title') or '排名'}前三位为{detail}；排名完全依据当前数据范围计算。"] if detail else []
    if component.get("id") == "power_structure" and rows:
        ordered = sorted(rows, key=lambda row: float(row.get("占比") or 0), reverse=True)
        leaders = [
            f"{row.get('动力类型')}（{fmt_pct(row.get('占比'))}）"
            for row in ordered[:3] if row.get("动力类型")
        ]
        return ["动力结构占比前三位为" + "、".join(leaders) + "。"] if leaders else []
    chart = component.get("chart") or {}
    lines: list[str] = []
    for series in (chart.get("series") or [])[:4]:
        points = [point for point in (series.get("points") or []) if isinstance(point.get("数值"), (int, float)) and not pd.isna(point.get("数值"))]
        if not points:
            continue
        first, last = points[0], points[-1]
        values = [float(point["数值"]) for point in points]
        value_format = fmt_pct if chart.get("y_format") == "percent" else fmt_num
        maximum = points[values.index(max(values))]
        minimum = points[values.index(min(values))]
        if chart.get("y_format") == "percent":
            change = f"{(float(last['数值']) - float(first['数值'])) * 100:+.1f}个百分点"
        elif float(first["数值"]) != 0:
            change = f"{(float(last['数值']) / float(first['数值']) - 1) * 100:+.1f}%"
        else:
            change = "无法计算相对变化"
        lines.append(
            f"{series.get('name') or '指标'}从{first.get('时间')}的{value_format(first.get('数值'))}"
            f"变至{last.get('时间')}的{value_format(last.get('数值'))}，区间变化{change}；"
            f"最高值出现在{maximum.get('时间')}，最低值出现在{minimum.get('时间')}。"
        )
    return lines


def build_report_payload(
    df: pd.DataFrame,
    context_items: list[dict[str, Any]] | None = None,
    use_llm: bool = True,
    meta: dict[str, Any] | None = None,
    report_config: dict[str, Any] | None = None,
    analysis_period: dict[str, Any] | None = None,
    report_plan: dict[str, Any] | None = None,
) -> dict[str, Any]:
    analyzer = MarketAnalyzer(df, **(analysis_period or {}))
    result = analyzer.run_all()
    groups = _category_groups(context_items)
    market_fb = _market_fallback(analyzer, result)
    config = normalize_report_config(report_config)

    all_charts = result.line_charts
    selected_titles = config.get("selected_line_charts") or []
    if not config.get("include_line_charts"):
        report_charts = []
    elif selected_titles:
        report_charts = [c for c in all_charts if c.get("title") in selected_titles]
    else:
        report_charts = all_charts

    fact_pack = build_market_fact_pack(analyzer, result)
    plan = normalize_report_plan(report_plan)
    dynamic_mode = plan.get("mode") == "custom"
    dynamic_observations: list[dict[str, Any]] = []
    if dynamic_mode:
        for index, observation in enumerate(plan.get("market_observations", []), 1):
            section_period = dict(analysis_period or {})
            section_period.update(observation.get("analysis_period") or {})
            try:
                component_engine = MarketComponentEngine(df, section_period)
                components = component_engine.build_components(plan, observation=observation)
                for component in components:
                    component["analysis"] = _component_analysis_lines(component)
                section_facts = component_engine.component_fact_pack(components)
                period_dict = component_engine.analyzer.period.to_dict()
                section_title = observation.get("custom_title") or "市场观察（待命名）"
                insights = [line for component in components for line in (component.get("analysis") or [])]
                if not insights:
                    insights.extend(f"已按当前数据生成：{c.get('title')}。" for c in components if c.get("title") and c.get("type") != "unavailable")
                section_warnings = [str(c.get("warning")) for c in components if c.get("warning")]
                dynamic_observations.append({
                    "id": observation.get("id"),
                    "number": f"1.{index}",
                    "title": section_title,
                    "auto_title": bool(observation.get("auto_title")),
                    "period_label": period_dict.get("display_label"),
                    "comparison_period_label": period_dict.get("comparison_label"),
                    "analysis_period": period_dict,
                    "filters": {},
                    "manual_content": observation.get("manual_content") or "",
                    "components": components,
                    "insights": insights[:8],
                    "warnings": section_warnings + list(period_dict.get("warnings") or []),
                    "fact_pack": section_facts,
                })
            except ValueError as exc:
                raise ValueError(f"第{index}个市场观察无法按当前Excel生成：{exc}") from exc
    dynamic_components = dynamic_observations[0]["components"] if dynamic_observations else []
    if dynamic_mode:
        fact_pack = dict(fact_pack)
        fact_pack["dynamic_market_observations"] = [x.get("fact_pack", {}) for x in dynamic_observations]
        if dynamic_observations:
            fact_pack["dynamic_market_observation"] = dynamic_observations[0].get("fact_pack", {})

    payload: dict[str, Any] = {
        "title": config.get("custom_title") or "汽车行业市场洞察周报",
        "report_date": date.today().strftime("%Y.%m.%d"),
        "latest_period": result.latest_period,
        "analysis_period": result.analysis_period or {},
        "period_label": (result.analysis_period or {}).get("display_label") or result.latest_period,
        "comparison_period_label": (result.analysis_period or {}).get("comparison_label"),
        "region": result.region,
        "market_observation_title": market_fb["title"],
        "market_key_insights": market_fb["key_insights"],
        "overview_table": result.overview_table if config.get("include_overview") else [],
        # PPT摘要仍使用Top10；Excel/Word同时保留完整排名，避免对象被报告导出层再次截断。
        "full_market": result.rankings.get("market", []) if config.get("include_rankings") and "market" in config.get("ranking_dimensions", []) else [],
        "full_oem": result.rankings.get("oem", []) if config.get("include_rankings") and "oem" in config.get("ranking_dimensions", []) else [],
        "full_brand": result.rankings.get("brand", []) if config.get("include_rankings") and "brand" in config.get("ranking_dimensions", []) else [],
        "full_model": result.rankings.get("model", []) if config.get("include_rankings") and "model" in config.get("ranking_dimensions", []) else [],
        "top_market": result.rankings.get("market", [])[:10] if config.get("include_rankings") and "market" in config.get("ranking_dimensions", []) else [],
        "top_oem": result.rankings.get("oem", [])[:10] if config.get("include_rankings") and "oem" in config.get("ranking_dimensions", []) else [],
        "top_brand": result.rankings.get("brand", [])[:10] if config.get("include_rankings") and "brand" in config.get("ranking_dimensions", []) else [],
        "top_model": result.rankings.get("model", [])[:10] if config.get("include_rankings") and "model" in config.get("ranking_dimensions", []) else [],
        "power_share": result.power_share if config.get("include_power_structure") else [],
        "trend": result.monthly_trend if config.get("include_monthly_trend") else [],
        "line_charts": report_charts,
        "all_available_line_charts": all_charts,
        "anomalies": result.anomalies if config.get("include_anomalies") else [],
        "warnings": result.warnings,
        "meta": meta or {},
        "context_counts": {k: len(groups.get(k, [])) for k in SECTION_LABELS},
        "report_config": config,
        "report_plan": plan,
        "report_plan_revision": plan.get("revision", 0),
        "report_plan_change_set": plan.get("last_change_set", {}),
        "dynamic_report_plan": dynamic_mode,
        "dynamic_market_observation": dynamic_mode,
        "market_observations": dynamic_observations,
        "market_observation_components": dynamic_components,
        "market_fact_pack": fact_pack,
        "dimension_integrity": result.dimension_integrity or {},
    }
    if dynamic_mode:
        first_observation = dynamic_observations[0] if dynamic_observations else None
        if first_observation:
            payload["market_observation_title"] = first_observation["title"]
            payload["market_key_insights"] = first_observation.get("insights", [])
        # When there is one market observation it is the report's primary data
        # scope, so the cover, preview and section must show the same period.
        # With multiple observations the cover keeps the overall period while
        # every section retains its own independently labelled scope.
        if len(dynamic_observations) == 1:
            primary = dynamic_observations[0]
            payload["analysis_period"] = primary.get("analysis_period") or {}
            payload["period_label"] = primary.get("period_label")
            payload["comparison_period_label"] = primary.get("comparison_period_label")
            payload["region"] = (primary.get("fact_pack") or {}).get("region") or payload.get("region")
        # 保持旧字段兼容，但其内容严格来自自然语言选择的数据组件。
        payload["overview_table"] = []
        payload["top_market"] = []; payload["top_oem"] = []; payload["top_brand"] = []; payload["top_model"] = []
        payload["full_market"] = []; payload["full_oem"] = []; payload["full_brand"] = []; payload["full_model"] = []
        payload["power_share"] = []; payload["trend"] = []; payload["line_charts"] = []
        for comp in dynamic_components:
            cid = comp.get("id")
            if cid == "market_summary": payload["overview_table"] = comp.get("rows", [])
            elif cid == "market_top10": payload["top_market"] = comp.get("rows", []); payload["full_market"] = comp.get("rows", [])
            elif cid == "oem_top10": payload["top_oem"] = comp.get("rows", []); payload["full_oem"] = comp.get("rows", [])
            elif cid == "brand_top10": payload["top_brand"] = comp.get("rows", []); payload["full_brand"] = comp.get("rows", [])
            elif cid == "model_top10": payload["top_model"] = comp.get("rows", []); payload["full_model"] = comp.get("rows", [])
            elif cid == "power_structure": payload["power_share"] = comp.get("rows", [])
            elif cid == "monthly_trend": payload["trend"] = comp.get("rows", [])
            if comp.get("type") == "chart" and comp.get("chart"):
                payload["line_charts"].append(comp.get("chart"))

    market_analysis = _ai_market_analysis(fact_pack, config) if use_llm else None
    payload["ai_market_analysis"] = market_analysis or {}
    ai = _ai_report_content(payload, fact_pack, market_analysis) if use_llm else None
    if ai:
        if dynamic_mode and len(payload.get("market_observations", [])) == 1 and payload["market_observations"][0].get("auto_title"):
            generated_title = str(ai.get("market_title") or "").strip()
            if generated_title:
                payload["market_observations"][0]["title"] = generated_title
                payload["market_observation_title"] = generated_title
        if not dynamic_mode and isinstance(ai.get("market_title"), str) and ai["market_title"].strip():
            payload["market_observation_title"] = ai["market_title"].strip()
        if isinstance(ai.get("market_key_insights"), list) and ai["market_key_insights"]:
            payload["market_key_insights"] = [str(x) for x in ai["market_key_insights"][:6]]
        payload["overview_table"] = _apply_ai_row_analysis(payload["overview_table"], ai.get("row_analysis") or {})
        if dynamic_mode:
            for observation in payload.get("market_observations", []):
                for comp in observation.get("components", []):
                    if comp.get("id") == "market_summary":
                        comp["rows"] = _apply_ai_row_analysis(comp.get("rows", []), ai.get("row_analysis") or {})
                    comp["analysis"] = _component_analysis_lines(comp)
                observation["insights"] = [
                    line for comp in observation.get("components", []) for line in (comp.get("analysis") or [])
                ][:8]
        payload["competition_insights"] = [str(x) for x in (ai.get("competition_insights") or [])[:6]]
    else:
        # 第二阶段失败时，优先使用第一阶段“当前表格分析”，仍不回退到参考模板事实。
        if market_analysis:
            headline = market_analysis.get("headline")
            if isinstance(headline, str) and headline.strip():
                payload["market_observation_title"] = headline.strip()
                if dynamic_mode and len(payload.get("market_observations", [])) == 1 and payload["market_observations"][0].get("auto_title"):
                    payload["market_observations"][0]["title"] = headline.strip()
            findings = market_analysis.get("key_findings") or []
            if isinstance(findings, list) and findings:
                payload["market_key_insights"] = [str(x) for x in findings[:6]]
            payload["overview_table"] = _apply_ai_row_analysis(payload["overview_table"], market_analysis.get("row_analysis") or {})
            payload["competition_insights"] = [str(x) for x in (market_analysis.get("competition_findings") or [])[:6]]
        else:
            payload["competition_insights"] = []
        if not payload.get("competition_insights"):
            for key, label in [("top_market", "市场"), ("top_oem", "OEM"), ("top_brand", "品牌"), ("top_model", "车型")]:
                rows = payload.get(key) or []
                if rows:
                    payload["competition_insights"].append(label + "销量前五为" + "、".join(str(x["对象"]) for x in rows[:5]) + "；排名仅依据当前上传数据计算。")
            if not payload["competition_insights"]:
                payload["competition_insights"].append("当前数据缺少可用排名维度，无法生成竞争格局排名。")

    for category in SECTION_LABELS:
        if not config.get("include_weekly_content"):
            payload[category] = []
            continue
        # Context chapters preserve evidence. AI can analyse market data but
        # cannot replace, shorten, merge or omit user-supplied source text.
        payload[category] = _source_context_entries(groups.get(category, []), category)

    exported_context = [item for category in SECTION_LABELS for item in payload.get(category, [])]
    expected_context = sum(len(groups.get(category, [])) for category in SECTION_LABELS) if config.get("include_weekly_content") else 0
    payload["content_audit"] = {
        "status": "complete" if len(exported_context) == expected_context else "incomplete",
        "expected_items": expected_context,
        "exported_items": len(exported_context),
        "source_hashes": [item.get("source_content_sha256") for item in exported_context],
        "export_hashes": [item.get("export_content_sha256") for item in exported_context],
    }
    if len(exported_context) != expected_context:
        raise RuntimeError(f"行业资料完整性校验失败：应输出{expected_context}条，实际输出{len(exported_context)}条")

    market_subsections = [f"{x['number']} {x['title']}" for x in dynamic_observations] if dynamic_mode else ["1.1 " + payload["market_observation_title"]]
    macro_number = f"1.{len(market_subsections) + 1}"
    payload["section_numbers"] = {"macro_policy": macro_number, "personnel": "2.1", "strategy": "2.2", "industry_chain": "3.1", "competition": "4.1"}
    for category, section_number in payload["section_numbers"].items():
        for item_index, item in enumerate(payload.get(category, []), 1):
            item["number"] = f"{section_number}.{item_index}"
    industry_subsections = list(market_subsections)
    if config.get("include_weekly_content"):
        industry_subsections.append(macro_number + " 宏观政策动态")
    payload["outline"] = [{"number": "1", "title": "行业全景", "subsections": industry_subsections}]
    if config.get("include_weekly_content"):
        payload["outline"].extend([
            {"number": "2", "title": "企业行动", "subsections": ["2.1 人事调整", "2.2 战略调整与布局"]},
            {"number": "3", "title": "产业链观察", "subsections": ["3.1 产业链观察"]},
            {"number": "4", "title": "竞争追踪", "subsections": ["4.1 竞争追踪"]},
        ])
    payload["report_sections"] = {
        **{f"{x['number']} {x['title']}": x.get("insights", []) for x in dynamic_observations},
        macro_number + " 宏观政策动态": [x["title"] for x in payload["macro_policy"]],
        "2.1 人事调整": [x["title"] for x in payload["personnel"]],
        "2.2 战略调整与布局": [x["title"] for x in payload["strategy"]],
        "3. 产业链观察": [x["title"] for x in payload["industry_chain"]],
        "4. 竞争追踪": [x["title"] for x in payload["competition"]],
    }
    modules = []
    if dynamic_mode:
        modules = list(dict.fromkeys(
            component_label(str(component.get("id")))
            for observation in dynamic_observations
            for component in observation.get("components", [])
            if component.get("id")
        ))
        if config.get("include_weekly_content"):
            modules.append("周报内容")
        if config.get("include_anomalies"):
            modules.append("异常提示")
    else:
        for key, label in [
            ("include_overview", "市场观察表"), ("include_rankings", "排名"),
            ("include_power_structure", "动力结构"), ("include_monthly_trend", "月度趋势"),
            ("include_line_charts", "折线图"), ("include_weekly_content", "周报内容"),
            ("include_anomalies", "异常提示"),
        ]:
            if config.get(key):
                modules.append(label)
    payload["included_dashboard_modules"] = modules
    payload["source_note"] = f"当前报告计划版本：v{payload.get('report_plan_revision', 0)}；默认数据分析周期：{payload.get('period_label') or '未指定'}；同比基期：{payload.get('comparison_period_label') or '无可比同期'}。所有市场观察中的数字、排名、占比、同比和图表数据均来自当前上传Excel/CSV的Python确定性计算；每个市场观察可以拥有独立的分析周期和看板组件。区间模式下销量/产量/出口等流量指标按月累计，库存等时点指标采用期末值。宏观政策、人事、战略、产业链和竞争追踪完整保留用户补充资料；资料缺失时不自动编造。"
    return _json_safe(payload)


# ---------------- Excel export ----------------
def autosize_columns(ws):
    for col in ws.columns:
        max_len = 8
        col_letter = get_column_letter(col[0].column)
        for cell in col:
            max_len = max(max_len, min(45, len(str(cell.value or ""))))
        ws.column_dimensions[col_letter].width = max_len + 2


def write_table(ws, start_row: int, title: str, rows: list[dict[str, Any]]) -> int:
    ws.cell(start_row, 1, title).font = Font(bold=True, color=BLUE, size=13)
    if not rows:
        ws.cell(start_row + 1, 1, "无数据或字段缺失")
        return start_row + 3
    headers = list(rows[0].keys())
    r = start_row + 1
    for c, h in enumerate(headers, 1):
        cell = ws.cell(r, c, h)
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor=BLUE)
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
    for i, row in enumerate(rows, r + 1):
        for c, h in enumerate(headers, 1):
            cell = ws.cell(i, c, row.get(h))
            if h in {"占比", "同比", "阈值", "占比合计"} and isinstance(row.get(h), (int, float)):
                cell.number_format = "0.00%"
            cell.alignment = Alignment(vertical="top", wrap_text=True)
    thin = Side(style="thin", color="D0D5DD")
    for row in ws.iter_rows(min_row=r, max_row=r + len(rows), min_col=1, max_col=len(headers)):
        for cell in row:
            cell.border = Border(left=thin, right=thin, top=thin, bottom=thin)
    return r + len(rows) + 3


def _chart_to_wide_rows(chart: dict[str, Any]) -> tuple[list[str], list[list[Any]]]:
    times: list[str] = []
    for s in chart.get("series", []):
        for p in s.get("points", []):
            t = str(p.get("时间"))
            if t not in times:
                times.append(t)
    headers = ["时间"] + [str(s.get("name")) for s in chart.get("series", [])]
    rows = []
    for t in times:
        row = [t]
        for s in chart.get("series", []):
            values = {str(p.get("时间")): p.get("数值") for p in s.get("points", [])}
            row.append(values.get(t))
        rows.append(row)
    return headers, rows


def _safe_sheet_name(name: str, used: set[str]) -> str:
    base = re.sub(r"[\\/*?:\[\]]", "", name)[:25] or "折线图"
    candidate = base
    i = 1
    while candidate in used:
        suffix = f"_{i}"
        candidate = base[:31 - len(suffix)] + suffix
        i += 1
    used.add(candidate)
    return candidate


def write_excel_line_charts(wb: Workbook, charts: list[dict[str, Any]]):
    used = {ws.title for ws in wb.worksheets}
    for idx, chart in enumerate(charts, 1):
        ws = wb.create_sheet(_safe_sheet_name(f"折线图{idx}_{chart.get('title', '')}", used))
        headers, rows = _chart_to_wide_rows(chart)
        ws.append(headers)
        for row in rows:
            ws.append(row)
        if len(rows) >= 2 and len(headers) >= 2:
            line = LineChart()
            line.title = chart.get("title", "折线图")
            line.y_axis.title = chart.get("unit", "")
            line.x_axis.title = "时间"
            if chart.get("y_format") == "percent":
                line.y_axis.numFmt = "0%"
            data_ref = Reference(ws, min_col=2, max_col=len(headers), min_row=1, max_row=len(rows) + 1)
            cats = Reference(ws, min_col=1, min_row=2, max_row=len(rows) + 1)
            line.add_data(data_ref, titles_from_data=True)
            line.set_categories(cats)
            line.height = 8
            line.width = 18
            ws.add_chart(line, "H2")
        autosize_columns(ws)


def export_excel(df: pd.DataFrame, out_path: Path, context_items=None, meta=None, report_config=None, analysis_period=None, report_plan=None) -> Path:
    payload = build_report_payload(df, context_items=context_items, meta=meta, report_config=report_config, analysis_period=analysis_period, report_plan=report_plan)
    wb = Workbook()
    ws = wb.active
    ws.title = "报告目录与摘要"
    ws["A1"] = payload["title"]
    ws["A1"].font = Font(bold=True, size=18, color=BLUE)
    ws["A2"] = payload["report_date"]
    ws["A3"] = f"分析周期：{payload.get('period_label') or '-'}｜同比基期：{payload.get('comparison_period_label') or '无可比同期'}"
    ws["A3"].font = Font(color=MUTED, italic=True)
    row = 5
    for section in payload["outline"]:
        ws.cell(row, 1, f"{section['number']}. {section['title']}").font = Font(bold=True, size=12, color=DARK)
        row += 1
        for sub in section["subsections"]:
            ws.cell(row, 2, "• " + sub)
            row += 1
    row += 1
    if payload.get("dynamic_report_plan"):
        if not payload.get("market_observations"):
            ws.cell(row, 1, "本期未编排市场观察章节").font = Font(italic=True, color=MUTED)
            row += 1
        for observation in payload.get("market_observations", []):
            ws.cell(row, 1, f"{observation.get('number')} {observation.get('title')}").font = Font(bold=True, color=BLUE, size=13)
            row += 1
            ws.cell(row, 1, f"分析周期：{observation.get('period_label') or '-'}")
            row += 1
            for line in [x.strip() for x in str(observation.get("manual_content") or "").splitlines() if x.strip()]:
                ws.cell(row, 1, "业务补充：" + line)
                row += 1
            for item in observation.get("insights", []):
                ws.cell(row, 1, "• " + str(item))
                row += 1
    else:
        ws.cell(row, 1, payload["market_observation_title"]).font = Font(bold=True, color=BLUE, size=13)
        row += 1
        for item in payload["market_key_insights"]:
            ws.cell(row, 1, "• " + str(item))
            row += 1
    autosize_columns(ws)

    cfg = payload["report_config"]
    sheets = []
    dynamic_charts: list[dict[str, Any]] = []
    if payload.get("dynamic_report_plan"):
        used_names = set(wb.sheetnames)
        for obs_index, observation in enumerate(payload.get("market_observations", []), 1):
            for comp_index, component in enumerate(observation.get("components", []), 1):
                if component.get("rows") is not None:
                    base_name = f"观察{obs_index}_{component.get('title') or comp_index}"
                    name = _safe_sheet_name(base_name, used_names)
                    sheet = wb.create_sheet(name)
                    write_table(sheet, 1, str(component.get("title") or "数据组件"), component.get("rows") or [])
                    detail_row = 2 + len(component.get("rows") or [])
                    sheet.cell(detail_row, 1, f"分析周期：{observation.get('period_label') or '-'}")
                    for line in component.get("analysis", []):
                        detail_row += 1
                        sheet.cell(detail_row, 1, "分析：" + str(line))
                    autosize_columns(sheet)
                if component.get("type") == "chart" and component.get("chart"):
                    dynamic_charts.append(component["chart"])
    elif cfg.get("include_overview"):
        sheets.append(("市场观察", payload["overview_table"]))
    if not payload.get("dynamic_report_plan") and cfg.get("include_rankings"):
        sheets.extend([("市场排名", payload["full_market"]), ("OEM排名", payload["full_oem"]), ("品牌排名", payload["full_brand"]), ("车型排名", payload["full_model"])])
    if not payload.get("dynamic_report_plan") and cfg.get("include_power_structure"):
        sheets.append(("动力结构", payload["power_share"]))
    if not payload.get("dynamic_report_plan") and cfg.get("include_monthly_trend"):
        sheets.append(("月度趋势", payload["trend"]))
    if cfg.get("include_anomalies"):
        sheets.append(("异常提示", payload["anomalies"]))
    if cfg.get("include_weekly_content"):
        sheets.extend([("宏观政策", payload["macro_policy"]), ("人事调整", payload["personnel"]), ("战略布局", payload["strategy"]), ("产业链观察", payload["industry_chain"]), ("竞争追踪", payload["competition"])])
    for name, rows in sheets:
        s = wb.create_sheet(name)
        write_table(s, 1, name, rows)
        autosize_columns(s)
    if payload.get("dynamic_report_plan"):
        write_excel_line_charts(wb, dynamic_charts)
    elif cfg.get("include_line_charts"):
        write_excel_line_charts(wb, payload.get("line_charts", []))
    raw = wb.create_sheet("标准化明细")
    raw.append(list(df.columns))
    for _, record in df.iterrows():
        raw.append([record.get(c) for c in df.columns])
    autosize_columns(raw)
    wb.save(out_path)
    _verify_exported_context(out_path, "xlsx", payload)
    return out_path


# ---------------- Word export ----------------
def _doc_set_font(run, size=10.5, bold=False, color=DARK):
    run.font.name = FONT_CN
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = DocxRGBColor.from_string(color)


def _set_docx_cell_shading(cell, fill: str):
    tc_pr = cell._tc.get_or_add_tcPr()
    shading = tc_pr.find(qn("w:shd"))
    if shading is None:
        shading = OxmlElement("w:shd")
        tc_pr.append(shading)
    shading.set(qn("w:fill"), fill)


def _set_docx_cell_margins(cell, top=90, start=110, bottom=90, end=110):
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for margin, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{margin}"))
        if node is None:
            node = OxmlElement(f"w:{margin}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def _market_summary_export_rows(rows: list[dict[str, Any]], metric_label: str) -> list[dict[str, Any]]:
    """把市场核心指标压缩成参考报告的三列表格。"""
    out = []
    uses_custom_values = any("显示值" in row or "数值" in row for row in rows)
    display_metric_label = "数值" if uses_custom_values else metric_label.split("/", 1)[0].strip() if "/" in metric_label else metric_label
    for row in rows:
        value = row.get("显示值") if uses_custom_values else row.get(metric_label)
        if value is None:
            metric_candidates = [
                key for key in row if key not in {"指标", "同比", "环比", "占比", "具体分析", "计算口径"}
            ]
            value = row.get(metric_candidates[0]) if metric_candidates else None
        comparisons = []
        for key in ["同比", "环比"]:
            comparison = row.get(key)
            if isinstance(comparison, (int, float)) and not pd.isna(comparison):
                comparisons.append(f"{key}{fmt_pct(comparison)}")
        value_text = fmt_num(value) if isinstance(value, (int, float)) and not pd.isna(value) else str(value or "缺失")
        if comparisons:
            value_text += "\n（" + "，".join(comparisons) + "）"
        out.append({
            "指标": row.get("指标") or "-",
            display_metric_label: value_text,
            "具体分析": row.get("具体分析") or "当前数据不足，无法形成可靠分析。",
        })
    return out


def add_docx_table(
    doc: Document,
    title: str,
    rows: list[dict[str, Any]],
    max_rows: int | None = 50,
    heading_level: int = 2,
    column_widths: list[float] | None = None,
):
    doc.add_heading(title, level=heading_level)
    if not rows:
        doc.add_paragraph("无数据或字段缺失。")
        return
    headers = list(rows[0].keys())
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = column_widths is None
    header_tr_pr = table.rows[0]._tr.get_or_add_trPr()
    repeat = OxmlElement("w:tblHeader")
    repeat.set(qn("w:val"), "true")
    header_tr_pr.append(repeat)
    for i, h in enumerate(headers):
        cell = table.rows[0].cells[i]
        cell.text = str(h)
        cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        _set_docx_cell_shading(cell, BLUE)
        _set_docx_cell_margins(cell)
        for paragraph in cell.paragraphs:
            paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
            for run in paragraph.runs:
                _doc_set_font(run, 9, True, "FFFFFF")
        if column_widths and i < len(column_widths):
            cell.width = Inches(column_widths[i])
    iterable = rows if max_rows is None else rows[:max_rows]
    for row_index, row in enumerate(iterable):
        cells = table.add_row().cells
        for i, h in enumerate(headers):
            value = row.get(h, "")
            cell = cells[i]
            cell.text = f"{float(value):.2%}" if h in {"占比", "同比", "环比", "阈值", "占比合计"} and isinstance(value, (int, float)) else str(value)
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            _set_docx_cell_margins(cell)
            if row_index % 2:
                _set_docx_cell_shading(cell, LIGHT_BLUE)
            for paragraph in cell.paragraphs:
                paragraph.alignment = WD_ALIGN_PARAGRAPH.LEFT if h in {"具体分析", "计算口径", "内容", "摘要"} else WD_ALIGN_PARAGRAPH.CENTER
                paragraph.paragraph_format.space_after = Pt(0)
                for run in paragraph.runs:
                    _doc_set_font(run, 8.5 if h in {"具体分析", "计算口径"} else 9)
            if column_widths and i < len(column_widths):
                cell.width = Inches(column_widths[i])


def _doc_event_section(doc: Document, heading: str, events: list[dict[str, str]]):
    # One parent subsection followed by numbered source items.
    doc.add_heading(heading, level=2)
    for event in events:
        child_heading = " ".join(x for x in [event.get("number", ""), event.get("title", "未命名事件")] if x)
        p = doc.add_heading(child_heading, level=3)
        for r in p.runs:
            _doc_set_font(r, 11, True, BLUE)
        content = str(event.get("content") or event.get("summary") or "")
        for source_paragraph in content.splitlines() or [""]:
            if not source_paragraph.strip():
                continue
            p2 = doc.add_paragraph(source_paragraph)
            for run in p2.runs:
                _doc_set_font(run, 10.5)
        if event.get("analysis"):
            p3 = doc.add_paragraph("分析：" + event["analysis"])
            for run in p3.runs:
                _doc_set_font(run, 10.5)
        if event.get("source"):
            p4 = doc.add_paragraph("来源：" + event["source"])
            for run in p4.runs:
                _doc_set_font(run, 9, False, MUTED)


def _mpl_config():
    plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "Arial Unicode MS", "DejaVu Sans"]
    plt.rcParams["axes.unicode_minus"] = False


def _save_line_chart_png(chart: dict[str, Any], path: Path) -> bool:
    _mpl_config()
    series = chart.get("series", []) or []
    if not series:
        return False
    fig, ax = plt.subplots(figsize=(9.6, 4.4), dpi=150)
    plotted = False
    for s in series[:6]:
        pts = s.get("points", []) or []
        xs = [str(p.get("时间", "")) for p in pts]
        ys = [p.get("数值") for p in pts]
        if xs and ys:
            ax.plot(xs, ys, marker="o", linewidth=1.8, label=str(s.get("name", "系列")))
            plotted = True
    if not plotted:
        plt.close(fig); return False
    ax.set_title(str(chart.get("title", "折线图")))
    ax.grid(True, axis="y", alpha=0.25)
    ax.tick_params(axis="x", rotation=35)
    if chart.get("y_format") == "percent":
        from matplotlib.ticker import PercentFormatter
        ax.yaxis.set_major_formatter(PercentFormatter(1.0))
    if len(series) > 1:
        ax.legend(loc="best", fontsize=8)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    return True


def _save_ranking_bar_png(title: str, rows: list[dict[str, Any]], path: Path) -> bool:
    if not rows:
        return False
    _mpl_config()
    data = rows[:10][::-1]
    labels = [str(x.get("对象", "")) for x in data]
    values = [float(x.get("销量/数值") or 0) for x in data]
    fig, ax = plt.subplots(figsize=(8.8, 4.8), dpi=150)
    ax.barh(labels, values)
    ax.set_title(title)
    ax.grid(True, axis="x", alpha=0.2)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    return True


def _save_power_pie_png(rows: list[dict[str, Any]], path: Path) -> bool:
    if not rows:
        return False
    _mpl_config()
    pairs = [(str(x.get("动力类型", "")), float(x.get("销量/数值") or 0)) for x in rows if float(x.get("销量/数值") or 0) > 0]
    pairs.sort(key=lambda item: item[1], reverse=True)
    if len(pairs) > 9:
        pairs = pairs[:8] + [("其他", sum(value for _, value in pairs[8:]))]
    labels = [item[0] for item in pairs]
    values = [item[1] for item in pairs]
    if sum(values) <= 0:
        return False
    total = sum(values)
    fig, ax = plt.subplots(figsize=(9.2, max(5.2, 3.8 + len(labels) * 0.18)), dpi=170)

    def visible_percentage(pct: float) -> str:
        return f"{pct:.1f}%" if pct >= 3.0 else ""

    wedges, _, autotexts = ax.pie(
        values,
        labels=None,
        autopct=visible_percentage,
        startangle=90,
        counterclock=False,
        pctdistance=0.77,
        wedgeprops={"width": 0.44, "edgecolor": "white", "linewidth": 1.0},
    )
    for text in autotexts:
        text.set_fontsize(9)
    legend_labels = [f"{label}  {value / total:.1%}" for label, value in zip(labels, values)]
    ax.legend(wedges, legend_labels, title="动力类型", loc="center left", bbox_to_anchor=(1.01, 0.5), frameon=False, fontsize=8, ncol=1 if len(labels) <= 9 else 2)
    ax.set_title("动力类型结构占比")
    ax.set_aspect("equal")
    fig.subplots_adjust(left=0.04, right=0.74, top=0.90, bottom=0.06)
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    return True


def _save_monthly_trend_png(rows: list[dict[str, Any]], path: Path) -> bool:
    if not rows:
        return False
    chart = {
        "title": "月度趋势",
        "series": [{"name": "销量/数值", "points": [{"时间": x.get("时间"), "数值": x.get("数值")} for x in rows]}],
        "y_format": "number",
    }
    return _save_line_chart_png(chart, path)


def export_docx(df: pd.DataFrame, out_path: Path, context_items=None, meta=None, report_config=None, analysis_period=None, report_plan=None) -> Path:
    payload = build_report_payload(df, context_items=context_items, meta=meta, report_config=report_config, analysis_period=analysis_period, report_plan=report_plan)
    cfg = payload["report_config"]
    doc = Document()
    sec = doc.sections[0]
    sec.top_margin = Inches(0.7)
    sec.bottom_margin = Inches(0.7)
    for style_name in ["Title", "Heading 1", "Heading 2", "Heading 3"]:
        style = doc.styles[style_name]
        style.font.name = FONT_CN
        style.font.color.rgb = DocxRGBColor.from_string("000000")
    title = doc.add_heading(payload["title"], level=0)
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for run in title.runs:
        _doc_set_font(run, 20, True, "000000")
    doc.add_paragraph(payload["report_date"]).alignment = WD_ALIGN_PARAGRAPH.CENTER
    period_p = doc.add_paragraph(f"分析周期：{payload.get('period_label') or '-'}｜同比基期：{payload.get('comparison_period_label') or '无可比同期'}")
    period_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    note = doc.add_paragraph("AI Agent 自动生成初稿｜市场数字由程序计算｜判断性内容需人工确认")
    note.alignment = WD_ALIGN_PARAGRAPH.CENTER

    doc.add_heading("目录", level=1)
    for section in payload["outline"]:
        doc.add_paragraph(f"{section['number']}. {section['title']}")
        for sub in section["subsections"]:
            doc.add_paragraph(sub, style="List Bullet")
    doc.add_paragraph("数据看板内容：" + "、".join(payload.get("included_dashboard_modules", [])))

    with tempfile.TemporaryDirectory(prefix="market_report_") as td:
        tmp = Path(td)
        doc.add_heading("1. 行业全景", level=1)
        if payload.get("dynamic_report_plan"):
            if not payload.get("market_observations"):
                doc.add_paragraph("本期未编排市场观察章节。")
            for obs_index, observation in enumerate(payload.get("market_observations", []), 1):
                doc.add_heading(f"{observation.get('number')} {observation.get('title')}", level=2)
                doc.add_paragraph(
                    f"分析周期：{observation.get('period_label') or '-'}｜同比基期：{observation.get('comparison_period_label') or '无可比同期'}"
                )
                if observation.get("manual_content"):
                    manual_lines = [line.strip() for line in str(observation["manual_content"]).splitlines() if line.strip()]
                    for line_index, line in enumerate(manual_lines):
                        paragraph = doc.add_paragraph()
                        label = paragraph.add_run("业务补充：" if line_index == 0 else "")
                        _doc_set_font(label, 10.5, True, DARK)
                        text_run = paragraph.add_run(line)
                        _doc_set_font(text_run, 10.5, False, DARK)
                for insight in observation.get("insights", [])[:4]:
                    doc.add_paragraph(str(insight), style="List Bullet")
                for comp_index, component in enumerate(observation.get("components", []), 1):
                    title_text = str(component.get("title") or "数据组件")
                    rows = component.get("rows")
                    if rows is not None:
                        if component.get("id") == "market_summary":
                            metric_label = str((observation.get("fact_pack") or {}).get("primary_metric_label") or "销量")
                            if component.get("matrix"):
                                add_docx_table(doc, title_text, rows, heading_level=3)
                            else:
                                export_rows = _market_summary_export_rows(rows, metric_label)
                                add_docx_table(doc, title_text, export_rows, heading_level=3, column_widths=[0.85, 1.35, 4.65])
                        else:
                            add_docx_table(doc, title_text, rows, heading_level=3)
                        image_path = tmp / f"obs_{obs_index}_component_{comp_index}.png"
                        if component.get("type") == "ranking" and _save_ranking_bar_png(title_text, rows, image_path):
                            doc.add_picture(str(image_path), width=Inches(6.7))
                        elif component.get("id") == "power_structure" and _save_power_pie_png(rows, image_path):
                            doc.add_picture(str(image_path), width=Inches(6.3))
                    if component.get("type") == "chart" and component.get("chart"):
                        image_path = tmp / f"obs_{obs_index}_chart_{comp_index}.png"
                        if _save_line_chart_png(component["chart"], image_path):
                            doc.add_heading(title_text, level=3)
                            doc.add_picture(str(image_path), width=Inches(6.7))
                            doc.add_paragraph("来源字段：" + str(component["chart"].get("source", "未知")))
                    if component.get("id") != "market_summary":
                        for analysis_line in component.get("analysis", []):
                            paragraph = doc.add_paragraph()
                            lead = paragraph.add_run("分析：")
                            _doc_set_font(lead, 10, True, DARK)
                            detail = paragraph.add_run(str(analysis_line))
                            _doc_set_font(detail, 10, False, DARK)
        else:
            doc.add_heading("1.1 " + payload["market_observation_title"], level=2)
            for insight in payload["market_key_insights"]:
                doc.add_paragraph(str(insight), style="List Bullet")

        if not payload.get("dynamic_report_plan") and cfg.get("include_overview"):
            add_docx_table(doc, "市场观察表", payload["overview_table"])

        if not payload.get("dynamic_report_plan") and cfg.get("include_rankings"):
            doc.add_heading("排名", level=2)
            ranking_sets = [
                ("市场排名", payload.get("top_market", [])), ("OEM TOP10", payload.get("top_oem", [])),
                ("品牌 TOP10", payload.get("top_brand", [])), ("车型 TOP10", payload.get("top_model", [])),
            ]
            for idx, (heading, rows) in enumerate(ranking_sets):
                if not rows:
                    continue
                add_docx_table(doc, heading, rows)
                img = tmp / f"ranking_{idx}.png"
                if _save_ranking_bar_png(heading, rows, img):
                    doc.add_picture(str(img), width=Inches(6.7))
            for insight in payload["competition_insights"]:
                doc.add_paragraph(str(insight), style="List Bullet")

        if not payload.get("dynamic_report_plan") and cfg.get("include_power_structure"):
            add_docx_table(doc, "动力类型结构占比", payload["power_share"])
            img = tmp / "power_share.png"
            if _save_power_pie_png(payload["power_share"], img):
                doc.add_picture(str(img), width=Inches(6.3))

        if not payload.get("dynamic_report_plan") and cfg.get("include_monthly_trend"):
            add_docx_table(doc, "月度趋势", payload["trend"])
            img = tmp / "monthly_trend.png"
            if _save_monthly_trend_png(payload["trend"], img):
                doc.add_picture(str(img), width=Inches(6.7))

        if not payload.get("dynamic_report_plan") and cfg.get("include_line_charts"):
            doc.add_heading("折线图", level=2)
            if not payload.get("line_charts"):
                doc.add_paragraph("当前数据没有可生成的折线图。")
            for idx, chart in enumerate(payload.get("line_charts", [])):
                img = tmp / f"line_{idx}.png"
                if _save_line_chart_png(chart, img):
                    doc.add_paragraph(str(chart.get("title", "折线图")))
                    doc.add_picture(str(img), width=Inches(6.7))
                    doc.add_paragraph("来源字段：" + str(chart.get("source", "未知")))

        if cfg.get("include_weekly_content"):
            macro_number = (payload.get("section_numbers") or {}).get("macro_policy", "1.2")
            _doc_event_section(doc, macro_number + " 宏观政策动态", payload["macro_policy"])
            doc.add_heading("2. 企业行动", level=1)
            _doc_event_section(doc, "2.1 人事调整", payload["personnel"])
            _doc_event_section(doc, "2.2 战略调整与布局", payload["strategy"])
            doc.add_heading("3. 产业链观察", level=1)
            _doc_event_section(doc, "3.1 产业链观察", payload["industry_chain"])
            doc.add_heading("4. 竞争追踪", level=1)
            _doc_event_section(doc, "4.1 竞争追踪", payload["competition"])

        if cfg.get("include_anomalies") and payload.get("anomalies"):
            add_docx_table(doc, "异常指标提示", payload["anomalies"])
        doc.save(out_path)
    _verify_exported_context(out_path, "docx", payload)
    return out_path


# ---------------- PowerPoint export ----------------
def _ppt_font(paragraph, size=12, bold=False, color=DARK, align=None):
    paragraph.font.name = FONT_CN
    paragraph.font.size = PPt(size)
    paragraph.font.bold = bold
    paragraph.font.color.rgb = RGBColor.from_string(color)
    if align is not None:
        paragraph.alignment = align


def _add_page_number(slide, num: int):
    box = slide.shapes.add_textbox(PInches(10.3), PInches(7.18), PInches(2.5), PInches(0.22))
    p = box.text_frame.paragraphs[0]
    p.text = str(num)
    _ppt_font(p, 9, False, MUTED, PP_ALIGN.RIGHT)


def add_title(slide, title: str, subtitle: str | None = None, page_num: int | None = None):
    box = slide.shapes.add_textbox(PInches(0.42), PInches(0.07), PInches(12.2), PInches(0.72))
    p = box.text_frame.paragraphs[0]
    p.text = title
    _ppt_font(p, 20, True, DARK)
    if subtitle:
        box2 = slide.shapes.add_textbox(PInches(0.45), PInches(0.70), PInches(12), PInches(0.46))
        box2.text_frame.word_wrap = True
        p2 = box2.text_frame.paragraphs[0]
        p2.text = subtitle
        p2.line_spacing = 1.0
        _ppt_font(p2, 8.8, False, MUTED)
    if page_num is not None:
        _add_page_number(slide, page_num)


def add_section_divider(prs: Presentation, blank, number: str, title: str, page_num: int):
    slide = prs.slides.add_slide(blank)
    # blank版式本身为白底，避免使用贴边满画布矩形导致某些PowerPoint/LibreOffice渲染器判定越界。
    box = slide.shapes.add_textbox(PInches(1.1), PInches(2.5), PInches(10.5), PInches(1.2))
    p = box.text_frame.paragraphs[0]
    p.text = f"{number}. {title}"
    _ppt_font(p, 32, True, DARK)
    accent = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, PInches(1.1), PInches(3.9), PInches(2.2), PInches(0.12))
    accent.fill.solid(); accent.fill.fore_color.rgb = RGBColor.from_string(BLUE); accent.line.fill.background()
    _add_page_number(slide, page_num)
    return slide


def add_bullets(slide, items: list[str], x, y, w, h, font_size=13, color=DARK):
    tx = slide.shapes.add_textbox(x, y, w, h)
    tf = tx.text_frame
    tf.clear(); tf.word_wrap = True
    for idx, item in enumerate(items):
        p = tf.paragraphs[0] if idx == 0 else tf.add_paragraph()
        p.text = str(item)
        p.level = 0
        p.space_after = PPt(6)
        _ppt_font(p, font_size, False, color)


def _table_visible_headers(rows: list[dict[str, Any]]) -> list[str]:
    if not rows:
        return []
    headers = list(rows[0].keys())
    visible = []
    for h in headers:
        vals = [str(r.get(h, "")) for r in rows]
        if h == "具体分析" or any(v not in {"", "缺失", "None", "nan"} for v in vals):
            visible.append(h)
    return visible[:6]


def _set_ppt_cell_border(cell, color: str = "D8DEE8", width_pt: float = 0.65):
    """Apply a subtle, explicit grid that renders consistently in PowerPoint."""
    tc_pr = cell._tc.get_or_add_tcPr()
    for edge in ("lnL", "lnR", "lnT", "lnB"):
        tag = f"{{http://schemas.openxmlformats.org/drawingml/2006/main}}{edge}"
        existing = tc_pr.find(tag)
        if existing is not None:
            tc_pr.remove(existing)
        line = PptxOxmlElement(f"a:{edge}")
        line.set("w", str(PPt(width_pt)))
        solid_fill = PptxOxmlElement("a:solidFill")
        rgb = PptxOxmlElement("a:srgbClr")
        rgb.set("val", color)
        solid_fill.append(rgb)
        line.append(solid_fill)
        dash = PptxOxmlElement("a:prstDash")
        dash.set("val", "solid")
        line.append(dash)
        tc_pr.append(line)


def _ppt_table_column_weights(headers: list[str]) -> list[float]:
    if not headers:
        return []
    if "具体分析" in headers:
        remaining = [header for header in headers if header != "具体分析"]
        if len(remaining) == 2:
            return [0.18 if header == remaining[0] else 0.22 if header == remaining[1] else 0.60 for header in headers]
        return [0.48 / max(1, len(remaining)) if header != "具体分析" else 0.52 for header in headers]
    if headers[:4] == ["动力类型", "销量/数值", "占比", "销量口径"]:
        return [0.22, 0.28, 0.22, 0.28]
    if "对象" in headers:
        weights = []
        for header in headers:
            weights.append(0.13 if header in {"排名", "序号"} else 0.35 if header == "对象" else 0.52 / max(1, len(headers) - 2))
        return weights
    return [1 / len(headers)] * len(headers)


def add_ppt_table(slide, rows: list[dict[str, Any]], x, y, w, h, max_rows=8, font_size=9):
    if not rows:
        tx = slide.shapes.add_textbox(x, y, w, h)
        p = tx.text_frame.paragraphs[0]; p.text = "无数据或字段缺失"; _ppt_font(p, 12, False, MUTED)
        return
    rows = rows if max_rows is None else rows[:max_rows]
    headers = _table_visible_headers(rows)
    shape = slide.shapes.add_table(len(rows) + 1, len(headers), x, y, w, h)
    table = shape.table
    for idx, weight in enumerate(_ppt_table_column_weights(headers)):
        table.columns[idx].width = int(w * weight)

    # Do not stretch a short table to the full placeholder height.  Fixed,
    # content-aware rows avoid the oversized whitespace seen in PowerPoint.
    header_height = PInches(0.44)
    usable_body = max(PInches(0.38), int(h) - int(header_height))
    natural_body = usable_body / max(1, len(rows))
    if "具体分析" in headers:
        body_height = min(max(natural_body, PInches(0.62)), PInches(0.82))
    else:
        body_height = min(max(natural_body, PInches(0.42)), PInches(0.58))
    table.rows[0].height = header_height
    for row_index in range(1, len(table.rows)):
        table.rows[row_index].height = int(body_height)

    body_font_size = max(float(font_size), 8.8)
    analysis_font_size = max(8.2, body_font_size - 0.4)
    for j, hname in enumerate(headers):
        cell = table.cell(0, j)
        cell.text = str(hname)
        cell.fill.solid(); cell.fill.fore_color.rgb = RGBColor.from_string(BLUE)
        cell.vertical_anchor = MSO_ANCHOR.MIDDLE
        cell.margin_left = PInches(0.06); cell.margin_right = PInches(0.06)
        cell.margin_top = PInches(0.025); cell.margin_bottom = PInches(0.025)
        _set_ppt_cell_border(cell, "0A4F91", 0.7)
        for p in cell.text_frame.paragraphs:
            _ppt_font(p, max(9.6, body_font_size + 0.6), True, "FFFFFF", PP_ALIGN.CENTER)
    for i, row in enumerate(rows, 1):
        for j, hname in enumerate(headers):
            cell = table.cell(i, j)
            value = row.get(hname, "")
            if value is None or (isinstance(value, float) and pd.isna(value)):
                text = "-"
            elif hname in {"占比", "同比", "环比", "阈值", "占比合计"} and isinstance(value, (int, float)):
                text = f"{float(value):.2%}"
            elif isinstance(value, (int, float)):
                numeric = float(value)
                text = f"{numeric:,.0f}" if numeric.is_integer() else f"{numeric:,.2f}".rstrip("0").rstrip(".")
            else:
                text = str(value)
            cell.text = text[:260]
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.text_frame.word_wrap = True
            cell.margin_left = PInches(0.07); cell.margin_right = PInches(0.07)
            cell.margin_top = PInches(0.035); cell.margin_bottom = PInches(0.035)
            if i % 2 == 0:
                cell.fill.solid(); cell.fill.fore_color.rgb = RGBColor.from_string("F4F7FB")
            else:
                cell.fill.solid(); cell.fill.fore_color.rgb = RGBColor.from_string("FFFFFF")
            _set_ppt_cell_border(cell)
            for p in cell.text_frame.paragraphs:
                alignment = PP_ALIGN.LEFT if hname in {"具体分析", "内容", "问题说明", "处理建议"} else PP_ALIGN.CENTER
                _ppt_font(p, analysis_font_size if hname == "具体分析" else body_font_size, False, DARK, alignment)
                p.space_before = PPt(0)
                p.space_after = PPt(0)


def _set_ppt_chart_tick_label_rotation(axis, degrees: int = 0):
    """Force chart tick labels to a predictable orientation in PowerPoint."""
    tx_pr = axis._element.get_or_add_txPr()
    tx_pr.bodyPr.set("rot", str(int(degrees * 60000)))
    tx_pr.bodyPr.set("vert", "horz")


def _set_ppt_category_label_skip(axis, skip: int):
    """Show every Nth category label while retaining every data point."""
    element = axis._element
    existing = element.find("{http://schemas.openxmlformats.org/drawingml/2006/chart}tickLblSkip")
    if existing is not None:
        element.remove(existing)
    tick_skip = PptxOxmlElement("c:tickLblSkip")
    tick_skip.set("val", str(max(1, int(skip))))
    element.insert_element_before(tick_skip, "c:noMultiLvlLbl", "c:extLst")


def _set_ppt_date_axis_interval(axis, months: int):
    """Set a monthly major interval for python-pptx versions without this API."""
    element = axis._element
    namespace = "{http://schemas.openxmlformats.org/drawingml/2006/chart}"
    for local_name in ("majorUnit", "majorTimeUnit"):
        existing = element.find(namespace + local_name)
        if existing is not None:
            element.remove(existing)
    major_unit = PptxOxmlElement("c:majorUnit")
    major_unit.set("val", str(max(1, int(months))))
    element.insert_element_before(major_unit, "c:crosses", "c:crossesAt", "c:extLst")
    major_time_unit = PptxOxmlElement("c:majorTimeUnit")
    major_time_unit.set("val", "months")
    element.insert_element_before(major_time_unit, "c:crosses", "c:crossesAt", "c:extLst")


def _ppt_axis_number_format(values: list[float], is_percent: bool) -> str:
    if is_percent:
        return "0%"
    max_abs = max((abs(value) for value in values), default=0)
    if max_abs >= 1_000_000:
        return '0.0,,"M"'
    if max_abs >= 1_000:
        return '0.0,"K"'
    return "0.##"


def _ppt_chart_categories(rows: list[list[Any]], width) -> tuple[list[Any], int, bool]:
    """Use a real date axis for monthly data so every renderer samples it safely."""
    desired_labels = 6 if width <= PInches(6.5) else 10
    interval = max(1, (len(rows) + desired_labels - 1) // desired_labels)
    month_categories: list[date] = []
    for row in rows:
        raw = str(row[0] or "")
        match = re.fullmatch(r"(\d{4})[-/.](\d{1,2})", raw)
        if not match:
            return [str(item[0] or "")[:12] for item in rows], interval, False
        year, month = match.groups()
        month_categories.append(date(int(year), int(month), 1))
    return month_categories, interval, True


def add_ppt_line_chart(slide, chart: dict[str, Any], x, y, w, h):
    headers, rows = _chart_to_wide_rows(chart)
    if len(headers) < 2 or len(rows) < 2:
        return
    chart_data = ChartData()
    categories, label_interval, is_month_axis = _ppt_chart_categories(rows, w)
    chart_data.categories = categories
    numeric_values: list[float] = []
    for col_idx, name in enumerate(headers[1:5], 1):
        values = [float(r[col_idx]) if col_idx < len(r) and r[col_idx] is not None and not pd.isna(r[col_idx]) else None for r in rows]
        numeric_values.extend(value for value in values if value is not None)
        chart_data.add_series(name, values)
    graphic = slide.shapes.add_chart(XL_CHART_TYPE.LINE_MARKERS, x, y, w, h, chart_data)
    chart_obj = graphic.chart
    chart_obj.has_legend = True
    chart_obj.legend.position = XL_LEGEND_POSITION.BOTTOM
    chart_obj.legend.include_in_layout = False
    chart_obj.legend.font.name = FONT_CN
    chart_obj.legend.font.size = PPt(9.5)
    chart_obj.chart_title.has_text_frame = True
    chart_obj.chart_title.text_frame.text = chart.get("title", "折线图")
    for p in chart_obj.chart_title.text_frame.paragraphs:
        _ppt_font(p, 12.5, True, DARK, PP_ALIGN.CENTER)
    palette = ["3E7CC3", "D3544C", "2F9E73", "8C67C7"]
    for index, series in enumerate(chart_obj.series):
        color = RGBColor.from_string(palette[index % len(palette)])
        series.format.line.color.rgb = color
        series.format.line.width = PPt(1.8)
        series.marker.style = XL_MARKER_STYLE.CIRCLE
        series.marker.size = 5
        series.marker.format.fill.solid()
        series.marker.format.fill.fore_color.rgb = color
        series.marker.format.line.color.rgb = color
    category_axis = chart_obj.category_axis
    category_axis.tick_labels.font.name = FONT_CN
    category_axis.tick_labels.font.size = PPt(8.5)
    category_axis.tick_labels.font.color.rgb = RGBColor.from_string(MUTED)
    _set_ppt_chart_tick_label_rotation(category_axis, 0)
    if is_month_axis:
        _set_ppt_date_axis_interval(category_axis, label_interval)
        category_axis.tick_labels.number_format = "yy-MM"
        category_axis.tick_labels.number_format_is_linked = False
    else:
        _set_ppt_category_label_skip(category_axis, label_interval)

    value_axis = chart_obj.value_axis
    value_axis.tick_labels.font.name = FONT_CN
    value_axis.tick_labels.font.size = PPt(8.5)
    value_axis.tick_labels.font.color.rgb = RGBColor.from_string(MUTED)
    value_axis.tick_labels.number_format = _ppt_axis_number_format(numeric_values, chart.get("y_format") == "percent")
    value_axis.tick_labels.number_format_is_linked = False
    chart_obj.value_axis.has_major_gridlines = True
    chart_obj.value_axis.major_gridlines.format.line.color.rgb = RGBColor(225, 230, 236)
    chart_obj.value_axis.major_gridlines.format.line.width = PPt(0.6)
    if chart.get("y_format") == "percent":
        value_axis.minimum_scale = 0
        value_axis.maximum_scale = 1
        value_axis.major_unit = 0.2


def add_ppt_bar_chart(slide, title: str, rows: list[dict[str, Any]], x, y, w, h):
    if not rows:
        return
    data = rows[:10]
    chart_data = ChartData()
    chart_data.categories = [str(r.get("对象", "")) for r in data]
    chart_data.add_series("销量/数值", [float(r.get("销量/数值") or 0) for r in data])
    graphic = slide.shapes.add_chart(XL_CHART_TYPE.BAR_CLUSTERED, x, y, w, h, chart_data)
    c = graphic.chart
    c.has_legend = False
    c.chart_title.has_text_frame = True
    c.chart_title.text_frame.text = title
    for p in c.chart_title.text_frame.paragraphs:
        _ppt_font(p, 12.5, True, DARK, PP_ALIGN.CENTER)
    for series in c.series:
        series.format.fill.solid()
        series.format.fill.fore_color.rgb = RGBColor.from_string("3E7CC3")
        series.format.line.color.rgb = RGBColor.from_string("3E7CC3")
    c.category_axis.tick_labels.font.name = FONT_CN
    c.category_axis.tick_labels.font.size = PPt(9)
    c.value_axis.tick_labels.font.name = FONT_CN
    c.value_axis.tick_labels.font.size = PPt(8.5)
    c.value_axis.has_major_gridlines = True
    try:
        c.category_axis.reverse_order = True
    except Exception:
        pass


def add_ppt_pie_chart(slide, title: str, rows: list[dict[str, Any]], x, y, w, h):
    if not rows:
        return
    data = [r for r in rows[:10] if r.get("销量/数值") is not None]
    if not data:
        return
    chart_data = ChartData()
    chart_data.categories = [str(r.get("动力类型", "")) for r in data]
    chart_data.add_series(str(data[0].get("销量口径") or "销量/数值"), [float(r.get("销量/数值") or 0) for r in data])
    graphic = slide.shapes.add_chart(XL_CHART_TYPE.PIE, x, y, w, h, chart_data)
    c = graphic.chart
    c.has_legend = True
    c.legend.position = XL_LEGEND_POSITION.BOTTOM
    c.legend.include_in_layout = False
    c.chart_title.has_text_frame = True
    c.chart_title.text_frame.text = title
    for p in c.chart_title.text_frame.paragraphs:
        _ppt_font(p, 12.5, True, DARK, PP_ALIGN.CENTER)
    c.legend.font.name = FONT_CN
    c.legend.font.size = PPt(9)
    plot = c.plots[0]
    # The adjacent native table already carries the exact values and shares.
    # Labels on eight pie slices collide in PowerPoint, so the chart uses a
    # clean legend rather than duplicating unreadable annotations.
    plot.has_data_labels = False
    palette = ["3E7CC3", "70AD47", "ED7D31", "A5A5A5", "FFC000", "8C67C7", "5B9BD5", "C55A11"]
    for index, point in enumerate(c.series[0].points):
        point.format.fill.solid()
        point.format.fill.fore_color.rgb = RGBColor.from_string(palette[index % len(palette)])
        point.format.line.color.rgb = RGBColor.from_string("FFFFFF")


def _choose_report_charts(charts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    priorities = ["新能源汽车市场占有率", "核心指标同比增速", "动力类型月度结构占比", "核心指标月度趋势", "Top车型月度销量趋势"]
    ordered = []
    for name in priorities:
        ordered.extend([c for c in charts if c.get("title") == name and c not in ordered])
    ordered.extend([c for c in charts if c not in ordered])
    return ordered


def _split_text_for_slides(text: str, max_chars: int = 700) -> list[str]:
    """Split text for slide layout without dropping source characters."""
    chunks: list[str] = []
    current = ""
    for paragraph in str(text or "").splitlines() or [""]:
        pieces = [paragraph[i:i + max_chars] for i in range(0, len(paragraph), max_chars)] or [""]
        for piece in pieces:
            addition = piece if not current else "\n" + piece
            if current and len(current) + len(addition) > max_chars:
                chunks.append(current)
                current = piece
            else:
                current += addition
    if current or not chunks:
        chunks.append(current)
    return chunks


def _add_event_cards(prs, blank, heading: str, events: list[dict[str, str]], start_page: int) -> int:
    page = start_page
    if not events:
        return page
    for event in events:
        content = str(event.get("content") or event.get("summary") or "")
        chunks = _split_text_for_slides(content)
        child_heading = " ".join(x for x in [event.get("number", ""), event.get("title", "未命名事件")] if x)
        for chunk_index, chunk in enumerate(chunks):
            slide = prs.slides.add_slide(blank)
            add_title(slide, child_heading + ("（续）" if chunk_index else ""), subtitle=f"所属章节：{heading}", page_num=page)
            body = slide.shapes.add_textbox(PInches(0.72), PInches(1.18), PInches(11.85), PInches(5.55))
            tf = body.text_frame
            tf.word_wrap = True
            tf.margin_left = PInches(0.08)
            tf.margin_right = PInches(0.08)
            tf.margin_top = PInches(0.08)
            paragraph = tf.paragraphs[0]
            paragraph.text = chunk
            paragraph.line_spacing = 1.18
            _ppt_font(paragraph, 12, False, DARK)
            if chunk_index == len(chunks) - 1 and event.get("source"):
                source = tf.add_paragraph()
                source.text = "来源：" + event["source"]
                source.space_before = PPt(8)
                _ppt_font(source, 9, False, MUTED)
            page += 1
    return page



def _ppt_market_summary_rows(rows: list[dict[str, Any]], metric_label: str) -> list[dict[str, Any]]:
    out=[]
    uses_custom_values = any("显示值" in row or "数值" in row for row in rows)
    value_header = "数值" if uses_custom_values else metric_label
    for row in rows:
        item={"指标": row.get("指标"), value_header: row.get("显示值") if uses_custom_values else row.get(metric_label), "同比": row.get("同比"), "环比": row.get("环比"), "具体分析": row.get("具体分析")}
        # PPT直接显示百分数，避免0.0643不直观。
        for k in ["同比","环比"]:
            v=item.get(k)
            if isinstance(v,(int,float)) and not pd.isna(v): item[k]=f"{v:+.1%}"
        val=item.get(value_header)
        if isinstance(val,(int,float)) and not pd.isna(val): item[value_header]=f"{val:,.0f}"
        out.append(item)
    return out


def _add_dynamic_market_observation_slides(prs, blank, payload: dict[str, Any], page: int) -> int:
    for observation in payload.get("market_observations", []) or []:
        comps = observation.get("components", []) or []
        number = observation.get("number") or "1.1"
        title = observation.get("title") or "市场观察"
        metric_label = (observation.get("fact_pack") or {}).get("primary_metric_label") or "销量"
        summary = next((c for c in comps if c.get("id") == "market_summary"), None)
        charts = [c for c in comps if c.get("type") == "chart" and c.get("chart")]
        others = [c for c in comps if c is not summary and c.get("type") != "chart"]
        used_chart_ids: set[int] = set()
        manual_content = str(observation.get("manual_content") or "").strip()
        if manual_content:
            for manual_index, manual_chunk in enumerate(_split_text_for_slides(manual_content)):
                slide = prs.slides.add_slide(blank)
                add_title(
                    slide,
                    f"{number} {title}" + ("（业务补充续）" if manual_index else ""),
                    subtitle=f"分析周期：{observation.get('period_label') or '-'}｜用户补充观察",
                    page_num=page,
                )
                box = slide.shapes.add_textbox(PInches(0.8), PInches(1.3), PInches(11.8), PInches(5.5))
                paragraph = box.text_frame.paragraphs[0]
                paragraph.text = manual_chunk
                _ppt_font(paragraph, 13, False, DARK)
                page += 1
        if not comps:
            continue
        if summary:
            all_summary_rows = (
                summary.get("rows", [])
                if summary.get("matrix")
                else _ppt_market_summary_rows(summary.get("rows", []), metric_label)
            )
            # Keep the table on its own slide. Mixing a business table with two
            # 24-month charts made both the body text and axis labels unreadable.
            first_capacity = 8
            row_chunks = [all_summary_rows[:first_capacity]]
            row_chunks.extend(all_summary_rows[start:start + 8] for start in range(first_capacity, len(all_summary_rows), 8))
            for summary_index, rows in enumerate(row_chunks):
                slide = prs.slides.add_slide(blank)
                suffix = "（续）" if summary_index else ""
                add_title(slide, f"{number} {title}{suffix}", subtitle=f"分析周期：{observation.get('period_label') or '-'}；内容由可视化报告计划选择", page_num=page)
                add_ppt_table(slide, rows, PInches(0.52), PInches(1.14), PInches(12.28), PInches(5.72), max_rows=None, font_size=9.2)
                page += 1
        for comp in others:
            rows = comp.get("rows", []) or []
            if not rows:
                continue
            analysis_text = "；".join(str(x) for x in (comp.get("analysis") or []))[:110]
            subtitle = f"所属市场观察：{title}｜分析周期：{observation.get('period_label') or '-'}"
            if analysis_text:
                subtitle += "｜" + analysis_text
            if comp.get("id") == "power_structure":
                slide = prs.slides.add_slide(blank)
                add_title(slide, f"{number} {comp.get('title') or '动力类型结构占比'}", subtitle=subtitle, page_num=page)
                add_ppt_table(slide, rows, PInches(0.55), PInches(1.18), PInches(6.35), PInches(5.5), max_rows=10, font_size=9.2)
                add_ppt_pie_chart(slide, str(comp.get("title") or "动力类型结构占比"), rows, PInches(7.2), PInches(1.38), PInches(5.45), PInches(4.95))
                page += 1
            elif comp.get("type") == "ranking":
                slide = prs.slides.add_slide(blank)
                add_title(slide, f"{number} {comp.get('title') or '数据组件'}", subtitle=subtitle, page_num=page)
                add_ppt_table(slide, rows, PInches(0.48), PInches(1.1), PInches(5.35), PInches(5.6), max_rows=10, font_size=9)
                add_ppt_bar_chart(slide, str(comp.get("title")), rows, PInches(6.05), PInches(1.2), PInches(6.7), PInches(5.4))
                page += 1
            else:
                for row_index in range(0, len(rows), 16):
                    slide = prs.slides.add_slide(blank)
                    suffix = "（续）" if row_index else ""
                    add_title(slide, f"{number} {comp.get('title') or '数据组件'}{suffix}", subtitle=subtitle, page_num=page)
                    add_ppt_table(slide, rows[row_index:row_index + 16], PInches(0.65), PInches(1.15), PInches(12.0), PInches(5.6), max_rows=None, font_size=9)
                    page += 1
        remaining = [c for c in charts if id(c) not in used_chart_ids]
        for chart_index in range(0, len(remaining), 2):
            chunk = remaining[chart_index:chart_index + 2]
            slide = prs.slides.add_slide(blank)
            chart_subtitle = f"分析周期：{observation.get('period_label') or '-'}；展示可视化报告计划中选择的趋势图"
            add_title(slide, f"{number} {title}图表" + ("（续）" if chart_index else ""), subtitle=chart_subtitle, page_num=page)
            if len(chunk) == 1:
                add_ppt_line_chart(slide, chunk[0]["chart"], PInches(0.75), PInches(1.25), PInches(11.8), PInches(5.55))
            else:
                add_ppt_line_chart(slide, chunk[0]["chart"], PInches(0.55), PInches(1.25), PInches(6.05), PInches(5.45))
                add_ppt_line_chart(slide, chunk[1]["chart"], PInches(6.72), PInches(1.25), PInches(6.05), PInches(5.45))
            page += 1
    return page

def export_pptx(df: pd.DataFrame, out_path: Path, context_items=None, meta=None, report_config=None, analysis_period=None, report_plan=None) -> Path:
    payload = build_report_payload(df, context_items=context_items, meta=meta, report_config=report_config, analysis_period=analysis_period, report_plan=report_plan)
    cfg = payload["report_config"]
    prs = Presentation()
    prs.slide_width = PInches(13.333)
    prs.slide_height = PInches(7.5)
    blank = prs.slide_layouts[6]
    page = 1

    # P1 封面
    s = prs.slides.add_slide(blank)
    title_box = s.shapes.add_textbox(PInches(1.55), PInches(2.0), PInches(10.2), PInches(0.9))
    p = title_box.text_frame.paragraphs[0]; p.text = payload["title"]; _ppt_font(p, 32, True, DARK, PP_ALIGN.CENTER)
    date_box = s.shapes.add_textbox(PInches(1.55), PInches(3.0), PInches(10.2), PInches(0.55))
    p = date_box.text_frame.paragraphs[0]; p.text = payload["report_date"]; _ppt_font(p, 20, True, DARK, PP_ALIGN.CENTER)
    period_box = s.shapes.add_textbox(PInches(1.55), PInches(3.62), PInches(10.2), PInches(0.55))
    p = period_box.text_frame.paragraphs[0]; p.text = f"分析周期：{payload.get('period_label') or '-'}｜同比基期：{payload.get('comparison_period_label') or '无可比同期'}"; _ppt_font(p, 14, False, MUTED, PP_ALIGN.CENTER)
    note = s.shapes.add_textbox(PInches(1.2), PInches(6.45), PInches(10.9), PInches(0.55))
    p = note.text_frame.paragraphs[0]; p.text = "AI Agent 自动生成初稿｜看板内容自动汇入报告｜可通过自然语言继续调整报告"; _ppt_font(p, 9, False, MUTED, PP_ALIGN.CENTER)
    page += 1

    # P2 目录 + 当前报告编排。
    s = prs.slides.add_slide(blank)
    add_title(s, "目录", subtitle="本次报告包含看板模块：" + "、".join(payload.get("included_dashboard_modules", [])), page_num=page)
    toc = [
        (str(section.get("number")) + ".", str(section.get("title")), list(section.get("subsections") or []))
        for section in payload.get("outline", [])
    ]
    y = 1.25
    for num, title, subs in toc:
        nb = s.shapes.add_textbox(PInches(0.65), PInches(y), PInches(0.6), PInches(0.45))
        p = nb.text_frame.paragraphs[0]; p.text = num; _ppt_font(p, 16, True, DARK)
        tb = s.shapes.add_textbox(PInches(1.25), PInches(y), PInches(5.2), PInches(0.45))
        p = tb.text_frame.paragraphs[0]; p.text = title; _ppt_font(p, 16, True, DARK)
        y += 0.52
        for sub in subs:
            sb = s.shapes.add_textbox(PInches(0.65), PInches(y), PInches(6.5), PInches(0.35))
            p = sb.text_frame.paragraphs[0]; p.text = "•    " + sub; _ppt_font(p, 13, True, DARK)
            y += 0.42
        y += 0.12
    page += 1

    add_section_divider(prs, blank, "1", "行业全景", page); page += 1

    if payload.get("dynamic_market_observation"):
        page = _add_dynamic_market_observation_slides(prs, blank, payload, page)

    # 默认模式保持v13.1看板编排；动态模式只输出用户选择的1.1组件。
    if not payload.get("dynamic_market_observation") and cfg.get("include_overview"):
        overview_rows = payload.get("overview_table", [])
        chunks = [overview_rows[i:i + 10] for i in range(0, len(overview_rows), 10)] or [[]]
        charts = _choose_report_charts(payload.get("line_charts", []))
        for idx, chunk in enumerate(chunks):
            s = prs.slides.add_slide(blank)
            heading = "1.1 " + payload["market_observation_title"] + ("（续）" if idx else "")
            add_title(s, heading, subtitle="数据源：上传Excel/CSV；市场观察完整保留源表车种/市场对象，不固定截断Top10", page_num=page)
            # 第一页保留参考周报的图表布局；续页优先展示完整明细。
            first_with_charts = idx == 0 and bool(charts)
            table_h = 4.0 if first_with_charts else 5.75
            add_ppt_table(s, chunk, PInches(0.52), PInches(1.02), PInches(12.45), PInches(table_h), max_rows=10, font_size=7.2)
            if first_with_charts:
                add_ppt_line_chart(s, charts[0], PInches(0.55), PInches(5.08), PInches(6.05), PInches(2.0))
                if len(charts) > 1:
                    add_ppt_line_chart(s, charts[1], PInches(6.72), PInches(5.08), PInches(6.05), PInches(2.0))
            page += 1

    # 看板“排名”：市场/OEM/品牌/车型全部按可用字段输出，表格+条形图。
    if not payload.get("dynamic_market_observation") and cfg.get("include_rankings"):
        ranking_sets = [
            ("市场排名", payload.get("top_market", [])), ("OEM排名", payload.get("top_oem", [])),
            ("品牌排名", payload.get("top_brand", [])), ("车型排名", payload.get("top_model", [])),
        ]
        any_ranking = False
        for heading, rows in ranking_sets:
            if not rows:
                continue
            any_ranking = True
            s = prs.slides.add_slide(blank)
            add_title(s, "1.1 " + heading, subtitle=f"{payload.get('period_label') or '当前分析周期'} {payload.get('market_fact_pack',{}).get('primary_sales_label') or '销量'}排名；占比使用可确认总体口径", page_num=page)
            add_ppt_table(s, rows, PInches(0.48), PInches(1.1), PInches(5.35), PInches(5.6), max_rows=10, font_size=8.2)
            add_ppt_bar_chart(s, heading, rows, PInches(6.05), PInches(1.2), PInches(6.7), PInches(5.4))
            page += 1
        if any_ranking and payload.get("competition_insights"):
            s = prs.slides.add_slide(blank)
            add_title(s, "1.1 竞争格局分析", page_num=page)
            add_bullets(s, payload["competition_insights"][:8], PInches(0.8), PInches(1.25), PInches(11.8), PInches(5.5), 14)
            page += 1

    # 看板“动力结构/趋势”：饼图+结构表+月度趋势。
    if not payload.get("dynamic_market_observation") and (cfg.get("include_power_structure") or cfg.get("include_monthly_trend")):
        s = prs.slides.add_slide(blank)
        trend_scope = (payload.get("analysis_period") or {}).get("trend_scope_label") or "所选分析区间逐月"
        add_title(s, "1.1 动力结构与月度趋势", subtitle=f"结构使用{payload.get('period_label') or '当前分析周期'}；趋势展示{trend_scope}", page_num=page)
        if cfg.get("include_power_structure") and payload.get("power_share"):
            add_ppt_pie_chart(s, "动力类型结构占比", payload["power_share"], PInches(0.45), PInches(1.1), PInches(5.7), PInches(3.3))
            add_ppt_table(s, payload["power_share"], PInches(0.55), PInches(4.35), PInches(5.5), PInches(2.4), max_rows=12, font_size=7.2)
        if cfg.get("include_monthly_trend") and payload.get("trend"):
            monthly_chart = {"title": "月度趋势", "series": [{"name": "销量/数值", "points": [{"时间": x.get("时间"), "数值": x.get("数值")} for x in payload["trend"]]}], "y_format": "number", "source": "time_period + primary_sales_metric"}
            add_ppt_line_chart(s, monthly_chart, PInches(6.35), PInches(1.15), PInches(6.45), PInches(3.4))
            add_ppt_table(s, payload["trend"], PInches(6.45), PInches(4.6), PInches(6.25), PInches(1.95), max_rows=12, font_size=7.5)
        page += 1

    # 看板“折线图”：所有选中的折线图都进入报告，而不仅是市场观察页的前两张。
    if not payload.get("dynamic_market_observation") and cfg.get("include_line_charts"):
        charts = _choose_report_charts(payload.get("line_charts", []))
        if charts:
            chunks = [charts[i:i+2] for i in range(0, len(charts), 2)]
            for idx, chunk in enumerate(chunks):
                s = prs.slides.add_slide(blank)
                add_title(s, "1.1 折线图" + ("（续）" if idx else ""), subtitle="根据当前数据字段自动生成；可在聊天中指定保留/移除某张图", page_num=page)
                if len(chunk) == 1:
                    add_ppt_line_chart(s, chunk[0], PInches(0.75), PInches(1.25), PInches(11.8), PInches(5.55))
                else:
                    add_ppt_line_chart(s, chunk[0], PInches(0.55), PInches(1.25), PInches(6.05), PInches(5.45))
                    add_ppt_line_chart(s, chunk[1], PInches(6.72), PInches(1.25), PInches(6.05), PInches(5.45))
                page += 1

    # 看板“周报内容预览”：这些章节就是预览内容的正式报告版。
    if cfg.get("include_weekly_content"):
        macro_number = (payload.get("section_numbers") or {}).get("macro_policy", "1.2")
        page = _add_event_cards(prs, blank, macro_number + " 宏观政策动态", payload["macro_policy"], page)
        add_section_divider(prs, blank, "2", "企业行动", page); page += 1
        page = _add_event_cards(prs, blank, "2.1 人事调整", payload["personnel"], page)
        page = _add_event_cards(prs, blank, "2.2 战略调整与布局", payload["strategy"], page)
        add_section_divider(prs, blank, "3", "产业链观察", page); page += 1
        page = _add_event_cards(prs, blank, "3.1 产业链观察", payload["industry_chain"], page)
        add_section_divider(prs, blank, "4", "竞争追踪", page); page += 1
        page = _add_event_cards(prs, blank, "4.1 竞争追踪", payload["competition"], page)

    # Formal output contains actual anomaly rows only. Missing-field warnings
    # and report-plan diagnostics remain available through the analysis API.
    if cfg.get("include_anomalies") and payload.get("anomalies"):
        s = prs.slides.add_slide(blank)
        add_title(s, "异常指标", page_num=page)
        add_ppt_table(s, payload["anomalies"], PInches(0.55), PInches(1.15), PInches(12.2), PInches(5.6), max_rows=14, font_size=8)
        page += 1

    prs.save(out_path)
    _verify_exported_context(out_path, "pptx", payload)
    return out_path


def _normalized_artifact_text(value: Any) -> str:
    return re.sub(r"\s+", "", str(value or ""))


def _artifact_text(path: Path, fmt: str) -> str:
    parts: list[str] = []
    if fmt == "docx":
        document = Document(path)
        parts.extend(paragraph.text for paragraph in document.paragraphs)
        for table in document.tables:
            parts.extend(cell.text for row in table.rows for cell in row.cells)
    elif fmt == "xlsx":
        workbook = load_workbook(path, read_only=True, data_only=True)
        try:
            for sheet in workbook.worksheets:
                parts.extend(str(value) for row in sheet.iter_rows(values_only=True) for value in row if value is not None)
        finally:
            workbook.close()
    elif fmt == "pptx":
        presentation = Presentation(path)
        for slide in presentation.slides:
            for shape in slide.shapes:
                if getattr(shape, "has_text_frame", False):
                    parts.append(shape.text)
                if getattr(shape, "has_table", False):
                    parts.extend(cell.text for row in shape.table.rows for cell in row.cells)
    return _normalized_artifact_text("\n".join(parts))


def _verify_exported_context(path: Path, fmt: str, payload: dict[str, Any]) -> dict[str, Any]:
    """Fail the export if any user-supplied source content disappears.

    This checks the finished file, not only the intermediate payload, so later
    layout/pagination changes cannot silently truncate evidence.
    """
    events = [item for category in SECTION_LABELS for item in payload.get(category, [])]
    if not events:
        return {"status": "complete", "expected_items": 0, "verified_items": 0}
    artifact_text = _artifact_text(path, fmt)
    missing: list[str] = []
    for event in events:
        raw_content = str(event.get("content") or "")
        pieces = _split_text_for_slides(raw_content) if fmt == "pptx" else [raw_content]
        if any(_normalized_artifact_text(piece) and _normalized_artifact_text(piece) not in artifact_text for piece in pieces):
            missing.append(event.get("number") or event.get("source") or event.get("export_content_sha256") or "unknown")
    if missing:
        path.unlink(missing_ok=True)
        raise RuntimeError(f"{fmt.upper()}行业资料成品完整性校验失败，缺少：{','.join(missing[:20])}")
    return {"status": "complete", "expected_items": len(events), "verified_items": len(events)}


def export_report(df: pd.DataFrame, fmt: str, out_dir: Path, dataset_id: str, context_items=None, meta=None, report_config=None, analysis_period=None, report_plan=None) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"market_insight_report_{dataset_id}.{fmt}"
    if fmt == "xlsx":
        return export_excel(df, path, context_items=context_items, meta=meta, report_config=report_config, analysis_period=analysis_period, report_plan=report_plan)
    if fmt == "docx":
        return export_docx(df, path, context_items=context_items, meta=meta, report_config=report_config, analysis_period=analysis_period, report_plan=report_plan)
    if fmt == "pptx":
        return export_pptx(df, path, context_items=context_items, meta=meta, report_config=report_config, analysis_period=analysis_period, report_plan=report_plan)
    raise ValueError("fmt must be xlsx/docx/pptx")
