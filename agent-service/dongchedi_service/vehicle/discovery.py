"""Dynamic brand-to-series discovery for Dongchedi's current vehicle library."""
from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from collections.abc import Mapping
import logging
from time import monotonic
from typing import Any
from urllib.parse import urljoin
import re

from playwright.sync_api import Page, Response


logger = logging.getLogger(__name__)


BRAND_API_FRAGMENT = "/motor/pc/car/brand/all_brand"
SERIES_API_FRAGMENT = "/motor/pc/car/brand/select_series_v2"
# `/auto/` currently redirects to the rendered vehicle library.  Starting at
# this stable entry point also works when the page provides its filter data in
# server-rendered DOM instead of issuing the observed JSON requests.
LIBRARY_URL = "https://www.dongchedi.com/auto/"

# The business brand whitelist is intentionally fixed by the POC.  These are
# Dongchedi *brand source identifiers*, observed from the site's own rendered
# brand routes; they are not a vehicle-series list.  Runtime discovery still
# reads every selectable series from the current rendered page.  The mapping
# is used only when a brand is absent from the homepage's rotating "popular
# brands" area.
TARGET_BRAND_SOURCE_IDS: dict[str, str] = {
    "比亚迪": "16",
    "蔚来": "112",
    "长城": "8",
    "吉利": "73",
    "长安": "35",
    "理想": "202",
    "奇瑞": "18",
}
SERIES_CARD_COUNT_SCRIPT = """() => Array.from(document.querySelectorAll('a[href]'))
  .filter((node) => /^\\/auto\\/series\\/\\d+$/.test(node.getAttribute('href') || ''))
  .length"""


@dataclass(frozen=True)
class DiscoveredSeries:
    series_id: str
    series_name: str
    parameter_url: str
    series_status: str = "unknown"
    raw_business_status: int | str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class BrandDiscoveryResult:
    brand: str
    brand_id: str
    discovered_at: str
    series: list[DiscoveredSeries]
    excluded_non_matching_brand_series: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "brand": self.brand,
            "brand_id": self.brand_id,
            "discovered_at": self.discovered_at,
            "series_count": len(self.series),
            "excluded_non_matching_brand_series": self.excluded_non_matching_brand_series,
            "series": [item.to_dict() for item in self.series],
        }


class DongchediSeriesDiscovery:
    """Observe brand-library responses, then select one exact brand in the UI.

    No known series list is embedded here.  The selected brand's ID is first
    read from Dongchedi's brand response and every returned series is filtered
    by that exact ID, so similarly named group/sub-brand entries are excluded.
    """

    def discover(self, page: Page, brand_name: str) -> BrandDiscoveryResult:
        started_at = monotonic()
        stage = "detect_page_state"

        def mark(next_stage: str) -> None:
            nonlocal stage
            stage = next_stage
            logger.info(
                "[series-discovery] stage brand=%s stage=%s elapsed=%dms",
                brand_name,
                stage,
                (monotonic() - started_at) * 1000,
            )

        payloads: dict[str, list[Mapping[str, Any]]] = {"brand": [], "series": []}

        def on_response(response: Response) -> None:
            self._collect_response(response, payloads)

        try:
            mark("detect_page_state")
            page.on("response", on_response)
            page.goto(LIBRARY_URL, wait_until="domcontentloaded", timeout=60_000)
            page.wait_for_timeout(4_000)
            mark("locate_brand")
            brand_id = self._resolve_brand_id(payloads["brand"], brand_name)
            series: list[DiscoveredSeries] = []
            excluded = 0

            if brand_id is not None:
                # A single, exact visible brand click lets the site perform its own
                # signed API request. We only observe the response; no API signature
                # is replayed or constructed by this project.
                locator = page.get_by_text(brand_name, exact=True)
                try:
                    mark("click_brand")
                    locator.first.click(timeout=5_000)
                    mark("wait_after_brand_click")
                    page.wait_for_timeout(3_000)
                except Exception as exc:
                    raise RuntimeError(f"品牌选择失败：{brand_name}") from exc
                series, excluded = self._resolve_series(payloads["series"], brand_id)

            # Some current pages server-render the brand filter and vehicle cards,
            # without issuing all_brand/select_series_v2 requests.  Read only the
            # exact brand's rendered filter URL and its resulting real series links;
            # this is a UI fallback, not a replay of private/signed APIs.
            if brand_id is None or not series:
                mark("locate_series_container")
                dom_brand_id, dom_series = self._discover_from_rendered_library(page, brand_name)
                if dom_brand_id is not None:
                    brand_id = dom_brand_id
                if dom_series:
                    series = dom_series

            mark("validate_series_result")
            if brand_id is None:
                raise LookupError(f"未在懂车帝品牌层级中找到精确品牌：{brand_name}")
            if not series:
                raise LookupError(f"未捕获品牌 {brand_name} 的可选车系")
            return BrandDiscoveryResult(
                brand=brand_name,
                brand_id=brand_id,
                discovered_at=datetime.now(timezone.utc).isoformat(),
                series=series,
                excluded_non_matching_brand_series=excluded,
            )
        except Exception as exc:
            try:
                page_url = page.url
                page_title = page.title()
            except Exception:
                page_url = "<unavailable>"
                page_title = "<unavailable>"
            logger.exception(
                "[series-discovery] failed brand=%s stage=%s root_exception_type=%s root_exception_message=%s "
                "page_url=%s page_title=%s elapsed=%dms",
                brand_name,
                stage,
                type(exc).__name__,
                str(exc),
                page_url,
                page_title,
                (monotonic() - started_at) * 1000,
            )
            raise

    @staticmethod
    def _discover_from_rendered_library(page: Page, brand_name: str) -> tuple[str | None, list[DiscoveredSeries]]:
        """Return one exact UI brand filter and the visible, filtered series cards.

        The DOM classes are intentionally not used because they are build-time
        hashes.  The route patterns and exact brand text are stable semantic
        anchors.  Only links matching `/auto/series/<numeric id>` are accepted;
        score/image sub-pages are excluded.
        """
        payload = page.evaluate(
            """(target) => {
              const brandLink = Array.from(document.querySelectorAll('a[href]')).find((node) =>
                (node.innerText || '').trim() === target &&
                /^\\/auto\\/library\\//.test(node.getAttribute('href') || '')
              );
              return { href: brandLink ? brandLink.getAttribute('href') : null };
            }""",
            brand_name,
        )
        href = payload.get("href") if isinstance(payload, Mapping) else None
        if not isinstance(href, str) or not href:
            # The homepage only renders a rotating popular-brand subset.  The
            # selected business brand may be absent even though it remains a
            # valid, supported Dongchedi brand.  Use its observed stable brand
            # route, then continue parsing *live* series cards below.
            source_id = TARGET_BRAND_SOURCE_IDS.get(brand_name)
            if source_id is None:
                return None, []
            href = f"/auto/library/x-x-x-x-x-x-x-x-x-x-x-x-x-x-x-{source_id}-x-x"

        # Brand filter routes contain the selected numeric brand slot.  We do
        # not infer it from a series card or from a similarly named group.
        numeric_slots = re.findall(r"(?<!\d)(\d+)(?!\d)", href)
        brand_id = numeric_slots[-1] if numeric_slots else None
        if brand_id is None:
            return None, []

        page.goto(urljoin(page.url, href), wait_until="domcontentloaded", timeout=60_000)
        page.wait_for_timeout(3_000)
        DongchediSeriesDiscovery._load_all_visible_series_cards(page)
        cards = page.evaluate(
            """() => Array.from(document.querySelectorAll('a[href]'))
              .map((node) => ({
                href: node.getAttribute('href'),
                name: (node.innerText || '').trim(),
              }))
              .filter((item) => /^\\/auto\\/series\\/\\d+$/.test(item.href || '') && item.name)
            """
        )
        found: dict[str, DiscoveredSeries] = {}
        if isinstance(cards, list):
            for card in cards:
                if not isinstance(card, Mapping):
                    continue
                match = re.fullmatch(r"/auto/series/(\d+)", str(card.get("href", "")))
                name = str(card.get("name", "")).strip()
                if not match or not name:
                    continue
                series_id = match.group(1)
                found.setdefault(
                    series_id,
                    DiscoveredSeries(
                        series_id=series_id,
                        series_name=name,
                        parameter_url=f"https://www.dongchedi.com/auto/params-carIds-x-{series_id}",
                    ),
                )
        return brand_id, list(found.values())

    @staticmethod
    def _load_all_visible_series_cards(page: Page) -> None:
        """Drive the site's ordinary infinite list until it stops adding cards.

        The vehicle library initially renders only a first batch (often 20 or
        30 cards).  It appends further cards when its bottom sentinel enters
        the viewport; there is no next-page URL to follow.  A bounded loop and
        consecutive no-growth checks prevent an endless scroll if the page
        changes or an upstream request fails.
        """
        previous_count = -1
        no_growth_rounds = 0
        for _ in range(50):
            try:
                current_count = int(page.evaluate(SERIES_CARD_COUNT_SCRIPT))
            except Exception:
                return
            if current_count > previous_count:
                previous_count = current_count
                no_growth_rounds = 0
            else:
                no_growth_rounds += 1
                if no_growth_rounds >= 3:
                    return
            page.mouse.wheel(0, 900)
            page.wait_for_timeout(700)

    @staticmethod
    def _collect_response(response: Response, payloads: dict[str, list[Mapping[str, Any]]]) -> None:
        category = "brand" if BRAND_API_FRAGMENT in response.url else "series" if SERIES_API_FRAGMENT in response.url else None
        if category is None:
            return
        try:
            payload = response.json()
        except Exception:
            return
        if isinstance(payload, Mapping):
            payloads[category].append(payload)

    @staticmethod
    def _resolve_brand_id(payloads: list[Mapping[str, Any]], target_brand: str) -> str | None:
        for payload in payloads:
            data = payload.get("data")
            if not isinstance(data, Mapping):
                continue
            for entry in data.get("brand", []):
                if not isinstance(entry, Mapping):
                    continue
                info = entry.get("info")
                if isinstance(info, Mapping) and str(info.get("brand_name", "")).strip() == target_brand:
                    brand_id = info.get("brand_id")
                    return str(brand_id) if brand_id is not None else None
        return None

    @staticmethod
    def _resolve_series(payloads: list[Mapping[str, Any]], brand_id: str) -> tuple[list[DiscoveredSeries], int]:
        found: dict[str, DiscoveredSeries] = {}
        excluded = 0
        for payload in payloads:
            data = payload.get("data")
            series_items = data.get("series", []) if isinstance(data, Mapping) else []
            for item in series_items:
                if not isinstance(item, Mapping):
                    continue
                if str(item.get("brand_id")) != brand_id:
                    excluded += 1
                    continue
                series_id = item.get("id")
                series_name = item.get("outter_name")
                if series_id is None or not str(series_name or "").strip():
                    continue
                identifier = str(series_id)
                found.setdefault(
                    identifier,
                    DiscoveredSeries(
                        series_id=identifier,
                        series_name=str(series_name).strip(),
                        parameter_url=f"https://www.dongchedi.com/auto/params-carIds-x-{identifier}",
                        raw_business_status=item.get("business_status"),
                    ),
                )
        return list(found.values()), excluded
