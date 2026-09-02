"""单元格值格式化工具."""
import datetime
from typing import Any, Optional


def format_display_value(raw_value: Any, number_format: str) -> Optional[str]:
    """根据 number_format 尝试生成展示值."""
    if raw_value is None:
        return None
    if isinstance(raw_value, (datetime.datetime, datetime.date)):
        return raw_value.strftime("%Y-%m-%d")

    fmt = (number_format or "").lower()
    try:
        if "%" in fmt:
            return f"{raw_value * 100:.1f}%"
        if any(k in fmt for k in ["yyyy", "mm", "dd"]):
            if isinstance(raw_value, (int, float)):
                dt = datetime.datetime(1899, 12, 30) + datetime.timedelta(days=raw_value)
                return dt.strftime("%Y-%m-%d")
    except Exception:
        pass
    return str(raw_value)


def infer_data_type(raw_value: Any, formula: Optional[str]) -> str:
    if formula is not None:
        return "formula"
    if raw_value is None:
        return "empty"
    if isinstance(raw_value, str):
        if raw_value.startswith("#"):
            return "error"
        return "string"
    if isinstance(raw_value, bool):
        return "boolean"
    if isinstance(raw_value, (int, float)):
        return "number"
    if isinstance(raw_value, (datetime.datetime, datetime.date)):
        return "date"
    return "unknown"
