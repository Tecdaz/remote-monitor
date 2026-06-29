"""In-process WebSocket fan-out registry (REQ-WS-02, REQ-WS-03).

The ingest service (``app.services.ingest``) calls
``manager.publish(patient_id, message)`` after a successful commit, and
every open WebSocket subscribed to that patient receives the frame.

Design notes:

- **In-process only.** A future change will swap the in-memory store
  for Redis pub/sub. The public API is intentionally small so the
  swap is local to this module.
- **One ``asyncio.Lock``** serialises all mutations and broadcasts.
  ``publish`` snapshots the subscriber set under the lock and sends
  to each socket OUTSIDE the lock so a slow client cannot block
  registration of new clients for a different patient.
- **Send errors are isolated per socket.** One bad client must not
  break fan-out to the others; the failing socket is dropped from
  the registry and the others still receive the frame.
"""
from __future__ import annotations

import asyncio
import logging
from uuid import UUID

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class ConnectionManager:
    """Tracks open WebSocket connections keyed by ``patient_id``.

    Lifecycle:

    - ``connect`` is called from the WS route AFTER ``websocket.accept()``
      so a frame published immediately after connect can reach the
      new client.
    - ``disconnect`` is idempotent \u2014 calling it twice is a no-op.
    - ``publish`` sends to every open socket for the patient. If no
      socket is registered, it is a silent no-op (the HTTP POST that
      triggered the publish still returns 200; the WS push is a
      courtesy, not a contract).
    """

    def __init__(self) -> None:
        self._connections: dict[UUID, set[WebSocket]] = {}
        self._lock = asyncio.Lock()

    async def connect(self, patient_id: UUID, ws: WebSocket) -> None:
        """Register an open WebSocket for ``patient_id``."""
        async with self._lock:
            self._connections.setdefault(patient_id, set()).add(ws)

    async def disconnect(self, patient_id: UUID, ws: WebSocket) -> None:
        """Unregister a WebSocket. Idempotent.

        A ``set.discard`` is a no-op for missing elements, so calling
        ``disconnect`` twice (or for an unknown patient) is safe.
        """
        async with self._lock:
            subscribers = self._connections.get(patient_id)
            if subscribers is None:
                return
            subscribers.discard(ws)
            if not subscribers:
                # Drop the entry so the dict does not grow unboundedly
                # with one-shot subscribers.
                self._connections.pop(patient_id, None)

    async def publish(self, patient_id: UUID, message: dict) -> None:
        """Send a JSON frame to every open socket for ``patient_id``.

        Send errors on individual sockets are caught and logged. A
        failing socket is removed from the registry; the others still
        receive the frame. No-op if no socket is registered.
        """
        async with self._lock:
            sockets = list(self._connections.get(patient_id, ()))
        if not sockets:
            return
        for ws in sockets:
            try:
                await ws.send_json(message)
            except Exception as exc:  # noqa: BLE001
                logger.warning(
                    "ws publish failed; dropping subscriber",
                    extra={"patient_id": str(patient_id), "error": str(exc)},
                )
                await self.disconnect(patient_id, ws)

    async def count(self, patient_id: UUID) -> int:
        """Number of currently-registered subscribers for ``patient_id``.

        Test-only helper. The production route does not depend on it.
        """
        async with self._lock:
            return len(self._connections.get(patient_id, ()))


# Module-level singleton (PoC). The ingest service imports this name
# and calls ``manager.publish(patient_id, ...)`` after ``session.commit()``.
# Tests that want an isolated manager should instantiate
# ``ConnectionManager()`` directly instead of touching this singleton.
manager = ConnectionManager()
