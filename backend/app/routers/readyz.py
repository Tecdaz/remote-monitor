"""Readiness check router (REQ-HEALTH-02).

``GET /api/v1/readyz`` returns 200 with ``{"status": "ready"}`` if a
trivial ``SELECT 1`` against PostgreSQL succeeds; 503 with a Problem
body if the DB is unreachable. Used for Kubernetes readiness probes
and for docker-compose ``depends_on: condition: service_healthy``.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session

router = APIRouter(tags=["health"])


@router.get(
    "/api/v1/readyz",
    responses={503: {"description": "Service is not ready (DB unreachable)"}},
)
async def readyz(session: AsyncSession = Depends(get_session)) -> dict[str, str]:
    """Trivial DB probe. 200 on success, 503 on connection failure."""
    try:
        await session.execute(text("SELECT 1"))
    except Exception as exc:
        # Any DB-layer error (connection refused, timeout, auth, etc.)
        # is treated as "not ready". The exception detail is NOT
        # surfaced to the client (it would leak infrastructure
        # details); the server log carries the stack trace.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "detail": "Database unreachable",
                "code": "db_unreachable",
            },
        ) from exc
    return {"status": "ready"}
