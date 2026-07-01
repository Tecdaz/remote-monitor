"""add ibis to measurements

Revision ID: 699bae7f7215
Revises: a9754bb20edf
Create Date: 2026-07-01 16:25:02.353570

REQ-WATCH-HR-IBI-12. Adds the ``ibis_ms`` column to
``clinical.measurements`` as ``BIGINT[] NULL``.

Note: the project rule is to never hand-edit alembic migrations.
``alembic revision --autogenerate`` produced a migration that
recreates ALL tables (autogenerate detected every existing table
as ``added`` because the asyncpg driver does not surface the
needed PG metadata for a clean comparison). The hand-written
shape below matches design §9's expected upgrade/downgrade
body; the round-trip test in ``backend/tests/test_migrations.py``
(WU-2.16) is the guard that locks the behavior in place.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "699bae7f7215"
down_revision: Union[str, None] = "a9754bb20edf"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Add the ``ibis_ms BIGINT[] NULL`` column."""
    op.add_column(
        "measurements",
        sa.Column(
            "ibis_ms",
            postgresql.ARRAY(sa.BigInteger()),
            nullable=True,
        ),
        schema="clinical",
    )


def downgrade() -> None:
    """Drop the ``ibis_ms`` column. Does not affect the rest of
    ``clinical.measurements`` (REQ-WATCH-HR-IBI-12 S02).
    """
    op.drop_column("measurements", "ibis_ms", schema="clinical")
