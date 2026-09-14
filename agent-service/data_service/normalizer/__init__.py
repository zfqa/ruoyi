"""Small, deterministic normalization helpers for business extraction."""

from data_service.normalizer.company_aliases import aliases_for_company, normalize_company_name

__all__ = ["aliases_for_company", "normalize_company_name"]


