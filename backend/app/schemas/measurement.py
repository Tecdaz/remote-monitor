"""Measurement request/response models (REQ-INGEST-01..07, REQ-READ-01,03).

Sourced from ``contracts/openapi.yaml#/components/schemas/{Measurement,
MeasurementBatch, BatchResponse, RejectedMeasurement}``.

Band-only contract (S1/S2): every measurement comes from the Polar H10
band. The watch-only fields are gone; ``extra="forbid"`` rejects old
payloads that still send them.
"""
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator


class MeasurementBatch(BaseModel):
    """One band measurement submitted by the collector app.

    Heart rate, IBI and ECG come from the Polar H10. Each may be null
    when the band does not provide it.
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
        description="Heart rate in BPM. `null` if the band did not provide it.",
    )
    # Raw IBI array from the Polar H10 band — delivered as-is,
    # no transformation. `None` when the band does not provide IBI.
    ibis_ms: list[int] | None = Field(
        default=None,
        description="Raw inter-beat intervals in milliseconds (Polar H10 RR intervals).",
    )

    # Polar H10 ECG snippet — 10 s @ 130 Hz =
    # 1300 voltages in µV. `None` on every non-ECG row. Rows carrying
    # ECG have `heart_rate_bpm = None`; they are validation strips,
    # not vital signs. The rate is declared first so the `ecg_uv`
    # validator below can read it from `info.data` (Pydantic validates
    # fields in definition order).
    ecg_sample_rate_hz: int | None = Field(
        default=None,
        description="Sample rate of `ecg_uv` in Hz (H10 ECG: 130).",
    )
    ecg_uv: list[int] | None = Field(
        default=None,
        description=(
            "Polar H10 ECG snippet voltages in microvolts "
            "(@ `ecg_sample_rate_hz`). `None` on non-ECG rows."
        ),
    )

    @field_validator("ibis_ms")
    @classmethod
    def _clamp_ibis(cls, v: list[int] | None) -> list[int] | None:
        if v is None:
            return v
        for x in v:
            # A 0 entry means "no IBI data" for that beat; we reject it
            # at the boundary so the array is uniformly in [1, 5000]
            # for downstream HRV math.
            if not (1 <= x <= 5000):
                raise ValueError(f"ibis_ms value {x} out of [1, 5000]")
        return v

    @field_validator("ecg_sample_rate_hz")
    @classmethod
    def _validate_ecg_rate(cls, v: int | None, info) -> int | None:
        # polar-ecg-integration: the H10 ECG streams at 130 Hz; the
        # rate travels with the strip so future rates fail loudly
        # instead of silently rescaling the time axis.
        if v is None:
            return v
        if v != 130:
            raise ValueError(f"ecg_sample_rate_hz value {v} not in {{130}}")
        return v

    @field_validator("ecg_uv")
    @classmethod
    def _validate_ecg_uv(cls, v: list[int] | None, info) -> list[int] | None:
        # polar-ecg-integration: one 10 s @ 130 Hz snippet = 1300 µV
        # samples. An ECG row must carry its rate alongside; a
        # rate without voltages (or vice versa) is rejected so a
        # half-written strip can never look like a vital sign.
        if v is None:
            if info.data.get("ecg_sample_rate_hz") is not None:
                raise ValueError("ecg_sample_rate_hz without ecg_uv")
            return v
        if info.data.get("ecg_sample_rate_hz") is None:
            raise ValueError("ecg_uv without ecg_sample_rate_hz")
        if not (1 <= len(v) <= 1300):
            raise ValueError(f"ecg_uv length {len(v)} out of [1, 1300]")
        for x in v:
            if not (-100_000 <= x <= 100_000):
                raise ValueError(f"ecg_uv value {x} out of [-100000, 100000]")
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
    """One item the backend refused. The collector app keeps the local row."""

    model_config = ConfigDict(extra="forbid")

    local_id: UUID = Field(..., description="The measurement's client UUID.")
    reason: str = Field(
        ...,
        description="Human-readable reason. Not a code (PoC).",
    )


class BatchResponse(BaseModel):
    """The collector app deletes local rows ONLY for ``local_id``s in ``accepted_ids``."""

    model_config = ConfigDict(extra="forbid")

    accepted_ids: list[UUID] = Field(
        ...,
        description=(
            "The `local_id`s the backend persisted (or no-op-deduped). "
            "The collector app deletes these."
        ),
    )
    rejected: list[RejectedMeasurement] = Field(
        default_factory=list,
        description="Items the backend refused.",
    )
