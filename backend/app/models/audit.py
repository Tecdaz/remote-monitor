"""``audit.audit_log``: one row per successful mutation (REQ-SCHEMA-04).

``context`` is a free-form JSONB blob that lets the ingest service
store ``{patient_id, local_ids, request_id}`` without changing the
table shape.
"""
from __future__ import annotations

from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import DateTime, Integer, String
from sqlalchemy.dialects.postgresql import JSONB, UUID as PgUUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models import Base


class AuditLog(Base):
    """``audit.audit_log``: append-only mutation trail."""

    __tablename__ = "audit_log"
    __table_args__ = {"schema": "audit"}

    id: Mapped[UUID] = mapped_column(
        PgUUID(as_uuid=True),
        primary_key=True,
    )
    actor: Mapped[str] = mapped_column(String, nullable=False)
    action: Mapped[str] = mapped_column(String, nullable=False)
    count: Mapped[int] = mapped_column(Integer, nullable=False)
    ts: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=None,  # server default is added by the migration
    )
    context: Mapped[dict[str, Any] | None] = mapped_column(JSONB, nullable=True)
