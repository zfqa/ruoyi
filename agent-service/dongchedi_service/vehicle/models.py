"""Output models for a single Dongchedi series parsing run."""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import StrEnum
from typing import Any


class FieldStatus(StrEnum):
    PARSED = "parsed"
    NOT_PRESENT = "not_present"
    EMPTY = "empty"
    NOT_SUPPORTED = "not_supported"
    PARSE_ERROR = "parse_error"


@dataclass
class VehicleParameterRecord:
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
    sources: dict[str, str | None] = field(default_factory=dict)
    field_status: dict[str, FieldStatus] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        result = asdict(self)
        result["field_status"] = {name: value.value for name, value in self.field_status.items()}
        return result


@dataclass
class SeriesParseResult:
    series_id: str | None
    series_name: str | None
    source_url: str
    cars: list[VehicleParameterRecord]
    price_source_status: str
    parser_warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "series_id": self.series_id,
            "series_name": self.series_name,
            "source_url": self.source_url,
            "price_source_status": self.price_source_status,
            "parser_warnings": self.parser_warnings,
            "cars": [car.to_dict() for car in self.cars],
        }
