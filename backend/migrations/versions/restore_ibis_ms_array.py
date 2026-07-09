"""restore ibis_ms ARRAY (revert scalar deconstruction)

Revision ID: restore_ibis_array
Revises: deconstruct_ibis
Create Date: 2026-07-09 18:00:00.000000

Reverts the scalar ibi_ms column back to the original ibis_ms BIGINT[]
array. The watch delivers the raw Samsung IBI_LIST without any
transformation.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "restore_ibis_array"
down_revision: Union[str, None] = "deconstruct_ibis"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.drop_column("measurements", "ibi_ms", schema="clinical")
    op.add_column(
        "measurements",
        sa.Column("ibis_ms", postgresql.ARRAY(sa.BigInteger()), nullable=True),
        schema="clinical",
    )


def downgrade() -> None:
    op.drop_column("measurements", "ibis_ms", schema="clinical")
    op.add_column(
        "measurements",
        sa.Column("ibi_ms", sa.BigInteger(), nullable=True),
        schema="clinical",
    )
