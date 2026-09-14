"""Pagination state and safe next-page discovery for static column pages."""
from __future__ import annotations

from dataclasses import dataclass, field

from bs4 import BeautifulSoup

from news_service.crawler.url_utils import is_allowed_domain, is_html_candidate, normalize_url
from news_service.models import NewsSourceConfig

_NEXT_LABELS = {"下一页", "下页", "next", ">", ">>", "›", "»"}


@dataclass
class PaginationState:
    max_pages: int
    visited_page_urls: set[str] = field(default_factory=set)
    pages_visited: int = 0
    stop_reason: str | None = None

    def can_visit(self, page_url: str) -> bool:
        if self.pages_visited >= self.max_pages:
            self.stop_reason = "max_pages"
            return False
        if page_url in self.visited_page_urls:
            self.stop_reason = "visited_page"
            return False
        return True

    def mark_visited(self, page_url: str) -> None:
        self.visited_page_urls.add(page_url)
        self.pages_visited += 1


def find_next_page(source: NewsSourceConfig, current_page_url: str, html: str) -> str | None:
    """Find a real pagination link; generic mode excludes ordinary article anchors."""
    soup = BeautifulSoup(html, "html.parser")
    candidates = (
        soup.select(source.selectors.next_page_selector)
        if source.selectors.next_page_selector
        else [*soup.select("link[rel~='next']"), *soup.select("a[rel~='next']"), *soup.select("a[href]")]
    )
    seen: set[str] = set()
    for node in candidates:
        href = str(node.get("href") or "").strip()
        if not href:
            continue
        if not source.selectors.next_page_selector:
            rel = " ".join(node.get("rel") or []).lower()
            # A document-level ``<link rel=next>`` is a standard HTML
            # pagination declaration and has no visible label.  Preserve the
            # existing label guard only for ordinary anchors.
            label = " ".join(node.get_text(" ", strip=True).split()).lower()
            if "next" not in rel and label not in _NEXT_LABELS:
                continue
        next_url = normalize_url(href, current_page_url)
        if not next_url or next_url in seen:
            continue
        seen.add(next_url)
        if is_allowed_domain(next_url, source.domain) and is_html_candidate(next_url):
            return next_url
    return None


