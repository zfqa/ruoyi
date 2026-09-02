"""Field normalization rules for automotive display workbooks."""
import re


ALIASES = {
    "year": ["year", "年份"],
    "quarter": ["quarter", "季度"],
    "quarter_year": ["qy", "year quarter", "季度年"],
    "panel_maker": ["maker", "panel maker", "fpd maker", "oem", "面板厂", "厂商"],
    "client": ["client", "set maker", "客户"],
    "original_specification": ["original specification", "specification", "原始规格"],
    "product": ["product", "产品"],
    "application": ["application", "应用"],
    "technology": ["technology", "技术"],
    "size": ["size", "size (inch)", "diagonal size", "尺寸", "英寸"],
    "quantity_000": ["qty (000)", "qty (000s)", "shipment", "shipments (000s)", "出货量"],
    "display_area": ["display area", "display area (m2)", "显示面积"],
    "month": ["month", "月份"],
}


def normalize_field_name(header: str, used: set[str] | None = None) -> str:
    text = str(header or "").strip()
    lower = re.sub(r"\s+", " ", text.lower())
    for canonical, aliases in ALIASES.items():
        if lower in aliases:
            return _dedupe(canonical, used)
    name = re.sub(r"[^0-9a-zA-Z]+", "_", lower).strip("_")
    return _dedupe(name or "field", used)


def _dedupe(name: str, used: set[str] | None) -> str:
    if used is None:
        return name
    candidate = name
    index = 2
    while candidate in used:
        candidate = f"{name}_{index}"
        index += 1
    used.add(candidate)
    return candidate
