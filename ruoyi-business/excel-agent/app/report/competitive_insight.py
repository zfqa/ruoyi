"""Generate the first competitive-insight report defined by the business template."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import json
import os
import re
from typing import Any

from app.llm.client import ArkChatClient, LlmError
from app.report.driver_narratives import build_driver_narratives
from app.report.metrics import calculate_competitive_metrics
from app.report.periods import (
    base_periods_for,
    clip_periods_to_data_through,
    full_year_period,
    latest_omdia_data_through,
    publication_label,
    q1_q3_period,
    report_horizon_from_data_through,
    summary_pair_from_data_through,
    year_label,
)


REPORT_TYPE = "competitive_insight_v1"
MAKERS = ("Tianma", "AUO", "CSOT", "BOE")
TARGET_SHEETS = ("shipment", "shipment share", "display area", "display area share")
# Backward-compatible aliases used by existing exporters.
HORIZON_Q1_Q3 = "y25_q1_q3"
HORIZON_FULL_YEAR = "y25_full_year"

SYSTEM_PROMPT_Q1_Q3 = """你是车载显示行业报告撰写员。当前生成Y25前三季度Summary，以及Tianma、AUO、CSOT、BOE四家公司的前装历史、产品线、客户/区域和应用增长分析。computed_metrics中的筛选、汇总、同比和占比均已由Python计算完成。
你只负责解释和组织已有指标，严禁重新计算、修改数值、从原始记录推导新指标或补写常识数据。
注意：Omdia文件名中的nQyy是网站发布季，实际更新数据截止到上一季度；不得把发布季当成数据截止季。
每条包含数字的文字必须引用已有metric_id。数据缺口由Python确定，不得新增客户、区域、应用或其他未要求部分。只输出JSON对象，不要Markdown。"""

SYSTEM_PROMPT_FULL_YEAR = """你是车载显示行业报告撰写员。当前输入已从Y25前三季度口径扩充至Y25全年：主叙事必须以Y25F（2025全年预测/跟踪）及相对Y24的全年同比为核心，前三季度实际（Y25Q1-Q3）与完成率作为过程补充。若表中出现Y26Qn，那是发布季之后的展望/预测列，不是本次实际更新截止季，不得喧宾夺主。依次撰写Tianma、AUO、CSOT、BOE的前装历史、产品线、客户/区域和应用增长分析。computed_metrics中的筛选、汇总、同比和占比均已由Python计算完成。
你只负责解释和组织已有指标，严禁重新计算、修改数值、从原始记录推导新指标或补写常识数据。
注意：例如1Q26文件表示Omdia在1Q26发布，实际更新内容到4Q25（Y25全年）。正文必须明确写出各厂Y25F全年出货及同比；可对照Y25Q1-Q3实际。每条包含数字的文字必须引用已有metric_id。数据缺口由Python确定，不得新增客户、区域、应用或其他未要求部分。只输出JSON对象，不要Markdown。"""

METRIC_REF_PATTERN = re.compile(r"\s*\[(.+)\]\s*$")
METRIC_REF_TOKEN_PATTERN = re.compile(r"\[([A-Za-z0-9_.-]+)\]")


def _history_period_order(metrics: dict[str, Any]) -> list[str]:
    details = metrics.get("maker_details") or {}
    for detail in details.values():
        order = (((detail or {}).get("history") or {}).get("scope") or {}).get("period_order") or []
        if order:
            return list(order)
    return list((((metrics.get("tianma_history") or {}).get("scope") or {}).get("period_order")) or [])


def _source_file_names(parsed: dict[str, Any]) -> list[str]:
    names: list[str] = []
    for item in parsed.get("source_workbooks") or []:
        name = (
            (item or {}).get("original_file_name")
            or (item or {}).get("file_name")
            or (item or {}).get("stored_file_name")
        )
        if name:
            names.append(str(name))
    if parsed.get("file_name"):
        names.append(str(parsed.get("file_name")))
    return names


def _resolve_horizon_meta(parsed: dict[str, Any], metrics: dict[str, Any]) -> dict[str, Any]:
    """报告口径与列裁剪共用同一 data_through，避免表头全年/前三季度与裁剪截止分叉。

    优先级：
    1) metrics.scope 已带 data_through_*（pipeline 从文件名算出）
    2) summary_mode / full_year
    3) 文件名 Omdia 滞后 → period_coverage → 默认前三季度
    最终若文件名截止季更新，整套 framing（含 full_year）一起重算，不单独抬高 through。
    """
    scope = (metrics or {}).get("scope") or {}
    summary_mode = str(scope.get("summary_mode") or "")
    target_year = int(scope.get("summary_target_year") or 2025)
    framing: dict[str, Any] | None = None

    scope_through_year = scope.get("data_through_year")
    scope_through_quarter = scope.get("data_through_quarter")
    if scope_through_year is not None and scope_through_quarter is not None:
        framing = report_horizon_from_data_through(
            int(scope_through_year), int(scope_through_quarter)
        )
    elif scope.get("full_year") is True or summary_mode.endswith("full_year"):
        framing = report_horizon_from_data_through(target_year, 4)
    elif summary_mode.endswith("q1_q3"):
        framing = report_horizon_from_data_through(target_year, 3)
    else:
        mode_match = re.search(r"q1_q([1-4])$", summary_mode)
        if mode_match:
            framing = report_horizon_from_data_through(target_year, int(mode_match.group(1)))

    latest = latest_omdia_data_through(_source_file_names(parsed))
    if framing is None:
        if latest:
            framing = report_horizon_from_data_through(
                latest["data_through_year"], latest["data_through_quarter"]
            )
        else:
            coverage = parsed.get("period_coverage") or []
            by_year: dict[int, set[int]] = {}
            for item in coverage:
                year = int(item.get("year") or 0)
                quarter = int(item.get("quarter") or 0)
                if year > 0 and quarter in {1, 2, 3, 4}:
                    by_year.setdefault(year, set()).add(quarter)
            for year in sorted(by_year, reverse=True):
                qs = by_year[year]
                if {1, 2, 3, 4} <= qs:
                    framing = report_horizon_from_data_through(year, 4)
                    break
                if {1, 2, 3} <= qs:
                    framing = report_horizon_from_data_through(year, 3)
                    break
            if framing is None:
                framing = {
                    **report_horizon_from_data_through(2025, 3),
                    "report_horizon": HORIZON_Q1_Q3,
                }

    framing["summary_target_year"] = target_year if scope.get("summary_target_year") else framing.get(
        "data_through_year", 2025
    )
    framing["omdia"] = latest
    # 若文件名截止更新，整套口径（含 full_year / 表头）一起重算
    if latest:
        metric_through = (
            int(framing["data_through_year"]),
            int(framing["data_through_quarter"]),
        )
        omdia_through = (
            int(latest["data_through_year"]),
            int(latest["data_through_quarter"]),
        )
        through_year, through_quarter = max(metric_through, omdia_through)
        if (through_year, through_quarter) != metric_through:
            refreshed = report_horizon_from_data_through(through_year, through_quarter)
            framing.update(refreshed)
            framing["summary_target_year"] = target_year if scope.get("summary_target_year") else through_year
            framing["omdia"] = latest
    return framing


def _report_horizon(parsed: dict[str, Any], metrics: dict[str, Any]) -> str:
    return _resolve_horizon_meta(parsed, metrics)["report_horizon"]


def generate_competitive_insight_report(
    parsed: dict[str, Any],
    use_llm: bool = True,
    llm_client: ArkChatClient | None = None,
) -> dict[str, Any]:
    """Build a traceable first report, with deterministic narratives as fallback."""
    metrics = parsed.get("computed_metrics") or calculate_competitive_metrics(_metric_tables(parsed))
    report = _empty_report(parsed, metrics)
    horizon_meta = ((report.get("methodology") or {}).get("scope") or {})
    horizon = horizon_meta.get("report_horizon") or HORIZON_Q1_Q3
    full_year = bool(horizon_meta.get("full_year") or horizon == HORIZON_FULL_YEAR)
    client = llm_client or (
        ArkChatClient(timeout_seconds=float(os.getenv("ARK_REPORT_TIMEOUT_SECONDS", "180")))
        if use_llm else None
    )
    if not use_llm:
        report["quality"]["generation_mode"] = "python_metrics_rule_narrative"
        return _finalize_narrative_traceability(_populate_rule_narratives(report))
    if client is None or not client.available:
        report["quality"].setdefault("warnings", []).append(
            "LLM API Key未配置，已使用确定性指标生成分析文案"
        )
        return _finalize_narrative_traceability(_populate_rule_narratives(report))

    scope_for_prompt = (report.get("methodology") or {}).get("scope") or {}
    cy = int(scope_for_prompt.get("current_year") or 2025)
    py = int(scope_for_prompt.get("prior_year") or (cy - 1))
    fy = scope_for_prompt.get("full_year_period") or full_year_period(cy)
    q13 = scope_for_prompt.get("q1_q3_period") or q1_q3_period(cy)
    yy_p = year_label(cy)
    prior_yy_p = year_label(py)
    if full_year:
        task = (
            f"仅依据Python已计算的指标，按终稿顺序撰写{yy_p}全年Summary（以{fy}及相对{prior_yy_p}同比为主，{q13}为过程补充）；"
            "再依次撰写Tianma、AUO、CSOT、BOE的主要驱动力、前装历史、产品线、客户/区域和应用分析；"
            f"不执行任何计算；所有YoY采用标准同比current/previous-1；必须写出各厂{fy}全年出货"
        )
        system_prompt = (
            f"你是车载显示行业报告撰写员。当前输入已从{yy_p}前三季度口径扩充至{yy_p}全年：主叙事必须以{fy}"
            f"（{cy}全年预测/跟踪）及相对{prior_yy_p}的全年同比为核心，前三季度实际（{q13}）与完成率作为过程补充。"
            f"若表中出现{year_label(cy + 1)}Qn，那是发布季之后的展望/预测列，不是本次实际更新截止季，不得喧宾夺主。"
            "依次撰写Tianma、AUO、CSOT、BOE的前装历史、产品线、客户/区域和应用增长分析。"
            "computed_metrics中的筛选、汇总、同比和占比均已由Python计算完成。\n"
            "你只负责解释和组织已有指标，严禁重新计算、修改数值、从原始记录推导新指标或补写常识数据。\n"
            "注意：Omdia文件名中的nQyy是网站发布季，实际更新数据截止到上一季度；不得把发布季当成数据截止季。"
            f"正文必须明确写出各厂{fy}全年出货及同比；可对照{q13}实际。每条包含数字的文字必须引用已有metric_id。"
            "数据缺口由Python确定，不得新增客户、区域、应用或其他未要求部分。只输出JSON对象，不要Markdown。"
        )
    else:
        task = (
            f"仅依据Python已计算的指标，按终稿顺序撰写{yy_p}前三季度Summary，并依次撰写Tianma、AUO、CSOT、BOE的主要驱动力、前装历史、产品线、客户/区域和应用分析；"
            "不执行任何计算；所有YoY采用标准同比current/previous-1"
        )
        system_prompt = (
            f"你是车载显示行业报告撰写员。当前生成{yy_p}前三季度Summary，以及Tianma、AUO、CSOT、BOE四家公司的前装历史、产品线、客户/区域和应用增长分析。"
            "computed_metrics中的筛选、汇总、同比和占比均已由Python计算完成。\n"
            "你只负责解释和组织已有指标，严禁重新计算、修改数值、从原始记录推导新指标或补写常识数据。\n"
            "注意：Omdia文件名中的nQyy是网站发布季，实际更新数据截止到上一季度；不得把发布季当成数据截止季。\n"
            "每条包含数字的文字必须引用已有metric_id。数据缺口由Python确定，不得新增客户、区域、应用或其他未要求部分。只输出JSON对象，不要Markdown。"
        )
    focus_periods = list(
        scope_for_prompt.get("focus_periods")
        or list(base_periods_for(cy))
    )
    request = {
        "task": task,
        "required_schema": _llm_schema(),
        "methodology": report["methodology"],
        "computed_metrics": _compact_metrics_for_llm(metrics),
        "focus_periods": focus_periods,
        "report_horizon": horizon,
    }
    try:
        candidate = client.complete_json(system_prompt, json.dumps(request, ensure_ascii=False), max_tokens=7000)
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
        distribution = product.get("size_distribution") or product.get("y25q1_q3_size_distribution") or {}
        points = distribution.get("points") or []
        if len(points) > 18:
            distribution["points"] = sorted(
                points, key=lambda item: float(item.get("shipment") or 0), reverse=True
            )[:18]
            product["size_distribution"] = distribution
            product["y25q1_q3_size_distribution"] = distribution
    for maker in (compact.get("makers") or {}).values():
        if isinstance(maker, dict):
            maker["clients"] = (maker.get("clients") or [])[:5]
            maker["applications"] = (maker.get("applications") or [])[:6]
    return compact


def _empty_report(parsed: dict[str, Any], metrics: dict[str, Any]) -> dict[str, Any]:
    maker_names = _template_makers(metrics)
    horizon_meta = _resolve_horizon_meta(parsed, metrics)
    horizon = horizon_meta["report_horizon"]
    full_year = bool(horizon_meta.get("full_year"))
    metric_scope = (metrics or {}).get("scope") or {}
    current_year = int(
        metric_scope.get("current_year")
        or horizon_meta.get("summary_target_year")
        or horizon_meta.get("data_through_year")
        or 2025
    )
    prior_year = int(metric_scope.get("prior_year") or (current_year - 1))
    pair = summary_pair_from_data_through(
        horizon_meta.get("data_through_year") or current_year,
        horizon_meta.get("data_through_quarter") or (4 if full_year else 3),
    )
    fy_key = metric_scope.get("full_year_period") or pair["full_year_period"]
    q13_key = metric_scope.get("q1_q3_period") or pair["q1_q3_period"]
    primary_period = metric_scope.get("primary_period") or (fy_key if full_year else q13_key)
    period_order = _history_period_order(metrics)
    focus_periods = list(dict.fromkeys([*list(base_periods_for(current_year)), *period_order]))
    focus_periods = list(
        clip_periods_to_data_through(
            focus_periods,
            horizon_meta.get("data_through_year"),
            horizon_meta.get("data_through_quarter"),
        )
    )
    source_workbooks = parsed.get("source_workbooks") or []
    omdia = horizon_meta.get("omdia") or {}
    pub_label = omdia.get("publication_label")
    through_label = omdia.get("data_through_label") or publication_label(
        horizon_meta.get("data_through_year") or current_year,
        horizon_meta.get("data_through_quarter") or 3,
    )
    yy = year_label(current_year)
    prior_yy = year_label(prior_year)
    notes = [
        "Omdia Tracker文件名中的nQyy（如1Q26）是网站发布季；实际更新数据固定滞后一个季度"
        f"（发布{pub_label or 'nQyy'} → 数据截止{through_label}）。该规则后续各季均适用。",
        f"{fy_key}为数据源中的{current_year}全年预测/跟踪值，{q13_key}为前三季度实际值。",
        "LTPS口径同时包含LTPS LCD与OLED；厂商出货和面积不计Oxide，市场份额分母保留全技术市场。",
        "前装口径为Original specification=Automobile monitor，并排除Application=Automobile monitor (Others)。",
        "市场汇总覆盖全部Maker；China Star统一展示为CSOT。",
        "尺寸段依次为<8、[8,12)、[12,15)、>=15，边界值归入右侧区间。",
        "Y22来自1Q25 with 4Q24 Results基准文件；Y23及以后来自当前History，多份History/Supply按Year/Quarter后写覆盖合并。",
        "客户区域按客户决策地映射；未命中的客户归为其他并保留清单。",
        "应用重点尺寸先跨Technology合并，同一应用×尺寸超过1,000K或为该应用第一大尺寸时入选。",
        "所有数值、同比、份额、完成率及增长贡献均由Python计算；LLM仅组织文字，不执行算术。",
    ]
    if full_year:
        notes.insert(
            1,
            f"解析结果：{current_year}"
            f"年四季齐全，已自动切换为{horizon_meta.get('summary_name') or f'{yy}全年'}汇总"
            f"（{fy_key}）；{q13_key} 作为过程实际与完成率补充。"
            + (
                f" Omdia 发布滞后：截止{through_label}。"
                if through_label
                else ""
            ),
        )
    else:
        notes.insert(
            1,
            f"解析结果：{current_year}"
            f"年尚未四季齐全，当前按{horizon_meta.get('summary_name') or f'{yy}前三季度'}汇总。",
        )
    summary_label = horizon_meta.get("summary_name") or (f"{yy}全年" if full_year else f"{yy}前三季度")
    flow = ["口径说明", f"{summary_label}Summary"]
    flow.extend(
        item for maker in maker_names for item in (
            f"{maker}洞察", f"{maker}前装出货与市占率", f"{maker}产品线",
            f"{maker}客户/区域", f"{maker}应用",
        )
    )
    title_suffix = horizon_meta.get("title_suffix") or (f"{yy}全年" if full_year else f"{yy}前三季度")
    title = f"竞争社对标分析 - Tianma、AUO、CSOT、BOE（{title_suffix}）"
    header_period_label = (
        metric_scope.get("header_period_label")
        or horizon_meta.get("title_suffix")
        or (f"{yy}全年" if full_year else f"{yy}前三季度")
    )
    return {
        "schema_version": "1.0",
        "report_type": REPORT_TYPE,
        "report_order": 1,
        "title": title,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": {
            "workbook_id": parsed.get("workbook_id"),
            "file_name": parsed.get("file_name"),
            "source_workbooks": source_workbooks,
            "period_coverage": parsed.get("period_coverage"),
            "omdia_publication_lag": {
                "rule": "publication_quarter_minus_one",
                "publication_label": pub_label,
                "data_through_label": through_label,
                "latest": omdia or None,
            },
        },
        "methodology": {
            "scope": {
                "original_specification": "Automobile monitor",
                "excluded_application": "Automobile monitor (Others)",
                "summary_years": list(metric_scope.get("summary_years") or [prior_year, current_year]),
                "current_year": current_year,
                "prior_year": prior_year,
                "primary_period": primary_period,
                "full_year_period": fy_key,
                "q1_q3_period": q13_key,
                "summary_quarters": ["Q1", "Q2", "Q3"] if not full_year else ["Q1", "Q2", "Q3", "Q4"],
                "focus_periods": focus_periods,
                "report_horizon": horizon,
                "horizon_kind": horizon_meta.get("horizon_kind"),
                "summary_mode": metric_scope.get("summary_mode") or horizon_meta.get("report_horizon"),
                "summary_target_year": current_year,
                "full_year": full_year,
                "full_year_2025": full_year and current_year == 2025,
                "header_period_label": header_period_label,
                "data_through_year": horizon_meta.get("data_through_year"),
                "data_through_quarter": horizon_meta.get("data_through_quarter"),
                "omdia_publication_label": pub_label,
                "omdia_data_through_label": through_label,
                "has_outlook_quarters": any(
                    str(p).startswith(year_label(current_year + 1)) for p in focus_periods
                ),
                "has_y26q1": any(str(p).startswith("Y26") for p in focus_periods),
                "makers": list(maker_names),
                "maker_aliases": {"China Star": "CSOT"},
                "technologies": ["LTPS", "a-Si"],
            },
            "size_buckets": ["<8", "[8,12)", "[12,15)", ">=15"],
            "formulas": {
                "yoy": f"{current_year} / {prior_year} - 1",
                "segment_share": "market segment shipment / total market shipment",
                "maker_internal_share": "maker segment shipment / maker total shipment",
                "segment_market_share": "maker segment shipment / market same-technology same-size segment shipment",
                "technology_market_share": "maker segment shipment / market technology total shipment",
                "same_size_market_share": "maker segment shipment / market same-size segment shipment",
                "forecast_completion": f"{q13_key} actual shipment / {fy_key} shipment",
                "y25f_yoy": f"{fy_key} / {prior_yy} - 1",
                "outlook_q1_yoy": f"{year_label(current_year + 1)}Q1 / {yy}Q1 - 1",
                "y26q1_yoy": "Y26 Q1 / Y25 Q1 - 1",
                "omdia_lag": "data_through = publication_quarter - 1 quarter",
                "growth_contribution": "segment shipment change / maker total shipment change",
            },
            "notes": notes,
            "limitations": [
                "当前基准文件不含2021年数据，因此Y22同比不计算。",
                "参考PDF个别图表与源数据存在内部不一致时，以Excel源表和统一公式的可复算结果为准。",
                "Pivot中可能含有晚于“数据截止季”的预测列；报告叙事以Omdia发布滞后规则确定的截止季为准。",
            ],
            "report_flow": flow,
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
        "driver_narratives": {"product": "", "customer": "", "application": ""},
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
    result["product_line"]["size_distribution"] = (
        product.get("size_distribution")
        or product.get("y25q1_q3_size_distribution")
        or metrics.get("sizes")
        or []
    )
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
    result = _attach_driver_narratives(deepcopy(report))
    source_file = (result.get("source") or {}).get("file_name")
    evidence_by_metric = {
        item.get("metric_id"): item.get("evidence")
        for item in (result.get("evidence") or [])
        if isinstance(item, dict) and item.get("metric_id")
    }
    metric_aliases = _metric_reference_aliases(evidence_by_metric)

    def resolve_metric_id(metric_id: str) -> str | None:
        if metric_id in evidence_by_metric:
            return metric_id
        aliased = metric_aliases.get(metric_id)
        if aliased:
            return aliased
        return _fuzzy_client_metric_id(metric_id, evidence_by_metric)

    declared_refs = {
        resolve_metric_id(metric_id) or metric_id
        for metric_id in ((result.get("quality") or {}).get("narrative_metric_refs") or [])
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
        resolved_refs = [resolve_metric_id(ref) for ref in refs]
        valid_refs = tuple(dict.fromkeys(ref for ref in resolved_refs if ref))
        invalid_refs.update(ref for ref, resolved in zip(refs, resolved_refs) if not resolved)
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
    gaps = [
        gap for gap in (result.get("quality", {}).get("data_gaps") or [])
        if "分析文案包含无法解析的指标引用" not in str(gap)
    ]
    if invalid_refs:
        gaps.append("分析文案包含无法解析的指标引用：" + "、".join(sorted(invalid_refs)))
    result.setdefault("quality", {})["data_gaps"] = gaps
    return result


def _metric_reference_aliases(evidence_by_metric: dict[str, Any]) -> dict[str, str]:
    """Map only unambiguous LLM formatting variants back to canonical metric IDs."""
    candidates: dict[str, set[str]] = {}
    for canonical in evidence_by_metric:
        variants = {canonical}
        if canonical.startswith("maker."):
            variants.add(canonical[len("maker."):])
        else:
            variants.add(f"maker.{canonical}")
        if canonical.endswith(".shipment"):
            without_measure = canonical[:-len(".shipment")]
            variants.add(without_measure)
            if without_measure.startswith("maker."):
                variants.add(without_measure[len("maker."):])
            else:
                variants.add(f"maker.{without_measure}")
        # Client slug with collapsed repeated letters: adaayo -> adayo
        client_match = CLIENT_METRIC_PATTERN.match(canonical)
        if client_match:
            maker = client_match.group("maker")
            slug = client_match.group("slug")
            collapsed = _collapse_repeated_chars(slug)
            if collapsed != slug:
                variants.add(f"{maker}.client.{collapsed}.shipment")
                variants.add(f"maker.{maker}.client.{collapsed}.shipment")
                variants.add(f"{maker}.client.{collapsed}")
                variants.add(f"maker.{maker}.client.{collapsed}")
            # Also accept the typo form that *adds* a repeated letter when LLM doubles a char
            for inflated in _inflate_single_repeats(slug):
                variants.add(f"{maker}.client.{inflated}.shipment")
                variants.add(f"maker.{maker}.client.{inflated}.shipment")
                variants.add(f"{maker}.client.{inflated}")
                variants.add(f"maker.{maker}.client.{inflated}")
        for alias in variants:
            if alias != canonical:
                candidates.setdefault(alias, set()).add(canonical)
    return {
        alias: next(iter(canonical_ids))
        for alias, canonical_ids in candidates.items()
        if len(canonical_ids) == 1
    }


CLIENT_METRIC_PATTERN = re.compile(
    r"^(?:maker\.)?(?P<maker>[a-z0-9_]+)\.client\.(?P<slug>[a-z0-9_]+)(?:\.shipment)?$"
)


def _collapse_repeated_chars(text: str) -> str:
    if not text:
        return text
    out = [text[0]]
    for ch in text[1:]:
        if ch != out[-1]:
            out.append(ch)
    return "".join(out)


def _inflate_single_repeats(text: str) -> list[str]:
    """Generate near-miss slugs where one character is accidentally doubled (adayo -> adaayo)."""
    variants: list[str] = []
    for index, ch in enumerate(text):
        if not ch.isalnum():
            continue
        variants.append(text[: index + 1] + ch + text[index + 1 :])
    return variants


def _edit_distance(left: str, right: str) -> int:
    if left == right:
        return 0
    if not left:
        return len(right)
    if not right:
        return len(left)
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, start=1):
        current = [i]
        for j, b in enumerate(right, start=1):
            insert_cost = current[j - 1] + 1
            delete_cost = previous[j] + 1
            replace_cost = previous[j - 1] + (0 if a == b else 1)
            current.append(min(insert_cost, delete_cost, replace_cost))
        previous = current
    return previous[-1]


def _fuzzy_client_metric_id(metric_id: str, evidence_by_metric: dict[str, Any]) -> str | None:
    """Resolve unambiguous LLM typos in client metric IDs (e.g. adaayo -> adayo)."""
    match = CLIENT_METRIC_PATTERN.match(metric_id or "")
    if not match:
        return None
    maker = match.group("maker")
    slug = match.group("slug")
    collapsed = _collapse_repeated_chars(slug)
    hits: list[str] = []
    for canonical in evidence_by_metric:
        canonical_match = CLIENT_METRIC_PATTERN.match(canonical)
        if not canonical_match or canonical_match.group("maker") != maker:
            continue
        if not canonical.endswith(".shipment"):
            continue
        canonical_slug = canonical_match.group("slug")
        if (
            canonical_slug == slug
            or canonical_slug == collapsed
            or _collapse_repeated_chars(canonical_slug) == collapsed
            or _edit_distance(slug, canonical_slug) <= 2
        ):
            hits.append(canonical)
    unique = list(dict.fromkeys(hits))
    if len(unique) == 1:
        return unique[0]
    return None


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
    scope = ((result.get("methodology") or {}).get("scope") or {})
    metric_scope = (metrics.get("scope") or {})
    full_year = bool(
        scope.get("full_year")
        or scope.get("report_horizon") == HORIZON_FULL_YEAR
    )
    current_year = int(scope.get("current_year") or metric_scope.get("current_year") or 2025)
    prior_year = int(scope.get("prior_year") or metric_scope.get("prior_year") or (current_year - 1))
    fy_key = scope.get("full_year_period") or metric_scope.get("full_year_period") or full_year_period(current_year)
    q13_key = scope.get("q1_q3_period") or metric_scope.get("q1_q3_period") or q1_q3_period(current_year)
    primary_period = scope.get("primary_period") or metric_scope.get("primary_period") or (fy_key if full_year else q13_key)
    yy = year_label(current_year)
    prior_yy = year_label(prior_year)
    year_key = str(current_year)
    period_label_full = f"{yy}全年"
    period_label_q13 = f"{yy}前三季度"
    base_keep = {year_label(y) for y in range(2022, current_year)} | {fy_key, q13_key, "Y22", "Y23", "Y24", "Y25F", "Y25Q1-Q3"}

    market = metrics.get("market") or {}
    market_values = market.get("values") or {}
    market_qty = market_values.get(year_key)
    if market_qty is None:
        market_qty = market_values.get("2025")
    market_yoy = market.get("yoy") or market.get("yoy_current_vs_prior") or market.get("yoy_2025_vs_2024")
    if market_qty is not None:
        if full_year:
            result["executive_summary"].append(
                _with_metric(
                    f"{period_label_full}市场总出货{_format_number(market_qty)}K，同比{_format_percent(market_yoy)}",
                    market,
                )
            )
        else:
            result["executive_summary"].append(
                _with_metric(f"{period_label_q13}市场总出货{_format_number(market_qty)}K，同比{_format_percent(market_yoy)}", market)
            )

    matrix_rows = ((metrics.get("summary_matrix") or {}).get("rows") or [])
    ltps_total = next((row for row in matrix_rows if row.get("row_key") == "ltps.total"), None)
    if ltps_total:
        ltps_market = ltps_total.get("market") or {}
        share = (ltps_market.get("segment_share") or {}).get(year_key)
        if share is None:
            share = (ltps_market.get("segment_share") or {}).get("2025")
        ltps_yoy = ltps_market.get("yoy") or ltps_market.get("yoy_2025_vs_2024")
        result["executive_summary"].append(
            _with_metric(f"LTPS市场占比{_format_percent(share)}，同比{_format_percent(ltps_yoy)}", ltps_market)
        )
    size_rows = [row for row in matrix_rows if not row.get("is_total")]
    growing = [
        row for row in size_rows
        if ((row.get("market") or {}).get("yoy") is not None
            or (row.get("market") or {}).get("yoy_2025_vs_2024") is not None)
    ]
    if growing:
        def _row_yoy(row):
            m = row.get("market") or {}
            return m.get("yoy") if m.get("yoy") is not None else m.get("yoy_2025_vs_2024")
        fastest = max(growing, key=_row_yoy)
        result["market_summary"]["insights"].append(
            _with_metric(
                f"{fastest.get('technology')} {fastest.get('label')}同比{_format_percent(_row_yoy(fastest))}，为增长最快尺寸段",
                fastest.get("market"),
            )
        )
    asi_total = next((row for row in matrix_rows if row.get("row_key") == "a_si.total"), None)
    if asi_total:
        asi_market = asi_total.get("market") or {}
        asi_share = (asi_market.get("segment_share") or {}).get(year_key)
        if asi_share is None:
            asi_share = (asi_market.get("segment_share") or {}).get("2025")
        asi_yoy = asi_market.get("yoy") or asi_market.get("yoy_2025_vs_2024")
        result["market_summary"]["insights"].append(
            _with_metric(f"a-Si市场同比{_format_percent(asi_yoy)}，占比{_format_percent(asi_share)}", asi_market)
        )
    for row in size_rows:
        metric = row.get("market") or {}
        yoy = metric.get("yoy") if metric.get("yoy") is not None else metric.get("yoy_2025_vs_2024")
        share = (metric.get("total_market_share") or {}).get(year_key)
        if share is None:
            share = (metric.get("total_market_share") or {}).get("2025")
        maker_metrics = row.get("makers") or {}
        leaders = [
            (
                name,
                (
                    (value.get("same_size_market_share") or {}).get(year_key)
                    if (value.get("same_size_market_share") or {}).get(year_key) is not None
                    else (value.get("same_size_market_share") or {}).get("2025")
                ),
            )
            for name, value in maker_metrics.items()
            if (
                (value.get("same_size_market_share") or {}).get(year_key) is not None
                or (value.get("same_size_market_share") or {}).get("2025") is not None
            )
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

        fy_qty = shipment_periods.get(fy_key)
        if fy_qty is None and fy_key != "Y25F":
            fy_qty = shipment_periods.get("Y25F")
        if full_year and fy_qty is not None:
            fy_yoy = shipment_yoy.get(fy_key)
            if fy_yoy is None:
                fy_yoy = shipment_yoy.get("Y25F")
            y25f_text = (
                f"{period_label_full}（{fy_key}）出货{_format_number(fy_qty)}K，同比"
                f"{_format_percent(fy_yoy)}"
            )
            y25f_text = _with_metric(y25f_text, shipment)
            maker_result["overview"].append(y25f_text)
            maker_result["global_trend"]["insights"].append(y25f_text)
            history.setdefault("insights", {}).setdefault("shipment", []).append(y25f_text)
            result["executive_summary"].append(
                _with_metric(
                    f"{maker} {period_label_full}出货{_format_number(fy_qty)}K，同比{_format_percent(fy_yoy)}",
                    shipment,
                )
            )

        q13_shipment = shipment_periods.get(q13_key)
        if q13_shipment is None and q13_key != "Y25Q1-Q3":
            q13_shipment = shipment_periods.get("Y25Q1-Q3")
        if q13_shipment is not None:
            q13_yoy = shipment_yoy.get(q13_key)
            if q13_yoy is None:
                q13_yoy = shipment_yoy.get("Y25Q1-Q3")
            shipment_text = (
                f"{period_label_q13}出货{_format_number(q13_shipment)}K，同比"
                f"{_format_percent(q13_yoy)}"
            )
            shipment_text = _with_metric(shipment_text, shipment)
            maker_result["overview"].append(shipment_text)
            maker_result["global_trend"]["insights"].append(shipment_text)
            history.setdefault("insights", {}).setdefault("shipment", []).append(shipment_text)
            completion = (
                shipment.get("forecast_completion_primary")
                if shipment.get("forecast_completion_primary") is not None
                else shipment.get("forecast_completion_y25_q1_q3")
            )
            if completion is not None:
                completion_text = _with_metric(
                    f"{period_label_q13}已完成全年预测的{_format_percent(completion)}",
                    shipment,
                )
                maker_result["global_trend"]["insights"].append(completion_text)
                history.setdefault("insights", {}).setdefault("shipment", []).append(completion_text)

        # Extended quarters (e.g. next-year Qn) only as outlook when full-year mode.
        for period in shipment_periods:
            if period in base_keep:
                continue
            if not str(period).startswith("Y") or "Q" not in str(period):
                continue
            qty = shipment_periods.get(period)
            if qty is None:
                continue
            outlook = (
                f"{period}展望出货{_format_number(qty)}K，同比"
                f"{_format_percent(shipment_yoy.get(period))}"
            )
            outlook = _with_metric(outlook, shipment)
            maker_result["overview"].append(outlook)
            maker_result["global_trend"]["insights"].append(outlook)
            history.setdefault("insights", {}).setdefault("shipment", []).append(outlook)

        share_periods = (history.get("shipment_share") or {}).get("periods") or {}
        if full_year:
            fy_share = share_periods.get(fy_key)
            if fy_share is None:
                fy_share = share_periods.get("Y25F")
            prior_share = share_periods.get(prior_yy)
            if prior_share is None:
                prior_share = share_periods.get("Y24")
            if fy_share is not None:
                share_text = f"{period_label_full}出货市占率{_format_percent(fy_share)}"
                if prior_share is not None:
                    share_text += f"，较{prior_yy}变化{_format_points(fy_share - prior_share)}个百分点"
                share_text = _with_metric(share_text, history.get("shipment_share"))
                maker_result["overview"].append(share_text)
                maker_result["global_trend"]["insights"].append(share_text)
                history.setdefault("insights", {}).setdefault("shipment_share", []).append(share_text)
        q13_share = share_periods.get(q13_key)
        if q13_share is None:
            q13_share = share_periods.get("Y25Q1-Q3")
        prior_share = share_periods.get(prior_yy)
        if prior_share is None:
            prior_share = share_periods.get("Y24")
        if q13_share is not None:
            share_text = f"{period_label_q13}出货市占率{_format_percent(q13_share)}"
            if prior_share is not None:
                share_text += f"，较{prior_yy}变化{_format_points(q13_share - prior_share)}个百分点"
            share_text = _with_metric(share_text, history.get("shipment_share"))
            maker_result["overview"].append(share_text)
            maker_result["global_trend"]["insights"].append(share_text)
            history.setdefault("insights", {}).setdefault("shipment_share", []).append(share_text)

        area = history.get("display_area") or {}
        if full_year:
            area_fy = (area.get("periods") or {}).get(fy_key)
            if area_fy is None:
                area_fy = (area.get("periods") or {}).get("Y25F")
            area_yoy_f = (area.get("yoy_periods") or {}).get(fy_key)
            if area_yoy_f is None:
                area_yoy_f = (area.get("yoy_periods") or {}).get("Y25F")
            if area_fy is not None:
                area_text = _with_metric(
                    f"{period_label_full}出货面积{_format_number(area_fy)}㎡，同比{_format_percent(area_yoy_f)}",
                    area,
                )
                maker_result["overview"].append(area_text)
                history.setdefault("insights", {}).setdefault("display_area", []).append(area_text)
        area_yoy = (area.get("yoy_periods") or {}).get(q13_key)
        if area_yoy is None:
            area_yoy = (area.get("yoy_periods") or {}).get("Y25Q1-Q3")
        area_q13 = (area.get("periods") or {}).get(q13_key)
        if area_q13 is None:
            area_q13 = (area.get("periods") or {}).get("Y25Q1-Q3")
        if area_q13 is not None:
            history.setdefault("insights", {}).setdefault("display_area", []).append(
                _with_metric(f"{period_label_q13}出货面积{_format_number(area_q13)}㎡，同比{_format_percent(area_yoy)}", area)
            )

        product_segments = []
        for row in size_rows:
            maker_metric = ((row.get("makers") or {}).get(maker) or {})
            contribution = (
                maker_metric.get("growth_contribution")
                if maker_metric.get("growth_contribution") is not None
                else maker_metric.get("growth_contribution_2025_vs_2024")
            )
            yoy = maker_metric.get("yoy") if maker_metric.get("yoy") is not None else maker_metric.get("yoy_2025_vs_2024")
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
                maker_value = (maker_metric.get("values") or {}).get(year_key)
                if maker_value is None:
                    maker_value = (maker_metric.get("values") or {}).get("2025")
                boe_value = (boe_metric.get("values") or {}).get(year_key)
                if boe_value is None:
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
            current = periods.get(fy_key) if full_year else periods.get(q13_key)
            if current is None and full_year:
                current = periods.get("Y25F") if periods.get("Y25F") is not None else periods.get(q13_key)
            if current is None:
                current = periods.get("Y25Q1-Q3")
            if current is not None:
                yoy_periods = (value or {}).get("yoy_periods") or {}
                yoy = yoy_periods.get(fy_key if full_year else q13_key)
                if yoy is None:
                    yoy = yoy_periods.get("Y25F" if full_year else "Y25Q1-Q3")
                technology_items.append((technology, current, yoy))
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
        clients = [
            item for item in clients
            if ((item.get("periods") or {}).get(primary_period)
                if (item.get("periods") or {}).get(primary_period) is not None
                else ((item.get("periods") or {}).get(q13_key)
                      if (item.get("periods") or {}).get(q13_key) is not None
                      else (item.get("periods") or {}).get("Y25Q1-Q3"))) is not None
        ]
        if clients:
            def _client_primary_qty(item):
                periods = item.get("periods") or {}
                if full_year and periods.get(fy_key) is not None:
                    return periods.get(fy_key)
                if full_year and periods.get("Y25F") is not None:
                    return periods.get("Y25F")
                if periods.get(q13_key) is not None:
                    return periods.get(q13_key)
                return periods.get("Y25Q1-Q3")

            top_client = max(clients, key=_client_primary_qty)
            text = (
                f"第一大客户{top_client.get('client')}出货"
                f"{_format_number(_client_primary_qty(top_client))}K"
            )
            text = _with_metric(text, top_client)
            maker_result["customer_region"]["insights"].append(text)
            maker_result["drivers"]["customer"].append(text)
            customer.setdefault("insights", {}).setdefault("top_clients", []).append(text)
            contribution_clients = [
                item for item in clients
                if (item.get("growth_contribution_primary")
                    if item.get("growth_contribution_primary") is not None
                    else item.get("growth_contribution_y25_q1_q3")) is not None
            ]
            if contribution_clients:
                driver = max(
                    contribution_clients,
                    key=lambda item: (
                        item.get("growth_contribution_primary")
                        if item.get("growth_contribution_primary") is not None
                        else item.get("growth_contribution_y25_q1_q3")
                    ),
                )
                share = (
                    driver.get("share_primary")
                    if driver.get("share_primary") is not None
                    else driver.get("share_y25_q1_q3")
                )
                growth = (
                    driver.get("growth_contribution_primary")
                    if driver.get("growth_contribution_primary") is not None
                    else driver.get("growth_contribution_y25_q1_q3")
                )
                period_label = period_label_full if full_year else period_label_q13
                driver_text = (
                    f"{driver.get('client')}占{period_label}出货{_format_percent(share)}，"
                    f"份额变化{_format_points(driver.get('share_change_points'))}个百分点，"
                    f"增长贡献{_format_percent(growth)}"
                )
                driver_text = _with_metric(driver_text, driver)
                maker_result["customer_region"]["insights"].append(driver_text)
                maker_result["drivers"]["customer"].append(driver_text)
                customer.setdefault("insights", {}).setdefault("top_clients", []).append(driver_text)
        regions = ((customer.get("regions") or {}).get("rows") or [])
        annual_key = yy
        if full_year:
            regions = [
                item for item in regions
                if item.get("region") != "其他"
                and (((item.get("full_year") or {}).get(annual_key)) is not None
                     or ((item.get("full_year") or {}).get("Y25")) is not None
                     or ((item.get("q1_q3") or {}).get(q13_key)) is not None
                     or ((item.get("q1_q3") or {}).get("Y25Q1-Q3")) is not None)
            ]
        else:
            regions = [
                item for item in regions
                if item.get("region") != "其他"
                and (((item.get("q1_q3") or {}).get(q13_key)) is not None
                     or ((item.get("q1_q3") or {}).get("Y25Q1-Q3")) is not None)
            ]
        if regions:
            def _region_primary_qty(item):
                fy_block = item.get("full_year") or {}
                q_block = item.get("q1_q3") or {}
                if full_year and fy_block.get(annual_key) is not None:
                    return fy_block.get(annual_key)
                if full_year and fy_block.get("Y25") is not None:
                    return fy_block.get("Y25")
                if q_block.get(q13_key) is not None:
                    return q_block.get(q13_key)
                return q_block.get("Y25Q1-Q3")

            top_region = max(regions, key=_region_primary_qty)
            fy = top_region.get("full_year") or {}
            q_block = top_region.get("q1_q3") or {}
            if full_year and (fy.get(annual_key) is not None or fy.get("Y25") is not None):
                qty = fy.get(annual_key) if fy.get(annual_key) is not None else fy.get("Y25")
                yoy_r = fy.get(f"yoy_{current_year}_vs_{prior_year}")
                if yoy_r is None:
                    yoy_r = fy.get("yoy_2025_vs_2024")
                text = (
                    f"{top_region.get('region')}区域全年出货{_format_number(qty)}K，"
                    f"同比{_format_percent(yoy_r)}"
                )
            else:
                qty = q_block.get(q13_key) if q_block.get(q13_key) is not None else q_block.get("Y25Q1-Q3")
                yoy_r = q_block.get(f"yoy_{current_year}_vs_{prior_year}")
                if yoy_r is None:
                    yoy_r = q_block.get("yoy_2025_vs_2024")
                text = (
                    f"{top_region.get('region')}区域出货{_format_number(qty)}K，"
                    f"同比{_format_percent(yoy_r)}"
                )
            text = _with_metric(text, top_region)
            maker_result["customer_region"]["insights"].append(text)
            maker_result["drivers"]["customer"].append(text)
            customer.setdefault("insights", {}).setdefault("regions", []).append(text)

        applications = ((application.get("application_history") or {}).get("series") or [])
        applications = [
            item for item in applications
            if ((item.get("periods") or {}).get(primary_period)
                if (item.get("periods") or {}).get(primary_period) is not None
                else ((item.get("periods") or {}).get(q13_key)
                      if (item.get("periods") or {}).get(q13_key) is not None
                      else (item.get("periods") or {}).get("Y25Q1-Q3"))) is not None
        ]
        if applications:
            def _app_primary_qty(item):
                periods = item.get("periods") or {}
                if full_year and periods.get(fy_key) is not None:
                    return periods.get(fy_key)
                if full_year and periods.get("Y25F") is not None:
                    return periods.get("Y25F")
                if periods.get(q13_key) is not None:
                    return periods.get(q13_key)
                return periods.get("Y25Q1-Q3")

            top_application = max(applications, key=_app_primary_qty)
            yoy_key = fy_key if full_year else q13_key
            app_yoy = (top_application.get("yoy_periods") or {}).get(yoy_key)
            if app_yoy is None:
                app_yoy = (top_application.get("yoy_periods") or {}).get("Y25F" if full_year else "Y25Q1-Q3")
            text = (
                f"{top_application.get('application')}出货{_format_number(_app_primary_qty(top_application))}K，"
                f"同比{_format_percent(app_yoy)}"
            )
            text = _with_metric(text, top_application)
            maker_result["application"]["insights"].append(text)
            maker_result["drivers"]["application"].append(text)
            application.setdefault("insights", {}).setdefault("application_history", []).append(text)
            contribution_apps = [
                item for item in applications
                if (item.get("growth_contribution_primary")
                    if item.get("growth_contribution_primary") is not None
                    else item.get("growth_contribution_y25_q1_q3")) is not None
            ]
            if contribution_apps:
                driver = max(
                    contribution_apps,
                    key=lambda item: (
                        item.get("growth_contribution_primary")
                        if item.get("growth_contribution_primary") is not None
                        else item.get("growth_contribution_y25_q1_q3")
                    ),
                )
                area = driver.get("display_area") or {}
                share = (
                    driver.get("share_primary")
                    if driver.get("share_primary") is not None
                    else driver.get("share_y25_q1_q3")
                )
                growth = (
                    driver.get("growth_contribution_primary")
                    if driver.get("growth_contribution_primary") is not None
                    else driver.get("growth_contribution_y25_q1_q3")
                )
                period_label = period_label_full if full_year else period_label_q13
                driver_text = (
                    f"{driver.get('application')}占{period_label}出货{_format_percent(share)}，"
                    f"增长贡献{_format_percent(growth)}"
                )
                area_share = (
                    area.get("share_primary")
                    if area.get("share_primary") is not None
                    else area.get("share_y25_q1_q3")
                )
                if area_share is not None:
                    driver_text += f"，面积占比{_format_percent(area_share)}"
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


def _attach_driver_narratives(report: dict[str, Any]) -> dict[str, Any]:
    """Always attach终稿-style driver essays from deterministic metrics."""
    result = report
    metrics = result.get("computed_metrics") or {}
    full_year = bool(
        ((result.get("methodology") or {}).get("scope") or {}).get("full_year")
        or ((result.get("methodology") or {}).get("scope") or {}).get("report_horizon")
        == HORIZON_FULL_YEAR
    )
    size_rows = [
        row for row in (((metrics.get("summary_matrix") or {}).get("rows") or []))
        if not row.get("is_total")
    ]
    maker_lookup = {item.get("maker"): item for item in result.get("makers") or []}
    boe_sections = (result.get("maker_sections") or {}).get("BOE") or {}
    for maker, maker_result in maker_lookup.items():
        if not maker or not maker_result:
            continue
        sections = (result.get("maker_sections") or {}).get(maker) or {}
        product = sections.get("product") or {}
        customer = sections.get("customer") or {}
        application = sections.get("application") or {}
        scope_drv = ((result.get("methodology") or {}).get("scope") or {})
        cy_drv = int(scope_drv.get("current_year") or 2025)
        essays = build_driver_narratives(
            maker,
            full_year=full_year,
            size_rows=size_rows,
            product=product,
            customer=customer,
            application=application,
            boe_customer=boe_sections.get("customer") if maker != "BOE" else None,
            boe_application=boe_sections.get("application") if maker != "BOE" else None,
            boe_product=boe_sections.get("product") if maker != "BOE" else None,
            current_year=cy_drv,
            fy_key=scope_drv.get("full_year_period") or full_year_period(cy_drv),
            q13_key=scope_drv.get("q1_q3_period") or q1_q3_period(cy_drv),
        )
        maker_result["driver_narratives"] = essays
        for key, title in (
            ("product", "驱动力一（产品）"),
            ("customer", "驱动力二（客户）"),
            ("application", "驱动力三（应用）"),
        ):
            body = (essays.get(key) or "").strip()
            if not body:
                continue
            titled = f"{title}：{body}"
            existing = list((maker_result.get("drivers") or {}).get(key) or [])
            # Avoid duplicating if finalize runs after rule populate twice.
            existing = [item for item in existing if not str(item).startswith(title)]
            maker_result.setdefault("drivers", {})[key] = [titled] + existing
            section = {"product": product, "customer": customer, "application": application}[key]
            bucket = section.setdefault("insights", {}).setdefault("driver_narrative", [])
            if titled not in bucket:
                bucket.append(titled)
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
