"""Rule-based, source-grounded extraction of meaningful automotive events."""
from __future__ import annotations

import re

from data_service.extractor.entity_extractor import extract_entities
from data_service.models.schemas import StructuredEvent
from data_service.normalizer.company_aliases import normalize_company_name

EVENT_COMPANY_NAMES = (
    "天马", "Tianma", "LGD", "雷诺", "福特", "长安汽车", "长安",
    "极氪", "鸿蒙智行", "零跑", "上汽", "一汽",
)

EVENT_RULES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("诉讼/知识产权", re.compile(r"起诉|诉讼|侵权|专利")),
    ("人事任命", re.compile(r"任命|出任|加盟|辞任|辞职")),
    ("战略合作", re.compile(r"战略合作|合作伙伴|签署.{0,20}(?:合作|协议)|建立.{0,20}合作")),
    ("投资并购", re.compile(r"收购|购买|并购|私有化|入股|投资|IPO")),
    ("政策动态", re.compile(r"加征.{0,20}关税|提高.{0,20}关税|出台.{0,20}(?:政策|规划|建议)|发布.{0,20}(?:通知|条例|政策)|调整.{0,20}(?:政策|制度|规划|计划)")),
    ("产能/生产", re.compile(r"扩产|投产|建厂|产能.{0,20}(?:提升|扩大|新增)")),
    ("产品发布", re.compile(r"发布|推出|上市")),
    ("销量动态", re.compile(r"销量突破|出货.{0,20}(?:增长|下降|下跌)|销量.{0,20}(?:增长|下降|下跌|激增|突破)|同比.{0,20}(?:增长|下降|下跌)|(?:增长|下降|下跌|激增).{0,12}(?:销量|出货)")),
    ("技术动态", re.compile(r"技术突破|芯片.{0,20}(?:发布|获准|出口|供应)|LTPS.{0,20}(?:增长|提升)|OLED.{0,20}(?:发布|量产|侵权)")),
    ("市场动态", re.compile(r"(?:进入|进军)(?:德国|中国|欧洲|市场)|推迟.{0,20}(?:燃油车|计划)|联合.{0,20}(?:造车|生产)|市场份额.{0,20}(?:增长|下降|提升)|召回|获得订单")),
)

KEYWORDS = ("新能源", "插混", "纯电", "800V", "固态电池", "智能驾驶", "LTPS", "LCD", "OLED", "MicroLED", "专利", "关税", "芯片")
TIME_PATTERNS = (
    re.compile(r"(?:20\d{2}|\d{2})年前三季度"),
    re.compile(r"20\d{2}年(?:1[0-2]|[1-9])月(?:\d{1,2}日)?"),
    re.compile(r"20\d{2}年Q[1-4]", re.IGNORECASE),
    re.compile(r"\d{1,2}月\d{1,2}日"),
    re.compile(r"20\d{2}年"),
)


def _sentences(text: str) -> list[str]:
    return [match.group(0).strip() for match in re.finditer(r"[^。！？；\n]+[。！？；]?", text) if match.group(0).strip()]


def _companies(sentence: str) -> list[str]:
    names = [entity.name for entity in extract_entities(sentence) if entity.type == "company"]
    for name in EVENT_COMPANY_NAMES:
        canonical, _ = normalize_company_name(name)
        if name.lower() in sentence.lower() and canonical and canonical not in names:
            names.append(canonical)
    return names


def _event_type(sentence: str) -> str | None:
    return next((event_type for event_type, pattern in EVENT_RULES if pattern.search(sentence)), None)


def _time(sentence: str) -> str | None:
    return next((match.group(0) for pattern in TIME_PATTERNS if (match := pattern.search(sentence))), None)


def _impact(sentence: str) -> str | None:
    match = re.search(r"(?:推动|支撑|带动|拖累|导致|提升|下降|加速|影响)[^，,。！？；\n]{0,80}", sentence)
    return match.group(0).strip() if match else None


def _keywords(sentence: str) -> list[str]:
    return [keyword for keyword in KEYWORDS if keyword in sentence]


def is_valid_event(event: StructuredEvent) -> bool:
    """Only retain a sentence that states an actual event action."""
    return bool(event.event and len(event.event.strip()) >= 4)


def structure_automotive_text(text: str) -> list[StructuredEvent]:
    """Identify independent sentence-level events; heading-only text yields none."""
    events: list[StructuredEvent] = []
    for sentence in _sentences(text):
        event_type = _event_type(sentence)
        if event_type is None:
            continue
        companies = _companies(sentence)
        event = StructuredEvent(
            company=companies[0] if companies else None,
            companies=companies,
            event_type=event_type,
            time=_time(sentence),
            event=sentence,
            impact=_impact(sentence),
            keywords=_keywords(sentence),
        )
        if is_valid_event(event):
            events.append(event)
    return events


