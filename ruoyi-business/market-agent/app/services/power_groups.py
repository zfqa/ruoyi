from __future__ import annotations

import re
from typing import Any

import pandas as pd


POWER_GROUP_LABELS = {
    "new_energy": "新能源车",
    "fuel": "燃油车",
    "unclassified": "未分类",
}

NEW_ENERGY_TYPES = {"ev", "bev", "phv", "phev", "fcv"}
FUEL_TYPES = {"ice", "hv", "hev", "mhv", "mhev"}


def normalize_power_type(value: Any) -> str:
    """Normalize aliases without guessing compound or missing categories."""
    text = re.sub(r"\s+", "", str(value or "").strip().casefold())
    text = text.replace("－", "-").replace("／", "/")
    return text


def classify_power_type(value: Any, overrides: dict[str, str] | None = None) -> str:
    normalized = normalize_power_type(value)
    override_map = {
        normalize_power_type(key): str(group)
        for key, group in (overrides or {}).items()
        if str(group) in POWER_GROUP_LABELS
    }
    if normalized in override_map:
        return override_map[normalized]
    if normalized in NEW_ENERGY_TYPES:
        return "new_energy"
    if normalized in FUEL_TYPES:
        return "fuel"
    return "unclassified"


def power_group_catalog(df: pd.DataFrame) -> dict[str, Any]:
    values: list[str] = []
    if "power_type" in df.columns:
        values = list(dict.fromkeys(
            str(value).strip()
            for value in df["power_type"].dropna().tolist()
            if str(value).strip()
        ))
    mappings = [
        {
            "source_value": value,
            "normalized_value": normalize_power_type(value),
            "default_group": classify_power_type(value),
            "default_group_label": POWER_GROUP_LABELS[classify_power_type(value)],
            "requires_review": classify_power_type(value) == "unclassified",
        }
        for value in values
    ]
    return {
        "groups": [
            {"value": key, "label": label}
            for key, label in POWER_GROUP_LABELS.items()
            if key != "unclassified"
        ],
        "mapping_options": [
            {"value": key, "label": label} for key, label in POWER_GROUP_LABELS.items()
        ],
        "mappings": mappings,
    }
