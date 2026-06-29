"""Patient ORM models: ``pii.patients`` + ``clinical.patients``.

The PII table stores an encrypted ``patient_number`` (bytea) via
pgcrypto; the clinical table holds the opaque ``patient_id`` + device
metadata. The two are joined on ``patient_id`` (the only link from
clinical to PII).
"""
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from sqlalchemy import Boolean, DateTime, LargeBinary, String, func
from sqlalchemy.dialects.postgresql import UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models import Base


class PiiPatient(Base):
    """``pii.patients``: PII row holding the encrypted ``patient_number``."""

    __tablename__ = "patients"
    __table_args__ = {"schema": "pii"}

    patient_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        primary_key=True,
        server_default=func.gen_random_uuid(),
    )
    patient_number: Mapped[bytes] = mapped_column(
        LargeBinary,
        nullable=False,
        unique=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
    )


class ClinicalPatient(Base):
    """``clinical.patients``: device metadata for the same ``patient_id``."""

    __tablename__ = "patients"
    __table_args__ = {"schema": "clinical"}

    patient_id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        primary_key=True,
    )
    device_model: Mapped[str] = mapped_column(
        String,
        nullable=False,
        server_default="unknown",
    )
    os_version: Mapped[str] = mapped_column(
        String,
        nullable=False,
        server_default="unknown",
    )
    is_active: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        server_default="true",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
    )
