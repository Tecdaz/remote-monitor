"""Common response envelopes (RFC 7807 Problem, paginated pages)."""
from __future__ import annotations

from typing import TYPE_CHECKING

from pydantic import BaseModel, ConfigDict, Field

if TYPE_CHECKING:
    from app.schemas.measurement import Measurement


class Problem(BaseModel):
    """RFC 7807-style problem detail."""

    model_config = ConfigDict(extra="forbid")

    detail: str = Field(..., description="Human-readable explanation.")
    code: str = Field(
        ...,
        description="Stable machine-readable error code.",
    )


class MeasurementPage(BaseModel):
    """A cursor-paginated page of measurements."""

    model_config = ConfigDict(extra="forbid")

    items: list["Measurement"] = Field(
        default_factory=list,
        description="The page of measurements.",
    )
    next_cursor: str | None = Field(
        None,
        description=(
            "Opaque cursor for the next page. `null` when no more pages."
        ),
    )
