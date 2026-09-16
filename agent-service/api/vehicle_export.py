"""HTTP response construction for locally saved Dongchedi vehicle exports."""
from __future__ import annotations

from datetime import datetime
from io import BytesIO
from urllib.parse import quote

from fastapi import HTTPException
from fastapi.responses import StreamingResponse

from dongchedi_service.vehicle.excel_export_service import VehicleExcelExportService
from dongchedi_service.vehicle.result_repository import SavedVehicleQueryResult, SavedVehicleRecord


XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def require_export_records(
    query_result: SavedVehicleQueryResult,
    *,
    brand: str | None,
    series_id: str | None,
) -> list[SavedVehicleRecord]:
    if query_result.records:
        return query_result.records
    message = (
        "没有符合筛选条件的已保存懂车帝车型数据"
        if brand is not None or series_id is not None
        else "没有找到可导出的懂车帝车型数据"
    )
    raise HTTPException(
        status_code=404,
        detail={"code": "saved_vehicle_data_not_found", "message": message},
    )


def build_vehicle_export_response(
    records: list[SavedVehicleRecord],
    *,
    exporter: VehicleExcelExportService,
    exported_at: datetime | None = None,
) -> StreamingResponse:
    content = exporter.build_workbook_bytes(records)
    timestamp = exported_at or datetime.now()
    filename = f"懂车帝车型数据_{timestamp:%Y%m%d_%H%M%S}.xlsx"
    return StreamingResponse(
        BytesIO(content),
        media_type=XLSX_CONTENT_TYPE,
        headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
    )
