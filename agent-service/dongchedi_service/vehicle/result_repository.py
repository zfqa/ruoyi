"""Read already-saved Dongchedi vehicle results without contacting the upstream site."""
from __future__ import annotations

from dataclasses import dataclass, fields
import json
from pathlib import Path
from typing import Any


BUSINESS_FIELDS = (
    "model_name",
    "manufacturer",
    "official_guide_price",
    "level",
    "energy_type",
    "instrument_screen_size_inch",
    "instrument_screen_style",
    "center_screen_size_inch",
    "center_screen_material",
    "passenger_screen_size_inch",
    "rear_screen_size_inch",
)


@dataclass(frozen=True)
class SavedVehicleRecord:
    series_id: str | None
    series_name: str | None
    car_id: str
    model_name: str | None = None
    manufacturer: str | None = None
    official_guide_price: str | None = None
    level: str | None = None
    energy_type: str | None = None
    instrument_screen_size_inch: str | None = None
    instrument_screen_style: str | None = None
    center_screen_size_inch: str | None = None
    center_screen_material: str | None = None
    passenger_screen_size_inch: str | None = None
    rear_screen_size_inch: str | None = None


@dataclass(frozen=True)
class SavedVehicleQueryResult:
    records: list[SavedVehicleRecord]
    duplicate_car_id_count: int
    valid_file_count: int
    warnings: list[str]


class SavedVehicleResultRepository:
    """Load flattened vehicle records from the project's saved series JSON files."""

    def __init__(self, output_dir: str | Path | None = None) -> None:
        project_root = Path(__file__).resolve().parents[2]
        self.output_dir = Path(output_dir) if output_dir is not None else project_root / "data" / "dongchedi_output"

    def list_saved_vehicles(
        self,
        *,
        brand: str | None = None,
        series_id: str | None = None,
    ) -> SavedVehicleQueryResult:
        warnings: list[str] = []
        valid_file_count = 0
        records: list[SavedVehicleRecord] = []
        if not self.output_dir.exists():
            return SavedVehicleQueryResult(records, 0, valid_file_count, warnings)

        for path in sorted(self.output_dir.glob("series_*.json")):
            if path.name.endswith("_quality.json"):
                continue
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
                if not isinstance(payload, dict) or not isinstance(payload.get("cars"), list):
                    raise ValueError("缺少 cars 数组")
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                warnings.append(f"跳过无效结果文件 {path.name}: {type(exc).__name__}")
                continue

            valid_file_count += 1
            # A car's manufacturer is not the series' brand (for example, a
            # brand may have multiple manufacturer sub-brands).  Only apply a
            # brand check when the saved series payload actually contains one.
            payload_brand = _text(payload.get("brand_name") or payload.get("brand"))
            if brand is not None and payload_brand is not None and payload_brand != brand:
                continue
            for car in payload["cars"]:
                if not isinstance(car, dict):
                    warnings.append(f"跳过无效车型记录 {path.name}")
                    continue
                car_id = str(car.get("car_id") or "").strip()
                if not car_id:
                    warnings.append(f"跳过缺少 car_id 的车型记录 {path.name}")
                    continue
                record = self._record(payload, car, car_id)
                if series_id is not None and record.series_id != str(series_id):
                    continue
                records.append(record)

        seen: set[str] = set()
        unique_records: list[SavedVehicleRecord] = []
        duplicate_car_id_count = 0
        for record in records:
            if record.car_id in seen:
                duplicate_car_id_count += 1
                continue
            seen.add(record.car_id)
            unique_records.append(record)

        unique_records.sort(
            key=lambda item: (
                item.manufacturer or "",
                item.series_name or "",
                item.model_name or "",
                item.car_id,
            )
        )
        return SavedVehicleQueryResult(unique_records, duplicate_car_id_count, valid_file_count, warnings)

    @staticmethod
    def _record(payload: dict[str, Any], car: dict[str, Any], car_id: str) -> SavedVehicleRecord:
        allowed = {field.name for field in fields(SavedVehicleRecord)}
        values: dict[str, str | None] = {
            "series_id": _text(payload.get("series_id")),
            "series_name": _text(payload.get("series_name")),
            "car_id": car_id,
        }
        for name in BUSINESS_FIELDS:
            if name in allowed:
                values[name] = _text(car.get(name))
        return SavedVehicleRecord(**values)


def _text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
