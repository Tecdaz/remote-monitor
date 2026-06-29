"""FastAPI application entry point.

PR1 (scaffold): the application object plus the health router, a
minimal structlog configuration, and a tiny X-Request-ID middleware
stub. The full observability stack (request-scoped logger, 4xx/5xx
log-level policy) lands in PR3 (T3.3).
"""
from __future__ import annotations

import logging
import uuid
from collections.abc import Awaitable, Callable

import structlog
from fastapi import FastAPI
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

from app.config import settings
from app.routers import health

app = FastAPI(
    title="remote-monitor-backend",
    version="0.1.0",
    description=(
        "FastAPI ingest service + WebSocket broadcast hub for the "
        "remote-monitor PoC. PR1 ships only the application skeleton, "
        "the /api/v1/health endpoint, and the observability stubs."
    ),
)

# --- Structured logging (minimal) -----------------------------------------
# PR3 will replace this with the full logging_config module that wires
# request-scoped loggers and the 4xx/5xx log-level policy (REQ-OBS-01).
structlog.configure(
    processors=[
        structlog.processors.add_log_level,
        structlog.processors.TimeStamper(fmt="iso"),
    ],
    wrapper_class=structlog.make_filtering_bound_logger(
        logging.getLevelName(settings.log_level)
    ),
    logger_factory=structlog.PrintLoggerFactory(),
)


# --- X-Request-ID middleware (minimal stub) -------------------------------
# PR3 will replace this with the full middleware (T3.3) that also
# carries the request id into the structlog context.
class XRequestIDMiddleware(BaseHTTPMiddleware):
    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        request_id = str(uuid.uuid4())
        request.state.request_id = request_id
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        return response


app.add_middleware(XRequestIDMiddleware)
app.include_router(health.router)
