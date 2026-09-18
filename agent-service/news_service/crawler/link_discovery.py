"""Column-page article link discovery constrained by a source whitelist."""
from __future__ import annotations

from dataclasses import dataclass
import re
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from news_service.crawler.url_utils import is_allowed_domain, is_html_candidate, normalize_url
from news_service.models import NewsSourceConfig


_BYD_FRONTEND_NEWS_ROUTE = re.compile(r"^/page/byd-cn/news-\d{4}/detail(?P<detail_id>\d+)/?$")


@dataclass(frozen=True)
class LinkDiscoveryResult:
    # (original URL, canonical URL, listing title, listing published-at text)
    article_links: list[tuple[str, str, str | None, str | None]]


def _listing_publish_time(
    anchor,
    selector: str | None,
    *,
    format_hint: str | None = None,
) -> str | None:
    """Read a configured listing-card date only.

    Without an explicit ``article_time_selector``, return ``None``.  Heuristic
    parent/sibling scanning previously invented unrelated dates (e.g. a nearby
    card or chrome date) and caused in-range articles to be dropped by the
    listing pre-filter before detail parsing could recover the real date.
    """
    from news_service.utils.date_utils import first_parseable_publish_value

    if not selector:
        return None

    def _usable(raw: str | None) -> str | None:
        text = " ".join(str(raw or "").split())
        if not text:
            return None
        if usable := first_parseable_publish_value(text, format_hint=format_hint):
            return usable
        # Allow a short absolute date embedded in a slightly longer label.
        if len(text) <= 80:
            from news_service.utils.date_utils import parse_publish_date

            if format_hint:
                return None
            if parse_publish_date(text) is not None:
                return first_parseable_publish_value(text)
        return None

    current = anchor
    # A selector is often a sibling of the link inside a card.  Search the
    # anchor and a few enclosing card-like elements, but never the whole
    # page (which could bind the first card's date to every article).
    for _ in range(5):
        if current is None:
            break
        for node in current.select(selector):
            # Prefer an explicit machine-readable datetime attribute when the
            # publisher exposes one (e.g. Mazda ``time datetime="2026.09.01"``).
            attr = node.get("datetime")
            if usable := _usable(attr):
                return usable
            if usable := _usable(node.get_text(" ", strip=True)):
                return usable
        current = current.parent
    return None


def normalize_article_url(source: NewsSourceConfig, value: str, base_url: str) -> str | None:
    """Return the crawlable article URL while retaining the discovered URL separately.

    BYD's news list exposes front-end routes such as
    ``/page/byd-cn/news-2026/detail620``.  Their server-rendered article page
    is ``/cn/detail620``.  The conversion is deliberately limited to the
    configured ``byd.com`` source so other sources retain existing behaviour.
    """
    canonical = normalize_url(value, base_url)
    if not canonical or source.domain.lower().strip().lstrip(".") != "byd.com":
        return canonical

    from urllib.parse import urlparse

    parsed = urlparse(canonical)
    if not is_allowed_domain(canonical, "byd.com"):
        return canonical
    if match := _BYD_FRONTEND_NEWS_ROUTE.fullmatch(parsed.path):
        converted = f"{parsed.scheme}://{parsed.netloc}/cn/detail{match.group('detail_id')}"
        return normalize_url(converted)
    return canonical


def discover_article_links(source: NewsSourceConfig, column_url: str, html: str, *, limit: int | None) -> LinkDiscoveryResult:
    soup = BeautifulSoup(html, "html.parser")
    anchors = soup.select(source.selectors.article_link_selector) if source.selectors.article_link_selector else soup.select("a[href]")
    format_hint = (
        source.structured_discovery.article_time_format
        if source.structured_discovery is not None
        else None
    )
    links: list[tuple[str, str, str | None, str | None]] = []
    for anchor in anchors:
        href = re.sub(r"\s+", "", str(anchor.get("href") or "").strip())
        if not href or href.startswith(("#", "javascript:", "mailto:", "tel:")):
            continue
        original = urljoin(column_url, href)
        canonical = normalize_article_url(source, href, column_url)
        allowed_domains = source.allowed_domains or [source.domain]
        if not canonical or not is_allowed_domain(canonical, allowed_domains) or not is_html_candidate(canonical):
            continue
        if canonical not in {item[1] for item in links}:
            title = " ".join(anchor.get_text(" ", strip=True).split()) or None
            links.append(
                (
                    original,
                    canonical,
                    title,
                    _listing_publish_time(
                        anchor,
                        source.selectors.article_time_selector,
                        format_hint=format_hint,
                    ),
                )
            )
        if limit is not None and len(links) >= limit:
            break
    return LinkDiscoveryResult(article_links=links)


