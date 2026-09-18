"""Typed configuration, crawl results and persistence models."""
from __future__ import annotations

import re
from typing import Any, Literal

from pydantic import BaseModel, Field, HttpUrl, model_validator

from news_service.utils.date_utils import parse_requested_date


class SourceSelectors(BaseModel):
    article_link_selector: str | None = None
    # Optional listing-card fields, retained in source configuration for
    # site-level diagnostics and future listing metadata extraction.
    article_title_selector: str | None = None
    article_time_selector: str | None = None
    title_selector: str | None = None
    title_exclude_selectors: list[str] = Field(default_factory=list)
    content_selector: str | None = None
    publish_time_selector: str | None = None
    # Optional source-local extraction pattern applied after the configured
    # publication-time node is read.  It lets a page expose "发布日期：
    # 2026-08-31" while storing only the publisher's date value.
    publish_time_regex: str | None = None
    next_page_selector: str | None = None
    # Some public listings append the next batch only after the viewport
    # reaches the document bottom. This opt-in flag uses the shared dynamic
    # crawler; it is not a site-specific URL or selector rule.
    infinite_scroll: bool = False
    # Opt-in quality gates for sources where generic h1/title or empty-body
    # fallback would turn a non-article page into a fake news record.
    require_title_selector: bool = False
    require_content_selector: bool = False
    title_strip_prefix: str | None = None
    title_strip_suffix: str | None = None
    content_exclude_selectors: list[str] = Field(default_factory=list)


class StructuredRequestValueFromPageConfig(BaseModel):
    """Read one public request value from the already fetched list DOM."""

    selector: str = Field(min_length=1)
    attribute: str = Field(min_length=1)


class StructuredDiscoveryConfig(BaseModel):
    """Configuration for safely reading public structured article listings.

    Supported modes parse JSON, HTML fragments, or RSS/Atom XML only.
    JavaScript is never evaluated or run.
    """

    mode: Literal[
        "json_endpoint",
        "json_html_fragment",
        "html_endpoint",
        "rss_endpoint",
        "script_json",
        "js_data_file",
    ]
    # When true, structured data is authoritative and is evaluated before
    # generic HTML anchors (which may contain navigation/product links).
    prefer: bool = False
    # For ``json_endpoint``, ``rss_endpoint`` and ``js_data_file`` this is the
    # public resource URL.  Relative URLs are resolved against the configured
    # column page.
    data_url: str | None = None
    # Optional static query parameters for a public JSON endpoint.
    query_params: dict[str, str | int | float | bool] = Field(default_factory=dict)
    # Public structured listings are usually GET endpoints, but some official
    # CMSs expose the same unauthenticated listing through a JSON POST body.
    # Keep both request method and fixed body declarative so a source never
    # needs a site-specific crawler merely to advance its page number.
    request_method: Literal["GET", "POST"] = "GET"
    # Public POST listings may use either JSON or the normal browser form
    # encoding.  Keeping this declarative lets all sources share one safe
    # structured discovery path.
    request_encoding: Literal["json", "form"] = "json"
    # Most public endpoints work through the shared HTTP client.  Some public
    # CMS endpoints are called by their own rendered list page and require
    # that normal same-origin browser context; this opt-in keeps that
    # distinction declarative without introducing source-specific crawlers.
    request_crawler_type: Literal["static", "dynamic"] = "static"
    # JSON POST bodies sometimes group the page number below a public
    # request object, for example ``{"paging": {"page": 2}}``.  Keep the
    # body declarative while allowing that ordinary nested JSON shape.
    request_body: dict[str, Any] = Field(default_factory=dict)
    # Some public CMS list endpoints require an opaque-but-public listing
    # context value embedded in a data-* attribute of their own page.  Read
    # it declaratively from the fetched DOM; this never reads cookies,
    # credentials, browser storage, or private tokens.
    request_body_from_page: dict[str, StructuredRequestValueFromPageConfig] = Field(default_factory=dict)
    # Dot-path to the article array inside the decoded JSON document.
    records_path: str = ""
    # Optional dot-path to an article array within each records_path item.
    # This keeps public CMS responses such as ``sections -> items`` fully
    # configuration-driven without evaluating page JavaScript.
    item_records_path: str | None = None
    # A candidate may use a direct URL field or a URL template such as
    # ``/article?id={id}``.  The latter is still data-driven per record.
    article_url_field: str | None = None
    article_url_template: str | None = None
    article_title_field: str | None = None
    article_time_field: str | None = None
    # Optional ``datetime.strptime`` format for a public list API's explicit
    # publication timestamp.  When configured, discovery normalizes it to
    # ISO ``YYYY-MM-DD`` before shared range filtering.  This avoids assuming
    # a locale for ambiguous numeric dates such as ``01/09/2026``.
    article_time_format: str | None = Field(default=None, max_length=100)
    # Optional exact-match filters evaluated against each list record before
    # a detail URL is constructed, e.g. ``{"contentType": 1}``.
    record_filters: dict[str, str | int | float | bool] = Field(default_factory=dict)
    # Exclude public records whose field exactly matches a configured value.
    # This complements record_filters for broad official hubs which expose a
    # separately branded section in the same response.
    record_exclude_filters: dict[str, str | int | float | bool] = Field(default_factory=dict)
    # Strip known presentation-only URL suffixes from public listing links
    # before canonical URL de-duplication, e.g. a CMS's ``/text`` rendition.
    article_url_strip_suffixes: list[str] = Field(default_factory=list)
    # Public list APIs can also expose product/landing routes alongside
    # article records.  Match against the raw listing URL before it is made
    # canonical; patterns are declarative regular expressions.
    article_url_exclude_patterns: list[str] = Field(default_factory=list)
    url_base: str | None = None
    # ``script_json`` selects an application/json script from the already
    # fetched column page. ``js_data_file`` can safely decode an assignment
    # only when its JSON value begins immediately after this literal prefix.
    script_selector: str | None = None
    json_assignment_prefix: str | None = None
    # ``json_html_fragment`` is for public list endpoints that return a JSON
    # envelope whose one field contains server-rendered article-card HTML.
    # ``html_endpoint`` is the equivalent public endpoint when the response
    # itself is already that HTML fragment.  Both use the normal source
    # selectors, so no source-specific discovery code is required.
    html_path: str | None = None
    # Some public endpoints negotiate their JSON representation through a
    # normal Accept header.  These are declarative request headers only; no
    # authentication, cookies or private tokens are supported here.
    request_headers: dict[str, str] = Field(default_factory=dict)
    pagination: "StructuredPagePaginationConfig | StructuredOffsetPaginationConfig | None" = None

    @model_validator(mode="after")
    def validate_data_source(self) -> "StructuredDiscoveryConfig":
        if self.mode in {
            "json_endpoint",
            "json_html_fragment",
            "html_endpoint",
            "rss_endpoint",
            "js_data_file",
        } and not self.data_url:
            raise ValueError("structured_discovery 的 endpoint/js_data_file 必须配置 data_url")
        if self.mode == "script_json" and not self.script_selector:
            raise ValueError("structured_discovery 的 script_json 必须配置 script_selector")
        if self.mode == "js_data_file" and not self.json_assignment_prefix:
            raise ValueError("structured_discovery 的 js_data_file 必须配置 json_assignment_prefix")
        if self.mode == "json_html_fragment" and not self.html_path:
            raise ValueError("structured_discovery.json_html_fragment 必须配置 html_path")
        if self.mode == "rss_endpoint" and not self.records_path:
            raise ValueError("structured_discovery.rss_endpoint 必须配置 records_path")
        if self.pagination is not None and self.mode not in {"json_endpoint", "json_html_fragment", "html_endpoint"}:
            raise ValueError("structured_discovery.pagination 当前仅支持公开 endpoint 分页模式")
        if self.mode not in {"json_html_fragment", "html_endpoint"} and not self.article_url_field and not self.article_url_template:
            raise ValueError("structured_discovery 必须配置 article_url_field 或 article_url_template")
        return self


class StructuredPagePaginationConfig(BaseModel):
    """Configuration for a public structured endpoint with a numeric page parameter.

    The source-level ``max_pages`` remains the shared hard upper bound for all
    discovery modes.  This nested configuration only describes how a public
    endpoint advances from one page to the next.
    """

    # A top-level name (``page``) updates a query/body field directly.  A
    # dotted name (``paging.page``) updates the corresponding nested JSON
    # request-body field, without a source-specific request implementation.
    page_param: str = Field(min_length=1, max_length=100)
    start_page: int = Field(default=1, ge=0)
    # Most endpoints increment by one page. Offset-based public endpoints
    # can use this same generic configuration with a larger step.
    page_step: int = Field(default=1, ge=1, le=500)


class StructuredOffsetPaginationConfig(BaseModel):
    """Configuration for a public endpoint that advances by record offset."""

    offset_param: str = Field(min_length=1, max_length=100)
    start_offset: int = Field(default=0, ge=0)
    # ``page_size`` is the public endpoint's fixed batch size.  The advance
    # may differ for endpoints which deliberately overlap batches.
    page_size: int = Field(ge=1, le=500)
    offset_step: int | None = Field(default=None, ge=1, le=500)
    # Some public APIs encode both values in one ordinary query parameter,
    # e.g. ``params=14,7``.  This remains declarative and only accepts the
    # two bounded placeholders below; it is never evaluated as code.
    parameter_template: str | None = Field(default=None, min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_parameter_template(self) -> "StructuredOffsetPaginationConfig":
        if self.parameter_template is None:
            return self
        fields = re.findall(r"\{([^{}]+)\}", self.parameter_template)
        remainder = re.sub(r"\{(?:offset|limit)\}", "", self.parameter_template)
        if remainder != self.parameter_template and ("{" in remainder or "}" in remainder):
            raise ValueError("parameter_template 只允许 {offset} 和 {limit} 占位符")
        if any(field not in {"offset", "limit"} for field in fields):
            raise ValueError("parameter_template 只允许 {offset} 和 {limit} 占位符")
        if not fields:
            raise ValueError("parameter_template 必须包含 {offset} 或 {limit}")
        return self

    @property
    def effective_offset_step(self) -> int:
        return self.offset_step or self.page_size


class DiscoveryFeedConfig(BaseModel):
    """One public discovery feed belonging to a single business source.

    A feed only controls how candidates are listed.  Detail parsing, storage,
    source identity and de-duplication remain source-level concerns.
    """

    name: str = Field(min_length=1, max_length=100)
    column_urls: list[HttpUrl] = Field(min_length=1)
    allowed_domains: list[str] | None = None
    crawler_type: Literal["static", "dynamic"] | None = None
    wait_time: float | None = Field(default=None, ge=0, le=30)
    max_pages: int | None = Field(default=None, ge=1, le=100)
    pagination_url_template: str | None = Field(default=None, max_length=2000)
    pagination_start_page: int | None = Field(default=None, ge=0)
    structured_discovery: StructuredDiscoveryConfig | None = None
    published_time_order: Literal["desc", "unknown"] | None = None
    selectors: SourceSelectors | None = None

    @model_validator(mode="after")
    def validate_pagination_template(self) -> "DiscoveryFeedConfig":
        if self.pagination_url_template is not None and "{page}" not in self.pagination_url_template:
            raise ValueError("discovery_feeds.pagination_url_template 必须包含 {page}")
        return self


class NewsSourceConfig(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    enabled: bool = True
    domain: str = Field(min_length=1, max_length=255)
    # Most sources use one official host.  A small number of official media
    # hubs deliberately link to separately hosted, first-party newsroom
    # domains.  This opt-in list preserves the primary ``domain`` field for
    # provenance while allowing those configured public hosts at discovery.
    allowed_domains: list[str] = Field(default_factory=list)
    column_urls: list[HttpUrl] = Field(min_length=1)
    keywords: list[str] = Field(default_factory=list)
    keyword_mode: Literal["any", "all", "off"] = "any"
    # Scheduler validates the interval per source, so one malformed source does
    # not prevent the remaining whitelist entries from loading.
    crawl_frequency: str = Field(default="6h", min_length=1, max_length=20)
    # ``cron`` is opt-in so existing frequency-only YAML files remain valid.
    # Scheduler registration gives cron precedence when it is configured.
    schedule_type: Literal["cron"] | None = None
    day_of_week: Literal["mon", "tue", "wed", "thu", "fri", "sat", "sun"] | None = None
    crawl_time: str | None = Field(default=None, max_length=5)
    max_articles_per_run: int = Field(default=20, ge=1, le=100)
    max_pages: int = Field(default=10, ge=1, le=100)
    # Optional public URL template for conventional numbered archives whose
    # pager is rendered as buttons without crawlable ``href`` attributes.
    # The template must contain ``{page}``, for example ``.../page/{page}/``.
    pagination_url_template: str | None = Field(default=None, max_length=2000)
    pagination_start_page: int = Field(default=1, ge=0)
    # Old configurations omit these fields and therefore retain static crawling.
    crawler_type: Literal["static", "dynamic"] = "static"
    # A public CMS listing can be static/structured while its official detail
    # pages require browser rendering.  Keep the existing one-crawler default
    # unless a source explicitly opts into this generic split.
    detail_crawler_type: Literal["static", "dynamic"] | None = None
    wait_time: float = Field(default=0, ge=0, le=30)
    structured_discovery: StructuredDiscoveryConfig | None = None
    # Optional additional official listings for a single business source.
    # Sources without this field retain their original single-discovery path.
    discovery_feeds: list[DiscoveryFeedConfig] = Field(default_factory=list)
    # Only an explicitly configured descending listing may stop pagination
    # once every visible listing date is older than a requested start date.
    published_time_order: Literal["desc", "unknown"] = "unknown"
    # When an official listing/API supplies a publication field, it can be
    # declared authoritative over date-like prose found in the detail body.
    # Default false preserves every existing source's behavior.
    prefer_listing_published_at: bool = False
    selectors: SourceSelectors = Field(default_factory=SourceSelectors)

    @model_validator(mode="after")
    def validate_cron_schedule(self) -> "NewsSourceConfig":
        if self.pagination_url_template is not None and "{page}" not in self.pagination_url_template:
            raise ValueError("pagination_url_template 必须包含 {page}")
        if self.schedule_type != "cron":
            return self
        if self.day_of_week is None:
            raise ValueError("schedule_type=cron 时必须配置 day_of_week")
        if not self.crawl_time or not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", self.crawl_time):
            raise ValueError("schedule_type=cron 时 crawl_time 必须为 HH:MM")
        return self


class NewsArticle(BaseModel):
    id: int | None = None
    source_name: str
    source_site: str
    title: str
    content: str
    published_at: str | None = None
    crawled_at: str
    url: str
    original_url: str
    canonical_url: str
    matched_keywords: list[str] = Field(default_factory=list)
    content_hash: str


class CrawlLog(BaseModel):
    id: int | None = None
    source_name: str
    column_url: str | None = None
    page_url: str | None = None
    article_url: str | None = None
    published_at: str | None = None
    status: str
    http_status: int | None = None
    retry_count: int = 0
    error_type: str | None = None
    error_message: str | None = None
    crawl_time: str


class SourceCrawlResult(BaseModel):
    source_name: str
    status: str
    discovered: int = 0
    pages_visited: int = 0
    page_stop_reason: str | None = None
    requested: int = 0
    success: int = 0
    duplicate_url: int = 0
    duplicate_content: int = 0
    keyword_filtered: int = 0
    publish_time_filtered: int = 0
    # Keep the legacy aggregate while exposing the two distinct stages that
    # can filter by publisher time.  This makes a time-range run auditable:
    # list-stage filtering never masquerades as a requested detail outcome.
    listing_publish_time_filtered: int = 0
    detail_publish_time_filtered: int = 0
    publish_time_unknown: int = 0
    fetch_failed: int = 0
    parse_failed: int = 0
    skipped_frequency: int = 0
    # A time-range crawl may intentionally stop detail requests at the
    # configured resource guard.  Expose that condition so callers never
    # mistake a bounded, partial result for a complete range result.
    result_complete: bool = True
    detail_limit_reached: bool = False
    detail: str | None = None
    feed_results: list["DiscoveryFeedResult"] = Field(default_factory=list)


class DiscoveryFeedResult(BaseModel):
    name: str
    discovered: int = 0
    pages_visited: int = 0
    stop_reason: str | None = None
    fetch_failed: int = 0
    result_complete: bool = True
    detail: str | None = None


class CrawlFailure(BaseModel):
    id: int | None = None
    report_id: int | None = None
    source_name: str
    article_url: str
    title: str | None = None
    status: str
    error_type: str | None = None
    error_message: str | None = None
    crawl_time: str


class CrawlReport(BaseModel):
    id: int | None = None
    source_name: str
    start_time: str
    end_time: str
    publish_time_start: str | None = None
    publish_time_end: str | None = None
    visited_pages: int = 0
    discovered_count: int = 0
    requested_count: int = 0
    success_count: int = 0
    duplicate_url_count: int = 0
    duplicate_content_count: int = 0
    keyword_filtered_count: int = 0
    publish_time_filtered_count: int = 0
    listing_publish_time_filtered_count: int = 0
    detail_publish_time_filtered_count: int = 0
    publish_time_unknown_count: int = 0
    fetch_failed_count: int = 0
    parse_failed_count: int = 0
    failed_count: int = 0
    duplicate_count: int = 0
    success_rate: float = 0.0
    result_complete: bool = True
    detail_limit_reached: bool = False


class NewsCrawlRequest(BaseModel):
    source_name: str | None = Field(default=None, max_length=100)
    force: bool = False
    publish_time_start: str | None = Field(default=None, max_length=100)
    publish_time_end: str | None = Field(default=None, max_length=100)

    @model_validator(mode="after")
    def validate_publish_time_range(self) -> "NewsCrawlRequest":
        start = parse_requested_date(self.publish_time_start, field_name="publish_time_start")
        end = parse_requested_date(self.publish_time_end, field_name="publish_time_end")
        if start is not None and end is not None and start > end:
            raise ValueError("publish_time_start 不能晚于 publish_time_end")
        return self


class NewsBatchDeleteRequest(BaseModel):
    ids: list[int] = Field(min_length=1, max_length=500)


class NewsDeleteByUrlsRequest(BaseModel):
    canonical_urls: list[str] = Field(min_length=1, max_length=500)


class NewsDeleteBySourceRequest(BaseModel):
    source_site: str = Field(min_length=1, max_length=255)
    publish_time_start: str | None = Field(default=None, max_length=100)
    publish_time_end: str | None = Field(default=None, max_length=100)


