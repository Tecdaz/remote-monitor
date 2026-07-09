"""Measurement request/response models (REQ-INGEST-01..07, REQ-READ-01,03).

Sourced from ``contracts/openapi.yaml#/components/schemas/{Measurement,
MeasurementBatch, BatchResponse, RejectedMeasurement}``.
"""
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class MeasurementBatch(BaseModel):
    """One measurement submitted by the watch.

    Either or both vital signs may be null if the sensor failed.
    """

    model_config = ConfigDict(extra="forbid")

    local_id: UUID = Field(
        ...,
        description=(
            "Client-generated UUID v4. Idempotency key. The backend "
            "unique-constraints on (patient_id, local_id)."
        ),
    )
    timestamp: datetime = Field(
        ...,
        description=(
            "ISO 8601 with timezone, within the last 24 hours and not "
            "more than 5 minutes in the future."
        ),
    )
    heart_rate_bpm: int | None = Field(
        None,
        ge=1,
        le=299,
        description="Heart rate in BPM. `null` if the sensor failed.",
    )
    spo2_percent: float | None = Field(
        None,
        gt=0,
        le=100,
        description="SpO2 percentage (0-100). `null` if the sensor failed.",
    )
    # Raw IBI array from the Samsung sensor — delivered as-is,
    # no transformation. `None` when the device does not provide IBI.
    ibis_ms: list[int] | None = Field(
        default=None,
        description="Raw inter-beat intervals in milliseconds (Samsung IBI_LIST).",
    )

    @field_validator("ibis_ms")
    @classmethod
    def _clamp_ibis(cls, v: list[int] | None) -> list[int] | None:
        if v is None:
            return v
        for x in v:
            if not (1 <= x <= 5000):
                raise ValueError(f"ibis_ms value {x} out of [1, 5000]")
        return v


class Measurement(MeasurementBatch):
    """A persisted measurement: ``MeasurementBatch`` + server-assigned fields."""

    model_config = ConfigDict(extra="forbid")

    id: UUID = Field(..., description="Server-assigned measurement ID.")
    patient_id: UUID = Field(
        ...,
        description=(
            "Opaque UUID; the only link from clinical data to PII is "
            "via the `Patient` record."
        ),
    )
    received_at: datetime = Field(
        ...,
        description="Server-assigned ingestion timestamp.",
    )


class RejectedMeasurement(BaseModel):
    """One item the backend refused. The watch keeps the Room row."""

    model_config = ConfigDict(extra="forbid")

    local_id: UUID = Field(..., description="The measurement's client UUID.")
    reason: str = Field(
        ...,
        description="Human-readable reason. Not a code (PoC).",
    )


class BatchResponse(BaseModel):
    """The watch deletes local Room rows ONLY for ``local_id``s in ``accepted_ids``."""

    model_config = ConfigDict(extra="forbid")

    accepted_ids: list[UUID] = Field(
        ...,
        description=(
            "The `local_id`s the backend persisted (or no-op-deduped). "
            "The watch deletes these."
        ),
    )
    rejected: list[RejectedMeasurement] = Field(
        default_factory=list,
        description="Items the backend refused.",
    )
