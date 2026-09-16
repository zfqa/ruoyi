"""Generic, DOM-first parser for one Dongchedi parameter-comparison page."""
from __future__ import annotations

from collections.abc import Mapping
import json
import re
from typing import Any
from urllib.parse import urlparse

from playwright.sync_api import Page

from .models import FieldStatus, SeriesParseResult, VehicleParameterRecord
from .price_client import DongchediPriceClient, PriceRecord


FIELD_SPECS: dict[str, dict[str, Any]] = {
    "manufacturer": {"labels": ("厂商",)},
    "level": {"labels": ("级别",)},
    "energy_type": {"labels": ("能源类型",)},
    "instrument_screen_size_inch": {"labels": ("液晶仪表尺寸(英寸)", "液晶仪表尺寸", "全液晶仪表尺寸")},
    "instrument_screen_style": {"labels": ("液晶仪表样式", "液晶仪表盘样式")},
    "center_screen_size_inch": {"labels": ("中控屏尺寸(英寸)", "中控屏尺寸", "中控液晶屏尺寸")},
    "center_screen_material": {"labels": ("中控屏幕材质", "中控屏材质")},
    # Only rows explicitly naming a dimension may satisfy these two fields.
    "passenger_screen_size_inch": {"labels": ("副驾驶屏幕尺寸(英寸)", "副驾屏幕尺寸(英寸)")},
    "rear_screen_size_inch": {"labels": ("后排屏幕尺寸(英寸)", "后排液晶屏尺寸(英寸)")},
}
BUSINESS_FIELDS = ("model_name", "manufacturer", "official_guide_price", "level", "energy_type", *FIELD_SPECS.keys())


class DongchediSeriesParser:
    """Convert one rendered comparison page plus price response into car records."""

    def __init__(self) -> None:
        self.price_client = DongchediPriceClient()

    def parse(self, page: Page, observed_price_payloads: list[Any]) -> SeriesParseResult:
        rows = self._read_parameter_rows(page)
        price_records = self._first_price_records(observed_price_payloads)
        dom_headers = self._read_car_headers(page)
        car_ids = [dom_headers[index]["car_id"] for index in sorted(dom_headers)]
        warnings: list[str] = []
        if not car_ids:
            car_ids = list(price_records) or self._extract_car_ids_from_page(page)
            warnings.append("未读取到参数页表头车型列；car_id 顺序无法由 DOM 表头确认。")
        if not car_ids:
            raise ValueError("未能从当前参数页识别具体车型 car_id")

        series_id = self._series_id(page.url)
        series_name = self._series_name(price_records, page.title())
        cars = [self._new_record(car_id, price_records.get(car_id), dom_headers.get(index, {}).get("model_name")) for index, car_id in enumerate(car_ids, start=1)]
        self._apply_dom_rows(cars, rows)
        return SeriesParseResult(
            series_id=series_id,
            series_name=series_name,
            source_url=page.url,
            cars=cars,
            price_source_status="observed_response" if price_records else "unavailable",
            parser_warnings=warnings,
        )

    @staticmethod
    def _new_record(car_id: str, price: PriceRecord | None, dom_model_name: str | None) -> VehicleParameterRecord:
        record = VehicleParameterRecord(car_id=car_id)
        for field in BUSINESS_FIELDS:
            record.sources[field] = None
            record.field_status[field] = FieldStatus.NOT_PRESENT
        if price is not None:
            record.model_name = DongchediSeriesParser._full_model_name(price)
            record.official_guide_price = price.official_price
            record.sources["model_name"] = "price_api" if price.car_name else None
            record.sources["official_guide_price"] = "price_api" if price.official_price else None
            record.field_status["model_name"] = FieldStatus.PARSED if price.car_name else FieldStatus.EMPTY
            record.field_status["official_guide_price"] = FieldStatus.PARSED if price.official_price else FieldStatus.EMPTY
        elif dom_model_name:
            record.model_name = dom_model_name
            record.sources["model_name"] = "dom"
            record.field_status["model_name"] = FieldStatus.PARSED
        return record

    @staticmethod
    def _full_model_name(price: PriceRecord) -> str | None:
        if not price.car_name:
            return None
        prefix = " ".join(part for part in (price.series_name, f"{price.year}款" if price.year else None) if part)
        return f"{prefix} {price.car_name}".strip() if prefix else price.car_name

    @classmethod
    def _read_parameter_rows(cls, page: Page) -> dict[str, dict[int, dict[str, Any]]]:
        """Read labelled comparison rows; exact aliases prevent semantic conflation."""
        raw_rows = page.evaluate(
            """() => Array.from(document.querySelectorAll('[class*="table_row"]')).map((row) => {
              const directCells = Array.from(row.children);
              const labelCell = directCells.find((cell) => /--config-col-index:0/.test(cell.getAttribute('style') || ''));
              const values = Array.from(row.querySelectorAll('[class*="table_col"]')).filter((cell) => {
                const style = cell.getAttribute('style') || '';
                const index = Number((style.match(/--config-col-index:(\\d+)/) || [])[1]);
                const containsNestedColumn = Array.from(cell.children).some((child) => String(child.className || '').includes('table_col'));
                return index > 0 && !containsNestedColumn;
              }).map((cell) => {
                const style = cell.getAttribute('style') || '';
                const index = Number((style.match(/--config-col-index:(\\d+)/) || [])[1]);
                return { index, text: (cell.innerText || '').trim(), has_media: Boolean(cell.querySelector('img, video')) };
              });
              return { label: (labelCell?.innerText || '').trim(), values };
            })"""
        )
        matched: dict[str, dict[int, dict[str, Any]]] = {}
        aliases = {label: field for field, spec in FIELD_SPECS.items() for label in spec["labels"]}
        for row in raw_rows:
            field = aliases.get(str(row.get("label", "")).strip())
            if field and field not in matched:
                matched[field] = {int(value["index"]): {"text": str(value["text"]).strip(), "has_media": bool(value["has_media"])} for value in row.get("values", [])}
        return matched

    @staticmethod
    def _read_car_headers(page: Page) -> dict[int, dict[str, str]]:
        """Map the visible comparison-table column index to its explicit car_id."""
        headers = page.evaluate(
            r"""() => Array.from(document.querySelectorAll('[class*="table_is-head-col"]')).map((column) => {
              const style = column.getAttribute('style') || '';
              const index = Number((style.match(/--config-col-index:(\d+)/) || [])[1]);
              const anchor = column.querySelector('a[href*="/model-"]');
              const match = (anchor?.getAttribute('href') || '').match(/\/model-(\d+)/);
              return { index, car_id: match?.[1] || null, model_name: (anchor?.getAttribute('title') || anchor?.innerText || '').trim() };
            }).filter(item => item.index > 0 && item.car_id);"""
        )
        return {int(item["index"]): {"car_id": str(item["car_id"]), "model_name": str(item["model_name"])} for item in headers}

    @staticmethod
    def _first_price_records(payloads: list[Any]) -> dict[str, PriceRecord]:
        client = DongchediPriceClient()
        for payload in payloads:
            records = client.parse_payload(payload)
            if records:
                return records
        return {}

    @staticmethod
    def _extract_car_ids_from_page(page: Page) -> list[str]:
        ids = page.evaluate(
            """() => {
              const found = new Set();
              for (const node of document.querySelectorAll('[href], [data-car-id], [data-car_id]')) {
                for (const value of [node.getAttribute('href'), node.getAttribute('data-car-id'), node.getAttribute('data-car_id')]) {
                  for (const match of String(value || '').matchAll(/(?:car[_-]?id=|carIds=)(\\d+)/gi)) found.add(match[1]);
                  if (/^\\d+$/.test(String(value || ''))) found.add(String(value));
                }
              }
              return [...found];
            }"""
        )
        return [str(item) for item in ids]

    @staticmethod
    def _series_id(url: str) -> str | None:
        match = re.search(r"params-carIds-x-(\d+)", urlparse(url).path)
        return match.group(1) if match else None

    @staticmethod
    def _series_name(prices: Mapping[str, PriceRecord], title: str) -> str | None:
        for price in prices.values():
            if price.series_name:
                return price.series_name
        # The title is only a display fallback; it deliberately makes no
        # brand-specific assumption and may remain unavailable.
        match = re.search(r"】([^\s【】]+?)\s+\d{4}款", title)
        return match.group(1) if match else None

    @staticmethod
    def _apply_dom_rows(cars: list[VehicleParameterRecord], rows: Mapping[str, Mapping[int, Mapping[str, Any]]]) -> None:
        for field in FIELD_SPECS:
            values = rows.get(field)
            if values is None:
                continue
            for index, car in enumerate(cars):
                cell = values.get(index + 1)
                if cell is None:
                    car.field_status[field] = FieldStatus.PARSE_ERROR
                    continue
                value = str(cell.get("text", "")).strip()
                if value in {"", "-"}:
                    car.field_status[field] = FieldStatus.NOT_SUPPORTED if cell.get("has_media") else FieldStatus.EMPTY
                    continue
                value = re.sub(r"^[●○]\s*", "", value).strip()
                setattr(car, field, value)
                car.sources[field] = "dom"
                car.field_status[field] = FieldStatus.PARSED


def write_result(path: str, result: SeriesParseResult) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(result.to_dict(), handle, ensure_ascii=False, indent=2)
