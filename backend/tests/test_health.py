"""Smoke test for REQ-HEALTH-01.

The health endpoint must return 200 with `{"status": "ok"}` regardless
of database reachability. The DB is intentionally not contacted here.
"""
from __future__ import annotations

from httpx import AsyncClient


async def test_health_returns_200(client: AsyncClient) -> None:
    response = await client.get("/api/v1/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
