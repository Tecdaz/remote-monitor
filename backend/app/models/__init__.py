"""SQLAlchemy 2.0 async ORM models for the 3 PG schemas / 4 tables.

The ``Base`` is shared between models and Alembic; ``env.py`` imports
it so ``alembic revision --autogenerate`` would work in a follow-up.
This module is data-only: no DB session, no service code.
"""
from __future__ import annotations

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Shared declarative base for every ORM model in the service."""


# Importing the modules here makes `Base.metadata` see every model and
# keeps the public package surface explicit.
from app.models.audit import AuditLog  # noqa: E402,F401
from app.models.measurement import ClinicalMeasurement  # noqa: E402,F401
from app.models.patient import ClinicalPatient, PiiPatient  # noqa: E402,F401

__all__ = ["AuditLog", "Base", "ClinicalMeasurement", "ClinicalPatient", "PiiPatient"]
