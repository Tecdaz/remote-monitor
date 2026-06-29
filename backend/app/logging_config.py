"""Structured logging configuration (REQ-OBS-01).

Wires structlog with:

- ISO timestamps.
- Log level on every line.
- ``contextvars`` merge so ``request_id`` (bound by
  ``XRequestIDMiddleware``) appears on every line of a request.
- A ``log_level`` filter driven by ``settings.log_level``.

The 4xx-vs-5xx log-level policy (4xx at INFO without stack trace, 5xx
at ERROR with stack trace) is enforced at the call sites
(routers/services) rather than globally — a single ``logging.exception``
or ``log.error(..., exc_info=True)`` is the signal. This keeps the
configuration trivial and the policy explicit at the call site.
"""
from __future__ import annotations

import logging

import structlog

from app.config import settings


def configure_logging() -> None:
    """Configure structlog + stdlib logging for the whole process.

    Idempotent — safe to call from ``app.main`` and from tests.
    """
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            # dev-friendly renderer; replace with JSONRenderer in prod
            structlog.dev.ConsoleRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            logging.getLevelName(settings.log_level)
        ),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )
