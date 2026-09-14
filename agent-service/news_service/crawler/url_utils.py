"""Safe URL normalization and source-domain allow-list checks."""
from __future__ import annotations

from collections.abc import Iterable
from urllib.parse import parse_qsl, urlencode, urljoin, urlparse, urlunparse

TRACKING_PARAMETERS = {"spm", "from", "source", "ref", "referrer", "gclid", "fbclid"}
NON_HTML_SUFFIXES = (".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".pdf", ".zip", ".rar", ".7z", ".doc", ".docx", ".xls", ".xlsx")


def normalize_url(value: str, base_url: str | None = None) -> str | None:
    resolved = urljoin(base_url or "", value.strip())
    parsed = urlparse(resolved)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        return None
    query = [(key, item) for key, item in parse_qsl(parsed.query, keep_blank_values=True) if key.lower() not in TRACKING_PARAMETERS and not key.lower().startswith("utm_")]
    path = parsed.path.rstrip("/") or "/"
    return urlunparse((parsed.scheme.lower(), parsed.netloc.lower(), path, "", urlencode(query, doseq=True), ""))


def is_allowed_domain(url: str, domain: str | Iterable[str]) -> bool:
    host = (urlparse(url).hostname or "").lower()
    domains = [domain] if isinstance(domain, str) else domain
    for item in domains:
        allowed = str(item).lower().strip().lstrip(".")
        if host and allowed and (host == allowed or host.endswith(f".{allowed}")):
            return True
    return False


def is_html_candidate(url: str) -> bool:
    return not urlparse(url).path.lower().endswith(NON_HTML_SUFFIXES)


