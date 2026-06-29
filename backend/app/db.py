"""Async SQLAlchemy engine + session factory.

PR1 only wires up the engine, session factory, and FastAPI dependency.
The actual ORM models and the 0001_initial migration land in PR2.

Pool choice rationale (PR4): ``NullPool`` is used instead of the
default ``AsyncAdaptedQueuePool`` so each ``AsyncSession`` opens a
fresh asyncpg connection in the CURRENT event loop and closes it
when the session ends. With the default queue pool, connections are
reused across event loops, which breaks under pytest-asyncio (each
test gets a fresh loop) AND the ``TestClient`` (its portal runs in
yet another loop). ``NullPool`` trades a small per-request
connection cost for a robust, loop-agnostic engine that the WS
end-to-end tests can drive without ``RuntimeError: ... attached to
a different loop``. In production the load is low (PoC) and the
trade is acceptable; revisit when we add a connection pooler (e.g.
PgBouncer) in front of Postgres.
"""
from __future__ import annotations

from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.pool import NullPool

from app.config import settings

# `pool_pre_ping` survives a Postgres restart without leaking broken
# connections into a request. `NullPool` (see module docstring) is
# loop-agnostic; `echo` is off in production — flip it on via
# APP_DATABASE_URL appending `?echo=true` (or by editing this line)
# when debugging SQL.
engine: AsyncEngine = create_async_engine(
    settings.database_url,
    pool_pre_ping=True,
    poolclass=NullPool,
)

AsyncSessionLocal: async_sessionmaker[AsyncSession] = async_sessionmaker(
    engine,
    expire_on_commit=False,
    class_=AsyncSession,
)


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI dependency that yields one session per request."""
    async with AsyncSessionLocal() as session:
        yield session
