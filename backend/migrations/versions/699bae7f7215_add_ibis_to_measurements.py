"""add ibis to measurements

Revision ID: 699bae7f7215
Revises: a9754bb20edf
Create Date: 2026-07-01 16:25:02.353570

REQ-WATCH-HR-IBI-12. Adds ``ibis_ms`` (``BIGINT[]`` nullable) to
``clinical.measurements``. Hand-written because autogenerate produced
a broken migration (recreates all 4 tables — asyncpg does not
expose the metadata alembic needs); the round-trip test
(backend/tests/test_migrations.py) is the guard.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "699bae7f7215"
down_revision: Union[str, None] = "a9754bb20edf"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "measurements",
        sa.Column("ibis_ms", postgresql.ARRAY(sa.BigInteger()), nullable=True),
        schema="clinical",
    )


def downgrade() -> None:
    op.drop_column("measurements", "ibis_ms", schema="clinical")
