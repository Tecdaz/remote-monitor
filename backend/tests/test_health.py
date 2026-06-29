"""Smoke tests for REQ-HEALTH-01 and REQ-HEALTH-02.

- ``/api/v1/health`` returns 200 with ``{"status": "ok"}`` regardless
  of database reachability.
- ``/api/v1/readyz`` returns 200 with ``{"status": "ready"}`` when a
  trivial ``SELECT 1`` succeeds.
"""
from __future__ import annotations

from httpx import AsyncClient


async def test_health_returns_200(client: AsyncClient) -> None:
    response = await client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


async def test_readyz_returns_200_when_db_reachable(
    client: AsyncClient,
) -> None:
    """REQ-HEALTH-02: 200 + ``{"status": "ready"}`` when DB is up."""
    response = await client.get("/api/v1/readyz")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}
