"""Generate the first competitive-insight report defined by the business template."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import json
import os
import re
from typing import Any

from app.llm.client import ArkChatClient, LlmError
from app.report.metrics import calculate_competitive_metrics


REPORT_TYPE = "competitive_insight_v1"
MAKERS = ("Tianma", "AUO", "CSOT", "BOE")
TARGET_SHEETS = ("shipment", "shipment share", "display area", "display area share")

SYSTEM_PROMPT = """你是车载显示行业报告撰写员。当前生成Y25前三季度Summary，以及Tianma、AUO、CSOT、BOE四家公司的前装历史、产品线、客户/区域和应用增长分析。computed_metrics中的筛选、汇总、同比和占比均已由Python计算完成。
你只负责解释和组织已有指标，严禁重新计算、修改数值、从原始记录推导新指标或补写常识数据。
每条包含数字的文字必须引用已有metric_id。数据缺口由Python确定，不得新增客户、区域、应用或其他未要求部分。只输出JSON对象，不要Markdown。"""

METRIC_REF_PATTERN = re.compile(r"\s*\[(.+)\]\s*$")


def generate_competitive_insight_report(
    parsed: dict[str, Any],
    use_llm: bool = True,
    llm_client: ArkChatClient | None = None,
) -> dict[str, Any]:
    """Build a traceable first report, with deterministic narratives as fallback."""
    metrics = parsed.get("computed_metrics") or calculate_competitive_metrics(_metric_tables(parsed))
    report = _empty_report(parsed, metrics)
    client = llm_client or (
        ArkChatClient(timeout_seconds=float(os.getenv("ARK_REPORT_TIMEOUT_SECONDS", "180")))
        if use_llm else None
    )
    if not use_llm or client is None or not client.available:
        report["quality"]["data_gaps"].append("LLM未启用或ARK_API_KEY未配置，已使用确定性指标生成分析文案")
        return _populate_rule_narratives(report)

    request = {
        "task": "仅依据Python已计算的指标，按终稿顺序撰写Y25前三季度Summary，并依次撰写Tianma、AUO、CSOT、BOE的主要驱动力、前装历史、产品线、客户/区域和应用分析；不执行任何计算；所有YoY采用标准同比current/previous-1",
        "required_schema": _llm_schema(),
        "methodology": report["methodology"],
        "computed_metrics": _compact_metrics_for_llm(metrics),
    }
    try:
        candidate = client.complete_json(SYSTEM_PROMPT, json.dumps(request, ensure_ascii=False), max_tokens=3500)
    except LlmError as exc:
        report["quality"]["data_gaps"].append(f"LLM报告生成失败，已使用确定性指标生成分析文案: {str(exc)[:300]}")
        report["quality"]["llm_model"] = client.model
        return _populate_rule_narratives(report)
    normalized = _normalize_candidate(candidate, report)
    normalized["quality"]["generation_mode"] = "python_metrics_llm_narrative"
    normalized["quality"]["llm_model"] = client.model
    return normalized


def _metric_tables(parsed: dict[str, Any]) -> list[dict[str, Any]]:
    return [{
        "sheet": sheet.get("sheet_name"),
        "table": table.get("table_name"),
        "range": (table.get("source") or {}).get("range"),
        "records": table.get("records") or [],
    } for sheet in (parsed.get("sheets") or []) for table in (sheet.get("tables") or [])]


def _compact_metrics_for_llm(metrics: dict[str, Any]) -> dict[str, Any]:
    """Keep narrative inputs small; complete evidence remains in the final report."""
    if metrics.get("summary_matrix"):
        compact_source = {
            "engine": metrics.get("engine"),
            "scope": metrics.get("scope"),
            "formulas": metrics.get("formulas"),
            "market": metrics.get("market"),
            "summary_matrix": metrics.get("summary_matrix"),
            "maker_details": metrics.get("maker_details"),
            "data_gaps": metrics.get("data_gaps"),
        }
        if not metrics.get("maker_details"):
            compact_source.update({
                "tianma_history": metrics.get("tianma_history"),
                "tianma_product": metrics.get("tianma_product"),
                "tianma_customer": metrics.get("tianma_customer"),
                "tianma_application": metrics.get("tianma_application"),
            })
        compact = deepcopy(compact_source)
    else:
        compact = deepcopy(metrics)
    scope = compact.get("scope")
    if isinstance(scope, dict):
        scope.pop("source_tables", None)

    def strip(value):
        if isinstance(value, dict):
            value.pop("evidence", None)
            for child in value.values():
                strip(child)
        elif isinstance(value, list):
            for child in value:
                strip(child)

    strip(compact)
    for detail in (compact.get("maker_details") or {}).values():
        product = detail.get("product") or {}
        distribution = product.get("y25q1_q3_size_distribution") or {}
        points = distribution.get("points") or []
        if len(points) > 18:
            distribution["points"] = sorted(
                points, key=lambda item: float(item.get("shipment") or 0), reverse=True
            )[:18]
    for maker in (compact.get("makers") or {}).values():
        if isinstance(maker, dict):
            maker["clients"] = (maker.get("clients") or [])[:5]
            maker["applications"] = (maker.get("applications") or [])[:6]
    return compact


def _empty_report(parsed: dict[str, Any], metrics: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "1.0",
        "report_type": REPORT_TYPE,
        "report_order": 1,
        "title": "竞争社对标分析 - Tianma、AUO、CSOT、BOE",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": {
            "workbook_id": parsed.get("workbook_id"),
            "file_name": parsed.get("file_name"),
        },
        "methodology": {
            "scope": {
                "original_specification": "Automobile monitor",
                "excluded_application": "Automobile monitor (Others)",
                "summary_years": [2024, 2025],
                "summary_quarters": ["Q1", "Q2", "Q3"],
                "makers": list(MAKERS),
                "maker_aliases": {"China Star": "CSOT"},
                "technologies": ["LTPS", "a-Si"],
            },
            "size_buckets": ["<8", "[8,12)", "[12,15)", ">=15"],
            "formulas": {
                "yoy": "current_year / previous_year - 1",
                "segment_share": "market segment shipment / total market shipment",
                "maker_internal_share": "maker segment shipment / maker total shipment",
                "segment_market_share": "maker segment shipment / market same-technology same-size segment shipment",
                "technology_market_share": "maker segment shipment / market technology total shipment",
                "same_size_market_share": "maker segment shipment / market same-size segment shipment",
            },
            "report_flow": [
                "口径说明", "Y25前三季度Summary",
                "Tianma洞察", "Tianma前装出货与市占率", "Tianma产品线", "Tianma客户/区域", "Tianma应用",
                "AUO洞察", "AUO前装出货与市占率", "AUO产品线", "AUO客户/区域", "AUO应用",
                "CSOT洞察", "CSOT前装出货与市占率", "CSOT产品线", "CSOT客户/区域", "CSOT应用",
                "BOE洞察", "BOE前装出货与市占率", "BOE产品线", "BOE客户/区域", "BOE应用",
            ],
        },
        "data_scope": {
            **(metrics.get("scope") or {}),
        },
        "executive_summary": [],
        "market_summary": {
            "rows": [metrics.get("market")] if metrics.get("market") else [],
            "summary_matrix": metrics.get("summary_matrix") or {"rows": []},
            "insights": [],
        },
        "tianma_history": {
            **(metrics.get("tianma_history") or {}),
            "insights": {
                "shipment": [],
                "display_area": [],
                "shipment_share": [],
                "display_area_share": [],
            },
        },
        "tianma_product": {
            **(metrics.get("tianma_product") or {}),
            "insights": {
                "technology_history": [],
                "size_distribution": [],
                "technology_size_growth": [],
            },
        },
        "tianma_customer": {
            **(metrics.get("tianma_customer") or {}),
            "insights": {"top_clients": [], "regions": []},
        },
        "tianma_application": {
            **(metrics.get("tianma_application") or {}),
            "insights": {"application_history": [], "key_sizes": []},
        },
        "maker_sections": _maker_sections(metrics),
        "makers": [
            _maker_with_metrics(
                maker,
                (metrics.get("makers") or {}).get(maker) or {},
                (metrics.get("maker_details") or {}).get(maker) or {},
            ) for maker in MAKERS
        ],
        "evidence": _metric_evidence(metrics),
        "computed_metrics": metrics,
        "quality": {
            "generation_mode": "python_metrics_template",
            "llm_model": None,
            "metrics_engine": metrics.get("engine"),
            "data_gaps": list(metrics.get("data_gaps") or []),
            "source_grounded": True,
            "llm_performed_arithmetic": False,
            "narrative_metric_refs": [],
        },
    }


def _empty_maker(maker: str) -> dict[str, Any]:
    return {
        "maker": maker,
        "overview": [],
        "global_trend": {"shipment": [], "display_area": [], "market_share": [], "insights": []},
        "product_line": {"technology": [], "size_distribution": [], "size_growth": [], "insights": []},
        "customer_region": {"top_customers": [], "regions": [], "insights": []},
        "application": {"applications": [], "key_sizes": [], "insights": []},
        "drivers": {"product": [], "customer": [], "application": []},
    }


def _maker_sections(metrics: dict[str, Any]) -> dict[str, Any]:
    sections = deepcopy(metrics.get("maker_details") or {})
    for detail in sections.values():
        history = detail.get("history") or {}
        history["insights"] = {
            "shipment": [], "display_area": [], "shipment_share": [], "display_area_share": []
        }
        product = detail.get("product") or {}
        product["insights"] = {
            "technology_history": [], "size_distribution": [], "technology_size_growth": []
        }
        customer = detail.get("customer") or {}
        customer["insights"] = {"top_clients": [], "regions": []}
        application = detail.get("application") or {}
        application["insights"] = {"application_history": [], "key_sizes": []}
    return sections


def _maker_with_metrics(
    maker: str, metrics: dict[str, Any], details: dict[str, Any] | None = None
) -> dict[str, Any]:
    result = _empty_maker(maker)
    details = details or {}
    history = details.get("history") or {}
    product = details.get("product") or {}
    customer = details.get("customer") or {}
    application = details.get("application") or {}
    result["global_trend"]["shipment"] = [history.get("shipment") or metrics.get("shipment")]
    result["global_trend"]["shipment"] = [item for item in result["global_trend"]["shipment"] if item]
    result["global_trend"]["display_area"] = history.get("display_area")
    result["global_trend"]["shipment_share"] = history.get("shipment_share")
    result["global_trend"]["display_area_share"] = history.get("display_area_share")
    shipment = metrics.get("shipment") or {}
    result["global_trend"]["market_share"] = [history.get("shipment_share") or shipment.get("market_share")] if (history or shipment) else []
    result["product_line"]["technology"] = product.get("technology_history") or metrics.get("technology") or []
    result["product_line"]["size_distribution"] = product.get("y25q1_q3_size_distribution") or metrics.get("sizes") or []
    result["product_line"]["size_growth"] = product.get("technology_size_growth") or metrics.get("technology_sizes") or []
    result["customer_region"]["top_customers"] = customer.get("top_clients") or metrics.get("clients") or []
    result["customer_region"]["regions"] = customer.get("regions") or []
    result["application"]["applications"] = application.get("application_history") or metrics.get("applications") or []
    result["application"]["key_sizes"] = application.get("key_sizes") or []
    return result


def _llm_schema() -> dict[str, Any]:
    return {
        "constraint": "每个数组最多2条，每条不超过60个汉字；只返回列出的字段，不得增加字段",
        "executive_summary": ["string，数字结论后附[metric_id]"],
        "market_insights": ["string，数字结论后附[metric_id]"],
        "maker_narratives": [{
            "maker": "Tianma、AUO、CSOT、BOE各返回一个对象",
            "overview": ["string"],
            "global_insights": ["string"],
            "product_line_insights": ["string"],
            "customer_region_insights": ["string"],
            "application_insights": ["string"],
            "drivers": {
                "product": ["string，数字结论后附[metric_id]"],
                "customer": ["string，数字结论后附[metric_id]"],
                "application": ["string，数字结论后附[metric_id]"],
            },
        }],
    }


def _normalize_candidate(candidate: dict[str, Any], base: dict[str, Any]) -> dict[str, Any]:
    result = deepcopy(base)
    result["quality"]["narrative_metric_refs"] = _metric_refs(candidate)
    if isinstance(candidate.get("executive_summary"), list):
        result["executive_summary"] = _clean_narrative_list(candidate["executive_summary"])
    if isinstance(candidate.get("market_insights"), list):
        result["market_summary"]["insights"] = _clean_narrative_list(candidate["market_insights"])
    history_insights = candidate.get("tianma_history_insights")
    if isinstance(history_insights, dict):
        for key in ("shipment", "display_area", "shipment_share", "display_area_share"):
            if isinstance(history_insights.get(key), list):
                result["tianma_history"]["insights"][key] = _clean_narrative_list(history_insights[key])
    product_insights = candidate.get("tianma_product_insights")
    if isinstance(product_insights, dict):
        for key in ("technology_history", "size_distribution", "technology_size_growth"):
            if isinstance(product_insights.get(key), list):
                result["tianma_product"]["insights"][key] = _clean_narrative_list(product_insights[key])
    customer_insights = candidate.get("tianma_customer_insights")
    if isinstance(customer_insights, dict):
        for key in ("top_clients", "regions"):
            if isinstance(customer_insights.get(key), list):
                result["tianma_customer"]["insights"][key] = _clean_narrative_list(customer_insights[key])
    application_insights = candidate.get("tianma_application_insights")
    if isinstance(application_insights, dict):
        for key in ("application_history", "key_sizes"):
            if isinstance(application_insights.get(key), list):
                result["tianma_application"]["insights"][key] = _clean_narrative_list(application_insights[key])
    by_maker = {item.get("maker"): item for item in candidate.get("maker_narratives", []) if isinstance(item, dict)}
    for maker_result in result["makers"]:
        source = by_maker.get(maker_result["maker"])
        if not source:
            continue
        maker_result["overview"] = _clean_narrative_list(source.get("overview") or [])
        maker_result["global_trend"]["insights"] = _clean_narrative_list(source.get("global_insights") or [])
        maker_result["product_line"]["insights"] = _clean_narrative_list(source.get("product_line_insights") or [])
        maker_result["customer_region"]["insights"] = _clean_narrative_list(source.get("customer_region_insights") or [])
        maker_result["application"]["insights"] = _clean_narrative_list(source.get("application_insights") or [])
        if isinstance(source.get("drivers"), dict):
            maker_result["drivers"] = {
                key: _clean_narrative_list(source["drivers"].get(key) or [])
                for key in ("product", "customer", "application")
            }
        sections = (result.get("maker_sections") or {}).get(maker_result["maker"]) or {}
        _copy_section_insights(sections.get("history"), source.get("history_insights"),
                               ("shipment", "display_area", "shipment_share", "display_area_share"))
        _copy_section_insights(sections.get("product"), source.get("product_insights"),
                               ("technology_history", "size_distribution", "technology_size_growth"))
        _copy_section_insights(sections.get("customer"), source.get("customer_insights"),
                               ("top_clients", "regions"))
        _copy_section_insights(sections.get("application"), source.get("application_detail_insights"),
                               ("application_history", "key_sizes"))
        _copy_flat_insights(sections.get("history"), "shipment", source.get("global_insights"))
        _copy_flat_insights(sections.get("product"), "technology_history", source.get("product_line_insights"))
        _copy_flat_insights(sections.get("customer"), "top_clients", source.get("customer_region_insights"))
        _copy_flat_insights(sections.get("application"), "application_history", source.get("application_insights"))
    tianma_sections = (result.get("maker_sections") or {}).get("Tianma") or {}
    for section_key, legacy_key in (
        ("history", "tianma_history"), ("product", "tianma_product"),
        ("customer", "tianma_customer"), ("application", "tianma_application"),
    ):
        section = tianma_sections.get(section_key) or {}
        legacy = result.get(legacy_key) or {}
        for key, values in (legacy.get("insights") or {}).items():
            if not (section.get("insights") or {}).get(key):
                section.setdefault("insights", {})[key] = list(values or [])
    return result


def _copy_section_insights(section, candidate, keys):
    if not isinstance(section, dict) or not isinstance(candidate, dict):
        return
    target = section.setdefault("insights", {})
    for key in keys:
        if isinstance(candidate.get(key), list):
            target[key] = _clean_narrative_list(candidate[key])


def _copy_flat_insights(section, key, candidate):
    if not isinstance(section, dict) or not isinstance(candidate, list):
        return
    target = section.setdefault("insights", {})
    if not target.get(key):
        target[key] = _clean_narrative_list(candidate)


def _metric_refs(value: Any) -> list[str]:
    refs: set[str] = set()

    def visit(item):
        if isinstance(item, str):
            refs.update(METRIC_REF_PATTERN.findall(item))
        elif isinstance(item, dict):
            for child in item.values():
                visit(child)
        elif isinstance(item, list):
            for child in item:
                visit(child)

    visit(value)
    return sorted(refs)


def _clean_narrative_list(values: list[Any]) -> list[str]:
    return [METRIC_REF_PATTERN.sub("", value).strip() for value in values if isinstance(value, str) and value.strip()]


def _populate_rule_narratives(report: dict[str, Any]) -> dict[str, Any]:
    """Produce useful source-grounded prose when the narrative model is unavailable."""
    result = deepcopy(report)
    metrics = result.get("computed_metrics") or {}
    market = metrics.get("market") or {}
    market_values = market.get("values") or {}
    market_y25 = market_values.get("2025")
    market_yoy = market.get("yoy_2025_vs_2024")
    if market_y25 is not None:
        result["executive_summary"].append(
            f"Y25前三季度市场总出货{_format_number(market_y25)}K，同比{_format_percent(market_yoy)}"
        )

    matrix_rows = ((metrics.get("summary_matrix") or {}).get("rows") or [])
    ltps_total = next((row for row in matrix_rows if row.get("row_key") == "ltps.total"), None)
    if ltps_total:
        ltps_market = ltps_total.get("market") or {}
        share = (ltps_market.get("segment_share") or {}).get("2025")
        result["executive_summary"].append(
            f"LTPS市场占比{_format_percent(share)}，同比{_format_percent(ltps_market.get('yoy_2025_vs_2024'))}"
        )
    size_rows = [row for row in matrix_rows if not row.get("is_total")]
    growing = [row for row in size_rows if (row.get("market") or {}).get("yoy_2025_vs_2024") is not None]
    if growing:
        fastest = max(growing, key=lambda row: (row.get("market") or {}).get("yoy_2025_vs_2024"))
        result["market_summary"]["insights"].append(
            f"{fastest.get('technology')} {fastest.get('label')}同比{_format_percent((fastest.get('market') or {}).get('yoy_2025_vs_2024'))}，为增长最快尺寸段"
        )
    asi_total = next((row for row in matrix_rows if row.get("row_key") == "a_si.total"), None)
    if asi_total:
        asi_market = asi_total.get("market") or {}
        asi_share = (asi_market.get("segment_share") or {}).get("2025")
        result["market_summary"]["insights"].append(
            f"a-Si市场同比{_format_percent(asi_market.get('yoy_2025_vs_2024'))}，占比{_format_percent(asi_share)}"
        )

    maker_lookup = {item.get("maker"): item for item in result.get("makers") or []}
    for maker in MAKERS:
        sections = (result.get("maker_sections") or {}).get(maker) or {}
        maker_result = maker_lookup.get(maker)
        if not maker_result:
            continue
        history = sections.get("history") or {}
        product = sections.get("product") or {}
        customer = sections.get("customer") or {}
        application = sections.get("application") or {}

        shipment = history.get("shipment") or {}
        shipment_periods = shipment.get("periods") or {}
        shipment_yoy = shipment.get("yoy_periods") or {}
        y25_shipment = shipment_periods.get("Y25Q1-Q3")
        if y25_shipment is not None:
            shipment_text = (
                f"Y25前三季度出货{_format_number(y25_shipment)}K，同比"
                f"{_format_percent(shipment_yoy.get('Y25Q1-Q3'))}"
            )
            maker_result["overview"].append(shipment_text)
            maker_result["global_trend"]["insights"].append(shipment_text)
            history.setdefault("insights", {}).setdefault("shipment", []).append(shipment_text)

        share_periods = (history.get("shipment_share") or {}).get("periods") or {}
        y25_share = share_periods.get("Y25Q1-Q3")
        y24_share = share_periods.get("Y24")
        if y25_share is not None:
            share_text = f"Y25前三季度出货市占率{_format_percent(y25_share)}"
            if y24_share is not None:
                share_text += f"，较Y24变化{_format_points(y25_share - y24_share)}个百分点"
            maker_result["overview"].append(share_text)
            maker_result["global_trend"]["insights"].append(share_text)
            history.setdefault("insights", {}).setdefault("shipment_share", []).append(share_text)

        area = history.get("display_area") or {}
        area_yoy = (area.get("yoy_periods") or {}).get("Y25Q1-Q3")
        area_y25 = (area.get("periods") or {}).get("Y25Q1-Q3")
        if area_y25 is not None:
            history.setdefault("insights", {}).setdefault("display_area", []).append(
                f"Y25前三季度出货面积{_format_number(area_y25)}㎡，同比{_format_percent(area_yoy)}"
            )

        technologies = product.get("technology_history") or {}
        technology_items = []
        for technology, value in technologies.items():
            periods = (value or {}).get("periods") or {}
            current = periods.get("Y25Q1-Q3")
            if current is not None:
                technology_items.append((technology, current, ((value or {}).get("yoy_periods") or {}).get("Y25Q1-Q3")))
        technology_items.sort(
            key=lambda item: (float("-inf") if item[2] is None else item[2], item[1]),
            reverse=True,
        )
        for technology, value, yoy in technology_items[:2]:
            text = f"{technology}出货{_format_number(value)}K，同比{_format_percent(yoy)}"
            maker_result["product_line"]["insights"].append(text)
            maker_result["drivers"]["product"].append(text)
            product.setdefault("insights", {}).setdefault("technology_history", []).append(text)

        clients = ((customer.get("top_clients") or {}).get("clients") or [])
        clients = [item for item in clients if ((item.get("periods") or {}).get("Y25Q1-Q3")) is not None]
        if clients:
            top_client = max(clients, key=lambda item: (item.get("periods") or {}).get("Y25Q1-Q3"))
            text = f"第一大客户{top_client.get('client')}出货{_format_number((top_client.get('periods') or {}).get('Y25Q1-Q3'))}K"
            maker_result["customer_region"]["insights"].append(text)
            maker_result["drivers"]["customer"].append(text)
            customer.setdefault("insights", {}).setdefault("top_clients", []).append(text)
        regions = ((customer.get("regions") or {}).get("rows") or [])
        regions = [
            item for item in regions
            if item.get("region") != "其他" and ((item.get("q1_q3") or {}).get("Y25Q1-Q3")) is not None
        ]
        if regions:
            top_region = max(regions, key=lambda item: (item.get("q1_q3") or {}).get("Y25Q1-Q3"))
            text = (
                f"{top_region.get('region')}区域出货{_format_number((top_region.get('q1_q3') or {}).get('Y25Q1-Q3'))}K，"
                f"同比{_format_percent((top_region.get('q1_q3') or {}).get('yoy_2025_vs_2024'))}"
            )
            maker_result["customer_region"]["insights"].append(text)
            maker_result["drivers"]["customer"].append(text)
            customer.setdefault("insights", {}).setdefault("regions", []).append(text)

        applications = ((application.get("application_history") or {}).get("series") or [])
        applications = [item for item in applications if ((item.get("periods") or {}).get("Y25Q1-Q3")) is not None]
        if applications:
            top_application = max(applications, key=lambda item: (item.get("periods") or {}).get("Y25Q1-Q3"))
            text = (
                f"{top_application.get('application')}出货{_format_number((top_application.get('periods') or {}).get('Y25Q1-Q3'))}K，"
                f"同比{_format_percent((top_application.get('yoy_periods') or {}).get('Y25Q1-Q3'))}"
            )
            maker_result["application"]["insights"].append(text)
            maker_result["drivers"]["application"].append(text)
            application.setdefault("insights", {}).setdefault("application_history", []).append(text)
        key_sizes = ((application.get("key_sizes") or {}).get("rows") or [])
        if key_sizes:
            top_size = max(key_sizes, key=lambda item: item.get("shipment") or 0)
            text = (
                f"重点尺寸{_format_number(top_size.get('size'))}寸（{top_size.get('technology')}）"
                f"出货{_format_number(top_size.get('shipment'))}K"
            )
            maker_result["application"]["insights"].append(text)
            maker_result["drivers"]["application"].append(text)
            application.setdefault("insights", {}).setdefault("key_sizes", []).append(text)

    tianma = (result.get("maker_sections") or {}).get("Tianma") or {}
    for section_key, legacy_key in (
        ("history", "tianma_history"), ("product", "tianma_product"),
        ("customer", "tianma_customer"), ("application", "tianma_application"),
    ):
        if section_key in tianma:
            result[legacy_key] = deepcopy(tianma[section_key])
    result["quality"]["generation_mode"] = "python_metrics_rule_narrative"
    return result


def _format_number(value: Any) -> str:
    if value is None:
        return "-"
    number = float(value)
    if number.is_integer():
        return f"{int(number):,}"
    return f"{number:,.3f}".rstrip("0").rstrip(".")


def _format_percent(value: Any) -> str:
    if value is None:
        return "-"
    return f"{float(value) * 100:.1f}%"


def _format_points(value: Any) -> str:
    if value is None:
        return "-"
    return f"{float(value) * 100:+.1f}"


def _metric_evidence(metrics: dict[str, Any]) -> list[dict[str, Any]]:
    evidence = []

    def visit(value):
        if isinstance(value, dict):
            metric_id = value.get("metric_id")
            if metric_id and value.get("evidence"):
                evidence.append({"metric_id": metric_id, "evidence": value["evidence"]})
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)

    visit(metrics)
    return evidence
