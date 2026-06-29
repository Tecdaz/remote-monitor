"""FastAPI application entry point.

PR1 (scaffold): bare-minimum app object so `uv run uvicorn app.main:app`
works. Routers, middleware, and structured logging land in later PRs
(PR3 for ingest/read, PR4 for WebSocket fan-out).
"""
from __future__ import annotations

from fastapi import FastAPI

app = FastAPI(
    title="remote-monitor-backend",
    version="0.1.0",
    description=(
        "FastAPI ingest service + WebSocket broadcast hub for the "
        "remote-monitor PoC. PR1 ships only the application skeleton."
    ),
)
