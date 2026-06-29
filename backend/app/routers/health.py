"""Health-check router.

Satisfies REQ-HEALTH-01: GET /api/v1/health returns 200 with
`{"status": "ok"}` and does NOT consult the database. The DB-probe
variant (/api/v1/readyz) lands in PR3 (T3.6) alongside the rest of the
HTTP surface.
"""
from __future__ import annotations

from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/api/v1/health")
async def health() -> dict[str, str]:
    """Liveness probe — the process is up and the HTTP layer works."""
    return {"status": "ok"}
