"""``clinical.measurements``: per-vital-sign reading from the watch.

UNIQUE(patient_id, local_id) is the DB-layer idempotency key
(REQ-SCHEMA-03): re-ingest of the same (patient_id, local_id) is a
no-op at the ORM layer thanks to ``ON CONFLICT DO NOTHING`` in the
ingest service (PR3 T3.2).
"""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from uuid import UUID

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, Numeric, UniqueConstraint
from sqlalchemy.dialects.postgresql import ARRAY, UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models import Base


class ClinicalMeasurement(Base):
    """``clinical.measurements``: one heart-rate / SpO2 sample."""

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
    spo2_percent: Mapped[Decimal | None] = mapped_column(Numeric, nullable=True)
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=None,  # server default is added by the migration
    )
    # Raw IBI array from the Samsung sensor, delivered as-is.
    # Stored as `bigint[]`. NULL when the device does not expose IBI.
    ibis_ms: Mapped[list[int] | None] = mapped_column(
        ARRAY(BigInteger), nullable=True
    )
    # Per-beat quality flags from the Samsung sensor.
    # 0 = noisy/rejected, non-zero = accepted/clean.
    # Stored as `integer[]`. NULL when the device does not expose status,
    # or when the status array length does not match `ibis_ms`.
    ibis_status: Mapped[list[int] | None] = mapped_column(
        ARRAY(Integer), nullable=True
    )
