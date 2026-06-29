"""WebSocket surface (REQ-WS-01..05).

PR4 introduces the in-process pub/sub used by the ingest service to
push measurement events to subscribed clients:

- ``app.ws.manager`` \u2014 the ``ConnectionManager`` registry
  (REQ-WS-02, REQ-WS-03).
- ``app.ws.routes``  \u2014 the FastAPI WebSocket route
  (REQ-WS-01, REQ-WS-04, REQ-WS-05).

The implementation is in-process only (a future change will swap the
in-memory store for Redis pub/sub so the broadcast survives multi-
instance deployments). The interface (``manager.connect``,
``manager.publish``) stays the same.
"""
