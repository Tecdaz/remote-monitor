"""Shared pytest fixtures for the backend test suite.

The PR1 client fixture is preserved (in-process httpx). PR3 adds:

- ``APP_PII_ENCRYPTION_KEY`` env var so the pgcrypto-backed
  ``app.services.crypto`` helpers have a key to use.
- ``clean_tables`` autouse fixture: TRUNCATEs the 4 tables between
  tests so each test starts from a known-empty state without paying
  the cost of a full alembic round-trip.
- ``session`` fixture: an ``AsyncSession`` for tests that bypass the
  HTTP layer and exercise services directly.
"""
from __future__ import annotations

# IMPORTANT: this must run before any ``app.*`` import below, because
# ``app.config.settings`` is a module-level singleton that reads
# ``APP_PII_ENCRYPTION_KEY`` on first instantiation. Set it here so
# tests that need pgcrypto have a key.
import os

os.environ.setdefault(
    "APP_PII_ENCRYPTION_KEY",
    "test-key-not-for-production-32+chars",
)

import pytest
import structlog
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import AsyncSessionLocal
from app.main import app


# Replace the ``PrintLoggerFactory`` configured by ``app.main`` with the
# stdlib integration so log calls that pass ``method=`` / ``path=`` /
# ``status_code=`` actually go through. ``PrintLogger`` is the dev
# default but its ``msg()`` does not accept keyword arguments, which
# breaks the middleware's structured log calls in tests. stdlib
# accepts kwargs as the log record's ``extra`` dict, which is what
# ``structlog.testing.capture_logs()`` expects.
import logging as _logging
structlog.configure(
    processors=[
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
    ],
    wrapper_class=structlog.make_filtering_bound_logger(_logging.INFO),
    logger_factory=structlog.stdlib.LoggerFactory(),
)


@pytest.fixture
async def client() -> AsyncClient:
    """An httpx AsyncClient wired directly to the in-process FastAPI app."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest.fixture
async def session() -> AsyncSession:
    """A bare ``AsyncSession`` for service-level tests (no HTTP)."""
    async with AsyncSessionLocal() as s:
        yield s


@pytest.fixture(autouse=True)
async def _clean_tables() -> None:
    """Wipe the 4 PII/clinical/audit tables before each test.

    Order matters: ``measurements`` FK -> ``clinical.patients`` ->
    ``pii.patients``; ``audit_log`` is independent. ``CASCADE`` makes
    the order forgiving but explicit ordering documents the
    dependencies.

    Also disposes the engine at the END of the test. pytest-asyncio
    gives each test a fresh event loop; without ``dispose()`` the
    asyncpg pool keeps connections from the previous loop and the
    next test sees ``RuntimeError: Event loop is closed``.
    """
    from app.db import engine

    try:
        async with AsyncSessionLocal() as s:
            async with s.begin():
                await s.execute(
                    text(
                        "TRUNCATE TABLE clinical.measurements, audit.audit_log, "
                        "clinical.patients, pii.patients RESTART IDENTITY CASCADE"
                    )
                )
        yield
    finally:
        await engine.dispose()
