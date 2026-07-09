"""Alembic round-trip guard for ``add_ibis_to_measurements`` (REQ-WATCH-HR-IBI-12).

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


def _alembic(*args: str) -> None:
    r = subprocess.run(
        ["uv", "run", "--directory", "backend", "alembic", *args],
        check=False, capture_output=True, text=True, cwd=REPO_ROOT, timeout=120,
    )
    if r.returncode != 0:
        raise AssertionError(
            f"alembic {' '.join(args)} failed (rc={r.returncode}).\n"
            f"stdout:\n{r.stdout}\nstderr:\n{r.stderr}"
        )


@pytest.fixture
def round_trip_db() -> None:
    if not _pg_container_running():
        pytest.xfail("postgres container not running; docker compose not available")
    _alembic("downgrade", "base")
    yield


def test_migrations_round_trip(round_trip_db: None) -> None:
    """Apply, revert, and re-apply the current head migration.

    Head is currently ``ca6ca1c8fa96`` (add last_measurement_at to
    clinical.patients); this guard also covers any migrations that
    came before it because the round-trip starts from base.
    """
    _alembic("upgrade", "head")
    _alembic("downgrade", "-1")
    _alembic("upgrade", "head")
