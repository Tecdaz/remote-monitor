"""Per-bed occupancy snapshot models (REQ-READ-04, REQ-SCHEMA-05).

Mirrors ``contracts/openapi.yaml#/components/schemas/{BedSnapshot,
BedSnapshotList}`` exactly. The PoC bed universe is hardcoded to
``1..5``; the endpoint always returns five entries (even when no rows
exist) so the watch can render the carousel without a separate
"empty" code path.
"""
from __future__ import annotations

from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class BedSnapshot(BaseModel):
    """One bed's occupancy state at a moment in time.

    ``current_patient_id`` is the opaque UUID of the active patient on
    the bed, or ``None`` when the bed is free. UI display MUST source
    the bed number from the snapshot (``bed_number``) and the patient
    id is intentionally opaque — do NOT surface it to operators.
    """

    model_config = ConfigDict(extra="forbid")

    bed_number: int = Field(
        ...,
        ge=1,
        le=5,
        description="Bed number in 1..5 (PoC hardcoded range).",
        examples=[3],
    )
    is_occupied: bool = Field(
        ...,
        description=(
            "`true` iff a `clinical.patients` row exists with "
            "`bed_number = N AND is_active = true`."
        ),
    )
    current_patient_id: UUID | None = Field(
        ...,
        description=(
            "Opaque UUID of the active patient on this bed, or `null` "
            "when `is_occupied = false`."
        ),
    )
