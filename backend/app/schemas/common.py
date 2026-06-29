"""Common response envelopes (RFC 7807 Problem, paginated pages)."""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field

# Runtime import (not TYPE_CHECKING) so Pydantic v2 can resolve the
# forward reference ``Measurement`` when FastAPI tries to validate a
# ``MeasurementPage`` response. Without it, Pydantic raises
# ``PydanticUserError: MeasurementPage is not fully defined``.
from app.schemas.measurement import Measurement  # noqa: F401


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

    items: list[Measurement] = Field(
        default_factory=list,
        description="The page of measurements.",
    )
    next_cursor: str | None = Field(
        None,
        description=(
            "Opaque cursor for the next page. `null` when no more pages."
        ),
    )
