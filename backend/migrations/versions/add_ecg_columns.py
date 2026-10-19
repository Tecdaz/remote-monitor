"""add ecg columns to clinical.measurements

Revision ID: add_ecg_columns
Revises: add_source_column
Create Date: 2026-10-18 00:00:00.000000

Adds nullable ``ecg_uv`` integer array (µV @ ``ecg_sample_rate_hz``)
and ``ecg_sample_rate_hz`` integer to ``clinical.measurements``
(polar-ecg-integration T3): Polar H10 ECG validation strips —
10 s @ 130 Hz = 1300 samples. NULL on every non-ECG row; no
backfill needed. The Pydantic layer owns the value set (rate must
be 130, strip length 1..1300, both-or-neither).
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "add_ecg_columns"
down_revision: Union[str, None] = "add_source_column"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "measurements",
        sa.Column("ecg_uv", postgresql.ARRAY(sa.Integer()), nullable=True),
        schema="clinical",
    )
    op.add_column(
        "measurements",
        sa.Column("ecg_sample_rate_hz", sa.Integer(), nullable=True),
        schema="clinical",
    )


def downgrade() -> None:
    op.drop_column("measurements", "ecg_sample_rate_hz", schema="clinical")
    op.drop_column("measurements", "ecg_uv", schema="clinical")
