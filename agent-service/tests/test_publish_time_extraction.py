"""Publish-time extraction must ignore non-date CSS hits and still find absolute dates."""
from __future__ import annotations

from datetime import date

from bs4 import BeautifulSoup

from news_service.crawler.link_discovery import _listing_publish_time
from news_service.crawler.static_crawler import StaticNewsCrawler
from news_service.utils.date_utils import first_parseable_publish_value, parse_publish_date


def test_first_parseable_rejects_source_label():
    assert first_parseable_publish_value("财联社") is None
    assert first_parseable_publish_value("学习时报") is None
    assert first_parseable_publish_value("2026-09-11 09:41 北京") == "2026-09-11"
    assert parse_publish_date("2026-09-11 09:41") == parse_publish_date("2026-09-11")


def test_parse_publish_date_accepts_sept_abbreviation():
    # Mazda newsroom uses ``Sept.`` rather than locale ``Sep``.
    assert parse_publish_date("Sept. 1, 2026") == date(2026, 9, 1)
    assert parse_publish_date("Sept 1, 2026") == date(2026, 9, 1)
    assert parse_publish_date("Aug. 28, 2026") == date(2026, 8, 28)
    assert parse_publish_date("2026.09.01") == date(2026, 9, 1)


def test_european_slash_dates_need_format_hint_when_ambiguous():
    # Renault media cards use DD/MM/YYYY. Ambiguous values must not become US dates.
    assert parse_publish_date("04/09/2026") is None
    assert first_parseable_publish_value("04/09/2026") is None
    assert first_parseable_publish_value("04/09/2026", format_hint="%d/%m/%Y") == "2026-09-04"
    assert first_parseable_publish_value("17/09/2026") == "2026-09-17"
    assert first_parseable_publish_value("14/09/2026", format_hint="%d/%m/%Y") == "2026-09-14"


def test_published_at_skips_source_label_sibling():
    html = """
    <div class="c-b m-b-20 f-s-14 l-h-2 c-999">
      <div class="f-l m-r-10">官方账号汽车</div>
      <div class="f-l m-r-10">2026-09-11 09:41 北京</div>
      <div class="f-l m-r-10">财联社</div>
    </div>
    """
    soup = BeautifulSoup(html, "html.parser")
    # Old fragile selector pointed at the source label.
    assert StaticNewsCrawler._published_at(
        soup,
        "div.c-b.m-b-20.f-s-14.l-h-2.c-999 > div.f-l.m-r-10:nth-of-type(3)",
    ) == "2026-09-11"
    assert StaticNewsCrawler._published_at(
        soup,
        "div.c-b.m-b-20.f-s-14.l-h-2.c-999 > div.f-l.m-r-10",
        value_regex=r"(\d{4}-\d{2}-\d{2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?)",
    ) == "2026-09-11"


def test_published_at_uses_meta_when_selector_missing():
    html = """
    <html><head>
      <meta property="article:published_time" content="2026-09-08T10:00:00+08:00" />
    </head><body><article>正文</article></body></html>
    """
    soup = BeautifulSoup(html, "html.parser")
    value = StaticNewsCrawler._published_at(soup, ".missing-date")
    assert value is not None
    assert value.startswith("2026-09-08")


def test_listing_publish_time_does_not_use_title():
    html = """
    <div class="card">
      <a href="/detail/1">工信部等九部门推动自动驾驶</a>
      <span class="time">2026-09-11</span>
    </div>
    """
    soup = BeautifulSoup(html, "html.parser")
    anchor = soup.select_one("a")
    assert _listing_publish_time(anchor, ".time") == "2026-09-11"
    # Without an explicit selector, never invent a listing date from nearby chrome.
    assert _listing_publish_time(anchor, None) is None
    lonely = BeautifulSoup('<a href="/detail/2">没有日期的标题</a>', "html.parser").select_one("a")
    assert _listing_publish_time(lonely, None) is None


def test_listing_publish_time_ignores_unrelated_neighbor_date():
    html = """
    <div class="feed">
      <div class="chrome">下次更新 2026-09-30</div>
      <div class="card">
        <a href="/detail/2475356">特斯拉推出限时优惠政策</a>
      </div>
    </div>
    """
    soup = BeautifulSoup(html, "html.parser")
    anchor = soup.select_one("a")
    assert _listing_publish_time(anchor, None) is None
