"""Deterministic parsing for absolute publication dates in news pages."""
from __future__ import annotations

import re
from datetime import date, datetime, timedelta, timezone


_DATE_PATTERN = re.compile(
    r"(?P<year>\d{4})\s*(?:[-/.]|年)\s*"
    r"(?P<month>\d{1,2})\s*(?:[-/.]|月)\s*"
    r"(?P<day>\d{1,2})(?:\s*日)?"
)

# Official overseas newsroom pages commonly expose publication dates as
# ``August 26, 2026`` / ``Aug. 26, 2026`` or ``26 August 2026``.  The
# crawler must retain the publisher's original string, but range filtering
# needs to turn those formats into a calendar date as well.
_ENGLISH_DATE_FORMATS = (
    "%B %d, %Y",
    "%b %d, %Y",
    "%b. %d, %Y",
    "%d %B %Y",
    "%d %b %Y",
    "%d %b. %Y",
)

# US-based official newsrooms also commonly publish a fully explicit numeric
# date such as ``05/16/2025``.  Treat it as month/day/year because this branch
# is intentionally limited to the four-digit-year slash format.
_US_NUMERIC_DATE_FORMAT = re.compile(
    r"(?<!\d)(?P<month>\d{1,2})/(?P<day>\d{1,2})/(?P<year>\d{4})(?!\d)"
)

# Some official European media rooms publish a compact day/month/two-digit-year
# value such as ``11/07/26``.  It is an explicit publisher date, not an ID.
# Restrict this branch to slash-delimited values with a two-digit final year so
# it cannot change the existing unambiguous four-digit-year handling above.
_EU_SHORT_NUMERIC_DATE_FORMAT = re.compile(
    r"(?<!\d)(?P<day>\d{1,2})[/.](?P<month>\d{1,2})[/.](?P<year>\d{2})(?!\d)"
)

# Some press rooms include the weekday, local clock time and a publisher
# timezone abbreviation, e.g. ``Wed Aug 26 00:01:00 CEST 2026``.  Timezone
# abbreviations are not portable in ``strptime`` (notably on Windows), but
# the date component is explicit and can be parsed safely without inferring a
# timezone conversion.
_ENGLISH_DATETIME_WITH_ZONE = re.compile(
    r"(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s+"
    r"(?P<month>[A-Za-z]{3,9})\.?\s+(?P<day>\d{1,2})\s+"
    r"\d{1,2}:\d{2}(?::\d{2})?\s+[A-Za-z]{2,8}\s+(?P<year>\d{4})",
    re.IGNORECASE,
)

_ISO_DATE_ONLY = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_NUMERIC_SLASH_DATE = re.compile(
    r"^(?P<first>\d{1,2})/(?P<second>\d{1,2})/(?P<year>\d{2}|\d{4})$"
)


def _epoch_datetime(value: str) -> datetime | None:
    """Return a UTC instant for a plausible 10/13 digit Unix timestamp."""
    if not re.fullmatch(r"\d{10}(?:\d{3})?", value):
        return None
    try:
        seconds = int(value) / (1000 if len(value) == 13 else 1)
        parsed = datetime.fromtimestamp(seconds, tz=timezone.utc)
    except (OverflowError, OSError, ValueError):
        return None
    # Do not reinterpret arbitrary numeric identifiers as dates.  This range
    # covers the POC archive while still allowing a reasonable near future.
    return parsed if 1970 <= parsed.year <= 2100 else None


def _utc_iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat()


def normalize_published_at(value: object | None, *, format_hint: str | None = None) -> str | None:
    """Convert a reliable publisher publication value to the storage contract.

    Instants become UTC ISO 8601 strings.  A source that supplies only a
    calendar date remains ``YYYY-MM-DD`` so the system never invents a clock
    time.  Naive datetimes likewise become date-only: without an explicit
    publisher timezone they cannot truthfully be converted to a UTC instant.
    Ambiguous numeric slash dates are rejected unless a configured format hint
    proves their order.
    """
    if value is None:
        return None
    if isinstance(value, datetime):
        return _utc_iso(value) if value.tzinfo is not None else value.date().isoformat()
    if isinstance(value, date):
        return value.isoformat()

    normalized = re.sub(r"\s+", " ", str(value).strip())
    if not normalized:
        return None

    epoch = _epoch_datetime(normalized)
    if epoch is not None:
        return _utc_iso(epoch)

    if _ISO_DATE_ONLY.fullmatch(normalized):
        try:
            return date.fromisoformat(normalized).isoformat()
        except ValueError:
            return None

    # ISO 8601 strings with an explicit offset identify an instant and can be
    # converted safely.  ``Z`` is normalised for ``fromisoformat`` on all
    # supported Python versions.
    iso_candidate = normalized[:-1] + "+00:00" if normalized.endswith("Z") else normalized
    try:
        parsed_iso = datetime.fromisoformat(iso_candidate)
    except ValueError:
        parsed_iso = None
    if parsed_iso is not None:
        return _utc_iso(parsed_iso) if parsed_iso.tzinfo is not None else parsed_iso.date().isoformat()

    slash = _NUMERIC_SLASH_DATE.fullmatch(normalized)
    if slash and int(slash.group("first")) <= 12 and int(slash.group("second")) <= 12 and not format_hint:
        return None
    if format_hint:
        try:
            parsed_hint = datetime.strptime(normalized, format_hint)
            return parsed_hint.date().isoformat()
        except ValueError:
            pass
    if slash:
        first, second, year_value = int(slash.group("first")), int(slash.group("second")), int(slash.group("year"))
        year = 2000 + year_value if len(slash.group("year")) == 2 else year_value
        try:
            # Values with one component above 12 are objectively ordered;
            # the ambiguous case returned above never reaches this branch.
            return date(year, second, first).isoformat() if first > 12 else date(year, first, second).isoformat()
        except ValueError:
            return None

    # ``parse_publish_date`` is deliberately retained for range comparisons.
    # It handles project-supported Chinese and English date forms.  At this
    # point a numeric slash date is either unambiguous or has a supplied hint.
    parsed_date = parse_publish_date(normalized)
    return parsed_date.isoformat() if parsed_date is not None else None


def parse_publish_date(value: str | None) -> date | None:
    """Extract an absolute calendar date from a publisher-provided value.

    Relative expressions such as ``今天`` and ``3小时前`` deliberately return
    ``None``: a user-provided publication range must never be inferred from
    the crawler's current time.
    """
    if not value:
        return None
    normalized = re.sub(r"\s+", " ", str(value).strip())
    # Public CMS list APIs often use a compact publisher timestamp such as
    # ``202609030830`` (YYYYMMDDHHMM).  This has an explicit calendar prefix
    # and is distinct from arbitrary numeric identifiers.
    compact = re.fullmatch(r"(?P<year>\d{4})(?P<month>\d{2})(?P<day>\d{2})(?:\d{4}(?:\d{2})?)?", normalized)
    if compact:
        try:
            return date(int(compact.group("year")), int(compact.group("month")), int(compact.group("day")))
        except ValueError:
            return None
    # Public CMS list endpoints commonly expose an absolute publication time
    # as Unix seconds or milliseconds.  It is still publisher-provided data,
    # not an inferred crawl timestamp.  Limit this to plausible 10/13 digit
    # epoch values so identifiers are never treated as dates.
    if re.fullmatch(r"\d{10}(?:\d{3})?", normalized):
        try:
            seconds = int(normalized) / (1000 if len(normalized) == 13 else 1)
            return datetime.fromtimestamp(seconds, tz=timezone.utc).date()
        except (OverflowError, OSError, ValueError):
            return None
    eu_short_numeric = _EU_SHORT_NUMERIC_DATE_FORMAT.search(normalized)
    if eu_short_numeric:
        try:
            return date(
                2000 + int(eu_short_numeric.group("year")),
                int(eu_short_numeric.group("month")),
                int(eu_short_numeric.group("day")),
            )
        except ValueError:
            return None
    us_numeric = _US_NUMERIC_DATE_FORMAT.search(normalized)
    if us_numeric:
        try:
            return date(
                int(us_numeric.group("year")),
                int(us_numeric.group("month")),
                int(us_numeric.group("day")),
            )
        except ValueError:
            return None
    matched = _DATE_PATTERN.search(normalized)
    if not matched:
        datetime_with_zone = _ENGLISH_DATETIME_WITH_ZONE.search(normalized)
        if datetime_with_zone:
            candidate = (
                f"{datetime_with_zone.group('month')} "
                f"{datetime_with_zone.group('day')}, "
                f"{datetime_with_zone.group('year')}"
            )
            for date_format in ("%B %d, %Y", "%b %d, %Y", "%b. %d, %Y"):
                try:
                    return datetime.strptime(candidate, date_format).date()
                except ValueError:
                    continue
            return None
        # Parse only explicit, absolute English calendar dates.  Do not infer
        # relative time phrases from the crawl timestamp.
        english = re.search(
            r"(?:\d{1,2}\s+[A-Za-z]{3,9}\.?\s+\d{4}|[A-Za-z]{3,9}\.?\s+\d{1,2},\s*\d{4})",
            normalized,
        )
        if not english:
            return None
        candidate = english.group(0)
        for date_format in _ENGLISH_DATE_FORMATS:
            try:
                return datetime.strptime(candidate, date_format).date()
            except ValueError:
                continue
        return None
    try:
        return date(
            int(matched.group("year")),
            int(matched.group("month")),
            int(matched.group("day")),
        )
    except ValueError:
        return None


def parse_requested_date(value: str | None, *, field_name: str) -> date | None:
    """Parse one optional crawl-range boundary or raise a clear API error."""
    if value is None or not value.strip():
        return None
    parsed = parse_publish_date(value)
    if parsed is None:
        raise ValueError(f"{field_name} 日期格式无效，请使用 YYYY-MM-DD")
    return parsed


def first_parseable_publish_value(value: object | None, *, format_hint: str | None = None) -> str | None:
    """Return a storage-ready publish value only when an absolute date is present.

    Used by detail/list extractors so a mis-targeted CSS node (source name,
    media label, share button text, etc.) never becomes ``published_at``.
    Relative phrases still return ``None``.
    """
    if value is None:
        return None
    text = re.sub(r"\s+", " ", str(value).strip())
    if not text:
        return None
    normalized = normalize_published_at(text, format_hint=format_hint)
    if normalized is not None:
        return normalized
    parsed = parse_publish_date(text)
    return parsed.isoformat() if parsed is not None else None


