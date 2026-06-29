"""Shared pytest fixtures for the backend test suite.

PR1 only needs an httpx AsyncClient bound to the FastAPI app via the
ASGI transport so the smoke test can hit the health endpoint without
spinning up docker-compose. REQ-HEALTH-01 is explicitly DB-independent.
"""
from __future__ import annotations

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app


@pytest.fixture
async def client() -> AsyncClient:
    """An httpx AsyncClient wired directly to the in-process FastAPI app."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
