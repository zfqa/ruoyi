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


def _listing_publish_time(anchor, selector: str | None) -> str | None:
    """Read a configured listing-card date without assuming a fixed DOM."""
    if selector:
        current = anchor
        # A selector is often a sibling of the link inside a card.  Search the
        # anchor and a few enclosing card-like elements, but never the whole
        # page (which could bind the first card's date to every article).
        for _ in range(5):
            if current is None:
                break
            node = current.select_one(selector)
            if node and (value := " ".join(node.get_text(" ", strip=True).split())):
                return value
            current = current.parent
    value = " ".join(anchor.get_text(" ", strip=True).split())
    return value or None


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
    links: list[tuple[str, str, str | None, str | None]] = []
    for anchor in anchors:
        href = str(anchor.get("href") or "").strip()
        if not href or href.startswith(("#", "javascript:", "mailto:", "tel:")):
            continue
        original = urljoin(column_url, href)
        canonical = normalize_article_url(source, href, column_url)
        allowed_domains = source.allowed_domains or [source.domain]
        if not canonical or not is_allowed_domain(canonical, allowed_domains) or not is_html_candidate(canonical):
            continue
        if canonical not in {item[1] for item in links}:
            title = " ".join(anchor.get_text(" ", strip=True).split()) or None
            links.append((original, canonical, title, _listing_publish_time(anchor, source.selectors.article_time_selector)))
        if limit is not None and len(links) >= limit:
            break
    return LinkDiscoveryResult(article_links=links)


