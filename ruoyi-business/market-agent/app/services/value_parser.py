from __future__ import annotations
import re
from datetime import datetime
from typing import Any

import pandas as pd

PCT_RE = re.compile(r"([+-]?\d+(?:\.\d+)?)\s*%")
NUM_RE = re.compile(r"([+-]?\d+(?:\.\d+)?)")
YYYYMM_RE = re.compile(r"^(20\d{2})(0[1-9]|1[0-2])(?:\.0)?$")


def normalize_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).replace("\u3000", " ").replace("（", "(").replace("）", ")").strip()


def parse_numeric(value: Any) -> float | None:
    """解析业务数值，保持源表数量级不变。

    POC阶段不擅自把“辆/万辆”统一换算，避免在无法确认单位时改变口径；
    但会正确解析百分比、逗号、中文业务描述中的数值。单位信息由解析元数据单独保留。
    """
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    text = normalize_text(value).replace(",", "")
    if not text or text in {"-", "--", "—", "–"}:
        return None
    pct = PCT_RE.search(text)
    # 只有“纯百分比/纯同比”单元格才按百分比解析；
    # 对“353.2万（同比+2.8%）”这类复合单元格，主数值必须取353.2，同比由extract_yoy单独提取。
    compact = re.sub(r"\s+", "", text)
    if pct and (compact.startswith("同比") or compact.startswith("环比") or (compact.endswith("%") and len(NUM_RE.findall(compact)) == 1)):
        return float(pct.group(1)) / 100
    m = NUM_RE.search(text)
    if not m:
        return None
    return float(m.group(1))


def extract_yoy(value: Any) -> float | None:
    text = normalize_text(value)
    if "同比" not in text:
        return None
    m = PCT_RE.search(text)
    return float(m.group(1)) / 100 if m else None


def parse_date_like(value: Any) -> str | None:
    """把常见年月写法统一成 YYYY-MM。

    重点支持甲方固定模板中的 202401/202402/.../202511 这种六位年月列。
    """
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value.strftime("%Y-%m")
    text = normalize_text(value).replace(" ", "")

    # Excel/CSV常见六位年月：202401，或读取后变成202401.0
    m0 = YYYYMM_RE.match(text)
    if m0:
        return f"{m0.group(1)}-{m0.group(2)}"

    # 2025.11 / 2025-11 / 2025/11 / 2025年11月
    m = re.search(r"^(20\d{2})[.\-/年](\d{1,2})(?:月)?$", text)
    if m:
        month = int(m.group(2))
        if 1 <= month <= 12:
            return f"{m.group(1)}-{month:02d}"

    # 季度保留为标准字符串，便于后续扩展，不伪造成月份。
    q = re.search(r"^(20\d{2})(?:年)?[Qq]([1-4])$", text)
    if q:
        return f"{q.group(1)}-Q{q.group(2)}"
    q2 = re.search(r"^(20\d{2})年([1-4])季度$", text)
    if q2:
        return f"{q2.group(1)}-Q{q2.group(2)}"

    m2 = re.search(r"^(\d{1,2})月$", text)
    if m2:
        return f"M{int(m2.group(1)):02d}"
    return text if len(text) <= 20 else None
