"""WebSocket route ``/ws/patients/{patient_id}`` (REQ-WS-01, REQ-WS-04, REQ-WS-05).

Implements the four behaviors of the per-patient server-push channel:

1. **WsSubscribed on connect (REQ-WS-01)**: immediately after
   ``websocket.accept()``, the server sends
   ``{"type": "subscribed", "patient_id": "<uuid>"}``. The
   ``ConnectionManager`` registration happens BEFORE the
   ``subscribed`` frame so a measurement published immediately
   after connect is not lost.
2. **Heartbeat 30s/60s (REQ-WS-04)**: a background task sends a
   ``{type: "ping", ts: ...}`` every ``settings.ws_ping_timeout_s``
   seconds. If the client does not produce any frame within
   ``settings.ws_pong_grace_s`` additional seconds (i.e. the total
   silent window is ``ws_ping_timeout_s + ws_pong_grace_s``), the
   server closes the connection with code 4401.
3. **Close 4403 on mismatched patient_id (REQ-WS-05)**: any client
   frame whose ``patient_id`` field differs from the URL path
   triggers a close with code 4403. ``{type: "ping"}`` (no
   ``patient_id`` field) is exempt.
4. **Client ping -> server pong**: a ``{type: "ping"}`` from the
   client is answered with ``{type: "pong", ts: <server_ts>}``
   immediately.

The 60s window is configurable via ``APP_WS_PING_TIMEOUT_S`` and
``APP_WS_PONG_GRACE_S`` env vars so tests can shrink the window to
sub-second and finish the suite in <1s.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from uuid import UUID

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

from app.ws.manager import manager

logger = logging.getLogger(__name__)

router = APIRouter(tags=["ws"])

# Close codes used by this route. 4401/4403 are in the
# application-private range (4xxx) so they cannot collide with
# IANA-registered codes.
CLOSE_CODE_HEARTBEAT_TIMEOUT = 4401
CLOSE_CODE_MISMATCHED_PATIENT = 4403


def _ping_timeout_s() -> float:
    """Return the current ping interval, honoring test env overrides."""
    return float(
        os.environ.get(
            "APP_WS_PING_TIMEOUT_S", str(_DEFAULT_PING_TIMEOUT_S)
        )
    )


def _pong_grace_s() -> float:
    """Return the current pong grace period, honoring test env overrides."""
    return float(
        os.environ.get("APP_WS_PONG_GRACE_S", str(_DEFAULT_PONG_GRACE_S))
    )


# Read defaults from ``Settings`` at import time. The route then
# re-reads the env var on every call (via ``_ping_timeout_s`` /
# ``_pong_grace_s``) so ``monkeypatch.setenv`` in a test takes effect
# without restarting the process.
_DEFAULT_PING_TIMEOUT_S: float
_DEFAULT_PONG_GRACE_S: float
try:
    from app.config import settings

    _DEFAULT_PING_TIMEOUT_S = settings.ws_ping_timeout_s
    _DEFAULT_PONG_GRACE_S = settings.ws_pong_grace_s
except Exception:  # noqa: BLE001
    # ``app.config`` not importable yet (early bootstrap). Fall back
    # to the spec defaults.
    _DEFAULT_PING_TIMEOUT_S = 30.0
    _DEFAULT_PONG_GRACE_S = 60.0


@router.websocket("/ws/patients/{patient_id}")
async def patient_measurements_ws(
    websocket: WebSocket, patient_id: UUID
) -> None:
    """Server-push channel for measurements persisted for ``patient_id``.

    Lifecycle:

    1. Accept the WebSocket.
    2. Register with the in-process ``ConnectionManager`` (BEFORE
       sending ``subscribed`` so a publish immediately after connect
       can reach the new client).
    3. Send ``{type: "subscribed", patient_id: "<uuid>"}``.
    4. Spawn a background heartbeat task that closes the connection
       after ``ping_timeout_s + pong_grace_s`` of client silence.
    5. Loop on ``receive_text``: handle ``{type: "ping"}`` (reply
       pong), enforce the 4403 guard for any frame with a
       ``patient_id`` that does not match the URL.
    6. On ``WebSocketDisconnect`` or any other exception: cancel the
       heartbeat task and unregister the socket.
    """
    await websocket.accept()
    await manager.connect(patient_id, websocket)
    await websocket.send_json(
        {"type": "subscribed", "patient_id": str(patient_id)}
    )

    # ``last_activity`` is shared between the receive loop and the
    # heartbeat task. The heartbeat polls it; the receive loop
    # updates it on every client frame.
    last_activity = time.monotonic()
    stop = asyncio.Event()
    timeout_s = _ping_timeout_s() + _pong_grace_s()

    async def heartbeat() -> None:
        """Close the connection if the client goes silent.

        Polls ``last_activity`` every ``ping_timeout_s`` seconds. If
        the gap exceeds ``ping_timeout_s + pong_grace_s``, closes the
        connection. Otherwise nudges the client with a server-side
        ``{type: "ping"}`` so a healthy client can reply.
        """
        try:
            while not stop.is_set():
                # Sleep the ping interval (or until stop is set).
                try:
                    await asyncio.wait_for(
                        stop.wait(), timeout=_ping_timeout_s()
                    )
                    return  # stop event fired
                except asyncio.TimeoutError:
                    pass
                # Check the silence window.
                if time.monotonic() - last_activity >= timeout_s:
                    try:
                        await websocket.close(
                            code=CLOSE_CODE_HEARTBEAT_TIMEOUT
                        )
                    except Exception:  # noqa: BLE001
                        pass
                    return
                # Send a server-side ping to nudge the client.
                try:
                    await websocket.send_json(
                        {"type": "ping", "ts": int(time.time() * 1000)}
                    )
                except Exception:  # noqa: BLE001
                    return
        except asyncio.CancelledError:
            return

    heartbeat_task = asyncio.create_task(heartbeat())
    try:
        while True:
            raw = await websocket.receive_text()
            last_activity = time.monotonic()
            try:
                frame = json.loads(raw)
            except json.JSONDecodeError:
                # Malformed JSON: ignore. The heartbeat will catch a
                # truly broken client.
                continue
            if not isinstance(frame, dict):
                continue

            # 4403 guard (REQ-WS-05) — check FIRST so a WsPing that
            # also carries a mismatched patient_id is treated as a
            # protocol violation rather than a heartbeat. Frames
            # without a patient_id field (e.g. a plain WsPing) are
            # exempt.
            frame_pid = frame.get("patient_id")
            if frame_pid is not None and frame_pid != str(patient_id):
                await websocket.close(code=CLOSE_CODE_MISMATCHED_PATIENT)
                return

            frame_type = frame.get("type")
            if frame_type == "ping":
                await websocket.send_json(
                    {"type": "pong", "ts": int(time.time() * 1000)}
                )
                continue
    except WebSocketDisconnect:
        pass
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "ws handler exception",
            extra={"patient_id": str(patient_id), "error": str(exc)},
        )
    finally:
        stop.set()
        heartbeat_task.cancel()
        try:
            await heartbeat_task
        except (asyncio.CancelledError, Exception):
            pass
        await manager.disconnect(patient_id, websocket)
