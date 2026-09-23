"""add hr_status column to clinical.measurements

Revision ID: add_hr_status_column
Revises: add_ibis_status_column
Create Date: 2026-09-23 00:00:00.000000

Adds a nullable ``hr_status`` integer column to ``clinical.measurements``
that carries the Samsung ``HEART_RATE_STATUS`` per-reading lifecycle code
(feat-watch-hr-status-surface). Documented values live in the Samsung
API Reference at ``ValueKey.HeartRateSet.html``:

    1  = successful HR measurement
    0  = initial state, OR a higher-priority sensor (e.g. BIA) operating
   -2  = wearable movement detected
   -3  = wearable detached (off-wrist)
   -8  = PPG signal weak / user moved
   -10 = PPG signal too weak / too much motion
   -999 = a higher-priority sensor (e.g. BIA) operating

NULL when the SDK did not provide a status for the batch. The Pydantic
validator on the ingest layer enforces the documented set; the column
itself is a plain nullable integer (no enum, no range constraint at the
DB layer — keep it simple and let the application layer own the contract).
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "add_hr_status_column"
down_revision: Union[str, None] = "add_ibis_status_column"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "measurements",
        sa.Column("hr_status", sa.Integer(), nullable=True),
        schema="clinical",
    )


def downgrade() -> None:
    op.drop_column("measurements", "hr_status", schema="clinical")
