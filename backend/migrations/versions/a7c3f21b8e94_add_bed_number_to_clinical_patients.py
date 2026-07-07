"""add bed_number to clinical.patients

Revision ID: a7c3f21b8e94
Revises: 699bae7f7215
Create Date: 2026-07-07 21:30:00.000000

Per sdd/wear-bed-picker-onboarding/design (D3, D11, D20) and §11.5 of
``sdd/wear-bed-picker-onboarding/design-files`` (pass-4 appendix):

- Add ``bed_number SMALLINT`` (NOT NULL intentionally DEFERRED — legacy
  rows cannot be backfilled deterministically; the migration is
  documented as a follow-up during sdd-archive per the §12.1 carry-over).
- Partial UNIQUE index ``ux_clinical_patients_active_bed`` enforces
  "at most one active session per bed" (D11). NULLs are excluded from
  partial indexes, so the CHECK constraint below is required to keep
  legacy NULL rows from being activated by mistake.
- CHECK constraint ``ck_bed_number_required_when_active`` (D20) is
  belt-and-suspenders: any row with ``is_active = true`` MUST have a
  non-NULL ``bed_number``.
- Drop the vestigial ``pii.patients_patient_number_key`` UNIQUE index
  (D3) — ``pgp_sym_encrypt`` is non-deterministic, so the index never
  actually prevented duplicate plaintexts and the duplicate check is
  now done in the router via decrypt-and-compare on ``bed_number``.

Hand-written following the same convention as the prior migration
(``699bae7f7215_add_ibis_to_measurements``). The round-trip is guarded
by ``backend/tests/test_migrations.py``.
"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op

revision: str = "a7c3f21b8e94"
down_revision: Union[str, None] = "699bae7f7215"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # NOT NULL is intentionally DEFERRED — legacy rows cannot be
    # backfilled deterministically (the operator's original P-XXXXX
    # mapping is non-recoverable without manual reconciliation).
    op.execute("ALTER TABLE clinical.patients ADD COLUMN bed_number SMALLINT")
    # Legacy rows have bed_number=NULL. The CHECK constraint below
    # requires is_active=false on any NULL-bed_number row, so flip
    # those rows to inactive BEFORE adding the constraint. This
    # preserves the data (rows remain queryable for audit) without
    # losing it. NOT NULL follow-up is a tracked debt item per §12.1.
    op.execute(
        "UPDATE clinical.patients SET is_active = false WHERE bed_number IS NULL"
    )
    op.execute(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS ux_clinical_patients_active_bed
          ON clinical.patients (bed_number)
          WHERE is_active = true
        """
    )
    # D20: belt-and-suspenders CHECK constraint — keeps legacy NULL
    # rows from accidentally being activated.
    op.execute(
        """
        ALTER TABLE clinical.patients
          ADD CONSTRAINT ck_bed_number_required_when_active
          CHECK (bed_number IS NOT NULL OR is_active = false)
        """
    )
    # D3: vestigial pii.patients.patient_number UNIQUE (dead under
    # non-deterministic pgp_sym_encrypt). Drop the CONSTRAINT, not the
    # backing index, because PostgreSQL rejects `DROP INDEX` when a
    # UNIQUE constraint still depends on it.
    op.execute(
        "ALTER TABLE pii.patients DROP CONSTRAINT IF EXISTS patients_patient_number_key"
    )


def downgrade() -> None:
    op.execute(
        "ALTER TABLE clinical.patients DROP CONSTRAINT IF EXISTS ck_bed_number_required_when_active"
    )
    op.execute("DROP INDEX IF EXISTS ux_clinical_patients_active_bed")
    op.execute("ALTER TABLE clinical.patients DROP COLUMN IF EXISTS bed_number")
    # pii.patients_patient_number_key is NOT recreated on downgrade (D3).
