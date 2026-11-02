"""remove watch-only fields from clinical.measurements

Revision ID: remove_watch_only_fields
Revises: add_ecg_columns
Create Date: 2026-11-02 00:00:00.000000

Band-only contract (S1/S2): the Polar H10 band is the sole
measurement source. Drops the Samsung-watch sensor artifacts
``spo2_percent``, ``ibis_status``, ``hr_status`` and the ``source``
discriminator from ``clinical.measurements``. Rows produced by the
old watch pipeline (``source = 'samsung_watch'``) are deleted first;
band rows carry no source marker.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op

revision: str = "remove_watch_only_fields"
down_revision: Union[str, None] = "add_ecg_columns"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute(
        "DELETE FROM clinical.measurements WHERE source = 'samsung_watch'"
    )
    op.execute("ALTER TABLE clinical.measurements DROP COLUMN spo2_percent")
    op.execute("ALTER TABLE clinical.measurements DROP COLUMN ibis_status")
    op.execute("ALTER TABLE clinical.measurements DROP COLUMN hr_status")
    op.execute("ALTER TABLE clinical.measurements DROP COLUMN source")


def downgrade() -> None:
    # Re-add the four watch-only columns so the pre-band schema
    # round-trips. Deleted ``samsung_watch`` rows are NOT restored —
    # downgrade restores shape, not data.
    op.execute(
        "ALTER TABLE clinical.measurements "
        "ADD COLUMN spo2_percent NUMERIC NULL"
    )
    op.execute(
        "ALTER TABLE clinical.measurements "
        "ADD COLUMN ibis_status INTEGER[] NULL"
    )
    op.execute(
        "ALTER TABLE clinical.measurements ADD COLUMN hr_status INTEGER NULL"
    )
    op.execute(
        "ALTER TABLE clinical.measurements ADD COLUMN source VARCHAR(16) "
        "NOT NULL DEFAULT 'polar_h10'"
    )
