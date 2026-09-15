from __future__ import annotations
import json
import re
from copy import deepcopy
from typing import Any

from app.services.storage import processed_base


DEFAULT_REPORT_CONFIG: dict[str, Any] = {
    "include_overview": True,
    "include_rankings": True,
    "ranking_dimensions": ["market", "oem", "brand", "model"],
    "include_power_structure": True,
    "include_monthly_trend": True,
    "include_line_charts": True,
    # 空数组表示使用全部可用折线图；非空时只输出列出的图表标题。
    "selected_line_charts": [],
    "include_weekly_content": True,
    "include_anomalies": True,
    "custom_title": "",
    "last_instruction": "",
}


def _path(dataset_id: str):
    return processed_base(dataset_id) / "report_config.json"


def normalize_report_config(config: dict[str, Any] | None = None) -> dict[str, Any]:
    out = deepcopy(DEFAULT_REPORT_CONFIG)
    if isinstance(config, dict):
        for k in out:
            if k in config:
                out[k] = config[k]
    out["ranking_dimensions"] = [x for x in (out.get("ranking_dimensions") or []) if x in {"market", "oem", "brand", "model"}]
    if not out["ranking_dimensions"]:
        out["ranking_dimensions"] = ["market", "oem", "brand", "model"]
    out["selected_line_charts"] = [str(x) for x in (out.get("selected_line_charts") or []) if str(x).strip()]
    out["custom_title"] = str(out.get("custom_title") or "").strip()
    out["last_instruction"] = str(out.get("last_instruction") or "").strip()
    return out


def load_report_config(dataset_id: str) -> dict[str, Any]:
    path = _path(dataset_id)
    if not path.exists():
        return normalize_report_config()
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return normalize_report_config(data if isinstance(data, dict) else None)
    except Exception:
        return normalize_report_config()


def save_report_config(dataset_id: str, config: dict[str, Any]) -> dict[str, Any]:
    base = processed_base(dataset_id)
    if not (base / "data.csv").exists():
        raise FileNotFoundError(f"dataset_id 不存在：{dataset_id}")
    normalized = normalize_report_config(config)
    _path(dataset_id).write_text(json.dumps(normalized, ensure_ascii=False, indent=2), encoding="utf-8")
    return normalized


def reset_report_config(dataset_id: str) -> dict[str, Any]:
    return save_report_config(dataset_id, DEFAULT_REPORT_CONFIG)


def _chart_matches(text: str, available_titles: list[str]) -> list[str]:
    text = text.lower()
    aliases = [
        (["新能源", "占有率", "市占", "渗透"], "新能源汽车市场占有率"),
        (["同比", "增速"], "核心指标同比增速"),
        (["动力", "结构", "占比"], "动力类型月度结构占比"),
        (["核心", "月度", "趋势"], "核心指标月度趋势"),
        (["车型", "top", "趋势"], "Top车型月度销量趋势"),
    ]
    exact = []
    for title in available_titles:
        if title.lower() in text:
            exact.append(title)
    if exact:
        return exact
    scores: list[tuple[int, str]] = []
    for title in available_titles:
        score = sum(1 for token in re.findall(r"[\u4e00-\u9fffA-Za-z0-9]+", title.lower()) if token and token in text)
        if score:
            scores.append((score, title))
    for tokens, preferred in aliases:
        if sum(1 for token in tokens if token.lower() in text) >= 2:
            for title in available_titles:
                if preferred.lower() in title.lower() or title.lower() in preferred.lower():
                    scores.append((10, title))
    if not scores:
        # 更宽松的关键词映射。
        for title in available_titles:
            key_tokens = [x for x in ["新能源", "同比", "动力", "车型", "月度", "市场占有率"] if x in title]
            score = sum(1 for x in key_tokens if x in text)
            if score:
                scores.append((score, title))
    if not scores:
        return []
    best = max(x[0] for x in scores)
    return list(dict.fromkeys(title for score, title in scores if score == best))


def apply_report_instruction(
    dataset_id: str,
    instruction: str,
    available_chart_titles: list[str] | None = None,
) -> tuple[dict[str, Any], list[str]]:
    """把自然语言报告修改要求落到可持久化配置。

    结构性要求由规则执行；自由的生成要求功能已从可视化编排中移除，
    未能识别为结构操作的表达不会保存或传给大模型。
    """
    config = load_report_config(dataset_id)
    text = instruction.strip()
    q = text.lower()
    changes: list[str] = []
    negative = any(x in q for x in ["不要", "移除", "删除", "取消", "不需要", "不放", "去掉", "排除"])
    positive = any(x in q for x in ["加入", "添加", "增加", "放入", "包含", "保留", "加上", "需要", "要有", "插入", "加回来", "恢复", "也加", "也放"])

    target_map = [
        (["市场观察", "市场观察表", "总览表"], "include_overview", "市场观察表"),
        (["排名", "oem", "品牌排名", "车型排名", "市场排名"], "include_rankings", "排名"),
        (["动力结构", "动力类型", "能源结构", "动力占比"], "include_power_structure", "动力结构"),
        (["月度趋势", "趋势数据"], "include_monthly_trend", "月度趋势"),
        (["折线图", "趋势图", "曲线图", "line chart"], "include_line_charts", "折线图"),
        (["周报内容", "宏观政策", "企业行动", "人事", "战略", "产业链", "竞争追踪", "正文"], "include_weekly_content", "周报正文"),
        (["异常提示", "异常指标", "预警", "数据质量"], "include_anomalies", "异常提示"),
    ]
    touched = False
    for tokens, field, label in target_map:
        if any(token in q for token in tokens):
            if negative:
                config[field] = False
                changes.append(f"已从报告中移除{label}")
                touched = True
            elif positive or "报告" in q:
                config[field] = True
                changes.append(f"已在报告中启用{label}")
                touched = True

    # 排名细分维度。
    if "只" in q and "排名" in q:
        dims = []
        if "市场" in q: dims.append("market")
        if any(x in q for x in ["oem", "主机厂", "车企", "厂商"]): dims.append("oem")
        if "品牌" in q: dims.append("brand")
        if "车型" in q: dims.append("model")
        if dims:
            config["ranking_dimensions"] = dims
            config["include_rankings"] = True
            changes.append("排名页仅保留：" + "、".join(dims))
            touched = True

    # 折线图可按自然语言选择。
    chart_titles = available_chart_titles or []
    if any(x in q for x in ["折线图", "趋势图", "曲线图", "line chart"]):
        if negative:
            config["include_line_charts"] = False
            config["selected_line_charts"] = []
        else:
            config["include_line_charts"] = True
            if any(x in q for x in ["全部折线图", "所有折线图", "全部趋势图", "所有趋势图"]):
                config["selected_line_charts"] = []
                changes.append("折线图设置为全部可用图表")
                touched = True
            else:
                matched = _chart_matches(q, chart_titles)
                if matched:
                    if "只" in q or "仅" in q:
                        config["selected_line_charts"] = matched
                        changes.append("折线图仅保留：" + "、".join(matched))
                    elif config.get("selected_line_charts"):
                        config["selected_line_charts"] = list(dict.fromkeys(config["selected_line_charts"] + matched))
                        changes.append("新增折线图：" + "、".join(matched))
                    else:
                        # 当前为空表示已经包含全部，所以无需缩窄。
                        changes.append("报告当前已包含全部可用折线图，其中包括：" + "、".join(matched))
                    touched = True

    # 标题修改。
    m = re.search(r"(?:标题|报告名)(?:改成|修改为|设为|叫做|改为)[：:\s]*[\"“]?([^\"”\n]{3,80})", text)
    if m:
        config["custom_title"] = m.group(1).strip(" 。；;，,")
        changes.append(f"报告标题已改为：{config['custom_title']}")
        touched = True

    config["last_instruction"] = text
    saved = save_report_config(dataset_id, config)
    return saved, changes


def config_summary(config: dict[str, Any], available_chart_titles: list[str] | None = None) -> list[str]:
    labels = [
        ("include_overview", "市场观察表"), ("include_rankings", "排名"),
        ("include_power_structure", "动力结构"), ("include_monthly_trend", "月度趋势"),
        ("include_line_charts", "折线图"), ("include_weekly_content", "周报正文"),
        ("include_anomalies", "异常提示"),
    ]
    enabled = [label for key, label in labels if config.get(key)]
    out = ["当前报告包含：" + "、".join(enabled)]
    if config.get("include_line_charts"):
        selected = config.get("selected_line_charts") or []
        out.append("折线图：" + ("、".join(selected) if selected else f"全部可用图表（{len(available_chart_titles or [])}个）"))
    return out
