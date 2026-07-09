"""FastAPI application entry point.

Wires:

- ``app.config.settings`` (env-driven via pydantic-settings).
- Structured logging via ``app.logging_config.configure_logging()``.
- ``app.middleware.XRequestIDMiddleware`` for the X-Request-ID header
  (REQ-OBS-01).
- The four HTTP routers shipped by PR3:

  - ``routers.health``  \u2014 ``GET /api/v1/health`` (REQ-HEALTH-01).
  - ``routers.measurements``  \u2014 uploadMeasurements + listMeasurements
    (REQ-INGEST-01..05, REQ-READ-01/03).
  - ``routers.patients``  \u2014 registerPatient + listPatients +
    getPatient + deactivatePatient-reserved (REQ-READ-02).
  - ``routers.readyz``  \u2014 ``GET /api/v1/readyz`` (REQ-HEALTH-02).
- The WebSocket router shipped by PR4:

  - ``ws.routes``  \u2014 ``/ws/patients/{patient_id}``
    (REQ-WS-01..05).
"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.logging_config import configure_logging
from app.middleware import XRequestIDMiddleware
from app.routers import health, measurements, patients, readyz
from app.services.inactivity import start_sweep_task
from app.ws.routes import router as ws_router

# Configure logging first so any startup log line uses the new policy.
configure_logging()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start background tasks on startup and clean them up on shutdown."""
    sweep_task = start_sweep_task(settings.patient_inactivity_threshold_s)
    try:
        yield
    finally:
        sweep_task.cancel()
        try:
            await sweep_task
        except asyncio.CancelledError:
            pass


app = FastAPI(
    title="remote-monitor-backend",
    version="0.1.0",
    lifespan=lifespan,
    description=(
        "FastAPI ingest service + WebSocket broadcast hub for the "
        "remote-monitor PoC. PR3 ships the HTTP surface; PR4 adds the "
        "in-process WebSocket fan-out (REQ-WS-01..05)."
    ),
)

# X-Request-ID is the outermost middleware so every request (and the
# log line emitted by it) carries a request_id from the moment the
# request enters the app.
app.add_middleware(XRequestIDMiddleware)

# CORS — allow the frontend (dev server and Docker) to call the API.
# PoC posture: open to localhost origins only. Tighten in production.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:3000",
        "http://localhost:5173",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Routers
app.include_router(health.router)
app.include_router(measurements.router)
app.include_router(patients.router)
app.include_router(readyz.router)
app.include_router(ws_router)
