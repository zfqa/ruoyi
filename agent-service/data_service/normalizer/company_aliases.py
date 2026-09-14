"""POC-level company aliases; unknown names deliberately remain unchanged."""
from __future__ import annotations

COMPANY_ALIASES: dict[str, str] = {
    "BYD": "比亚迪",
    "比亚迪汽车": "比亚迪",
    "深天马": "天马微电子",
    "天马": "天马微电子",
    "BOE": "京东方",
    "京东方科技": "京东方",
    "TCL华星": "TCL华星",
    "华星光电": "TCL华星",
    "理想": "理想汽车",
    "吉利": "吉利汽车",
}


def normalize_company_name(value: str | None) -> tuple[str | None, str | None]:
    """Return (standard_name, original_name) without inventing unknown aliases."""
    original = (value or "").strip()
    if not original:
        return None, None
    return COMPANY_ALIASES.get(original, original), original


def aliases_for_company(standard_name: str) -> tuple[str, ...]:
    """All configured spellings used only to locate source-grounded evidence."""
    aliases = [alias for alias, canonical in COMPANY_ALIASES.items() if canonical == standard_name]
    return tuple(dict.fromkeys([standard_name, *aliases]))


