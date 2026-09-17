"""Static listing/article fetch and HTML extraction; no browser automation."""
from __future__ import annotations

import hashlib
import logging
import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Mapping

import httpx
from bs4 import BeautifulSoup, Tag

from news_service.models import NewsArticle, NewsSourceConfig

logger = logging.getLogger(__name__)
_USER_AGENT = "AutomotiveMarketInsightPOC/0.2 (+static-news-crawler)"
_CONTENT_SELECTORS = ("article", "main article", ".article-content", ".article-body", ".content", "main")
_IMAGE_ONLY_CONTENT = "【该新闻正文为图片类新闻】"
_VIDEO_ONLY_CONTENT = "【该新闻正文为视频类新闻】"
_IMAGE_AND_VIDEO_CONTENT = "【该新闻正文为图片及视频类新闻】"
_FINANCIAL_PDF_CONTENT = "【该新闻正文为财务业绩PDF】"
_PUBLIC_TEXT_MARKERS = (
    "在线客服",
    "客服电话",
    "媒体咨询",
    "联系我们",
    "隐私政策",
    "网站地图",
    "版权信息",
    "版权所有",
    "icp",
    "备案",
    "服务热线",
)
_FINANCIAL_PDF_TERMS = re.compile(
    r"\b(?:financial\s+(?:results|summary|performance|report|statements?)|"
    r"quarter(?:ly)?\s+results?|earnings\s+results?)\b|"
    r"财务业绩|财务报告|财务结果|业绩报告",
    re.IGNORECASE,
)
_ABSOLUTE_DATE_TEXT = re.compile(
    r"(?:\d{4}\s*[-/.]\s*\d{1,2}\s*[-/.]\s*\d{1,2}|"
    r"\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日?|"
    r"(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
    r"Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|"
    r"Dec(?:ember)?)\.?\s+\d{1,2},\s*\d{4}|"
    r"\d{1,2}\s+(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|"
    r"Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|"
    r"Nov(?:ember)?|Dec(?:ember)?)\.?\s+\d{4})",
    re.IGNORECASE,
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _clean_text(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def _content_hash(title: str, content: str) -> str:
    return hashlib.sha256(_clean_text(f"{title}\n{content}").lower().encode("utf-8")).hexdigest()


class FetchError(RuntimeError):
    def __init__(self, message: str, *, http_status: int | None, retry_count: int, error_type: str) -> None:
        super().__init__(message)
        self.http_status, self.retry_count, self.error_type = http_status, retry_count, error_type


@dataclass(frozen=True)
class FetchResult:
    html: str
    requested_url: str
    http_status: int
    retry_count: int


@dataclass(frozen=True)
class ParsedArticle:
    status: str
    article: NewsArticle | None = None
    warning: str | None = None


class StaticNewsCrawler:
    def __init__(self, retries: int = 3, timeout_seconds: float = 15.0) -> None:
        self.retries, self.timeout_seconds = retries, timeout_seconds

    def fetch(
        self,
        url: str,
        *,
        request_headers: Mapping[str, str] | None = None,
        method: str = "GET",
        json_body: Mapping[str, object] | None = None,
        form_body: Mapping[str, object] | None = None,
    ) -> FetchResult:
        last_error: Exception | None = None
        status: int | None = None
        for attempt in range(1, self.retries + 1):
            try:
                headers = {"User-Agent": _USER_AGENT, **(dict(request_headers) if request_headers else {})}
                with httpx.Client(timeout=self.timeout_seconds, follow_redirects=True, headers=headers) as client:
                    response = client.request(
                        method,
                        url,
                        json=dict(json_body) if json_body is not None else None,
                        data=dict(form_body) if form_body is not None else None,
                    )
                    status = response.status_code
                    response.raise_for_status()
                    return FetchResult(response.text, str(response.url), response.status_code, attempt - 1)
            except httpx.HTTPError as exc:
                last_error = exc
                logger.warning("[NEWS_CRAWL] request failed attempt=%s/%s url=%s error=%s", attempt, self.retries, url, exc)
                if attempt < self.retries:
                    time.sleep(min(2 ** (attempt - 1), 4))
        raise FetchError("静态网页请求失败", http_status=status, retry_count=self.retries - 1, error_type=type(last_error).__name__ if last_error else "RequestError")

    @staticmethod
    def _by_selector(soup: BeautifulSoup, selector: str | None, exclude_selectors: list[str] | None = None) -> str:
        node = soup.select_one(selector) if selector else None
        if node and exclude_selectors:
            node = BeautifulSoup(str(node), "html.parser")
            for excluded in node.select(", ".join(exclude_selectors)):
                excluded.decompose()
        return _clean_text(str(node.get("content") or node.get_text(" ", strip=True))) if node else ""

    @classmethod
    def _title(
        cls,
        soup: BeautifulSoup,
        selector: str | None,
        *,
        require_selector: bool = False,
        exclude_selectors: list[str] | None = None,
        strip_prefix: str | None = None,
        strip_suffix: str | None = None,
    ) -> str:
        selected = cls._by_selector(soup, selector, exclude_selectors)
        value = selected if selected or require_selector else cls._by_selector(soup, "h1") or cls._by_selector(soup, "title")
        if strip_prefix and value.startswith(strip_prefix):
            value = value[len(strip_prefix) :].strip()
        if strip_suffix and value.endswith(strip_suffix):
            value = value[: -len(strip_suffix)].strip()
        return value

    @classmethod
    def _content_node(cls, soup: BeautifulSoup, selector: str | None) -> Tag | None:
        """Locate the configured or generic article body without downloading media."""
        if selector:
            return soup.select_one(selector)
        for item in _CONTENT_SELECTORS:
            if node := soup.select_one(item):
                return node
        return None

    @classmethod
    def _content(cls, soup: BeautifulSoup, selector: str | None, *, exclude_selectors: list[str] | None = None) -> str:
        # A source-specific selector is an explicit assertion that this is the
        # article body.  Do not fall back to generic ``.content`` nodes (which
        # frequently contain cookie banners) merely because a short official
        # announcement has fewer than 80 characters.
        if selector:
            node = cls._content_node(soup, selector)
            if node:
                # Remove only explicitly configured, source-local public
                # widgets from this extraction.  The original parsed page is
                # left intact for title/date/media detection.
                node = BeautifulSoup(str(node), "html.parser")
                for exclude_selector in exclude_selectors or []:
                    for excluded in node.select(exclude_selector):
                        excluded.decompose()
                # A site-specific selector identifies the article body.  Use
                # all text within that body (not only ``<p>`` elements), so
                # headings, list items and text in nested containers are not
                # silently omitted from the stored article.
                return _clean_text(node.get_text(" ", strip=True))
            return ""
        if node := cls._content_node(soup, selector):
            text = "\n".join(_clean_text(p.get_text(" ", strip=True)) for p in node.select("p")) or _clean_text(node.get_text(" ", strip=True))
            if text.strip():
                return text.strip()
        return "\n".join(_clean_text(p.get_text(" ", strip=True)) for p in soup.select("p") if _clean_text(p.get_text(" ", strip=True))).strip()

    @classmethod
    def _is_public_text(cls, value: str) -> bool:
        """Recognise common shared footer/contact text, never as article text."""
        normalized = _clean_text(value).lower()
        return bool(normalized) and any(marker in normalized for marker in _PUBLIC_TEXT_MARKERS)

    @classmethod
    def _meaningful_text_blocks(cls, node: Tag) -> list[str]:
        """Return substantive paragraph-like blocks, excluding shared page text."""
        blocks: list[str] = []
        seen: set[str] = set()
        # Do not count navigation/share-menu ``li`` elements as news prose.
        # They are common in article pages and caused video-led pages to look
        # like they had several independent text paragraphs.
        for element in node.select("p, blockquote"):
            text = _clean_text(element.get_text(" ", strip=True))
            if not text or cls._is_public_text(text) or text in seen:
                continue
            seen.add(text)
            blocks.append(text)
        return blocks

    @classmethod
    def _media_content_marker(cls, soup: BeautifulSoup, selector: str | None) -> str | None:
        """Classify media-led detail pages without downloading or analysing media.

        A media element alone does not override an article that has a complete
        paragraph structure.  Conversely, a player plus only a title, slogan,
        or shared footer/contact copy is treated as media-led content.
        """
        node = cls._content_node(soup, selector)
        if node is None:
            return None
        has_image = bool(node.select("img, picture"))
        # Some sites place a release-page player beside (rather than inside)
        # the text node selected as the body.  Check the rendered document for
        # explicit player/video structures, but do not treat every iframe as a
        # video: maps, analytics and shared widgets must not change a story.
        has_video = bool(
            soup.select(
                "video, [data-video], [class*='video'], [class*='player'], "
                "[id*='player'], iframe[src*='video'], iframe[class*='video'], "
                "iframe[class*='player']"
            )
        )
        if not has_image and not has_video:
            return None

        # This is a DOM-structure rule, not a character-count threshold.
        # A still image is routinely placed before a single, complete text
        # paragraph on official press pages.  That is a normal text article,
        # not an image-led article, so preserve its real body whenever there
        # is substantive text and no video player.  Video-led pages retain
        # the stricter multi-block check below because a short player slogan
        # is common there.
        meaningful_blocks = cls._meaningful_text_blocks(node)
        if has_image and not has_video and meaningful_blocks:
            return None
        # A normal article with media has at least two independent substantive
        # paragraph-like blocks; a one-line player introduction remains media
        # led.  Shared footer/contact text is excluded above.
        if len(meaningful_blocks) >= 2:
            return None
        if has_image and has_video:
            return _IMAGE_AND_VIDEO_CONTENT
        if has_image:
            return _IMAGE_ONLY_CONTENT
        if has_video:
            return _VIDEO_ONLY_CONTENT
        return None

    @classmethod
    def _published_at(cls, soup: BeautifulSoup, selector: str | None, *, value_regex: str | None = None) -> str | None:
        """Extract a publisher absolute date; never keep unparseable selector prose.

        Mis-targeted CSS nodes (source labels, media names) previously short-
        circuited extraction and caused in-range articles to be dropped as
        ``publish_time_unknown``.  Candidates are validated with
        ``first_parseable_publish_value`` before acceptance.
        """
        from news_service.utils.date_utils import first_parseable_publish_value

        def _from_raw(raw: str | None, *, apply_regex: bool) -> str | None:
            text = _clean_text(raw or "")
            if not text:
                return None
            if apply_regex and value_regex:
                match = re.search(value_regex, text)
                if not match:
                    return None
                text = match.group(1) if match.groups() else match.group(0)
            return first_parseable_publish_value(text)

        candidates: list[tuple[str, bool]] = []
        if selector:
            nodes = soup.select(selector)
            for node in nodes:
                raw = str(node.get("content") or node.get_text(" ", strip=True))
                candidates.append((raw, True))
            # Fragile nth-of-type selectors often hit a sibling label.  When the
            # configured match is unparseable, also try every direct sibling
            # under the same parent (still scoped to the meta bar, not the page).
            if nodes:
                parents = []
                for node in nodes[:3]:
                    if node.parent is not None and node.parent not in parents:
                        parents.append(node.parent)
                for parent in parents:
                    for child in parent.find_all(recursive=False):
                        raw = str(child.get("content") or child.get_text(" ", strip=True))
                        candidates.append((raw, True))

        for css, attr in (
            ("meta[property='article:published_time']", "content"),
            ("meta[property='og:published_time']", "content"),
            ("meta[name='publishdate']", "content"),
            ("meta[name='pubdate']", "content"),
            ("meta[name='publish_date']", "content"),
            ("meta[name='date']", "content"),
            ("meta[itemprop='datePublished']", "content"),
            ("time[datetime]", "datetime"),
            ("time", "datetime"),
        ):
            node = soup.select_one(css)
            if node:
                candidates.append((str(node.get(attr) or node.get_text(" ", strip=True)), False))

        for raw, apply_regex in candidates:
            if usable := _from_raw(raw, apply_regex=apply_regex):
                return usable

        # Some official press rooms put the dateline at the beginning of the
        # article copy or description but expose no semantic date element.
        # Keep that publisher-provided absolute date so range filtering can
        # still operate; relative dates are deliberately not inferred.
        text = _clean_text(soup.get_text(" ", strip=True))
        if match := _ABSOLUTE_DATE_TEXT.search(text[:8000]):
            if usable := first_parseable_publish_value(match.group(0)):
                return usable
        return None

    @staticmethod
    def _matched_keywords(source: NewsSourceConfig, title: str, content: str) -> list[str]:
        if not source.keywords or source.keyword_mode == "off":
            return []
        combined = f"{title}\n{content}".lower()
        return [keyword for keyword in source.keywords if keyword.lower() in combined]

    @staticmethod
    def _is_financial_pdf_entry(soup: BeautifulSoup) -> bool:
        """Recognise an official financial-news page whose primary material is a PDF.

        The PDF is deliberately not downloaded or parsed.  Require both a
        public PDF link and an explicit financial-results phrase so ordinary
        press pages that merely attach a brochure are unaffected.
        """
        has_pdf = any(
            ".pdf" in str(node.get("href") or "").lower().split("?")[0]
            for node in soup.select("a[href]")
        )
        return has_pdf and bool(_FINANCIAL_PDF_TERMS.search(_clean_text(soup.get_text(" ", strip=True))))

    def parse_article(
        self,
        source: NewsSourceConfig,
        *,
        original_url: str,
        canonical_url: str,
        fetched: FetchResult,
        fallback_title: str | None = None,
    ) -> ParsedArticle:
        soup = BeautifulSoup(fetched.html, "html.parser")
        title = self._title(
            soup,
            source.selectors.title_selector,
            require_selector=source.selectors.require_title_selector,
            exclude_selectors=source.selectors.title_exclude_selectors,
            strip_prefix=source.selectors.title_strip_prefix,
            strip_suffix=source.selectors.title_strip_suffix,
        )
        financial_pdf = self._is_financial_pdf_entry(soup)
        if not title and financial_pdf:
            # The discovery card is the publisher's own title and is used
            # only for a verified financial-PDF entry whose detail template
            # omits the normal HTML headline.
            title = _clean_text(fallback_title or "")
        content = self._content(
            soup,
            source.selectors.content_selector,
            exclude_selectors=source.selectors.content_exclude_selectors,
        )
        if not title:
            return ParsedArticle(status="parse_failed", warning="标题为空")
        media_marker = self._media_content_marker(soup, source.selectors.content_selector)
        if financial_pdf:
            content = _FINANCIAL_PDF_CONTENT
        elif media_marker is not None:
            content = media_marker
        elif source.selectors.require_content_selector and not content:
            return ParsedArticle(status="parse_failed", warning="未命中站点专用正文容器")
        elif not content:
            # A confirmed detail URL remains a valid news record even where
            # the body is empty.  No crawler time or invented text is used as
            # a substitute.
            content = ""
        matched = self._matched_keywords(source, title, content)
        if source.keywords and source.keyword_mode == "any" and not matched:
            return ParsedArticle(status="keyword_filtered")
        if source.keywords and source.keyword_mode == "all" and len(matched) != len(source.keywords):
            return ParsedArticle(status="keyword_filtered")
        published_at = self._published_at(
            soup,
            source.selectors.publish_time_selector,
            value_regex=source.selectors.publish_time_regex,
        )
        return ParsedArticle(
            status="success",
            article=NewsArticle(source_name=source.name, source_site=source.domain, title=title, content=content, published_at=published_at, crawled_at=utc_now(), url=fetched.requested_url, original_url=original_url, canonical_url=canonical_url, matched_keywords=matched, content_hash=_content_hash(title, content)),
            warning=None if published_at else "未提取到发布时间",
        )


