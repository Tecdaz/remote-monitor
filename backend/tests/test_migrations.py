"""Alembic round-trip guard for the measurement migrations (band-only).

Mirrors the docker-aware xfail pattern from test_schema_introspection.py.
Head is ``remove_watch_only_fields``: the watch-only sensor columns
are gone and only the Polar H10 band contract remains.
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

    Head is currently ``remove_watch_only_fields`` (drops the
    watch-only measurement columns); this guard also covers any
    migrations that came before it because the round-trip starts
    from base.
    """
    _alembic("upgrade", "head")
    _alembic("downgrade", "-1")
    _alembic("upgrade", "head")


async def test_migrations_head_has_no_watch_only_columns(
    round_trip_db: None,
) -> None:
    """S2: at head the four watch-only columns are gone from the table.

    Upgrades to head, then asserts ``clinical.measurements`` has no
    watch-only column while the band columns remain.
    """
    import os

    import asyncpg

    _alembic("upgrade", "head")
    dsn = os.environ.get(
        "APP_DATABASE_URL",
        "postgresql+asyncpg://postgres:postgres@localhost:5432/remote_monitor",
    ).replace("postgresql+asyncpg", "postgresql")

    conn = await asyncpg.connect(dsn)
    try:
        cols = await conn.fetch(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = 'clinical' AND table_name = 'measurements'"
        )
        names = {r["column_name"] for r in cols}
    finally:
        await conn.close()

    for removed in ("spo2_percent", "ibis_status", "hr_status", "source"):
        assert removed not in names, f"watch-only column {removed} still at head"
    for kept in (
        "id",
        "patient_id",
        "local_id",
        "timestamp",
        "received_at",
        "heart_rate_bpm",
        "ibis_ms",
        "ecg_uv",
        "ecg_sample_rate_hz",
    ):
        assert kept in names, f"band column {kept} missing at head"


async def test_migrations_ecg_columns_round_trip(round_trip_db: None) -> None:
    """polar-ecg-integration: the new ``ecg_uv`` / ``ecg_sample_rate_hz``
    columns are added on upgrade and removed on downgrade.
    """
    import os

    import asyncpg

    _alembic("upgrade", "head")
    dsn = os.environ.get(
        "APP_DATABASE_URL",
        "postgresql+asyncpg://postgres:postgres@localhost:5432/remote_monitor",
    ).replace("postgresql+asyncpg", "postgresql")

    pid = "00000000-0000-0000-0000-000000000020"
    conn = await asyncpg.connect(dsn)
    try:
        await conn.execute(
            "INSERT INTO clinical.patients (patient_id, is_active, bed_number) "
            "VALUES ($1, true, 1)",
            pid,
        )
        await conn.execute(
            "INSERT INTO clinical.measurements "
            "(id, patient_id, local_id, timestamp, received_at, "
            "ecg_uv, ecg_sample_rate_hz) "
            "VALUES ('00000000-0000-0000-0000-000000000022', $1, "
            "'00000000-0000-0000-0000-000000000021', now(), now(), $2, 130)",
            pid,
            [100, 200, 300],
        )
        row = await conn.fetchrow(
            "SELECT ecg_uv, ecg_sample_rate_hz FROM clinical.measurements "
            "WHERE patient_id = $1",
            pid,
        )
        assert row is not None
        assert list(row["ecg_uv"]) == [100, 200, 300]
        assert row["ecg_sample_rate_hz"] == 130
    finally:
        await conn.close()

    _alembic("downgrade", "add_ecg_columns-1")
    conn2 = await asyncpg.connect(dsn)
    try:
        cols = await conn2.fetch(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = 'clinical' AND table_name = 'measurements' "
            "AND column_name IN ('ecg_uv', 'ecg_sample_rate_hz')"
        )
        assert cols == []
    finally:
        await conn2.close()
