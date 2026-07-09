"""deconstruct ibis_ms ARRAY into scalar ibi_ms

Revision ID: deconstruct_ibis
Revises: ca6ca1c8fa96
Create Date: 2026-07-09 17:10:00.000000

Each measurement row now carries a single inter-beat interval (BIGINT)
instead of a BIGINT[] array. The watch deconstructs the sensor's IBI
array into individual rows at capture time.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = "deconstruct_ibis"
down_revision: Union[str, None] = "ca6ca1c8fa96"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.drop_column("measurements", "ibis_ms", schema="clinical")
    op.add_column(
        "measurements",
        sa.Column("ibi_ms", sa.BigInteger(), nullable=True),
        schema="clinical",
    )


def downgrade() -> None:
    op.drop_column("measurements", "ibi_ms", schema="clinical")
    op.add_column(
        "measurements",
        sa.Column(
            "ibis_ms",
            sa.ARRAY(sa.BigInteger()),
            nullable=True,
        ),
        schema="clinical",
    )
