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
METRIC_REF_TOKEN_PATTERN = re.compile(r"\[([A-Za-z0-9_.-]+)\]")


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
    if not use_llm:
        report["quality"]["generation_mode"] = "python_metrics_rule_narrative"
        return _finalize_narrative_traceability(_populate_rule_narratives(report))
    if client is None or not client.available:
        report["quality"].setdefault("warnings", []).append(
            "ARK_API_KEY未配置，已使用确定性指标生成分析文案"
        )
        return _finalize_narrative_traceability(_populate_rule_narratives(report))

    request = {
        "task": "仅依据Python已计算的指标，按终稿顺序撰写Y25前三季度Summary，并依次撰写Tianma、AUO、CSOT、BOE的主要驱动力、前装历史、产品线、客户/区域和应用分析；不执行任何计算；所有YoY采用标准同比current/previous-1",
        "required_schema": _llm_schema(),
        "methodology": report["methodology"],
        "computed_metrics": _compact_metrics_for_llm(metrics),
    }
    try:
        candidate = client.complete_json(SYSTEM_PROMPT, json.dumps(request, ensure_ascii=False), max_tokens=7000)
    except LlmError as exc:
        message = str(exc)
        if "SetLimitExceeded" in message or "HTTP 429" in message:
            warning = "LLM推理额度已达到平台限制，本次已使用确定性指标生成分析文案；指标计算不受影响"
        else:
            warning = f"LLM报告生成失败，已使用确定性指标生成分析文案: {message[:180]}"
        report["quality"].setdefault("warnings", []).append(warning)
        report["quality"]["llm_model"] = client.model
        return _finalize_narrative_traceability(_populate_rule_narratives(report))
    normalized = _normalize_candidate(candidate, report)
    normalized["quality"]["generation_mode"] = "python_metrics_llm_narrative"
    normalized["quality"]["llm_model"] = client.model
    return _finalize_narrative_traceability(normalized)


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
    maker_names = _template_makers(metrics)
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
                "makers": list(maker_names),
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
                "forecast_completion": "Y25 Q1-Q3 actual shipment / Y25F shipment",
                "growth_contribution": "segment shipment change / maker total shipment change",
            },
            "notes": [
                "Y25F为数据源中的2025全年预测值，Y25 Q1-Q3为前三季度实际值。",
                "LTPS口径同时包含LTPS LCD与OLED；厂商出货和面积不计Oxide，市场份额分母保留全技术市场。",
                "前装口径为Original specification=Automobile monitor，并排除Application=Automobile monitor (Others)。",
                "市场汇总覆盖全部Maker；China Star统一展示为CSOT。",
                "尺寸段依次为<8、[8,12)、[12,15)、>=15，边界值归入右侧区间。",
                "Y22来自1Q25 with 4Q24 Results基准文件，Y23-Y25来自4Q25 with 3Q25 Results当前文件。",
                "客户区域按客户决策地映射；未命中的客户归为其他并保留清单。",
                "应用重点尺寸先跨Technology合并，同一应用×尺寸超过1,000K或为该应用第一大尺寸时入选。",
                "所有数值、同比、份额、完成率及增长贡献均由Python计算；LLM仅组织文字，不执行算术。",
            ],
            "limitations": [
                "当前基准文件不含2021年数据，因此Y22同比不计算。",
                "参考PDF个别图表与源数据存在内部不一致时，以Excel源表和统一公式的可复算结果为准。",
            ],
                "report_flow": [
                "口径说明", "Y25前三季度Summary",
                *[item for maker in maker_names for item in (
                    f"{maker}洞察", f"{maker}前装出货与市占率", f"{maker}产品线",
                    f"{maker}客户/区域", f"{maker}应用")],
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
        "maker_sections": _maker_sections(metrics, maker_names),
        "makers": [
            _maker_with_metrics(
                maker,
                (metrics.get("makers") or {}).get(maker) or {},
                (metrics.get("maker_details") or {}).get(maker) or {},
            ) for maker in maker_names
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


def _template_makers(metrics: dict[str, Any]) -> tuple[str, ...]:
    """Return stable default maker slots plus makers discovered in the current input."""
    configured = [item.strip() for item in os.getenv("REPORT_TEMPLATE_MAKERS", "").split(",") if item.strip()]
    discovered = list((metrics.get("maker_details") or {}).keys()) + list((metrics.get("makers") or {}).keys())
    names = configured or list(MAKERS)
    for name in discovered:
        if name and name not in names:
            names.append(name)
    return tuple(names)


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


def _maker_sections(metrics: dict[str, Any], maker_names: tuple[str, ...] | None = None) -> dict[str, Any]:
    sections = deepcopy(metrics.get("maker_details") or {})
    for maker in maker_names or _template_makers(metrics):
        sections.setdefault(maker, {
            "history": {}, "product": {}, "customer": {}, "application": {},
            "data_gaps": [f"未找到{maker}对应的有效数据，已保留固定模板字段"],
        })
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
        detail["history"] = history
        detail["product"] = product
        detail["customer"] = customer
        detail["application"] = application
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
    return [value.strip() for value in values if isinstance(value, str) and value.strip()]


def _finalize_narrative_traceability(report: dict[str, Any]) -> dict[str, Any]:
    """Strip internal metric references from prose and retain a displayable conclusion-to-evidence map."""
    result = deepcopy(report)
    declared_refs = set((result.get("quality") or {}).get("narrative_metric_refs") or [])
    source_file = (result.get("source") or {}).get("file_name")
    evidence_by_metric = {
        item.get("metric_id"): item.get("evidence")
        for item in (result.get("evidence") or [])
        if isinstance(item, dict) and item.get("metric_id")
    }
    narrative_sources: list[dict[str, Any]] = []
    seen: dict[tuple[str, tuple[str, ...]], str] = {}
    invalid_refs: set[str] = set()

    def clean(value):
        if isinstance(value, dict):
            for key in list(value.keys()):
                if key in {"computed_metrics", "evidence", "narrative_sources"}:
                    continue
                value[key] = clean(value[key])
            return value
        if isinstance(value, list):
            return [clean(item) for item in value]
        if not isinstance(value, str):
            return value
        match = METRIC_REF_PATTERN.search(value)
        if not match:
            return value
        refs = METRIC_REF_TOKEN_PATTERN.findall(match.group(0))
        text = METRIC_REF_PATTERN.sub("", value).strip()
        valid_refs = tuple(dict.fromkeys(ref for ref in refs if ref in evidence_by_metric))
        invalid_refs.update(ref for ref in refs if ref not in evidence_by_metric)
        if not text or not valid_refs:
            return text
        key = (text, valid_refs)
        if key not in seen:
            label = f"R{len(narrative_sources) + 1}"
            seen[key] = label
            narrative_sources.append({
                "citation_label": label,
                "conclusion": text,
                "source_file": source_file,
                "metric_ids": list(valid_refs),
                "metrics": [
                    {"metric_id": metric_id, "evidence": evidence_by_metric[metric_id]}
                    for metric_id in valid_refs
                ],
            })
        return text

    clean(result)
    result["narrative_sources"] = narrative_sources
    result.setdefault("quality", {})["narrative_metric_refs"] = sorted(declared_refs | {
        metric_id for item in narrative_sources for metric_id in item["metric_ids"]
    })
    if invalid_refs:
        result["quality"].setdefault("data_gaps", []).append(
            "分析文案包含无法解析的指标引用：" + "、".join(sorted(invalid_refs))
        )
    return result


def _with_metric(text: str, metric: Any) -> str:
    metric_id = metric.get("metric_id") if isinstance(metric, dict) else None
    return f"{text} [{metric_id}]" if metric_id else text


def _with_metrics(text: str, *metrics: Any) -> str:
    metric_ids = [
        metric.get("metric_id") for metric in metrics
        if isinstance(metric, dict) and metric.get("metric_id")
    ]
    suffix = " ".join(f"[{metric_id}]" for metric_id in dict.fromkeys(metric_ids))
    return f"{text} {suffix}" if suffix else text


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
            _with_metric(f"Y25前三季度市场总出货{_format_number(market_y25)}K，同比{_format_percent(market_yoy)}", market)
        )

    matrix_rows = ((metrics.get("summary_matrix") or {}).get("rows") or [])
    ltps_total = next((row for row in matrix_rows if row.get("row_key") == "ltps.total"), None)
    if ltps_total:
        ltps_market = ltps_total.get("market") or {}
        share = (ltps_market.get("segment_share") or {}).get("2025")
        result["executive_summary"].append(
            _with_metric(f"LTPS市场占比{_format_percent(share)}，同比{_format_percent(ltps_market.get('yoy_2025_vs_2024'))}", ltps_market)
        )
    size_rows = [row for row in matrix_rows if not row.get("is_total")]
    growing = [row for row in size_rows if (row.get("market") or {}).get("yoy_2025_vs_2024") is not None]
    if growing:
        fastest = max(growing, key=lambda row: (row.get("market") or {}).get("yoy_2025_vs_2024"))
        result["market_summary"]["insights"].append(
            _with_metric(f"{fastest.get('technology')} {fastest.get('label')}同比{_format_percent((fastest.get('market') or {}).get('yoy_2025_vs_2024'))}，为增长最快尺寸段", fastest.get("market"))
        )
    asi_total = next((row for row in matrix_rows if row.get("row_key") == "a_si.total"), None)
    if asi_total:
        asi_market = asi_total.get("market") or {}
        asi_share = (asi_market.get("segment_share") or {}).get("2025")
        result["market_summary"]["insights"].append(
            _with_metric(f"a-Si市场同比{_format_percent(asi_market.get('yoy_2025_vs_2024'))}，占比{_format_percent(asi_share)}", asi_market)
        )
    for row in size_rows:
        metric = row.get("market") or {}
        yoy = metric.get("yoy_2025_vs_2024")
        share = (metric.get("total_market_share") or {}).get("2025")
        maker_metrics = row.get("makers") or {}
        leaders = [
            (name, ((value.get("same_size_market_share") or {}).get("2025")))
            for name, value in maker_metrics.items()
            if ((value.get("same_size_market_share") or {}).get("2025")) is not None
        ]
        leader_text = ""
        if leaders:
            leader, leader_share = max(leaders, key=lambda item: item[1])
            leader_text = f"，四家中{leader}份额最高（{_format_percent(leader_share)}）"
        result["market_summary"]["insights"].append(
            _with_metric(
                f"{row.get('technology')} {row.get('label')}占市场{_format_percent(share)}，同比{_format_percent(yoy)}{leader_text}",
                metric,
            )
        )

    maker_lookup = {item.get("maker"): item for item in result.get("makers") or []}
    for maker in (item.get("maker") for item in result.get("makers") or []):
        if not maker:
            continue
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
            shipment_text = _with_metric(shipment_text, shipment)
            maker_result["overview"].append(shipment_text)
            maker_result["global_trend"]["insights"].append(shipment_text)
            history.setdefault("insights", {}).setdefault("shipment", []).append(shipment_text)
            completion = shipment.get("forecast_completion_y25_q1_q3")
            if completion is not None:
                completion_text = _with_metric(
                    f"Y25前三季度已完成全年预测的{_format_percent(completion)}",
                    shipment,
                )
                maker_result["global_trend"]["insights"].append(completion_text)
                history.setdefault("insights", {}).setdefault("shipment", []).append(completion_text)

        share_periods = (history.get("shipment_share") or {}).get("periods") or {}
        y25_share = share_periods.get("Y25Q1-Q3")
        y24_share = share_periods.get("Y24")
        if y25_share is not None:
            share_text = f"Y25前三季度出货市占率{_format_percent(y25_share)}"
            if y24_share is not None:
                share_text += f"，较Y24变化{_format_points(y25_share - y24_share)}个百分点"
            share_text = _with_metric(share_text, history.get("shipment_share"))
            maker_result["overview"].append(share_text)
            maker_result["global_trend"]["insights"].append(share_text)
            history.setdefault("insights", {}).setdefault("shipment_share", []).append(share_text)

        area = history.get("display_area") or {}
        area_yoy = (area.get("yoy_periods") or {}).get("Y25Q1-Q3")
        area_y25 = (area.get("periods") or {}).get("Y25Q1-Q3")
        if area_y25 is not None:
            history.setdefault("insights", {}).setdefault("display_area", []).append(
                _with_metric(f"Y25前三季度出货面积{_format_number(area_y25)}㎡，同比{_format_percent(area_yoy)}", area)
            )

        product_segments = []
        for row in size_rows:
            maker_metric = ((row.get("makers") or {}).get(maker) or {})
            contribution = maker_metric.get("growth_contribution_2025_vs_2024")
            yoy = maker_metric.get("yoy_2025_vs_2024")
            if contribution is not None and yoy is not None:
                product_segments.append((row, maker_metric, contribution, yoy))
        positive_segments = sorted(
            (item for item in product_segments if item[2] > 0),
            key=lambda item: item[2], reverse=True,
        )
        negative_segments = sorted(
            (item for item in product_segments if item[2] < 0),
            key=lambda item: item[2],
        )
        for rank, (row, maker_metric, contribution, yoy) in enumerate(positive_segments[:2], start=1):
            driver_text = _with_metric(
                f"产品驱动力{rank}：{row.get('technology')} {row.get('label')}同比{_format_percent(yoy)}，增长贡献{_format_percent(contribution)}",
                maker_metric,
            )
            maker_result["product_line"]["insights"].append(driver_text)
            maker_result["drivers"]["product"].append(driver_text)
            product.setdefault("insights", {}).setdefault("technology_size_growth", []).append(driver_text)
        if negative_segments:
            row, maker_metric, contribution, yoy = negative_segments[0]
            drag_text = _with_metric(
                f"主要拖累：{row.get('technology')} {row.get('label')}同比{_format_percent(yoy)}，增长贡献{_format_percent(contribution)}",
                maker_metric,
            )
            maker_result["product_line"]["insights"].append(drag_text)
            maker_result["drivers"]["product"].append(drag_text)
            product.setdefault("insights", {}).setdefault("technology_size_growth", []).append(drag_text)

        if maker != "BOE" and "BOE" in ((result.get("maker_sections") or {})):
            comparisons = []
            for row in size_rows:
                maker_metric = ((row.get("makers") or {}).get(maker) or {})
                boe_metric = ((row.get("makers") or {}).get("BOE") or {})
                maker_value = (maker_metric.get("values") or {}).get("2025")
                boe_value = (boe_metric.get("values") or {}).get("2025")
                if maker_value is None or boe_value in {None, 0}:
                    continue
                comparisons.append((float(maker_value) / float(boe_value), row, maker_metric, boe_metric))
            if comparisons:
                strongest = max(comparisons, key=lambda item: item[0])
                ratio, row, maker_metric, boe_metric = strongest
                relation = "领先" if ratio >= 1 else "差距最小"
                comparison_text = _with_metrics(
                    f"相较BOE，{maker}在{row.get('technology')} {row.get('label')}的相对表现最强，出货为BOE的{ratio:.1f}倍（{relation}）",
                    maker_metric, boe_metric,
                )
                maker_result["product_line"]["insights"].append(comparison_text)
                maker_result["drivers"]["product"].append(comparison_text)
                product.setdefault("insights", {}).setdefault("technology_size_growth", []).append(comparison_text)

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
            text = _with_metric(text, technologies.get(technology))
            maker_result["product_line"]["insights"].append(text)
            maker_result["drivers"]["product"].append(text)
            product.setdefault("insights", {}).setdefault("technology_history", []).append(text)

        clients = ((customer.get("top_clients") or {}).get("clients") or [])
        clients = [item for item in clients if ((item.get("periods") or {}).get("Y25Q1-Q3")) is not None]
        if clients:
            top_client = max(clients, key=lambda item: (item.get("periods") or {}).get("Y25Q1-Q3"))
            text = f"第一大客户{top_client.get('client')}出货{_format_number((top_client.get('periods') or {}).get('Y25Q1-Q3'))}K"
            text = _with_metric(text, top_client)
            maker_result["customer_region"]["insights"].append(text)
            maker_result["drivers"]["customer"].append(text)
            customer.setdefault("insights", {}).setdefault("top_clients", []).append(text)
            contribution_clients = [
                item for item in clients if item.get("growth_contribution_y25_q1_q3") is not None
            ]
            if contribution_clients:
                driver = max(contribution_clients, key=lambda item: item["growth_contribution_y25_q1_q3"])
                driver_text = (
                    f"{driver.get('client')}占Y25前三季度出货{_format_percent(driver.get('share_y25_q1_q3'))}，"
                    f"份额变化{_format_points(driver.get('share_change_points'))}个百分点，"
                    f"增长贡献{_format_percent(driver.get('growth_contribution_y25_q1_q3'))}"
                )
                driver_text = _with_metric(driver_text, driver)
                maker_result["customer_region"]["insights"].append(driver_text)
                maker_result["drivers"]["customer"].append(driver_text)
                customer.setdefault("insights", {}).setdefault("top_clients", []).append(driver_text)
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
            text = _with_metric(text, top_region)
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
            text = _with_metric(text, top_application)
            maker_result["application"]["insights"].append(text)
            maker_result["drivers"]["application"].append(text)
            application.setdefault("insights", {}).setdefault("application_history", []).append(text)
            contribution_apps = [
                item for item in applications if item.get("growth_contribution_y25_q1_q3") is not None
            ]
            if contribution_apps:
                driver = max(contribution_apps, key=lambda item: item["growth_contribution_y25_q1_q3"])
                area = driver.get("display_area") or {}
                driver_text = (
                    f"{driver.get('application')}占Y25前三季度出货{_format_percent(driver.get('share_y25_q1_q3'))}，"
                    f"增长贡献{_format_percent(driver.get('growth_contribution_y25_q1_q3'))}"
                )
                if area.get("share_y25_q1_q3") is not None:
                    driver_text += f"，面积占比{_format_percent(area.get('share_y25_q1_q3'))}"
                driver_text = _with_metric(driver_text, driver)
                maker_result["application"]["insights"].append(driver_text)
                maker_result["drivers"]["application"].append(driver_text)
                application.setdefault("insights", {}).setdefault("application_history", []).append(driver_text)
        key_sizes = ((application.get("key_sizes") or {}).get("rows") or [])
        if key_sizes:
            top_size = max(key_sizes, key=lambda item: item.get("shipment") or 0)
            text = (
                f"重点尺寸{_format_number(top_size.get('size'))}寸（{top_size.get('technology')}）"
                f"出货{_format_number(top_size.get('shipment'))}K"
            )
            text = _with_metric(text, top_size)
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
