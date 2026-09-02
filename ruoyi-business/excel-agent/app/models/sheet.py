"""Excel Sheet 数据模型."""
from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional
from app.models.cell import ExcelCell


@dataclass
class SheetInfo:
    sheet_name: str
    sheet_index: int
    max_row: int
    max_column: int
    effective_min_row: Optional[int] = None
    effective_max_row: Optional[int] = None
    effective_min_col: Optional[int] = None
    effective_max_col: Optional[int] = None
    effective_range: Optional[str] = None
    merged_ranges: List[str] = field(default_factory=list)
    cells: List[ExcelCell] = field(default_factory=list)
    tables: List[Dict[str, Any]] = field(default_factory=list)

    def to_dict(self):
        return {
            "sheet_name": self.sheet_name,
            "sheet_index": self.sheet_index,
            "max_row": self.max_row,
            "max_column": self.max_column,
            "effective_min_row": self.effective_min_row,
            "effective_max_row": self.effective_max_row,
            "effective_min_col": self.effective_min_col,
            "effective_max_col": self.effective_max_col,
            "effective_range": self.effective_range,
            "merged_ranges": self.merged_ranges,
            "tables": self.tables,
        }
