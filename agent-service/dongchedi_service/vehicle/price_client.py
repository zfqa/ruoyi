"""Price-response extraction for a currently loaded Dongchedi series page."""
from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any


PRICE_API_PATH = "/motor/pc/car/series/car_dealer_price"


@dataclass(frozen=True)
class PriceRecord:
    car_id: str
    car_name: str | None
    official_price: str | None
    series_name: str | None
    year: str | None


class DongchediPriceClient:
    """Parse the observed, per-car price API response without using dealer price."""

    @staticmethod
    def is_price_api(url: str) -> bool:
        return PRICE_API_PATH in url

    def parse_payload(self, payload: Any) -> dict[str, PriceRecord]:
        if not isinstance(payload, Mapping):
            return {}
        data = payload.get("data")
        if not isinstance(data, Mapping):
            return {}
        records: dict[str, PriceRecord] = {}
        for car_id, item in data.items():
            if not isinstance(item, Mapping):
                continue
            records[str(car_id)] = PriceRecord(
                car_id=str(car_id),
                car_name=self._as_text(item.get("car_name")),
                official_price=self._as_text(item.get("official_price")),
                series_name=self._as_text(item.get("series_name")),
                year=self._as_text(item.get("year")),
            )
        return records

    @staticmethod
    def _as_text(value: Any) -> str | None:
        text = str(value).strip() if value is not None else ""
        return text or None
