"""Async SQLAlchemy engine + session factory.

PR1 only wires up the engine, session factory, and FastAPI dependency.
The actual ORM models and the 0001_initial migration land in PR2.
"""
from __future__ import annotations

from collections.abc import AsyncIterator

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.config import settings

# `pool_pre_ping` survives a Postgres restart without leaking broken
# connections into a request. `echo` is off in production; flip it on
# via APP_DATABASE_URL appending `?echo=true` (or by editing this line)
# when debugging SQL.
engine: AsyncEngine = create_async_engine(
    settings.database_url,
    pool_pre_ping=True,
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
