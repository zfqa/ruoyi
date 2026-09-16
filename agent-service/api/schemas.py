"""Request schemas shared by agent-service HTTP routes."""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class VehicleExportFromDataRequest(BaseModel):
    """Trusted Java-to-Python vehicle task export payload; no staging lookup."""

    brand: str = Field(..., min_length=1, max_length=100)
    series_id: str = Field(..., min_length=1, max_length=100)
    series_name: str = Field(..., min_length=1, max_length=200)
    models: list[dict[str, Any]] = Field(..., min_length=1, max_length=1000)
