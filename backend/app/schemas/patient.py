"""Patient request/response models (REQ-READ-01..03, REQ-INGEST-07).

Sourced from ``contracts/openapi.yaml#/components/schemas/{Patient,
PatientRegistration}`` and ``contracts/data-models.md``.
"""
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class Patient(BaseModel):
    """A registered patient. ``patient_number`` is the only PII field."""

    model_config = ConfigDict(extra="forbid")

    patient_id: UUID = Field(
        ...,
        description=(
            "Server-assigned opaque UUID. The only link from clinical "
            "records to PII."
        ),
    )
    patient_number: str = Field(
        ...,
        description=(
            "PII \u2014 the operator-typed identifier (e.g. P-00042). "
            "Handle per HIPAA-like retention policy."
        ),
        examples=["P-00042"],
    )
    device_model: str = Field(..., description="Watch hardware model.")
    os_version: str = Field(..., description="Watch OS version string.")
    created_at: datetime = Field(
        ...,
        description="Server-assigned registration timestamp.",
    )
    is_active: bool = Field(
        ...,
        description="Soft-delete flag. `false` means deactivated.",
    )


class PatientRegistration(BaseModel):
    """Payload for ``POST /patients``. Omits server-assigned fields."""

    model_config = ConfigDict(extra="forbid")

    patient_number: str = Field(
        ...,
        description="PII \u2014 the operator-typed identifier.",
        examples=["P-00042"],
    )
    device_model: str = Field(
        ...,
        description="Watch hardware model.",
        examples=["Samsung Galaxy Watch 4"],
    )
    os_version: str = Field(
        ...,
        description="Watch OS version string.",
        examples=["Wear OS 6 (API 36)"],
    )
