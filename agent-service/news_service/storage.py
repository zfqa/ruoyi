"""SQLite persistence for articles, URL/content deduplication and crawl logs."""
from __future__ import annotations

import json
import sqlite3
from datetime import timedelta
from pathlib import Path

from news_service.models import CrawlFailure, CrawlLog, CrawlReport, NewsArticle
from news_service.utils.date_utils import normalize_published_at, parse_publish_date

DEFAULT_NEWS_DB = Path("data/news.db")


class NewsStore:
    def __init__(self, db_path: str | Path = DEFAULT_NEWS_DB) -> None:
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path)
        connection.row_factory = sqlite3.Row
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS news_articles (id INTEGER PRIMARY KEY AUTOINCREMENT, source_name TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, published_at TEXT, crawled_at TEXT NOT NULL, url TEXT NOT NULL, content_hash TEXT NOT NULL UNIQUE);
                CREATE TABLE IF NOT EXISTS news_crawl_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, source_name TEXT NOT NULL, started_at TEXT NOT NULL, finished_at TEXT, status TEXT NOT NULL, article_count INTEGER NOT NULL DEFAULT 0, new_count INTEGER NOT NULL DEFAULT 0, duplicate_count INTEGER NOT NULL DEFAULT 0, detail TEXT);
                CREATE TABLE IF NOT EXISTS news_crawl_reports (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_name TEXT NOT NULL,
                    start_time TEXT NOT NULL,
                    end_time TEXT NOT NULL,
                    publish_time_start TEXT,
                    publish_time_end TEXT,
                    visited_pages INTEGER NOT NULL DEFAULT 0,
                    discovered_count INTEGER NOT NULL DEFAULT 0,
                    requested_count INTEGER NOT NULL DEFAULT 0,
                    success_count INTEGER NOT NULL DEFAULT 0,
                    duplicate_url_count INTEGER NOT NULL DEFAULT 0,
                    duplicate_content_count INTEGER NOT NULL DEFAULT 0,
                    keyword_filtered_count INTEGER NOT NULL DEFAULT 0,
                    publish_time_filtered_count INTEGER NOT NULL DEFAULT 0,
                    listing_publish_time_filtered_count INTEGER NOT NULL DEFAULT 0,
                    detail_publish_time_filtered_count INTEGER NOT NULL DEFAULT 0,
                    publish_time_unknown_count INTEGER NOT NULL DEFAULT 0,
                    fetch_failed_count INTEGER NOT NULL DEFAULT 0,
                    parse_failed_count INTEGER NOT NULL DEFAULT 0,
                    failed_count INTEGER NOT NULL DEFAULT 0,
                    duplicate_count INTEGER NOT NULL DEFAULT 0,
                    success_rate REAL NOT NULL DEFAULT 0,
                    result_complete INTEGER NOT NULL DEFAULT 1,
                    detail_limit_reached INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS news_crawl_failures (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    report_id INTEGER,
                    source_name TEXT NOT NULL,
                    article_url TEXT NOT NULL,
                    title TEXT,
                    status TEXT NOT NULL,
                    error_type TEXT,
                    error_message TEXT,
                    crawl_time TEXT NOT NULL
                );
            """)
            article_columns = {row["name"] for row in connection.execute("PRAGMA table_info(news_articles)")}
            for name, definition in {"source_site": "TEXT", "original_url": "TEXT", "canonical_url": "TEXT", "matched_keywords": "TEXT NOT NULL DEFAULT '[]'"}.items():
                if name not in article_columns:
                    connection.execute(f"ALTER TABLE news_articles ADD COLUMN {name} {definition}")
            connection.execute("UPDATE news_articles SET source_site=COALESCE(source_site, source_name), original_url=COALESCE(original_url, url), canonical_url=COALESCE(canonical_url, url), matched_keywords=COALESCE(matched_keywords, '[]')")
            connection.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_news_articles_canonical_url ON news_articles(canonical_url)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_articles_source_time ON news_articles(source_name, crawled_at DESC)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_articles_source_site ON news_articles(source_site)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_articles_published_at ON news_articles(published_at)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_articles_crawled_at ON news_articles(crawled_at DESC)")
            log_columns = {row["name"] for row in connection.execute("PRAGMA table_info(news_crawl_logs)")}
            for name, definition in {"column_url": "TEXT", "page_url": "TEXT", "article_url": "TEXT", "published_at": "TEXT", "http_status": "INTEGER", "retry_count": "INTEGER NOT NULL DEFAULT 0", "error_type": "TEXT", "error_message": "TEXT", "crawl_time": "TEXT"}.items():
                if name not in log_columns:
                    connection.execute(f"ALTER TABLE news_crawl_logs ADD COLUMN {name} {definition}")
            connection.execute("UPDATE news_crawl_logs SET crawl_time=COALESCE(crawl_time, finished_at, started_at)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_logs_source_time ON news_crawl_logs(source_name, crawl_time DESC)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_logs_status_time ON news_crawl_logs(status, crawl_time DESC)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_logs_article_url ON news_crawl_logs(article_url)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_logs_page_url ON news_crawl_logs(page_url)")
            report_columns = {row["name"] for row in connection.execute("PRAGMA table_info(news_crawl_reports)")}
            for name, definition in {
                "publish_time_start": "TEXT",
                "publish_time_end": "TEXT",
                "publish_time_filtered_count": "INTEGER NOT NULL DEFAULT 0",
                "listing_publish_time_filtered_count": "INTEGER NOT NULL DEFAULT 0",
                "detail_publish_time_filtered_count": "INTEGER NOT NULL DEFAULT 0",
                "publish_time_unknown_count": "INTEGER NOT NULL DEFAULT 0",
                "result_complete": "INTEGER NOT NULL DEFAULT 1",
                "detail_limit_reached": "INTEGER NOT NULL DEFAULT 0",
            }.items():
                if name not in report_columns:
                    connection.execute(f"ALTER TABLE news_crawl_reports ADD COLUMN {name} {definition}")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_reports_source_time ON news_crawl_reports(source_name, end_time DESC)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_reports_end_time ON news_crawl_reports(end_time DESC)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_failures_source_time ON news_crawl_failures(source_name, crawl_time DESC)")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_news_failures_report ON news_crawl_failures(report_id)")

    def url_exists(self, canonical_url: str) -> bool:
        with self._connect() as connection:
            return connection.execute("SELECT 1 FROM news_articles WHERE canonical_url=? LIMIT 1", (canonical_url,)).fetchone() is not None

    def get_article_by_canonical_url(self, canonical_url: str) -> NewsArticle | None:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM news_articles WHERE canonical_url=? LIMIT 1", (canonical_url,)).fetchone()
        return self._article(row) if row else None

    def content_hash_exists(self, content_hash: str) -> bool:
        with self._connect() as connection:
            return connection.execute("SELECT 1 FROM news_articles WHERE content_hash=? LIMIT 1", (content_hash,)).fetchone() is not None

    def save_article(self, article: NewsArticle) -> str:
        # This is the sole persistence boundary for every crawler mode.  Keep
        # raw publisher values in crawl logs when needed, but never persist
        # source-specific date representations in ``news_articles``.
        article = article.model_copy(update={"published_at": normalize_published_at(article.published_at)})
        with self._connect() as connection:
            existing = connection.execute("SELECT id, content_hash, published_at FROM news_articles WHERE canonical_url=?", (article.canonical_url,)).fetchone()
            if existing:
                if existing["content_hash"] == article.content_hash:
                    # A later crawl can carry a publisher-provided date that
                    # was unavailable (or only heuristically detected) in an
                    # earlier run.  Refresh that one metadata field without
                    # creating a duplicate news record.
                    if article.published_at and article.published_at != existing["published_at"]:
                        connection.execute(
                            "UPDATE news_articles SET published_at=?, crawled_at=? WHERE id=?",
                            (article.published_at, article.crawled_at, existing["id"]),
                        )
                    return "duplicate_url"
                if self.content_hash_exists(article.content_hash):
                    return "duplicate_content"
                connection.execute("UPDATE news_articles SET source_name=?,source_site=?,title=?,content=?,published_at=?,crawled_at=?,url=?,original_url=?,matched_keywords=?,content_hash=? WHERE id=?", (article.source_name, article.source_site, article.title, article.content, article.published_at, article.crawled_at, article.url, article.original_url, json.dumps(article.matched_keywords, ensure_ascii=False), article.content_hash, existing["id"]))
                return "success"
            if self.content_hash_exists(article.content_hash):
                return "duplicate_content"
            connection.execute("INSERT INTO news_articles (source_name,source_site,title,content,published_at,crawled_at,url,original_url,canonical_url,matched_keywords,content_hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)", (article.source_name, article.source_site, article.title, article.content, article.published_at, article.crawled_at, article.url, article.original_url, article.canonical_url, json.dumps(article.matched_keywords, ensure_ascii=False), article.content_hash))
        return "success"

    def add_log(self, log: CrawlLog) -> int:
        with self._connect() as connection:
            cursor = connection.execute("INSERT INTO news_crawl_logs (source_name,column_url,page_url,article_url,published_at,status,http_status,retry_count,error_type,error_message,crawl_time,started_at,finished_at,article_count,new_count,duplicate_count,detail) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,0,?)", (log.source_name, log.column_url, log.page_url, log.article_url, log.published_at, log.status, log.http_status, log.retry_count, log.error_type, log.error_message, log.crawl_time, log.crawl_time, log.crawl_time, log.error_message))
        return int(cursor.lastrowid)

    @staticmethod
    def _report(row: sqlite3.Row) -> CrawlReport:
        return CrawlReport.model_validate(dict(row))

    @staticmethod
    def _failure(row: sqlite3.Row) -> CrawlFailure:
        return CrawlFailure.model_validate(dict(row))

    def save_crawl_report(self, report: CrawlReport, failures: list[CrawlFailure]) -> CrawlReport:
        values = report.model_dump(exclude={"id"})
        columns = list(values)
        with self._connect() as connection:
            cursor = connection.execute(
                f"INSERT INTO news_crawl_reports ({','.join(columns)}) VALUES ({','.join('?' for _ in columns)})",
                [values[column] for column in columns],
            )
            report_id = int(cursor.lastrowid)
            for failure in failures:
                data = failure.model_dump(exclude={"id", "report_id"})
                failure_columns = ["report_id", *data]
                connection.execute(
                    f"INSERT INTO news_crawl_failures ({','.join(failure_columns)}) VALUES ({','.join('?' for _ in failure_columns)})",
                    [report_id, *[data[column] for column in data]],
                )
        return report.model_copy(update={"id": report_id})

    def latest_crawl_report(self, *, source_name: str | None) -> CrawlReport | None:
        where, parameters = ("WHERE source_name=?", [source_name]) if source_name else ("", [])
        with self._connect() as connection:
            row = connection.execute(f"SELECT * FROM news_crawl_reports {where} ORDER BY end_time DESC,id DESC LIMIT 1", parameters).fetchone()
        return self._report(row) if row else None

    def list_crawl_reports(self, *, source_name: str | None, start_time: str | None, end_time: str | None, limit: int, offset: int) -> tuple[int, list[CrawlReport]]:
        clauses: list[str] = []
        parameters: list[object] = []
        if source_name:
            clauses.append("source_name=?")
            parameters.append(source_name)
        if start_time:
            clauses.append("start_time>=?")
            parameters.append(start_time)
        if end_time:
            clauses.append("end_time<=?")
            parameters.append(end_time)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as connection:
            total = int(connection.execute(f"SELECT COUNT(*) FROM news_crawl_reports {where}", parameters).fetchone()[0])
            rows = connection.execute(f"SELECT * FROM news_crawl_reports {where} ORDER BY end_time DESC,id DESC LIMIT ? OFFSET ?", [*parameters, limit, offset]).fetchall()
        return total, [self._report(row) for row in rows]

    def list_crawl_failures(self, *, source_name: str | None, status: str | None, report_id: int | None, limit: int, offset: int) -> tuple[int, list[CrawlFailure]]:
        clauses: list[str] = []
        parameters: list[object] = []
        for column, value in (("source_name", source_name), ("status", status), ("report_id", report_id)):
            if value is not None:
                clauses.append(f"{column}=?")
                parameters.append(value)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as connection:
            total = int(connection.execute(f"SELECT COUNT(*) FROM news_crawl_failures {where}", parameters).fetchone()[0])
            rows = connection.execute(f"SELECT * FROM news_crawl_failures {where} ORDER BY crawl_time DESC,id DESC LIMIT ? OFFSET ?", [*parameters, limit, offset]).fetchall()
        return total, [self._failure(row) for row in rows]

    def last_success_at(self, source_name: str) -> str | None:
        with self._connect() as connection:
            row = connection.execute("SELECT crawl_time FROM news_crawl_logs WHERE source_name=? AND status='success' AND column_url IS NULL AND page_url IS NULL AND article_url IS NULL ORDER BY id DESC LIMIT 1", (source_name,)).fetchone()
        return str(row["crawl_time"]) if row else None

    @staticmethod
    def _article(row: sqlite3.Row) -> NewsArticle:
        return NewsArticle.model_validate({**dict(row), "matched_keywords": json.loads(row["matched_keywords"] or "[]")})

    def list_articles(
        self,
        *,
        source_name: str | None,
        source_site: str | None,
        keyword: str | None,
        publish_time_start: str | None,
        publish_time_end: str | None,
        crawl_time_start: str | None,
        crawl_time_end: str | None,
        title: str | None,
        limit: int,
        offset: int,
    ) -> tuple[int, list[NewsArticle]]:
        clauses: list[str] = []
        parameters: list[object] = []
        if source_name:
            clauses.append("source_name=?")
            parameters.append(source_name)
        if source_site:
            clauses.append("source_site=?")
            parameters.append(source_site)
        if keyword:
            clauses.append("EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid(matched_keywords) THEN matched_keywords ELSE '[]' END) WHERE value=?)")
            parameters.append(keyword)
        if title:
            clauses.append("title LIKE ?")
            parameters.append(f"%{title}%")
        # Publication filters have calendar-day semantics.  Canonical
        # date-only and UTC ISO values share the same YYYY-MM-DD prefix, so a
        # half-open end boundary includes every instant of the requested day.
        start_date = parse_publish_date(publish_time_start)
        end_date = parse_publish_date(publish_time_end)
        if publish_time_start:
            clauses.append("published_at>=?")
            parameters.append(start_date.isoformat() if start_date is not None else publish_time_start)
        if publish_time_end:
            clauses.append("published_at<?")
            parameters.append((end_date + timedelta(days=1)).isoformat() if end_date is not None else publish_time_end)
        for column, value, operator in (
            ("crawled_at", crawl_time_start, ">="),
            ("crawled_at", crawl_time_end, "<="),
        ):
            if value:
                clauses.append(f"{column}{operator}?")
                parameters.append(value)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as connection:
            total = int(connection.execute(f"SELECT COUNT(*) FROM news_articles {where}", parameters).fetchone()[0])
            rows = connection.execute(f"SELECT * FROM news_articles {where} ORDER BY crawled_at DESC,id DESC LIMIT ? OFFSET ?", [*parameters, limit, offset]).fetchall()
        return total, [self._article(row) for row in rows]

    def get_article(self, article_id: int) -> NewsArticle | None:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM news_articles WHERE id=?", (article_id,)).fetchone()
        return self._article(row) if row else None

    def delete_article(self, article_id: int) -> bool:
        with self._connect() as connection:
            cursor = connection.execute("DELETE FROM news_articles WHERE id=?", (article_id,))
        return cursor.rowcount > 0

    def delete_articles(self, article_ids: list[int]) -> int:
        unique_ids = list(dict.fromkeys(article_ids))
        placeholders = ",".join("?" for _ in unique_ids)
        with self._connect() as connection:
            cursor = connection.execute(f"DELETE FROM news_articles WHERE id IN ({placeholders})", unique_ids)
        return cursor.rowcount

    def delete_by_source_site(self, *, source_site: str, publish_time_start: str | None, publish_time_end: str | None) -> int:
        clauses = ["source_site=?"]
        parameters: list[object] = [source_site]
        if publish_time_start:
            clauses.append("published_at>=?")
            parameters.append(publish_time_start)
        if publish_time_end:
            clauses.append("published_at<=?")
            parameters.append(publish_time_end)
        with self._connect() as connection:
            cursor = connection.execute(f"DELETE FROM news_articles WHERE {' AND '.join(clauses)}", parameters)
        return cursor.rowcount

    @staticmethod
    def _log(row: sqlite3.Row) -> CrawlLog:
        return CrawlLog.model_validate(dict(row))

    def list_logs(
        self,
        *,
        source_name: str | None,
        status: str | None,
        article_url: str | None,
        page_url: str | None,
        crawl_time_start: str | None,
        crawl_time_end: str | None,
        limit: int,
        offset: int,
    ) -> tuple[int, list[CrawlLog]]:
        clauses: list[str] = []
        parameters: list[object] = []
        for column, value in (("source_name", source_name), ("status", status), ("article_url", article_url), ("page_url", page_url)):
            if value:
                clauses.append(f"{column}=?")
                parameters.append(value)
        if crawl_time_start:
            clauses.append("crawl_time>=?")
            parameters.append(crawl_time_start)
        if crawl_time_end:
            clauses.append("crawl_time<=?")
            parameters.append(crawl_time_end)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._connect() as connection:
            total = int(connection.execute(f"SELECT COUNT(*) FROM news_crawl_logs {where}", parameters).fetchone()[0])
            rows = connection.execute(f"SELECT * FROM news_crawl_logs {where} ORDER BY crawl_time DESC,id DESC LIMIT ? OFFSET ?", [*parameters, limit, offset]).fetchall()
        return total, [self._log(row) for row in rows]


