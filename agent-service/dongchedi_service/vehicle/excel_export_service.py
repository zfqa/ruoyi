"""Build the client-facing Dongchedi vehicle workbook in memory."""
from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from typing import Any, Mapping

from openpyxl import Workbook
from openpyxl.styles import Font

from .result_repository import SavedVehicleRecord


SHEET_NAME = "懂车帝车型数据"


@dataclass(frozen=True)
class ExportColumn:
    header: str
    field: str


EXPORT_COLUMNS = (
    ExportColumn("车系ID", "series_id"),
    ExportColumn("车系名称", "series_name"),
    ExportColumn("车型ID", "car_id"),
    ExportColumn("车型名称", "model_name"),
    ExportColumn("厂商", "manufacturer"),
    ExportColumn("官方指导价", "official_guide_price"),
    ExportColumn("车辆级别", "level"),
    ExportColumn("能源类型", "energy_type"),
    ExportColumn("仪表屏尺寸（英寸）", "instrument_screen_size_inch"),
    ExportColumn("仪表屏样式", "instrument_screen_style"),
    ExportColumn("中控屏尺寸（英寸）", "center_screen_size_inch"),
    ExportColumn("中控屏材质", "center_screen_material"),
    ExportColumn("副驾屏尺寸（英寸）", "passenger_screen_size_inch"),
    ExportColumn("后排屏尺寸（英寸）", "rear_screen_size_inch"),
)

# Task exports are generated from the authoritative RuoYi MySQL task-model
# relation, rather than from this service's local JSON staging files.  Keep
# this intentionally separate from EXPORT_COLUMNS so the legacy staging export
# retains its established workbook contract.
TASK_EXPORT_COLUMNS = (
    ExportColumn("车型名称", "model_name"),
    ExportColumn("厂商", "manufacturer"),
    ExportColumn("官方指导价", "official_guide_price"),
    ExportColumn("车辆级别", "level"),
    ExportColumn("能源类型", "energy_type"),
    ExportColumn("仪表屏尺寸（英寸）", "instrument_screen_size_inch"),
    ExportColumn("中控屏尺寸（英寸）", "center_screen_size_inch"),
    ExportColumn("中控屏材质", "center_screen_material"),
    ExportColumn("副驾屏尺寸（英寸）", "passenger_screen_size_inch"),
    ExportColumn("后排屏尺寸（英寸）", "rear_screen_size_inch"),
)


class VehicleExcelExportService:
    """Create a single-sheet xlsx from already-loaded vehicle records."""

    def build_workbook_bytes(self, records: list[SavedVehicleRecord]) -> bytes:
        return self._build_workbook_bytes(records, EXPORT_COLUMNS, missing_value=None)

    def build_task_workbook_bytes(self, models: list[Mapping[str, Any]]) -> bytes:
        """Build the fixed ten-column workbook from RuoYi task model data."""
        return self._build_workbook_bytes(models, TASK_EXPORT_COLUMNS, missing_value="--")

    def _build_workbook_bytes(
        self,
        records: list[SavedVehicleRecord] | list[Mapping[str, Any]],
        columns: tuple[ExportColumn, ...],
        *,
        missing_value: str | None,
    ) -> bytes:
        workbook = Workbook()
        sheet = workbook.active
        sheet.title = SHEET_NAME
        headers = [column.header for column in columns]
        sheet.append(headers)
        for cell in sheet[1]:
            cell.font = Font(bold=True)

        for record in records:
            sheet.append([_field_value(record, column.field, missing_value) for column in columns])

        sheet.freeze_panes = "A2"
        sheet.auto_filter.ref = f"A1:{_column_letter(len(columns))}{len(records) + 1}"
        for index, column in enumerate(columns, start=1):
            values = [column.header, *(_field_value(record, column.field, "") or "" for record in records)]
            sheet.column_dimensions[_column_letter(index)].width = min(max(max(len(str(value)) for value in values) + 2, 12), 36)

        stream = BytesIO()
        workbook.save(stream)
        return stream.getvalue()


def _field_value(record: SavedVehicleRecord | Mapping[str, Any], field: str, missing_value: str | None) -> Any:
    camel_field = _camel_case(field)
    if isinstance(record, Mapping):
        value = record.get(field, record.get(camel_field))
    else:
        value = getattr(record, field)
    return missing_value if value is None or value == "" else value


def _camel_case(value: str) -> str:
    pieces = value.split("_")
    return pieces[0] + "".join(piece.capitalize() for piece in pieces[1:])


def _column_letter(index: int) -> str:
    result = ""
    while index:
        index, remainder = divmod(index - 1, 26)
        result = chr(65 + remainder) + result
    return result
