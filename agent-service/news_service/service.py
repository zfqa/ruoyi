"""Column-to-article news ingestion with explicit outcome logging."""
from __future__ import annotations

import logging
import re
from datetime import date, datetime, timedelta, timezone
from typing import Callable

from news_service.config import load_sources
from news_service.crawler.link_discovery import discover_article_links
from news_service.crawler.structured_discovery import StructuredDiscoveryError, discover_structured_articles
from news_service.crawler.pagination import PaginationState, find_next_page
from news_service.crawler.dynamic_crawler import DynamicCrawler
from news_service.crawler.static_crawler import FetchError, StaticNewsCrawler, utc_now
from news_service.crawler.url_utils import normalize_url
from news_service.models import CrawlFailure, CrawlLog, CrawlReport, DiscoveryFeedResult, NewsSourceConfig, SourceCrawlResult
from news_service.storage import NewsStore
from news_service.utils.date_utils import parse_publish_date, parse_requested_date

logger = logging.getLogger(__name__)


def _frequency_delta(value: str) -> timedelta:
    matched = re.fullmatch(r"(\d+)([mhd])", value.strip().lower())
    if not matched:
        raise ValueError(f"不支持的抓取频率: {value}")
    amount, unit = int(matched.group(1)), matched.group(2)
    return timedelta(minutes=amount) if unit == "m" else timedelta(hours=amount) if unit == "h" else timedelta(days=amount)


def _with_listing_published_at(parsed, listing_published_at: str | None, *, prefer_listing_date: bool = False):
    """Retain an official list-card date when the detail page has none/unusable.

    Some public newsrooms expose a publisher date on the listing card but do
    not repeat it in their article HTML, or the detail CSS selector lands on a
    non-date label.  Detail-page dates win when parseable; otherwise a
    parseable listing date is kept.  No date is inferred from crawl time.
    """
    from news_service.utils.date_utils import first_parseable_publish_value

    if parsed.article is None:
        return parsed
    listing_value = first_parseable_publish_value(listing_published_at)
    detail_value = first_parseable_publish_value(parsed.article.published_at)
    if detail_value is not None and not prefer_listing_date:
        if detail_value != parsed.article.published_at:
            return parsed.__class__(
                status=parsed.status,
                article=parsed.article.model_copy(update={"published_at": detail_value}),
                warning=parsed.warning,
            )
        return parsed
    if listing_value is None:
        # Drop unparseable detail prose so range filtering can report a clear
        # missing date instead of treating labels as timestamps.
        if parsed.article.published_at and detail_value is None:
            return parsed.__class__(
                status=parsed.status,
                article=parsed.article.model_copy(update={"published_at": None}),
                warning=parsed.warning or "未提取到可解析的发布时间",
            )
        return parsed
    return parsed.__class__(
        status=parsed.status,
        article=parsed.article.model_copy(update={"published_at": listing_value}),
        warning=None,
    )


class NewsService:
    def __init__(self, store: NewsStore | None = None, crawler: StaticNewsCrawler | None = None, dynamic_crawler: DynamicCrawler | None = None) -> None:
        self.store = store or NewsStore()
        self.static_crawler = crawler or StaticNewsCrawler()
        # ``crawler`` remains an alias for integrations that injected the old crawler.
        self.crawler = self.static_crawler
        self.dynamic_crawler = dynamic_crawler or DynamicCrawler()

    def _crawler_for(self, source: NewsSourceConfig, *, detail: bool = False) -> StaticNewsCrawler:
        crawler_type = source.detail_crawler_type if detail and source.detail_crawler_type else source.crawler_type
        return self.dynamic_crawler if crawler_type == "dynamic" else self.static_crawler

    def _fetch(self, source: NewsSourceConfig, url: str, *, detail: bool = False):
        crawler_type = source.detail_crawler_type if detail and source.detail_crawler_type else source.crawler_type
        crawler = self._crawler_for(source, detail=detail)
        if crawler_type == "dynamic":
            logger.info("[DYNAMIC_CRAWLER] source=%s url=%s", source.name, url)
            return self.dynamic_crawler.fetch(url, wait_time_seconds=source.wait_time)
        return crawler.fetch(url)

    def _due(self, source: NewsSourceConfig) -> bool:
        last_success = self.store.last_success_at(source.name)
        if not last_success:
            return True
        try:
            return datetime.now(timezone.utc) >= datetime.fromisoformat(last_success) + _frequency_delta(source.crawl_frequency)
        except ValueError:
            return True

    def _log(self, source: NewsSourceConfig, *, column_url: str | None, page_url: str | None = None, article_url: str | None, status: str, published_at: str | None = None, http_status: int | None = None, retry_count: int = 0, error_type: str | None = None, error_message: str | None = None) -> None:
        self.store.add_log(CrawlLog(source_name=source.name, column_url=column_url, page_url=page_url, article_url=article_url, published_at=published_at, status=status, http_status=http_status, retry_count=retry_count, error_type=error_type, error_message=(error_message or "")[:500] or None, crawl_time=utc_now()))

    @staticmethod
    def _increase(result: SourceCrawlResult, status: str) -> None:
        if hasattr(result, status):
            setattr(result, status, getattr(result, status) + 1)

    def _discover_single(
        self,
        source: NewsSourceConfig,
        result: SourceCrawlResult,
        *,
        publish_time_start: date | None,
        publish_time_end: date | None,
    ) -> list[tuple[str, str, str, str, str | None, str | None]]:
        """Return root/listing/original/canonical URLs plus listing title and date."""
        output: list[tuple[str, str, str, str, str | None, str | None]] = []
        seen_articles: set[str] = set()
        pagination = PaginationState(max_pages=source.max_pages)
        time_range_mode = publish_time_start is not None or publish_time_end is not None

        def is_outside_listing_range(value: str | None) -> bool:
            """Pre-filter only explicit publisher list dates.

            A missing or unparseable listing date remains eligible for detail
            parsing, where the detail-page date is still authoritative.
            """
            listed_date = parse_publish_date(value)
            if listed_date is None:
                return False
            return (
                (publish_time_start is not None and listed_date < publish_time_start)
                or (publish_time_end is not None and listed_date > publish_time_end)
            )

        def retain_links(links, *, root_column_url: str, page_url: str) -> None:
            for original_url, canonical_url, title, listing_published_at in links.article_links:
                if canonical_url in seen_articles:
                    continue
                seen_articles.add(canonical_url)
                # ``discovered`` counts unique article candidates exposed by
                # discovery before requested-time filtering.
                result.discovered += 1
                if time_range_mode and is_outside_listing_range(listing_published_at):
                    # Only trust listing dates that came from an explicit card
                    # time selector / structured field.  Heuristic listing dates
                    # must not discard candidates before detail parsing.
                    listing_date_is_configured = bool(
                        source.selectors.article_time_selector
                        or (
                            source.structured_discovery
                            and source.structured_discovery.prefer
                            and source.structured_discovery.article_time_field
                        )
                    )
                    if listing_date_is_configured:
                        result.publish_time_filtered += 1
                        result.listing_publish_time_filtered += 1
                        self._log(
                            source,
                            column_url=root_column_url,
                            page_url=page_url,
                            article_url=canonical_url,
                            published_at=listing_published_at,
                            status="publish_time_filtered",
                            error_type="listing_published_at_out_of_range",
                            error_message="列表发布时间不在指定 publish_time 范围内",
                        )
                        continue
                    listing_published_at = None
                output.append((root_column_url, page_url, original_url, canonical_url, title, listing_published_at))

        for configured_url in source.column_urls:
            root_column_url = str(configured_url)
            column_url = normalize_url(root_column_url) or root_column_url
            template_page = source.pagination_start_page
            if (
                source.crawler_type == "dynamic"
                and (source.selectors.next_page_selector or source.selectors.infinite_scroll)
                and not (source.structured_discovery and source.structured_discovery.prefer)
            ):
                # Dynamic "load more" pages retain earlier cards in every
                # later DOM snapshot.  Track only newly observed cards when
                # evaluating the descending-date stop condition; examining the
                # whole cumulative document would never become "all old".
                dynamic_seen_for_stop: set[str] = set()

                def continue_dynamic_pagination(page_html: str) -> bool:
                    if publish_time_start is None or source.published_time_order != "desc":
                        return True
                    page_links = discover_article_links(source, column_url, page_html, limit=None)
                    new_dates: list[date | None] = []
                    for _original, canonical, _title, listed_at in page_links.article_links:
                        if canonical in dynamic_seen_for_stop:
                            continue
                        dynamic_seen_for_stop.add(canonical)
                        new_dates.append(parse_publish_date(listed_at))
                    return not (
                        new_dates
                        and all(item is not None and item < publish_time_start for item in new_dates)
                    )

                try:
                    dynamic_pages = (
                        self.dynamic_crawler.fetch_scrolled(
                            column_url,
                            max_pages=source.max_pages,
                            wait_time_seconds=source.wait_time,
                        )
                        if source.selectors.infinite_scroll
                        else self.dynamic_crawler.fetch_paginated(
                            column_url,
                            next_page_selector=source.selectors.next_page_selector or "",
                            max_pages=source.max_pages,
                            wait_time_seconds=source.wait_time,
                            should_continue=continue_dynamic_pagination,
                        )
                    )
                except FetchError as exc:
                    result.fetch_failed += 1
                    self._log(source, column_url=root_column_url, page_url=column_url, article_url=None, status="fetch_failed", http_status=exc.http_status, retry_count=exc.retry_count, error_type=exc.error_type, error_message=str(exc))
                    pagination.stop_reason = "request_failed"
                    break
                for fetched in dynamic_pages.pages:
                    pagination.pages_visited += 1
                    self._log(source, column_url=root_column_url, page_url=fetched.requested_url, article_url=None, status="success", http_status=fetched.http_status, retry_count=fetched.retry_count)
                    discovery_limit = None if time_range_mode else source.max_articles_per_run - len(output)
                    links = discover_article_links(source, fetched.requested_url, fetched.html, limit=discovery_limit)
                    retain_links(links, root_column_url=root_column_url, page_url=fetched.requested_url)
                    if not time_range_mode and len(output) >= source.max_articles_per_run:
                        pagination.stop_reason = "max_articles"
                        break
                    listing_dates = [parse_publish_date(item[3]) for item in links.article_links]
                    if (
                        publish_time_start is not None
                        and source.published_time_order == "desc"
                        and listing_dates
                        and all(item is not None and item < publish_time_start for item in listing_dates)
                    ):
                        pagination.stop_reason = "publish_time_before_start"
                        break
                if pagination.stop_reason is None:
                    pagination.stop_reason = dynamic_pages.stop_reason
                break
            while column_url:
                if not time_range_mode and len(output) >= source.max_articles_per_run:
                    pagination.stop_reason = "max_articles"
                    break
                if not pagination.can_visit(column_url):
                    break
                pagination.mark_visited(column_url)
                try:
                    fetched = self._fetch(source, column_url)
                except FetchError as exc:
                    result.fetch_failed += 1
                    self._log(source, column_url=root_column_url, page_url=column_url, article_url=None, status="fetch_failed", http_status=exc.http_status, retry_count=exc.retry_count, error_type=exc.error_type, error_message=str(exc))
                    pagination.stop_reason = "request_failed"
                    break
                self._log(source, column_url=root_column_url, page_url=column_url, article_url=None, status="success", http_status=fetched.http_status, retry_count=fetched.retry_count)
                discovery_limit = None if time_range_mode else source.max_articles_per_run - len(output)
                links = discover_article_links(source, column_url, fetched.html, limit=discovery_limit)
                structured_links = None
                # HTML discovery remains the default.  A configured public
                # structured source is a fallback only when the column DOM
                # exposes no article links; detail parsing remains unchanged.
                if source.structured_discovery and (source.structured_discovery.prefer or not links.article_links):
                    try:
                        structured_links = discover_structured_articles(
                            source,
                            page_url=column_url,
                            page_html=fetched.html,
                            limit=discovery_limit,
                            fetch_text=lambda url: self.static_crawler.fetch(
                                url,
                                request_headers=source.structured_discovery.request_headers,
                            ).html,
                            fetch_request=lambda url, method, request_body, request_headers, request_encoding="json": (
                                self.dynamic_crawler.fetch_public_request(
                                    page_url=column_url,
                                    request_url=url,
                                    method=method,
                                    request_body=dict(request_body or {}),
                                    request_headers=dict(request_headers or {}),
                                    request_encoding=request_encoding,
                                    wait_time_seconds=source.wait_time,
                                ).html
                                if source.structured_discovery
                                and source.structured_discovery.request_crawler_type == "dynamic"
                                else self.static_crawler.fetch(
                                    url,
                                    request_headers=request_headers,
                                    method=method,
                                    json_body=request_body if request_encoding == "json" else None,
                                    form_body=request_body if request_encoding == "form" else None,
                                ).html
                            ),
                            publish_time_start=publish_time_start,
                            max_pages=source.max_pages,
                        )
                        if structured_links.article_links or source.structured_discovery.prefer:
                            links = structured_links
                    except FetchError as exc:
                        result.fetch_failed += 1
                        self._log(source, column_url=root_column_url, page_url=column_url, article_url=None, status="structured_discovery_fetch_failed", http_status=exc.http_status, retry_count=exc.retry_count, error_type=exc.error_type, error_message=str(exc))
                        logger.warning("[STRUCTURED_DISCOVERY] source=%s fetch failed: %s", source.name, exc)
                    except StructuredDiscoveryError as exc:
                        self._log(source, column_url=root_column_url, page_url=column_url, article_url=None, status="structured_discovery_failed", error_type=type(exc).__name__, error_message=str(exc))
                        logger.warning("[STRUCTURED_DISCOVERY] source=%s skipped: %s", source.name, exc)
                retain_links(links, root_column_url=root_column_url, page_url=column_url)
                if source.structured_discovery and source.structured_discovery.pagination and structured_links is not None:
                    # The public JSON endpoint already advanced every page
                    # through the configured page parameter.  Do not then
                    # attempt unrelated HTML pagination on the shell page.
                    pagination.pages_visited += max(0, structured_links.pages_visited - 1)
                    pagination.stop_reason = structured_links.stop_reason
                    break
                if not time_range_mode and len(output) >= source.max_articles_per_run:
                    pagination.stop_reason = "max_articles"
                    break
                # Early pagination termination is opt-in.  It is safe only
                # when the source explicitly declares a descending listing and
                # every visible card exposes an absolute date older than start.
                listing_dates = [parse_publish_date(item[3]) for item in links.article_links]
                if (
                    publish_time_start is not None
                    and source.published_time_order == "desc"
                    and listing_dates
                    and all(item is not None and item < publish_time_start for item in listing_dates)
                ):
                    pagination.stop_reason = "publish_time_before_start"
                    break
                if source.pagination_url_template:
                    if pagination.pages_visited >= source.max_pages:
                        pagination.stop_reason = "max_pages"
                        break
                    template_page += 1
                    next_page = source.pagination_url_template.format(page=template_page)
                else:
                    next_page = find_next_page(source, column_url, fetched.html)
                if not next_page:
                    pagination.stop_reason = "no_next_page"
                    break
                if next_page in pagination.visited_page_urls:
                    pagination.stop_reason = "visited_page"
                    break
                if pagination.pages_visited >= source.max_pages:
                    pagination.stop_reason = "max_pages"
                    break
                column_url = next_page
            if pagination.stop_reason in {"max_pages", "max_articles", "visited_page", "request_failed"}:
                break
        result.pages_visited = pagination.pages_visited
        result.page_stop_reason = pagination.stop_reason or "no_next_page"
        if time_range_mode and result.page_stop_reason in {
            "max_pages",
            "max_articles",
            "visited_page",
            "structured_repeated_page",
            "request_failed",
        }:
            result.result_complete = False
            if result.detail is None:
                result.detail = "发布时间范围尚未到达可靠停止边界，结果不完整"
        return output

    @staticmethod
    def _source_for_feed(source: NewsSourceConfig, feed) -> NewsSourceConfig:
        """Overlay feed-local listing settings without changing source identity.

        This deliberately uses the existing single-discovery machinery for
        every feed, so static, dynamic and structured listings retain the
        same pagination and requested-time semantics.
        """
        overrides = {
            "column_urls": feed.column_urls,
            "allowed_domains": feed.allowed_domains if feed.allowed_domains is not None else source.allowed_domains,
            "crawler_type": feed.crawler_type if feed.crawler_type is not None else source.crawler_type,
            "wait_time": feed.wait_time if feed.wait_time is not None else source.wait_time,
            "max_pages": feed.max_pages if feed.max_pages is not None else source.max_pages,
            "pagination_url_template": (
                feed.pagination_url_template
                if feed.pagination_url_template is not None
                else source.pagination_url_template
            ),
            "pagination_start_page": (
                feed.pagination_start_page
                if feed.pagination_start_page is not None
                else source.pagination_start_page
            ),
            "structured_discovery": (
                feed.structured_discovery
                if feed.structured_discovery is not None
                else source.structured_discovery
            ),
            "published_time_order": (
                feed.published_time_order
                if feed.published_time_order is not None
                else source.published_time_order
            ),
            "selectors": feed.selectors if feed.selectors is not None else source.selectors,
        }
        return source.model_copy(update=overrides)

    def _discover(
        self,
        source: NewsSourceConfig,
        result: SourceCrawlResult,
        *,
        publish_time_start: date | None,
        publish_time_end: date | None,
    ) -> list[tuple[str, str, str, str, str | None, str | None]]:
        """Discover candidates from one legacy listing or several official feeds."""
        feeds = source.discovery_feeds
        if not feeds:
            return self._discover_single(
                source,
                result,
                publish_time_start=publish_time_start,
                publish_time_end=publish_time_end,
            )

        output: list[tuple[str, str, str, str, str | None, str | None]] = []
        seen: set[str] = set()
        for feed in feeds:
            feed_source = self._source_for_feed(source, feed)
            feed_result = SourceCrawlResult(source_name=source.name, status="success")
            feed_candidates = self._discover_single(
                feed_source,
                feed_result,
                publish_time_start=publish_time_start,
                publish_time_end=publish_time_end,
            )
            # Aggregate existing compatible counters without changing their
            # meaning.  Candidate URL merging happens below, at source scope.
            for field in (
                "pages_visited", "discovered", "publish_time_filtered",
                "listing_publish_time_filtered", "fetch_failed",
            ):
                setattr(result, field, getattr(result, field) + getattr(feed_result, field))
            result.result_complete = result.result_complete and feed_result.result_complete
            result.feed_results.append(
                DiscoveryFeedResult(
                    name=feed.name,
                    # Feed diagnostics describe the candidates retained for
                    # this invocation's requested-time window.  The legacy
                    # source counter remains the merged unique count below.
                    discovered=len(feed_candidates),
                    pages_visited=feed_result.pages_visited,
                    stop_reason=feed_result.page_stop_reason,
                    fetch_failed=feed_result.fetch_failed,
                    result_complete=feed_result.result_complete,
                    detail=feed_result.detail,
                )
            )
            for candidate in feed_candidates:
                if candidate[3] in seen:
                    continue
                seen.add(candidate[3])
                output.append(candidate)

        # Legacy ``discovered`` is defined as the merged candidate set, not a
        # sum of feed-local values.  Listing time filters intentionally remain
        # aggregate diagnostics because they refer to raw feed observations.
        result.discovered = len(seen)
        result.page_stop_reason = "; ".join(
            f"{feed.name}:{feed.stop_reason or 'unknown'}" for feed in result.feed_results
        ) or None
        if not result.result_complete and result.detail is None:
            incomplete = [feed.name for feed in result.feed_results if not feed.result_complete]
            result.detail = "未完整完成的 discovery feed: " + ", ".join(incomplete)
        return output

    @staticmethod
    def _report_for(source: NewsSourceConfig, start_time: str, end_time: str, result: SourceCrawlResult, *, publish_time_start: str | None, publish_time_end: str | None) -> CrawlReport:
        duplicate_count = result.duplicate_url + result.duplicate_content
        failed_count = result.fetch_failed + result.parse_failed
        # ``requested`` counts real detail HTTP requests. URL duplicates are
        # deliberately detected before such a request, so include them in
        # both sides of this coverage metric instead of allowing rates > 1.
        covered_count = result.success + duplicate_count
        coverage_total = result.requested + duplicate_count
        success_rate = round(covered_count / coverage_total, 6) if coverage_total else 0.0
        return CrawlReport(
            source_name=source.name,
            start_time=start_time,
            end_time=end_time,
            publish_time_start=publish_time_start,
            publish_time_end=publish_time_end,
            visited_pages=result.pages_visited,
            discovered_count=result.discovered,
            requested_count=result.requested,
            success_count=result.success,
            duplicate_url_count=result.duplicate_url,
            duplicate_content_count=result.duplicate_content,
            keyword_filtered_count=result.keyword_filtered,
            publish_time_filtered_count=result.publish_time_filtered,
            listing_publish_time_filtered_count=result.listing_publish_time_filtered,
            detail_publish_time_filtered_count=result.detail_publish_time_filtered,
            publish_time_unknown_count=result.publish_time_unknown,
            fetch_failed_count=result.fetch_failed,
            parse_failed_count=result.parse_failed,
            failed_count=failed_count,
            duplicate_count=duplicate_count,
            success_rate=success_rate,
            result_complete=result.result_complete,
            detail_limit_reached=result.detail_limit_reached,
        )

    def _save_report(self, source: NewsSourceConfig, start_time: str, result: SourceCrawlResult, failures: list[CrawlFailure], *, publish_time_start: str | None = None, publish_time_end: str | None = None) -> None:
        try:
            self.store.save_crawl_report(self._report_for(source, start_time, utc_now(), result, publish_time_start=publish_time_start, publish_time_end=publish_time_end), failures)
        except Exception:
            logger.exception("[NEWS_REPORT] source=%s failed to persist crawl report", source.name)

    def _crawl_source(self, source: NewsSourceConfig, *, force: bool, publish_time_start: date | None, publish_time_end: date | None, publish_time_start_raw: str | None, publish_time_end_raw: str | None, on_article: Callable[[object, str], None] | None = None) -> SourceCrawlResult:
        start_time = utc_now()
        result = SourceCrawlResult(source_name=source.name, status="success")
        failures: list[CrawlFailure] = []
        candidates = self._discover(
            source,
            result,
            publish_time_start=publish_time_start,
            publish_time_end=publish_time_end,
        )
        time_range_mode = publish_time_start is not None or publish_time_end is not None
        detail_attempts = 0
        for column_url, page_url, original_url, canonical_url, listing_title, listing_published_at in candidates:
            if not force and self.store.url_exists(canonical_url):
                result.duplicate_url += 1
                # A duplicate is only a staging-store decision.  RuoYi MySQL
                # can still be seeing this canonical URL for the first time,
                # so return the exact stored article in full.  This is one
                # indexed lookup for the current candidate, never a history
                # backfill and never another detail request.
                if on_article is not None:
                    existing = self.store.get_article_by_canonical_url(canonical_url)
                    if existing is not None:
                        on_article(
                            {
                                "source_name": existing.source_name,
                                "source_site": existing.source_site,
                                "title": existing.title,
                                "content": existing.content,
                                "url": existing.url,
                                "original_url": existing.original_url,
                                "canonical_url": existing.canonical_url,
                                "published_at": existing.published_at,
                                "crawled_at": existing.crawled_at,
                                "content_hash": existing.content_hash,
                                "matched_keywords": existing.matched_keywords,
                            },
                            "duplicate_url",
                        )
                self._log(source, column_url=column_url, page_url=page_url, article_url=canonical_url, status="duplicate_url")
                continue
            if time_range_mode and detail_attempts >= source.max_articles_per_run:
                result.result_complete = False
                result.detail_limit_reached = True
                result.detail = "指定发布时间范围内待解析详情超过 max_articles_per_run，结果不完整"
                logger.warning(
                    "[NEWS_CRAWL][DETAIL_LIMIT] source=%s limit=%s remaining_candidates=%s",
                    source.name,
                    source.max_articles_per_run,
                    len(candidates) - detail_attempts,
                )
                break
            result.requested += 1
            if time_range_mode:
                detail_attempts += 1
            try:
                fetched = self._fetch(source, canonical_url, detail=True)
            except FetchError as exc:
                result.fetch_failed += 1
                self._log(source, column_url=column_url, page_url=page_url, article_url=canonical_url, status="fetch_failed", http_status=exc.http_status, retry_count=exc.retry_count, error_type=exc.error_type, error_message=str(exc))
                failures.append(CrawlFailure(source_name=source.name, article_url=canonical_url, title=listing_title, status="fetch_failed", error_type=exc.error_type, error_message=str(exc)[:500], crawl_time=utc_now()))
                continue
            parsed = self._crawler_for(source, detail=True).parse_article(
                source,
                original_url=original_url,
                canonical_url=canonical_url,
                fetched=fetched,
                fallback_title=listing_title,
            )
            parsed = _with_listing_published_at(
                parsed,
                listing_published_at,
                # A source that explicitly maps an official list-card or
                # public structured ``article_time_field`` has supplied a
                # publication timestamp with stronger provenance than a
                # generic date-like fragment in the detail HTML.  This avoids
                # misreading a day/month string in article prose while still
                # retaining the detail value whenever no usable listing date
                # is configured.
                prefer_listing_date=bool(
                    source.prefer_listing_published_at
                    or
                    source.selectors.article_time_selector
                    or (
                        source.structured_discovery
                        and source.structured_discovery.prefer
                        and source.structured_discovery.article_time_field
                    )
                ),
            )
            if parsed.status != "success" or parsed.article is None:
                self._increase(result, parsed.status)
                self._log(source, column_url=column_url, page_url=page_url, article_url=canonical_url, status=parsed.status, http_status=fetched.http_status, retry_count=fetched.retry_count, error_message=parsed.warning)
                if parsed.status in {"parse_failed", "keyword_filtered"}:
                    failures.append(CrawlFailure(source_name=source.name, article_url=canonical_url, title=listing_title, status=parsed.status, error_type="ArticleParseError" if parsed.status == "parse_failed" else None, error_message=parsed.warning or ("关键词过滤" if parsed.status == "keyword_filtered" else None), crawl_time=utc_now()))
                continue
            if publish_time_start is not None or publish_time_end is not None:
                published_date = parse_publish_date(parsed.article.published_at)
                if published_date is None:
                    result.publish_time_unknown += 1
                    reason = "missing_published_at" if not parsed.article.published_at else "invalid_published_at"
                    self._log(source, column_url=column_url, page_url=page_url, article_url=canonical_url, published_at=parsed.article.published_at, status="publish_time_unknown", http_status=fetched.http_status, retry_count=fetched.retry_count, error_type=reason, error_message="指定发布时间范围时无法识别新闻原始发布时间")
                    failures.append(CrawlFailure(source_name=source.name, article_url=canonical_url, title=listing_title, status="publish_time_unknown", error_type=reason, error_message="指定发布时间范围时无法识别新闻原始发布时间", crawl_time=utc_now()))
                    continue
                if publish_time_start is not None and published_date < publish_time_start:
                    result.publish_time_filtered += 1
                    result.detail_publish_time_filtered += 1
                    self._log(source, column_url=column_url, page_url=page_url, article_url=canonical_url, published_at=parsed.article.published_at, status="publish_time_filtered", http_status=fetched.http_status, retry_count=fetched.retry_count, error_type="published_at_before_start", error_message="新闻发布时间早于 publish_time_start")
                    failures.append(CrawlFailure(source_name=source.name, article_url=canonical_url, title=listing_title, status="publish_time_filtered", error_type="published_at_before_start", error_message="新闻发布时间早于 publish_time_start", crawl_time=utc_now()))
                    continue
                if publish_time_end is not None and published_date > publish_time_end:
                    result.publish_time_filtered += 1
                    result.detail_publish_time_filtered += 1
                    self._log(source, column_url=column_url, page_url=page_url, article_url=canonical_url, published_at=parsed.article.published_at, status="publish_time_filtered", http_status=fetched.http_status, retry_count=fetched.retry_count, error_type="published_at_after_end", error_message="新闻发布时间晚于 publish_time_end")
                    failures.append(CrawlFailure(source_name=source.name, article_url=canonical_url, title=listing_title, status="publish_time_filtered", error_type="published_at_after_end", error_message="新闻发布时间晚于 publish_time_end", crawl_time=utc_now()))
                    continue
            existing_before_save = self.store.get_article_by_canonical_url(parsed.article.canonical_url)
            status = self.store.save_article(parsed.article)
            self._increase(result, status)
            # The bridge callback is emitted from this exact crawl execution,
            # never reconstructed later by a time-window query.
            if on_article is not None:
                operation = status
                if (
                    status == "success"
                    and existing_before_save is not None
                    and existing_before_save.content_hash != parsed.article.content_hash
                ):
                    # ``save_article`` intentionally reports this as success
                    # for historical aggregate statistics.  The bridge needs
                    # the more specific exact-run operation for traceability.
                    operation = "updated"
                on_article(parsed.article, operation)
            self._log(source, column_url=column_url, page_url=page_url, article_url=canonical_url, status=status, http_status=fetched.http_status, retry_count=fetched.retry_count, error_message=parsed.warning)
        if result.fetch_failed and not candidates:
            result.status, result.detail = "failed", "栏目页请求失败"
        self._log(source, column_url=None, page_url=None, article_url=None, status=result.status, error_message=result.detail)
        self._save_report(source, start_time, result, failures, publish_time_start=publish_time_start_raw, publish_time_end=publish_time_end_raw)
        return result

    def crawl(
        self,
        *,
        source_name: str | None = None,
        force: bool = False,
        respect_frequency: bool = False,
        publish_time_start: str | None = None,
        publish_time_end: str | None = None,
        on_article: Callable[[object, str], None] | None = None,
    ) -> list[SourceCrawlResult]:
        """Crawl configured sources.

        Manual calls should run immediately so that ``force=False`` can verify
        canonical-URL/content de-duplication.  The scheduler opts in to the
        frequency guard for its background jobs.
        """
        start_date = parse_requested_date(publish_time_start, field_name="publish_time_start")
        end_date = parse_requested_date(publish_time_end, field_name="publish_time_end")
        if start_date is not None and end_date is not None and start_date > end_date:
            raise ValueError("publish_time_start 不能晚于 publish_time_end")
        sources = load_sources()
        selected = [source for source in sources if source.enabled and (source_name is None or source.name == source_name)]
        if source_name and not selected:
            raise ValueError(f"未找到或未启用新闻源: {source_name}")
        results: list[SourceCrawlResult] = []
        for source in selected:
            if respect_frequency and not force and not self._due(source):
                skipped = SourceCrawlResult(source_name=source.name, status="skipped_frequency", skipped_frequency=1, detail=f"未到抓取频率 {source.crawl_frequency}")
                self._save_report(source, utc_now(), skipped, [], publish_time_start=publish_time_start, publish_time_end=publish_time_end)
                results.append(skipped)
                continue
            try:
                results.append(self._crawl_source(source, force=force, publish_time_start=start_date, publish_time_end=end_date, publish_time_start_raw=publish_time_start, publish_time_end_raw=publish_time_end, on_article=on_article))
            except Exception as exc:
                logger.exception("[NEWS_CRAWL] source=%s unexpected failure", source.name)
                # Preserve a bounded root-cause hint in the run record.  The
                # former generic text made an operational browser failure
                # indistinguishable from a source configuration problem.
                reason = str(exc).strip().replace("\n", " ")[:300]
                detail = f"采集流程异常: {type(exc).__name__}" + (f": {reason}" if reason else "")
                self._log(source, column_url=None, page_url=None, article_url=None, status="fetch_failed", error_type=type(exc).__name__, error_message=detail)
                failed = SourceCrawlResult(source_name=source.name, status="failed", fetch_failed=1, detail=detail)
                self._save_report(source, utc_now(), failed, [], publish_time_start=publish_time_start, publish_time_end=publish_time_end)
                results.append(failed)
        return results

    def list_articles(self, **filters):
        return self.store.list_articles(**filters)

    def get_article(self, article_id: int):
        return self.store.get_article(article_id)

    def delete_article(self, article_id: int) -> bool:
        return self.store.delete_article(article_id)

    def delete_articles(self, article_ids: list[int]) -> int:
        return self.store.delete_articles(article_ids)

    def delete_by_canonical_urls(self, canonical_urls: list[str]) -> int:
        return self.store.delete_by_canonical_urls(canonical_urls)

    def delete_by_source_site(self, **filters) -> int:
        return self.store.delete_by_source_site(**filters)

    def list_logs(self, **filters):
        return self.store.list_logs(**filters)

    def latest_report(self, *, source_name: str | None = None):
        return self.store.latest_crawl_report(source_name=source_name)

    def list_report_history(self, **filters):
        return self.store.list_crawl_reports(**filters)

    def list_failures(self, **filters):
        return self.store.list_crawl_failures(**filters)


