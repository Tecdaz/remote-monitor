"""add last_measurement_at to clinical.patients

Revision ID: ca6ca1c8fa96
Revises: a7c3f21b8e94
Create Date: 2026-07-09 12:00:00.000000

Per sdd/feat-patient-inactivity-sweep/design:

- Add ``last_measurement_at TIMESTAMPTZ NOT NULL DEFAULT now()``.
- The DDL default populates existing rows at migration time; no
  explicit backfill is required.
- The sweep only queries ``is_active = true`` rows, so inactive rows
  receiving ``now()`` is acceptable.
- Downgrade drops the column.

Hand-written following the same convention as the prior migration
(``a7c3f21b8e94_add_bed_number_to_clinical_patients``). The round-trip
is guarded by ``backend/tests/test_migrations.py``.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op

revision: str = "ca6ca1c8fa96"
down_revision: Union[str, None] = "a7c3f21b8e94"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute(
        "ALTER TABLE clinical.patients "
        "ADD COLUMN last_measurement_at TIMESTAMPTZ NOT NULL DEFAULT now()"
    )


def downgrade() -> None:
    op.execute(
        "ALTER TABLE clinical.patients DROP COLUMN IF EXISTS last_measurement_at"
    )
