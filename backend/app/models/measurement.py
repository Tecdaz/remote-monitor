"""``clinical.measurements``: per-reading Polar H10 band measurement.

UNIQUE(patient_id, local_id) is the DB-layer idempotency key
(REQ-SCHEMA-03): re-ingest of the same (patient_id, local_id) is a
no-op at the ORM layer thanks to ``ON CONFLICT DO NOTHING`` in the
ingest service (PR3 T3.2).
"""
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, UniqueConstraint
from sqlalchemy.dialects.postgresql import ARRAY, UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models import Base


class ClinicalMeasurement(Base):
    """``clinical.measurements``: one Polar H10 band reading."""

    __tablename__ = "measurements"
    __table_args__ = (
        UniqueConstraint(
            "patient_id",
            "local_id",
            name="ix_clinical_measurements_patient_id_local_id",
        ),
        {"schema": "clinical"},
    )

    id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        primary_key=True,
        server_default=None,  # server default is added by the migration
    )
    patient_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        ForeignKey("clinical.patients.patient_id"),
        nullable=False,
    )
    local_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        nullable=False,
    )
    timestamp: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
    )
    heart_rate_bpm: Mapped[int | None] = mapped_column(Integer, nullable=True)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=None,  # server default is added by the migration
    )
    # Raw IBI array from the Polar H10 band, delivered as-is.
    # Stored as `bigint[]`. NULL when the band does not expose IBI.
    ibis_ms: Mapped[list[int] | None] = mapped_column(
        ARRAY(BigInteger), nullable=True
    )
    # Polar H10 ECG snippet — 10 s @ 130 Hz =
    # 1300 voltages in µV. Stored as `integer[]`. NULL on every
    # non-ECG row. Rows carrying ECG have `heart_rate_bpm = NULL`;
    # they are validation strips, not vitals.
    ecg_uv: Mapped[list[int] | None] = mapped_column(
        ARRAY(Integer), nullable=True
    )
    # Sample rate of `ecg_uv` in Hz (H10 ECG streams at 130 Hz).
    ecg_sample_rate_hz: Mapped[int | None] = mapped_column(
        Integer, nullable=True
    )
