"""add source column to clinical.measurements

Revision ID: add_source_column
Revises: add_hr_status_column
Create Date: 2026-10-18 00:00:00.000000

Adds a non-null ``source`` discriminator to ``clinical.measurements``
(polar-h10-validation T3): ``samsung_watch`` = Samsung Health Sensor SDK
pipeline, ``polar_h10`` = Polar H10 chest strap via BLE GATT. The server
default backfills pre-T3 rows as Samsung. The Pydantic validator owns
the closed value set; the column is a plain short string.
"""
from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "add_source_column"
down_revision: Union[str, None] = "add_hr_status_column"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "measurements",
        sa.Column(
            "source",
            sa.String(16),
            nullable=False,
            server_default="samsung_watch",
        ),
        schema="clinical",
    )


def downgrade() -> None:
    op.drop_column("measurements", "source", schema="clinical")
