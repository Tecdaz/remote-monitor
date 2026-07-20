"""add ibis_status column to clinical.measurements

Revision ID: add_ibis_status_column
Revises: restore_ibis_array
Create Date: 2026-07-15 00:00:00.000000

Adds a nullable `ibis_status` integer array to `clinical.measurements`.
The array carries per-beat quality flags from the Samsung sensor:
0 = normal/valid beat (accept), -1 = error/invalid beat (reject).
Mirrors the Samsung Health IBI_STATUS_LIST convention. When present its
length must match `ibis_ms`.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "add_ibis_status_column"
down_revision: Union[str, None] = "restore_ibis_array"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "measurements",
        sa.Column("ibis_status", postgresql.ARRAY(sa.Integer()), nullable=True),
        schema="clinical",
    )


def downgrade() -> None:
    op.drop_column("measurements", "ibis_status", schema="clinical")
