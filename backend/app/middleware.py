"""HTTP middleware.

REQ-OBS-01 requires every response to carry an ``X-Request-ID`` header
(generated when the client did not provide one) and the same id to
appear in every log line emitted while handling the request. The
``XRequestIDMiddleware`` sets the header and binds the id into structlog
context vars so any ``structlog.get_logger()`` call inside the request
sees it.
"""
from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable

import structlog
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response

REQUEST_ID_HEADER = "X-Request-ID"


class XRequestIDMiddleware(BaseHTTPMiddleware):
    """Stamp every request with a UUID and echo it on the response.

    - If the client sent an ``X-Request-ID``, use that.
    - Otherwise generate a fresh UUID4.
    - Bind the id into structlog contextvars so downstream log calls
      (``log.info("...")``) automatically include ``request_id``.
    """

    async def dispatch(
        self,
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid.uuid4())
        request.state.request_id = request_id

        # structlog.contextvars merges kwargs into the per-task context
        # so any log line emitted during this request (including from
        # background tasks) carries the request_id.
        structlog.contextvars.bind_contextvars(request_id=request_id)
        try:
            response = await call_next(request)
        except Exception as exc:
            # 5xx path: an unhandled exception from a route handler.
            # Log with stack trace at ERROR so the on-call engineer can
            # find it. Re-raise so FastAPI's exception handler turns
            # it into a 500 response.
            structlog.get_logger().error(
                "request failed",
                method=request.method,
                path=request.url.path,
                exc_info=exc,
            )
            structlog.contextvars.unbind_contextvars("request_id")
            raise
        # 4xx vs 2xx/3xx log level policy (REQ-OBS-01).
        status_code = response.status_code
        if status_code >= 500:
            structlog.get_logger().error(
                "request completed",
                method=request.method,
                path=request.url.path,
                status_code=status_code,
            )
        elif status_code >= 400:
            structlog.get_logger().info(
                "request rejected",
                method=request.method,
                path=request.url.path,
                status_code=status_code,
            )
        else:
            structlog.get_logger().info(
                "request completed",
                method=request.method,
                path=request.url.path,
                status_code=status_code,
            )
        # Unbind so the next request on the same worker doesn't
        # inherit a stale request_id.
        structlog.contextvars.unbind_contextvars("request_id")
        response.headers[REQUEST_ID_HEADER] = request_id
        return response
