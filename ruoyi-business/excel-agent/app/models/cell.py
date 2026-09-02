"""Excel 单元格数据模型."""
from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass
class ExcelCell:
    sheet_name: str
    row: int
    column: int
    coordinate: str

    raw_value: Any = None
    display_value: Optional[str] = None
    data_type: str = "unknown"

    formula: Optional[str] = None
    cached_value: Any = None
    number_format: Optional[str] = None

    is_merged: bool = False
    merged_range: Optional[str] = None
    merged_source_cell: Optional[str] = None
    merged_source_value: Any = None

    row_hidden: bool = False
    column_hidden: bool = False

    hyperlink: Optional[str] = None
    comment: Optional[str] = None

    style_id: Optional[int] = None

    def to_dict(self):
        return {
            "sheet_name": self.sheet_name,
            "row": self.row,
            "column": self.column,
            "coordinate": self.coordinate,
            "raw_value": _safe(self.raw_value),
            "display_value": self.display_value,
            "data_type": self.data_type,
            "formula": _safe(self.formula),
            "cached_value": _safe(self.cached_value),
            "number_format": self.number_format,
            "is_merged": self.is_merged,
            "merged_range": self.merged_range,
            "merged_source_cell": self.merged_source_cell,
            "merged_source_value": _safe(self.merged_source_value),
            "row_hidden": self.row_hidden,
            "column_hidden": self.column_hidden,
            "hyperlink": self.hyperlink,
            "comment": self.comment,
        }


def _safe(value):
    if hasattr(value, "isoformat"):
        return value.isoformat(sep=" ")
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)
