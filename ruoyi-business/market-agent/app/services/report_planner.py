from __future__ import annotations

from copy import deepcopy
from typing import Any
import json
import re

from app.core.llm import LLMClient
from app.services.storage import processed_base
from app.services.period_service import normalize_period
from app.services.market_components import (
    COMPONENT_LABELS,
    SEGMENT_LABELS,
    MarketComponentEngine,
    component_label,
    is_line_chart_component,
    line_chart_component_id,
)
from app.services.core_indicators import (
    normalize_indicator_specs,
    normalize_summary_rows,
    validate_indicator_specs,
    validate_matrix_column_specs,
    validate_summary_rows,
)


DEFAULT_OBSERVATION: dict[str, Any] = {
    "id": "market_observation_1",
    "custom_title": "",
    "auto_title": False,
    # v17: 1.1 is only a report container.  A newly created observation must
    # never smuggle a fixed US/November/table/chart template into the report.
    "components": [],
    "summary_segments": [],
    # v21: every dataset can define its own measures, aggregation and filters.
    # summary_segments remains only for migration of plans saved by v14-v20.
    "core_indicators": [],
    "market_summary_rows": [],
    "market_summary_columns": [],
    "component_options": {},
    # 用户可直接写入的正文补充，预览和导出均原样保留。
    "manual_content": "",
    "lookback_months": 12,
    "filters": {},
    "analysis_period": {},
}

DEFAULT_REPORT_PLAN: dict[str, Any] = {
    "version": 5,
    "revision": 0,
    "mode": "custom",
    "active_observation_id": "",
    "market_observations": [],
    # Compatibility projection for older clients. It mirrors the active
    # observation and is empty while 1.1 has not been configured by dialogue.
    "market_observation": {},
    "other_sections": {
        "macro_policy": True,
        "personnel": True,
        "strategy": True,
        "industry_chain": True,
        "competition": True,
    },
    "last_instruction": "",
    "planner_source": "empty",
    "last_change_set": {"added": [], "removed": [], "preserved": [], "operations": []},
}

REPORT_PLANNER_SYSTEM_PROMPT = """你是汽车行业报告编排器。你的任务不是计算数字，而是把用户要求转换成结构化报告计划。
报告中的市场观察不是固定的1.1模板，可以新建、追加、删除或修改；一个报告可以有零个、一个或多个市场观察。
只能从supported_components中选择数据组件；具体折线图使用line_chart:开头的组件ID。所有数值和图表均由Python计算。
同一句话可以包含多个组件操作，必须输出operations数组；删除和添加不得合并成一个全局动作。
数据范围筛选功能已取消，filters必须始终输出空对象；analysis_period只使用period_mode/start_period/end_period/year。
只有用户明确点名表格/图表/排名/组件时才能加入components，创建市场观察本身不得自动加入任何默认组件。
用户指令中的地区、品牌、车型等词只能作为标题或分析重点，不得转换为数据过滤条件。
输出严格JSON，不要Markdown。"""

def _path(dataset_id: str):
    return processed_base(dataset_id) / "report_plan.json"


def _valid_component_id(value: Any) -> bool:
    cid = str(value or "")
    return cid in COMPONENT_LABELS or is_line_chart_component(cid)


def _normalize_period_config(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    mode = str(value.get("period_mode") or value.get("mode") or "").strip()
    if mode not in {"latest", "single", "range", "year"}:
        return {}
    out: dict[str, Any] = {"period_mode": mode}
    for key in ["start_period", "end_period"]:
        raw = str(value.get(key) or "").strip()
        if re.fullmatch(r"\d{4}-\d{2}", raw):
            out[key] = raw
    if value.get("year") is not None:
        try:
            out["year"] = int(value["year"])
        except Exception:
            pass
    return out


def _normalize_component_options(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    out: dict[str, Any] = {}
    power_chart_id = line_chart_component_id("动力类型月度结构占比")
    for component_id, raw_options in value.items():
        cid = str(component_id or "")
        if not isinstance(raw_options, dict):
            continue
        if cid == "market_summary":
            raw_segment_mappings = raw_options.get("segment_mappings") or {}
            segment_mappings: dict[str, dict[str, str]] = {}
            allowed_by_dimension = {
                "market": {"passenger_vehicle", "commercial_vehicle", "unclassified"},
                "vehicle_type": {"passenger_vehicle", "commercial_vehicle", "unclassified"},
                "power_type": {"new_energy_vehicle", "ice_vehicle", "unclassified"},
            }
            if isinstance(raw_segment_mappings, dict):
                for dimension, raw_mapping in raw_segment_mappings.items():
                    dim = str(dimension or "")
                    if dim not in allowed_by_dimension or not isinstance(raw_mapping, dict):
                        continue
                    cleaned = {
                        str(source)[:120]: str(target)
                        for source, target in list(raw_mapping.items())[:500]
                        if str(source).strip() and str(target) in allowed_by_dimension[dim]
                    }
                    if cleaned:
                        segment_mappings[dim] = cleaned
            out[cid] = {"segment_mappings": segment_mappings}
            continue
        if cid != power_chart_id:
            continue
        series = list(dict.fromkeys(
            str(item) for item in (raw_options.get("series") or [])
            if str(item) in {"new_energy", "fuel"}
        )) or ["new_energy", "fuel"]
        raw_mapping = raw_options.get("power_type_mapping") or {}
        mapping = {
            str(source)[:120]: str(group)
            for source, group in raw_mapping.items()
            if str(source).strip() and str(group) in {"new_energy", "fuel", "unclassified"}
        } if isinstance(raw_mapping, dict) else {}
        out[cid] = {"series": series, "power_type_mapping": mapping}
    return out


def _normalize_observation(value: Any, index: int = 1) -> dict[str, Any]:
    src = value if isinstance(value, dict) else {}
    out = deepcopy(DEFAULT_OBSERVATION)
    raw_id = str(src.get("id") or f"market_observation_{index}")
    out["id"] = re.sub(r"[^A-Za-z0-9_-]+", "_", raw_id).strip("_") or f"market_observation_{index}"
    out["custom_title"] = str(src.get("custom_title") or src.get("title") or "").strip()
    out["auto_title"] = bool(src.get("auto_title", False))
    if "components" in src:
        out["components"] = list(dict.fromkeys(str(x) for x in (src.get("components") or []) if _valid_component_id(x)))
    if "summary_segments" in src:
        out["summary_segments"] = list(dict.fromkeys(str(x) for x in (src.get("summary_segments") or []) if str(x) in SEGMENT_LABELS))
    out["core_indicators"] = normalize_indicator_specs(src.get("core_indicators"))
    out["market_summary_rows"] = normalize_summary_rows(src.get("market_summary_rows"))
    out["market_summary_columns"] = normalize_indicator_specs(src.get("market_summary_columns"))
    out["component_options"] = _normalize_component_options(src.get("component_options"))
    if out["market_summary_rows"] and out["market_summary_columns"]:
        out["core_indicators"] = []
        out["summary_segments"] = []
    elif out["core_indicators"]:
        out["summary_segments"] = []
    # 历史版本的 focus 来自已移除的“分析生成要求”。必须在读取时清空，
    # 防止旧配置继续以不可见方式影响后续的预览和导出。
    out["manual_content"] = str(src.get("manual_content") or "").strip()
    try:
        out["lookback_months"] = max(2, min(36, int(src.get("lookback_months") or 12)))
    except Exception:
        pass
    # v20 removes report-scope filters. Always clear historical saved values so
    # an invisible legacy filter can never keep affecting future exports.
    out["filters"] = {}
    out["analysis_period"] = _normalize_period_config(src.get("analysis_period"))
    return out


def normalize_report_plan(plan: dict[str, Any] | None) -> dict[str, Any]:
    src = plan if isinstance(plan, dict) else {}
    out = deepcopy(DEFAULT_REPORT_PLAN)
    out["version"] = 5
    try:
        out["revision"] = max(0, int(src.get("revision") or 0))
    except Exception:
        out["revision"] = 0
    out["mode"] = str(src.get("mode") or out["mode"])
    if "market_observations" in src:
        raw_observations = src.get("market_observations") or []
    elif isinstance(src.get("market_observation"), dict):
        raw_observations = [src.get("market_observation")]
    else:
        raw_observations = []
    observations = [_normalize_observation(x, i + 1) for i, x in enumerate(raw_observations) if isinstance(x, dict)]
    seen: set[str] = set()
    for obs in observations:
        base = obs["id"]
        suffix = 2
        while obs["id"] in seen:
            obs["id"] = f"{base}_{suffix}"
            suffix += 1
        seen.add(obs["id"])
    out["market_observations"] = observations
    requested_active = str(src.get("active_observation_id") or "")
    if observations:
        active = next((x for x in observations if x["id"] == requested_active), observations[-1])
        out["active_observation_id"] = active["id"]
        out["market_observation"] = deepcopy(active)
    else:
        out["active_observation_id"] = ""
        out["market_observation"] = {}
    other = src.get("other_sections") or {}
    if isinstance(other, dict):
        for key in out["other_sections"]:
            if key in other:
                out["other_sections"][key] = bool(other[key])
    out["last_instruction"] = str(src.get("last_instruction") or "")
    out["planner_source"] = str(src.get("planner_source") or out["planner_source"])
    change_set = src.get("last_change_set") or {}
    if isinstance(change_set, dict):
        out["last_change_set"] = {
            "added": [str(x) for x in (change_set.get("added") or [])],
            "removed": [str(x) for x in (change_set.get("removed") or [])],
            "preserved": [str(x) for x in (change_set.get("preserved") or [])],
            "operations": [dict(x) for x in (change_set.get("operations") or []) if isinstance(x, dict)],
        }
    return out


def load_report_plan(dataset_id: str) -> dict[str, Any]:
    path = _path(dataset_id)
    if not path.exists():
        return normalize_report_plan(None)
    try:
        return normalize_report_plan(json.loads(path.read_text(encoding="utf-8")))
    except Exception:
        return normalize_report_plan(None)


def save_report_plan(dataset_id: str, plan: dict[str, Any]) -> dict[str, Any]:
    base = processed_base(dataset_id)
    if not (base / "data.csv").exists():
        raise FileNotFoundError(f"dataset_id 不存在：{dataset_id}")
    out = normalize_report_plan(plan)
    _path(dataset_id).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return out


def reset_report_plan(dataset_id: str) -> dict[str, Any]:
    """Clear the conversation-composed 1.1 section without restoring content."""
    current = load_report_plan(dataset_id)
    out = deepcopy(DEFAULT_REPORT_PLAN)
    out["revision"] = int(current.get("revision") or 0) + 1
    out["last_instruction"] = "清空1.1市场观察编排"
    out["planner_source"] = "manual_clear"
    removed = [component for obs in current.get("market_observations", []) for component in obs.get("components", [])]
    out["last_change_set"] = {
        "added": [],
        "removed": list(dict.fromkeys(removed)),
        "preserved": [],
        "operations": [{"action": "remove_all", "component_id": "market_observations"}],
    }
    return save_report_plan(dataset_id, out)


def save_visual_report_plan(
    dataset_id: str,
    df,
    *,
    observation_id: str | None = None,
    observation_title: str = "",
    component_ids: list[str] | None = None,
    summary_segments: list[str] | None = None,
    core_indicators: list[dict[str, Any]] | None = None,
    market_summary_rows: list[dict[str, Any]] | None = None,
    market_summary_columns: list[dict[str, Any]] | None = None,
    component_options: dict[str, Any] | None = None,
    manual_content: str = "",
    lookback_months: int = 12,
    filters: dict[str, list[str]] | None = None,
    analysis_period: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """保存可视化编辑器中的一个市场观察，并严格校验当前数据能力。

    这个入口与自然语言编排共用同一份 report_plan.json。组件 ID 的顺序
    就是页面预览和所有导出格式的输出顺序，不再经过 report_config 二次推断。
    """
    current = load_report_plan(dataset_id)
    engine = MarketComponentEngine(df, analysis_period or {})
    capabilities = engine.capabilities()
    supported = capabilities.get("supported_components") or {}
    components = list(dict.fromkeys(str(x).strip() for x in (component_ids or []) if str(x).strip()))
    if not components:
        raise ValueError("1.1 市场观察至少需要选择一个表格或图表组件")
    unavailable = [component for component in components if component not in supported]
    if unavailable:
        names = "、".join(unavailable)
        raise ValueError(f"当前数据或周期无法生成这些组件：{names}")

    segments = list(dict.fromkeys(str(x) for x in (summary_segments or []) if str(x) in SEGMENT_LABELS))
    indicators = validate_indicator_specs(core_indicators or [], capabilities.get("indicator_capabilities") or {})
    matrix_rows = validate_summary_rows(market_summary_rows or [], capabilities.get("indicator_capabilities") or {})
    matrix_columns = validate_matrix_column_specs(market_summary_columns or [], capabilities.get("indicator_capabilities") or {})
    if bool(matrix_rows) != bool(matrix_columns):
        raise ValueError("二维市场概览的报告行和指标列必须同时配置")
    normalized_component_options = _normalize_component_options(component_options)
    normalized_component_options = {
        key: value for key, value in normalized_component_options.items() if key in components
    }
    market_options = normalized_component_options.get("market_summary") or {}
    segment_mappings = market_options.get("segment_mappings") or {}
    segment_capabilities, _ = engine.segment_capabilities(segment_mappings)
    capability_by_segment = {item["id"]: item for item in segment_capabilities}
    unavailable_rows = [
        row for row in matrix_rows
        if row.get("segment") != "custom"
        and not capability_by_segment.get(str(row.get("segment")), {}).get("available")
    ]
    if unavailable_rows:
        details = "、".join(
            f"{row.get('display_name')}（{capability_by_segment.get(str(row.get('segment')), {}).get('reason') or '当前Excel不支持该口径'}）"
            for row in unavailable_rows
        )
        raise ValueError(f"存在无法由当前Excel计算的统计口径：{details}")
    if "market_summary" not in components:
        segments = []
        indicators = []
        matrix_rows = []
        matrix_columns = []
    elif matrix_rows and matrix_columns:
        segments = []
        indicators = []
    elif indicators:
        segments = []
    requested_title = re.sub(r"^\s*1\.\d+\s*", "", str(observation_title or "")).strip()
    title = requested_title
    period_info = capabilities.get("period") or {}
    end_period = str(period_info.get("end_period") or "")
    title_month = re.search(r"(?<!\d)(1[0-2]|0?[1-9])月", requested_title)
    if title_month and re.fullmatch(r"\d{4}-\d{2}", end_period):
        expected_month = int(end_period[-2:])
        if int(title_month.group(1)) != expected_month:
            raise ValueError(f"1.1 标题中的{int(title_month.group(1))}月与当前分析周期{end_period}不一致")
    title_year = re.search(r"(20\d{2})年", requested_title)
    if title_year and re.fullmatch(r"\d{4}-\d{2}", end_period) and int(title_year.group(1)) != int(end_period[:4]):
        raise ValueError(f"1.1 标题中的{title_year.group(1)}年与当前分析周期{end_period}不一致")
    if not title:
        period_label = str(period_info.get("display_label") or "当前周期")
        region = str(capabilities.get("region") or "").strip()
        title = f"{region}{period_label}市场观察" if region else f"{period_label}市场观察"

    observations = deepcopy(current.get("market_observations") or [])
    requested_id = str(observation_id or current.get("active_observation_id") or "").strip()
    target_index = next((i for i, item in enumerate(observations) if item.get("id") == requested_id), None)
    if target_index is None and observations:
        target_index = 0
        requested_id = str(observations[0].get("id") or "market_observation_1")
    if target_index is None:
        requested_id = requested_id or "market_observation_1"

    observation = _normalize_observation({
        "id": requested_id,
        "custom_title": title,
        "auto_title": not bool(requested_title),
        "components": components,
        "summary_segments": segments,
        "core_indicators": indicators,
        "market_summary_rows": matrix_rows,
        "market_summary_columns": matrix_columns,
        "component_options": normalized_component_options,
        "manual_content": manual_content,
        "lookback_months": lookback_months,
        "filters": {},
        "analysis_period": analysis_period or {},
    }, (target_index or 0) + 1)
    previous_components = [] if target_index is None else list(observations[target_index].get("components") or [])
    if target_index is None:
        observations.append(observation)
    else:
        observations[target_index] = observation

    added = [x for x in components if x not in previous_components]
    removed = [x for x in previous_components if x not in components]
    preserved = [x for x in components if x in previous_components]
    updated = deepcopy(current)
    updated.update({
        "mode": "custom",
        "revision": int(current.get("revision") or 0) + 1,
        "active_observation_id": observation["id"],
        "market_observations": observations,
        "last_instruction": "通过可视化编排器保存市场观察",
        "planner_source": "visual_composer",
        "last_change_set": {
            "added": added,
            "removed": removed,
            "preserved": preserved,
            "operations": [{"action": "visual_replace", "observation_id": observation["id"], "components": components}],
        },
    })
    return save_report_plan(dataset_id, updated)


def _extract_json(text: str | None) -> dict[str, Any] | None:
    if not text or text.startswith("[LLM调用失败"):
        return None
    match = re.search(r"\{.*\}", text, re.S)
    if not match:
        return None
    try:
        return json.loads(match.group(0))
    except Exception:
        return None


def _available_chart_titles(capabilities: dict[str, Any] | None) -> list[str]:
    return [str(x.get("title")) for x in ((capabilities or {}).get("available_line_charts") or []) if x.get("title")]


def _match_chart_titles(text: str, capabilities: dict[str, Any] | None) -> list[str]:
    q = text.lower()
    titles = _available_chart_titles(capabilities)
    exact = [title for title in titles if title.lower() in q]
    if exact:
        return exact
    aliases = [
        (["新能源", "占有率"], "新能源汽车市场占有率"),
        (["新能源", "市占"], "新能源汽车市场占有率"),
        (["核心", "同比"], "核心指标同比增速"),
        (["动力", "月度", "结构"], "动力类型月度结构占比"),
        (["动力", "月度", "占比"], "动力类型月度结构占比"),
        (["核心", "月度", "趋势"], "核心指标月度趋势"),
        (["车型", "top", "趋势"], "Top车型月度销量趋势"),
    ]
    matches: list[str] = []
    for tokens, preferred in aliases:
        if all(token in q for token in tokens):
            matches.extend(title for title in titles if title == preferred or preferred in title)
    if matches:
        return list(dict.fromkeys(matches))
    scored: list[tuple[int, str]] = []
    for title in titles:
        tokens = [x for x in ["新能源", "同比", "动力", "车型", "月度", "结构", "占比", "趋势"] if x in title]
        score = sum(1 for token in tokens if token in q)
        if score >= 2:
            scored.append((score, title))
    if not scored:
        return []
    best = max(score for score, _ in scored)
    return [title for score, title in scored if score == best]


def _extract_period(text: str, capabilities: dict[str, Any] | None) -> dict[str, Any]:
    q = text.replace("—", "-").replace("－", "-")
    available = [str(x) for x in ((capabilities or {}).get("available_periods") or [])]
    range_match = re.search(r"(20\d{2})年\s*(\d{1,2})月\s*(?:至|到|[-~～])\s*(?:(20\d{2})年\s*)?(\d{1,2})月", q)
    if range_match:
        y1, m1, y2, m2 = range_match.groups()
        return {"period_mode": "range", "start_period": f"{int(y1):04d}-{int(m1):02d}", "end_period": f"{int(y2 or y1):04d}-{int(m2):02d}"}
    month_match = re.search(r"(20\d{2})[年./-]\s*(\d{1,2})月?", q)
    if month_match:
        year, month = map(int, month_match.groups())
        return {"period_mode": "single", "start_period": f"{year:04d}-{month:02d}", "end_period": f"{year:04d}-{month:02d}"}
    year_match = re.search(r"(20\d{2})年\s*(?:全年|年度)", q)
    if year_match:
        return {"period_mode": "year", "year": int(year_match.group(1))}
    month_only = re.search(r"(?<!\d)(1[0-2]|0?[1-9])月", q)
    if month_only:
        month = int(month_only.group(1))
        candidates = [p for p in available if re.fullmatch(r"\d{4}-\d{2}", p) and int(p[-2:]) == month]
        if candidates:
            selected = sorted(candidates)[-1]
            return {"period_mode": "single", "start_period": selected, "end_period": selected}
    return {}


def _extract_explicit_title(text: str) -> tuple[str, str]:
    """提取“标题为/名称设为”一类显式标题，并从语义指令中剔除该片段。

    标题里的“新能源、品牌”等词只是名称，不应被误判为组件要求。
    """
    pattern = re.compile(
        r"(?:章节标题|标题|题目|名称)\s*(?:改为|修改为|改成|更改为|为|是|设为|定为|[:：])\s*"
        r"[“\"'‘]?([^，。；;\n]{2,80}?)[”\"'’]?"
        r"(?=(?:，|。|；|;|\n|并(?:加入|添加|放入)|然后|$))"
    )
    match = pattern.search(text)
    if not match:
        return "", text
    title = match.group(1).strip(" ：:，。；;“”\"'‘’")
    semantic_text = (text[: match.start()] + " " + text[match.end() :]).strip()
    return title, semantic_text


REMOVE_WORDS = ("不要", "移除", "删除", "去掉", "取消", "排除")
ADD_WORDS = ("加入", "添加", "增加", "放入", "插入", "加上", "放到", "加到", "加回来", "恢复")
REPLACE_WORDS = ("只要", "只保留", "仅保留", "全部替换为", "替换成")
CURRENT_COMPONENT_WORDS = ("这张图", "当前图", "该图", "选中的图", "选择的图", "这个图表", "当前组件")


def _split_action_clauses(text: str) -> list[str]:
    normalized = re.sub(
        r"并(?=(?:将|把)?[^，。；;\n]{0,80}(?:加入|添加|增加|放入|插入|加上|放到|加到))",
        "；",
        text,
    )
    parts = re.split(r"[，,。；;、\n]+|(?:然后|随后|接着|并且|同时|再(?=[把将]))", normalized)
    return [x.strip() for x in parts if x and x.strip()]


def _supported_component_ids(capabilities: dict[str, Any] | None) -> set[str]:
    configured = {str(x) for x in ((capabilities or {}).get("supported_components") or {})}
    return configured or set(COMPONENT_LABELS)


def _component_ids_from_clause(
    clause: str,
    capabilities: dict[str, Any] | None,
    selected_component_id: str | None = None,
    selected_chart_title: str | None = None,
) -> list[str]:
    q = clause.lower()
    supported = _supported_component_ids(capabilities)
    found: list[str] = []
    refers_to_current = any(x in q for x in CURRENT_COMPONENT_WORDS)
    if refers_to_current:
        if selected_component_id and selected_component_id in supported:
            found.append(selected_component_id)
        elif selected_chart_title:
            legacy_id = line_chart_component_id(selected_chart_title)
            if legacy_id in supported:
                found.append(legacy_id)

    # Exact dashboard chart titles win over fuzzy static-component aliases.
    # Fuzzy line-chart matching is only allowed when the clause itself carries
    # chart/time context; otherwise "动力类型结构占比" must remain the static
    # table+pie component instead of being expanded to the monthly line chart.
    available_titles = _available_chart_titles(capabilities)
    dynamic_context = any(x in q for x in ["图", "曲线", "月度", "这张图", "当前图", "该图"]) or any(
        title.lower() in q for title in available_titles
    )
    if dynamic_context:
        for title in _match_chart_titles(clause, capabilities):
            component_id = line_chart_component_id(title)
            if component_id in supported:
                found.append(component_id)

    static_matches = [
        ("market_summary", ["市场核心指标表", "市场观察表", "核心指标表"]),
        ("oem_top10", ["oem销量排名top10", "oem排名", "ome销量排名", "ome排名", "主机厂排名", "车企排名", "厂商排名"]),
        ("brand_top10", ["品牌销量排名top10", "品牌排名"]),
        ("model_top10", ["车型销量排名top10", "车型排名"]),
        ("market_top10", ["市场/车种销量排名top10", "市场排名", "车种排名"]),
        ("monthly_trend", ["核心指标月度趋势", "月度销量趋势", "销量月度趋势"]),
        ("nev_share_trend", ["新能源占有率", "新能源市占", "新能源渗透率", "新能源销量占有率"]),
        ("nev_vs_ice_yoy", ["新能源/燃油同比", "新能源车与燃油车销量同比变化"]),
        ("all_line_charts", ["全部折线图", "所有折线图", "全部趋势图", "所有趋势图"]),
    ]
    for component_id, aliases in static_matches:
        if component_id in supported and any(alias in q for alias in aliases):
            found.append(component_id)

    # Segment names describe rows inside the market-summary component.  They
    # create that component only when the user explicitly asks to put those
    # rows into the report; merely naming an observation "新能源市场观察" must
    # not inject a default table.
    if "market_summary" in supported and any(word in q for word in ADD_WORDS) and any(
        token in q for token in ["总体市场", "总市场", "整体市场", "大盘", "乘用车", "商用车", "新能源车指标", "燃油车指标"]
    ):
        found.append("market_summary")

    monthly_power = any(x in q for x in ["动力类型月度结构占比", "动力月度结构占比", "动力类型月度占比"])
    if not monthly_power and "power_structure" in supported and any(
        x in q for x in ["动力类型结构占比", "动力结构占比", "动力类型结构", "动力结构"]
    ):
        found.append("power_structure")
    unique = list(dict.fromkeys(found))
    alias_lookup = {component_id: aliases for component_id, aliases in static_matches}

    def mention_position(component_id: str) -> int:
        terms = [component_label(component_id).lower()] + alias_lookup.get(component_id, [])
        positions = [q.find(term) for term in terms if term and q.find(term) >= 0]
        if component_id == selected_component_id and refers_to_current:
            positions.extend(q.find(term) for term in CURRENT_COMPONENT_WORDS if q.find(term) >= 0)
        return min(positions) if positions else len(q) + unique.index(component_id)

    return sorted(unique, key=mention_position)


def _component_operations(
    text: str,
    capabilities: dict[str, Any] | None,
    selected_component_id: str | None = None,
    selected_chart_title: str | None = None,
) -> list[dict[str, str]]:
    operations: list[dict[str, str]] = []
    carry_action: str | None = None
    for clause in _split_action_clauses(text):
        q = clause.lower()
        ids = _component_ids_from_clause(clause, capabilities, selected_component_id, selected_chart_title)
        if not ids:
            continue
        if any(word in q for word in REPLACE_WORDS):
            action = "replace"
        elif any(word in q for word in REMOVE_WORDS):
            action = "remove"
        elif any(word in q for word in ADD_WORDS) or any(word in q for word in ["生成", "制作", "创建", "新增", "放"]):
            action = "add"
        elif carry_action:
            # "加入A、B和C" is split into several punctuation clauses.  The
            # leading verb applies to the following named dashboard items.
            action = carry_action
        else:
            continue
        carry_action = action
        operations.extend({"action": action, "component_id": component_id} for component_id in ids)
    return operations


def _rule_patch(
    text: str,
    capabilities: dict[str, Any] | None = None,
    selected_chart_title: str | None = None,
    selected_component_id: str | None = None,
) -> dict[str, Any]:
    explicit_title, semantic_text = _extract_explicit_title(text)
    q = semantic_text.lower()
    segment_text = q
    for chart_title in _available_chart_titles(capabilities):
        segment_text = segment_text.replace(chart_title.lower(), " ")
    segment_text = re.sub(
        r"新能源(?:汽车|车)?(?:销量|市场)?(?:占有率|市占率|渗透率)(?:折线图|趋势图|图)?",
        " ",
        segment_text,
    )
    segments: list[str] = []
    focus: list[str] = []
    if any(x in segment_text for x in ["总体市场", "总市场", "整体市场", "大盘"]): segments.append("total_market")
    if "乘用车" in segment_text: segments.append("passenger_vehicle")
    if "商用车" in segment_text: segments.append("commercial_vehicle")
    if any(x in segment_text for x in ["新能源车", "新能源汽车", "新能源"]): segments.append("new_energy_vehicle")
    if any(x in segment_text for x in ["燃油车指标", "燃油车销量放入", "燃油车也放", "加入燃油车"]): segments.append("ice_vehicle")
    operations = _component_operations(semantic_text, capabilities, selected_component_id, selected_chart_title)
    if "重点" in q or "重点分析" in q:
        if "新能源" in q: focus.append("新能源市场")
        if "oem" in q: focus.append("OEM竞争格局")
        if "车型" in q: focus.append("车型竞争格局")
    title = explicit_title
    if not title:
        match = re.search(r"(?:生成|做|写|制作|创建|新增)\s*(?:一个)?\s*([^，。；;\n]{2,60}市场[^，。；;\n]{0,30}观察)", semantic_text)
        if match:
            title = match.group(1).strip(" ：:")
    # Kept for v14/v15 compatibility and summary-segment initialization.
    # Component replacement itself is controlled only by explicit `replace`
    # operations produced from REPLACE_WORDS.
    replace = any(x in q for x in REPLACE_WORDS)
    create_new = any(x in q for x in ["再生成", "再创建", "新增一个", "新增市场观察", "另一个市场观察", "另加一个"])
    remove_all = any(x in q for x in ["不要市场观察", "删除市场观察章节", "移除市场观察章节", "本期不需要市场观察"])
    component_union = [x["component_id"] for x in operations]
    return {
        "replace": replace,
        "create_new": create_new,
        "remove_all": remove_all,
        "custom_title": title,
        "components": list(dict.fromkeys(component_union)),
        "operations": operations,
        "summary_segments": list(dict.fromkeys(segments)),
        "focus": focus,
        "filters": {},
        "analysis_period": _extract_period((semantic_text + " " + explicit_title).strip(), capabilities),
    }


def _llm_patch(
    text: str,
    capabilities: dict[str, Any],
    current: dict[str, Any],
    selected_chart_title: str | None = None,
    selected_component_id: str | None = None,
) -> dict[str, Any] | None:
    llm = LLMClient()
    if not llm.enabled:
        return None
    request = {
        "action": "update|create|remove_all",
        "operations": [{"action": "add|remove|replace", "component_id": "supported_components中的一个ID"}],
        "replace": "只有用户明确说只保留或全部替换时才为true",
        "custom_title": "用户指定的市场观察标题",
        "components": "只允许supported_components中的ID",
        "summary_segments": "只允许supported_segments中的ID",
        "focus": "分析重点数组",
        "filters": {},
        "analysis_period": {"period_mode": "latest|single|range|year", "start_period": "YYYY-MM", "end_period": "YYYY-MM", "year": 2025},
        "lookback_months": "2到36",
    }
    messages = [
        {"role": "system", "content": REPORT_PLANNER_SYSTEM_PROMPT},
        {"role": "user", "content": "用户指令：" + text + "\n当前选中组件ID：" + str(selected_component_id or "无") + "\n当前选中图表：" + str(selected_chart_title or "无") + "\n当前计划：" + json.dumps(current, ensure_ascii=False) + "\n能力：" + json.dumps(capabilities, ensure_ascii=False, default=str) + "\n请输出：" + json.dumps(request, ensure_ascii=False)},
    ]
    return _extract_json(llm.chat(messages, temperature=0, max_tokens=1400))


def _active_observation(plan: dict[str, Any]) -> dict[str, Any] | None:
    active_id = plan.get("active_observation_id")
    return next((x for x in plan.get("market_observations", []) if x.get("id") == active_id), None)


def _new_observation(plan: dict[str, Any]) -> dict[str, Any]:
    used = {str(x.get("id")) for x in plan.get("market_observations", [])}
    index = 1
    while f"market_observation_{index}" in used:
        index += 1
    obs = deepcopy(DEFAULT_OBSERVATION)
    obs["id"] = f"market_observation_{index}"
    plan.setdefault("market_observations", []).append(obs)
    plan["active_observation_id"] = obs["id"]
    return obs


def _merge_rule_fallback(patch: dict[str, Any], rule_patch: dict[str, Any]) -> dict[str, Any]:
    out = dict(patch)
    for key in ["components", "summary_segments", "custom_title", "focus", "analysis_period"]:
        if not out.get(key) and rule_patch.get(key):
            out[key] = rule_patch[key]
    if "replace" not in out:
        out["replace"] = rule_patch.get("replace", False)
    # Component actions deterministically parsed from explicit clauses are
    # authoritative. This prevents an LLM from turning "remove A, add B" into
    # one global negative or replacement action.
    if rule_patch.get("operations"):
        out["operations"] = rule_patch["operations"]
        out["components"] = rule_patch.get("components", [])
    action = str(out.get("action") or "").lower()
    out["create_new"] = action == "create" or bool(rule_patch.get("create_new"))
    out["remove_all"] = action == "remove_all" or bool(rule_patch.get("remove_all"))
    return out


def _validated_operations(patch: dict[str, Any], capabilities: dict[str, Any]) -> list[dict[str, str]]:
    supported = _supported_component_ids(capabilities)
    operations: list[dict[str, str]] = []
    for raw in patch.get("operations") or []:
        if not isinstance(raw, dict):
            continue
        action = str(raw.get("action") or "").lower()
        component_id = str(raw.get("component_id") or "")
        if action not in {"add", "remove", "replace"}:
            continue
        if component_id not in supported:
            raise ValueError(f"当前数据看板不存在组件：{component_id or '未指定'}；报告计划未修改。")
        operations.append({"action": action, "component_id": component_id})
    if not operations and patch.get("components"):
        action = "replace" if patch.get("replace") else "add"
        for component_id in patch.get("components") or []:
            component_id = str(component_id)
            if component_id in supported:
                operations.append({"action": action, "component_id": component_id})
    return operations


def _apply_relative_component_order(
    instruction: str,
    components: list[str],
    capabilities: dict[str, Any],
    selected_component_id: str | None = None,
    selected_chart_title: str | None = None,
) -> list[str]:
    """Handle natural orders such as “把这张图放到核心指标表后面”."""
    match = re.search(
        r"(?:把|将)?(.{1,80}?)(?:放到|移到|移动到)(.{1,80}?)(前面|后面|之前|之后)",
        instruction,
    )
    if not match:
        return components
    moving = _component_ids_from_clause(
        match.group(1), capabilities, selected_component_id, selected_chart_title,
    )
    anchors = _component_ids_from_clause(match.group(2), capabilities)
    moving = [x for x in moving if x in components]
    anchor = next((x for x in anchors if x in components and x not in moving), None)
    if not moving or not anchor:
        return components
    out = [x for x in components if x not in moving]
    anchor_index = out.index(anchor)
    insert_at = anchor_index if match.group(3) in {"前面", "之前"} else anchor_index + 1
    return out[:insert_at] + moving + out[insert_at:]


def _validate_period_request(instruction: str, patch: dict[str, Any], capabilities: dict[str, Any]) -> None:
    available = {str(x) for x in capabilities.get("available_periods") or [] if re.fullmatch(r"\d{4}-\d{2}", str(x))}
    period = _normalize_period_config(patch.get("analysis_period"))
    if period:
        mode = period.get("period_mode")
        if mode == "single":
            requested = str(period.get("start_period") or "")
            if available and requested not in available:
                raise ValueError(f"当前数据中没有{requested}数据，不能用最新月份替代；报告计划未修改。")
        elif mode == "year":
            requested_year = int(period.get("year") or 0)
            if available and not any(x.startswith(f"{requested_year:04d}-") for x in available):
                raise ValueError(f"当前数据中没有{requested_year}年数据，报告计划未修改。")
        elif mode == "range":
            start = str(period.get("start_period") or "")
            end = str(period.get("end_period") or "")
            if available and (start not in available or end not in available):
                raise ValueError(f"当前数据不完整覆盖{start}至{end}，不能静默改用其他月份；报告计划未修改。")
        return
    if not re.search(r"(?<!\d)(?:1[0-2]|0?[1-9])月", instruction):
        return
    available_list = list(available)
    requested = int(re.search(r"(?<!\d)(1[0-2]|0?[1-9])月", instruction).group(1))
    if not any(int(p[-2:]) == requested for p in available_list):
        raise ValueError(f"当前数据中没有可用的{requested}月数据，不能用最新月份替代；报告计划未修改。")


def _validate_candidate_observation(df, observation: dict[str, Any]) -> None:
    # MarketComponentEngine/MarketAnalyzer validates that the requested month,
    # range or year actually exists. No fallback to the latest period is allowed.
    observation["filters"] = {}
    engine = MarketComponentEngine(df, observation.get("analysis_period") or {})
    validate_indicator_specs(
        observation.get("core_indicators") or [],
        engine.capabilities().get("indicator_capabilities") or {},
    )


def apply_report_plan_instruction(
    dataset_id: str,
    instruction: str,
    df,
    analysis_period: dict[str, Any] | None = None,
    use_llm: bool = True,
    selected_chart_title: str | None = None,
    selected_component_id: str | None = None,
) -> tuple[dict[str, Any], list[str]]:
    plan = load_report_plan(dataset_id)
    engine = MarketComponentEngine(df, analysis_period)
    capabilities = engine.capabilities()
    capabilities["available_periods"] = sorted({
        normalized for normalized in (normalize_period(x) for x in df["time_period"].dropna().tolist()) if normalized
    }) if "time_period" in df.columns else []
    rule_patch = _rule_patch(instruction, capabilities, selected_chart_title, selected_component_id)
    llm_patch = _llm_patch(instruction, capabilities, plan, selected_chart_title, selected_component_id) if use_llm else None
    patch = _merge_rule_fallback(llm_patch, rule_patch) if llm_patch else rule_patch
    source = "llm+rules" if llm_patch and rule_patch.get("operations") else "llm" if llm_patch else "rules"
    _validate_period_request(instruction, patch, capabilities)

    if patch.get("remove_all"):
        removed = [component for obs in plan.get("market_observations", []) for component in obs.get("components", [])]
        plan["market_observations"] = []
        plan["active_observation_id"] = ""
        plan["mode"] = "custom"
        plan["revision"] = int(plan.get("revision") or 0) + 1
        plan["last_instruction"] = instruction.strip()
        plan["planner_source"] = source
        plan["last_change_set"] = {"added": [], "removed": list(dict.fromkeys(removed)), "preserved": [], "operations": [{"action": "remove_all", "component_id": "market_observations"}]}
        saved = save_report_plan(dataset_id, plan)
        return saved, ["已从本期报告中移除全部市场观察章节", f"报告计划版本：v{saved['revision']}"]

    observation = _active_observation(plan)
    if observation is None or patch.get("create_new"):
        observation = _new_observation(plan)
    plan["active_observation_id"] = observation["id"]
    before = list(observation.get("components") or [])
    operations = _validated_operations(patch, capabilities)
    component_action_requested = any(word in instruction.lower() for word in (*REMOVE_WORDS, *ADD_WORDS, *REPLACE_WORDS)) and any(
        word in instruction.lower() for word in ["图", "表", "排名", "指标", "趋势", "结构", "组件"]
    )
    if component_action_requested and not operations:
        raise ValueError("没有识别到要操作的具体看板组件，报告计划未修改；请先在看板选中组件，或使用完整图表名称。")

    replacement_ids = [x["component_id"] for x in operations if x["action"] == "replace"]
    current = list(dict.fromkeys(replacement_ids)) if replacement_ids else list(before)
    for operation in operations:
        component_id = operation["component_id"]
        if operation["action"] == "remove":
            current = [x for x in current if x != component_id]
        elif operation["action"] == "add" and component_id not in current:
            current.append(component_id)
    observation["components"] = current

    segments = [str(x) for x in (patch.get("summary_segments") or []) if str(x) in SEGMENT_LABELS]
    if patch.get("replace") and segments:
        observation["summary_segments"] = list(dict.fromkeys(segments))
    elif segments:
        observation["summary_segments"] = list(dict.fromkeys(observation.get("summary_segments", []) + segments))
    if str(patch.get("custom_title") or "").strip():
        observation["custom_title"] = str(patch["custom_title"]).strip()
    if patch.get("focus"):
        observation["focus"] = list(dict.fromkeys(str(x) for x in patch["focus"] if str(x).strip()))
    observation["filters"] = {}
    if patch.get("analysis_period"):
        observation["analysis_period"] = _normalize_period_config(patch["analysis_period"])
    if patch.get("lookback_months"):
        try:
            observation["lookback_months"] = max(2, min(36, int(patch["lookback_months"])))
        except Exception:
            pass
    q = instruction.lower()
    moving_ids = [x["component_id"] for x in operations if x["action"] != "remove"]
    if moving_ids and any(x in q for x in ["置顶", "放最前", "移到最前"]):
        moving = [x for x in observation["components"] if x in moving_ids]
        observation["components"] = moving + [x for x in observation["components"] if x not in moving]
    if moving_ids and any(x in q for x in ["放最后", "移到最后"]):
        moving = [x for x in observation["components"] if x in moving_ids]
        observation["components"] = [x for x in observation["components"] if x not in moving] + moving
    observation["components"] = _apply_relative_component_order(
        instruction,
        observation["components"],
        capabilities,
        selected_component_id,
        selected_chart_title,
    )

    # Validate the complete candidate before persisting any operation. This
    # makes mixed remove/add instructions atomic and keeps title/scope/time tied.
    _validate_candidate_observation(df, observation)
    added = [x for x in observation["components"] if x not in before]
    removed = [x for x in before if x not in observation["components"]]
    preserved = [x for x in before if x in observation["components"]]
    plan["mode"] = "custom"
    plan["revision"] = int(plan.get("revision") or 0) + 1
    plan["last_instruction"] = instruction.strip()
    plan["planner_source"] = source
    plan["last_change_set"] = {"added": added, "removed": removed, "preserved": preserved, "operations": operations}
    saved = save_report_plan(dataset_id, plan)
    active = _active_observation(saved) or observation
    index = next((i for i, x in enumerate(saved["market_observations"], 1) if x["id"] == active["id"]), 1)
    changes = [f"已原子更新第{index}个市场观察章节"]
    changes.append("已删除：" + ("、".join(component_label(x) for x in removed) or "无"))
    changes.append("已添加：" + ("、".join(component_label(x) for x in added) or "无"))
    changes.append("保持不变：" + ("、".join(component_label(x) for x in preserved) or "无"))
    if active.get("custom_title"):
        changes.append("标题：" + active["custom_title"])
    changes.append("当前数据组件：" + ("、".join(component_label(x) for x in active.get("components", [])) or "无"))
    if "market_summary" in active.get("components", []):
        indicator_names = [str(x.get("display_name") or x.get("metric")) for x in active.get("core_indicators", [])]
        changes.append("核心指标：" + "、".join(indicator_names or [SEGMENT_LABELS.get(x, x) for x in active.get("summary_segments", [])]))
    if active.get("analysis_period"):
        period = active["analysis_period"]
        changes.append("分析周期：" + str(period.get("start_period") or period.get("year") or period.get("period_mode")))
    if active.get("focus"):
        changes.append("分析重点：" + "、".join(active["focus"]))
    changes.append(f"报告计划版本：v{saved['revision']}")
    return saved, changes


def report_plan_summary(plan: dict[str, Any]) -> list[str]:
    normalized = normalize_report_plan(plan)
    observations = normalized.get("market_observations", [])
    out = [
        "模式：" + ("尚未编排" if not observations else "对话动态编排"),
        f"报告计划版本：v{normalized.get('revision', 0)}",
        f"市场观察章节数：{len(observations)}",
    ]
    for index, obs in enumerate(observations, 1):
        title = obs.get("custom_title") or "市场观察"
        out.append(f"第{index}节：{title}")
        out.append("  数据组件：" + ("、".join(component_label(x) for x in obs.get("components", [])) or "无"))
        if obs.get("analysis_period"):
            period = obs["analysis_period"]
            out.append("  分析周期：" + str(period.get("start_period") or period.get("year") or period.get("period_mode")))
    return out
