"""Alembic round-trip guard for the measurement migrations.

Mirrors the docker-aware xfail pattern from test_schema_introspection.py.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]


def _pg_container_running() -> bool:
    if shutil.which("docker") is None:
        return False
    r = subprocess.run(
        ["docker", "compose", "ps", "--services", "--filter", "status=running"],
        check=False, capture_output=True, text=True, cwd=REPO_ROOT, timeout=10,
    )
    return "postgres" in (r.stdout or "").split()


def _alembic(*args: str) -> subprocess.CompletedProcess[str]:
    r = subprocess.run(
        ["uv", "run", "--directory", "backend", "alembic", *args],
        check=False, capture_output=True, text=True, cwd=REPO_ROOT, timeout=120,
    )
    if r.returncode != 0:
        raise AssertionError(
            f"alembic {' '.join(args)} failed (rc={r.returncode}).\n"
            f"stdout:\n{r.stdout}\nstderr:\n{r.stderr}"
        )
    return r


@pytest.fixture
def round_trip_db() -> None:
    if not _pg_container_running():
        pytest.xfail("postgres container not running; docker compose not available")
    _alembic("downgrade", "base")
    yield
    # Restore head so other test modules see the current schema.
    _alembic("upgrade", "head")


def test_migrations_round_trip(round_trip_db: None) -> None:
    """Apply, revert, and re-apply the current head migration.

    Head is currently ``add_ibis_status_column`` (adds ibis_status to
    clinical.measurements); this guard also covers any migrations that
    came before it because the round-trip starts from base.
    """
    _alembic("upgrade", "head")
    _alembic("downgrade", "-1")
    _alembic("upgrade", "head")


async def test_migrations_ibis_status_column_round_trip(round_trip_db: None) -> None:
    """REQ-NOISE-BE-01: the new ibis_status column is added and round-trips.

    After upgrading to head we insert and read an integer array to prove
    the column exists and stores data; after downgrading one step the
    column is gone.
    """
    import os

    import asyncpg

    _alembic("upgrade", "head")
    dsn = os.environ.get(
        "APP_DATABASE_URL",
        "postgresql+asyncpg://postgres:postgres@localhost:5432/remote_monitor",
    ).replace("postgresql+asyncpg", "postgresql")

    pid = "00000000-0000-0000-0000-000000000001"
    conn = await asyncpg.connect(dsn)
    try:
        # Insert a patient with a required bed_number so the active CHECK
        # constraint is satisfied, then insert a measurement with ibis_status.
        await conn.execute(
            "INSERT INTO clinical.patients (patient_id, is_active, bed_number) "
            "VALUES ($1, true, 1)",
            pid,
        )
        await conn.execute(
            "INSERT INTO clinical.measurements "
            "(id, patient_id, local_id, timestamp, received_at, ibis_status) "
            "VALUES ('00000000-0000-0000-0000-000000000003', $1, "
            "'00000000-0000-0000-0000-000000000002', now(), now(), $2)",
            pid,
            [1, 0, 1],
        )
        row = await conn.fetchrow(
            "SELECT ibis_status FROM clinical.measurements WHERE patient_id = $1",
            pid,
        )
        assert row is not None
        assert list(row["ibis_status"]) == [1, 0, 1]
    finally:
        await conn.close()

    _alembic("downgrade", "-1")
    conn2 = await asyncpg.connect(dsn)
    try:
        cols = await conn2.fetch(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = 'clinical' AND table_name = 'measurements' "
            "AND column_name = 'ibis_status'"
        )
        assert cols == []
    finally:
        await conn2.close()
