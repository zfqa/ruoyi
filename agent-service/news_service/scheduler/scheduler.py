"""Per-source APScheduler integration that reuses the existing news service."""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from datetime import timedelta
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger

from news_service.config import load_sources
from news_service.models import NewsSourceConfig
from news_service.service import NewsService

logger = logging.getLogger(__name__)
_FREQUENCY_PATTERN = re.compile(r"^(?P<amount>[1-9]\d*)(?P<unit>[mhd])$", re.IGNORECASE)
_JOB_PREFIX = "news_crawl:"
_TIMEZONE_NAME = "Asia/Shanghai"
_TIMEZONE = ZoneInfo(_TIMEZONE_NAME)


def parse_crawl_frequency(value: str) -> timedelta:
    """Convert supported YAML intervals such as 30m, 6h and 1d to a delta."""
    matched = _FREQUENCY_PATTERN.fullmatch(value.strip())
    if not matched:
        raise ValueError(f"不支持的 crawl_frequency: {value}")
    amount = int(matched.group("amount"))
    unit = matched.group("unit").lower()
    if unit == "m":
        return timedelta(minutes=amount)
    if unit == "h":
        return timedelta(hours=amount)
    return timedelta(days=amount)


@dataclass(frozen=True)
class RegisteredJob:
    source_name: str
    schedule_type: str
    crawl_frequency: str
    day_of_week: str | None = None
    crawl_time: str | None = None


class NewsScheduler:
    """Owns only the news jobs for one FastAPI worker process."""

    def __init__(self) -> None:
        self._scheduler = BackgroundScheduler(timezone=_TIMEZONE)
        self._registered: dict[str, RegisteredJob] = {}

    @staticmethod
    def _job_id(source_name: str) -> str:
        return f"{_JOB_PREFIX}{source_name}"

    def _run_source(self, source_name: str, schedule_type: str) -> None:
        logger.info("[NEWS_SCHEDULER][START] source=%s", source_name)
        try:
            results = NewsService().crawl(
                source_name=source_name,
                force=False,
                # Cron itself is the authoritative weekly cadence.  Interval
                # jobs retain the legacy persisted-frequency guard.
                respect_frequency=schedule_type != "cron",
            )
            result = results[0] if results else None
            success = result.success if result else 0
            failed = (result.fetch_failed + result.parse_failed) if result else 0
            logger.info("[NEWS_SCHEDULER][FINISH] source=%s success=%s failed=%s", source_name, success, failed)
        except Exception as exc:
            logger.exception("[NEWS_SCHEDULER][ERROR] source=%s error=%s", source_name, exc)

    def reload(self) -> dict[str, int]:
        """Reconcile scheduler jobs against the current YAML configuration."""
        try:
            sources = load_sources()
        except Exception as exc:
            logger.error("[NEWS_SCHEDULER][ERROR] reload config error=%s", exc)
            return {"added": 0, "updated": 0, "removed": 0, "jobs": len(self._registered)}

        desired: dict[str, RegisteredJob] = {}
        seen_names: set[str] = set()
        for source in sources:
            if not source.enabled:
                continue
            if source.name in seen_names:
                logger.warning("[NEWS_SCHEDULER][WARNING] duplicate source=%s skipped", source.name)
                continue
            seen_names.add(source.name)
            job_id = self._job_id(source.name)
            schedule_type = source.schedule_type or "interval"
            if schedule_type == "cron":
                # Model validation guarantees both fields; the defensive
                # check keeps an invalid configuration isolated to this source.
                if source.day_of_week is None or source.crawl_time is None:
                    logger.warning("[NEWS_SCHEDULER][WARNING] source=%s cron configuration incomplete", source.name)
                    continue
                hour, minute = (int(part) for part in source.crawl_time.split(":", 1))
                trigger = CronTrigger(
                    day_of_week=source.day_of_week,
                    hour=hour,
                    minute=minute,
                    timezone=_TIMEZONE,
                )
                registered = RegisteredJob(
                    source_name=source.name,
                    schedule_type="cron",
                    crawl_frequency=source.crawl_frequency,
                    day_of_week=source.day_of_week,
                    crawl_time=source.crawl_time,
                )
                misfire_grace_time = 3600
            else:
                try:
                    interval = parse_crawl_frequency(source.crawl_frequency)
                except ValueError as exc:
                    logger.warning("[NEWS_SCHEDULER][WARNING] source=%s frequency=%s skipped: %s", source.name, source.crawl_frequency, exc)
                    continue
                trigger = IntervalTrigger(seconds=int(interval.total_seconds()), timezone=_TIMEZONE)
                registered = RegisteredJob(
                    source_name=source.name,
                    schedule_type="interval",
                    crawl_frequency=source.crawl_frequency,
                )
                misfire_grace_time = int(interval.total_seconds())
            desired[job_id] = registered
            previous = self._registered.get(job_id)
            if previous == registered:
                continue
            self._scheduler.add_job(
                self._run_source,
                trigger=trigger,
                args=[source.name, registered.schedule_type],
                id=job_id,
                name=f"News crawl: {source.name}",
                replace_existing=True,
                max_instances=1,
                coalesce=True,
                misfire_grace_time=misfire_grace_time,
            )
            action = "added" if previous is None else "updated"
            logger.info("[NEWS_SCHEDULER][REGISTER] source=%s schedule_type=%s schedule=%s action=%s", source.name, registered.schedule_type, source.crawl_time if registered.schedule_type == "cron" else source.crawl_frequency, action)

        existing_ids = {job.id for job in self._scheduler.get_jobs() if job.id.startswith(_JOB_PREFIX)}
        removed_ids = (set(self._registered) | existing_ids) - set(desired)
        for job_id in removed_ids:
            try:
                self._scheduler.remove_job(job_id)
            except Exception:
                # A stale job can disappear during scheduler shutdown/reload.
                pass
        added = sum(1 for job_id, item in desired.items() if job_id not in self._registered)
        updated = sum(1 for job_id, item in desired.items() if job_id in self._registered and self._registered[job_id] != item)
        self._registered = desired
        return {"added": added, "updated": updated, "removed": len(removed_ids), "jobs": len(desired)}

    def start(self) -> dict[str, int]:
        result = self.reload()
        if not self._scheduler.running:
            self._scheduler.start()
        return result

    def shutdown(self) -> None:
        if self._scheduler.running:
            self._scheduler.shutdown(wait=False)
        self._registered.clear()

    def status(self) -> dict:
        jobs: list[dict] = []
        for job in self._scheduler.get_jobs():
            registered = self._registered.get(job.id)
            if not registered:
                continue
            jobs.append({
                "source_name": registered.source_name,
                "job_id": job.id,
                "schedule_type": registered.schedule_type,
                "crawl_frequency": registered.crawl_frequency,
                "day_of_week": registered.day_of_week,
                "crawl_time": registered.crawl_time,
                "timezone": _TIMEZONE_NAME,
                "next_run_time": job.next_run_time.isoformat() if job.next_run_time else None,
                "enabled": True,
            })
        return {"running": bool(self._scheduler.running), "jobs": jobs}


_scheduler: NewsScheduler | None = None


def get_news_scheduler() -> NewsScheduler:
    global _scheduler
    if _scheduler is None:
        _scheduler = NewsScheduler()
    return _scheduler


