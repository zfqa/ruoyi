"""Background scheduling for configured whitelist news crawls."""

from news_service.scheduler.scheduler import NewsScheduler, get_news_scheduler

__all__ = ["NewsScheduler", "get_news_scheduler"]


