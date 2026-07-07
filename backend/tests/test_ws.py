"""WebSocket test suite (REQ-WS-01..05).

This file is built incrementally across T4.1 -> T4.4 and committed
only in T4.4 (the dedicated test commit). Each intermediate PR
(T4.1 / T4.2 / T4.3) only adds the relevant test class so the
between-commit verify commands can find the class they need.

Test classes:

- ``TestManagerFanOut`` (T4.1) - direct tests of the
  ``ConnectionManager`` using a fake WebSocket object.
- ``TestWsRoute`` (T4.2) - tests of the FastAPI WS route using
  ``starlette.testclient.TestClient``.
- ``TestEndToEnd`` (T4.3) - tests that open a WS, POST a measurement
  via the HTTP layer, and assert the WS receives the
  ``measurement.created`` frame.
- ``TestReqWsXX`` (T4.4) - the 6 end-to-end tests named after the
  5 WS requirements, including a heartbeat close-on-silence test
  with ``APP_WS_PING_TIMEOUT_S=0.1`` / ``APP_WS_PONG_GRACE_S=0.2``
  so the 60s window collapses to <1s.

Imports are LAZY inside each test function so the file is importable
even when some ``app.*`` modules don't exist yet (during incremental
T4.1 -> T4.3 development).
"""
from __future__ import annotations

import asyncio
import os
import queue
import threading
from uuid import UUID, uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text
from starlette.websockets import WebSocketDisconnect


# --- autouse cleanup (overrides conftest's _clean_tables) -----------------


@pytest.fixture(autouse=True)
async def _clean_tables() -> None:
    """Wipe the 4 tables before each test.

    Override of the conftest's autouse fixture. Uses a RAW asyncpg
    connection (not the shared ``AsyncSessionLocal``) so the TRUNCATE
    does NOT add a connection to the engine's pool in the TEST's
    event loop. The TestClient's portal (separate thread + loop)
    would otherwise see those pool connections and try to use them
    from its own loop, raising
    ``RuntimeError: ... attached to a different loop``.

    We also SKIP the conftest's ``engine.dispose()``: the TestClient
    tears down its own portal at end of ``with TestClient(...)``,
    which releases the portal's connections. The engine's pool
    remains usable for subsequent tests (each test gets a fresh
    autouse call that opens its own raw asyncpg connection).
    """
    import asyncpg

    dsn = os.environ.get(
        "APP_DATABASE_URL",
        "postgresql+asyncpg://postgres:postgres@localhost:5432/remote_monitor",
    )
    # Strip the asyncpg driver suffix for raw asyncpg.
    raw_dsn = dsn.replace("postgresql+asyncpg", "postgresql")
    conn = await asyncpg.connect(raw_dsn)
    try:
        await conn.execute(
            "TRUNCATE TABLE clinical.measurements, audit.audit_log, "
            "clinical.patients, pii.patients RESTART IDENTITY CASCADE"
        )
        yield
    finally:
        await conn.close()


# --- WS cleanup helper -----------------------------------------------------


@pytest.fixture
def clean_ws() -> "WebSocketCollector":
    """Tracks open WS connections and closes them on teardown.

    Use as ``with clean_ws.ws("/ws/patients/<uuid>") as ws:`` inside
    a test. If the test fails or exits early, the collector closes
    the WS so the next test starts with an empty registry.
    """
    collector = WebSocketCollector()
    try:
        yield collector
    finally:
        collector.close_all()


class WebSocketCollector:
    """Helper that records open TestClient WS sessions for cleanup."""

    def __init__(self) -> None:
        self._open: list = []

    def ws(self, test_client: TestClient, path: str):
        """Open a WS and remember it for ``close_all``."""
        ws = test_client.websocket_connect(path)
        ws.__enter__()
        self._open.append(ws)
        return ws

    def close_all(self) -> None:
        for ws in self._open:
            try:
                ws.__exit__(None, None, None)
            except Exception:
                pass
        self._open.clear()


# =========================================================================
# T4.1 - ConnectionManager unit tests
# =========================================================================


class TestManagerFanOut:
    """Direct tests of ``app.ws.manager.ConnectionManager``.

    Uses a minimal fake WebSocket that records the frames it received
    in an in-memory list. The manager only ever calls
    ``ws.send_json(message)``; we don't need a real WS for the unit
    test.
    """

    async def test_publish_to_all_subscribers_of_same_patient(self) -> None:
        """Two sockets subscribed to the same patient both receive the frame."""
        from app.ws.manager import ConnectionManager

        mgr = ConnectionManager()
        patient_id = uuid4()

        ws1 = _FakeWebSocket()
        ws2 = _FakeWebSocket()
        await mgr.connect(patient_id, ws1)
        await mgr.connect(patient_id, ws2)

        await mgr.publish(patient_id, {"type": "test", "value": 1})

        assert ws1.received == [{"type": "test", "value": 1}]
        assert ws2.received == [{"type": "test", "value": 1}]

    async def test_publish_does_not_leak_to_other_patients(self) -> None:
        """A frame for patient A is not received by a socket for patient B."""
        from app.ws.manager import ConnectionManager

        mgr = ConnectionManager()
        patient_a = uuid4()
        patient_b = uuid4()

        ws_a = _FakeWebSocket()
        ws_b = _FakeWebSocket()
        await mgr.connect(patient_a, ws_a)
        await mgr.connect(patient_b, ws_b)

        await mgr.publish(patient_a, {"type": "for_a"})

        assert ws_a.received == [{"type": "for_a"}]
        assert ws_b.received == []

    async def test_publish_to_no_subscribers_is_noop(self) -> None:
        """``publish`` on an unknown patient does not raise and does nothing."""
        from app.ws.manager import ConnectionManager

        mgr = ConnectionManager()
        # No sockets registered; should silently no-op.
        await mgr.publish(uuid4(), {"type": "nobody_home"})
        assert await mgr.count(uuid4()) == 0

    async def test_disconnect_is_idempotent(self) -> None:
        """Calling ``disconnect`` twice is safe; ``count`` returns 0."""
        from app.ws.manager import ConnectionManager

        mgr = ConnectionManager()
        patient_id = uuid4()
        ws = _FakeWebSocket()
        await mgr.connect(patient_id, ws)
        assert await mgr.count(patient_id) == 1
        await mgr.disconnect(patient_id, ws)
        await mgr.disconnect(patient_id, ws)  # no-op
        assert await mgr.count(patient_id) == 0

    async def test_publish_drops_failing_socket_and_delivers_to_others(
        self,
    ) -> None:
        """A failing ``send_json`` does not break delivery to the other sockets."""
        from app.ws.manager import ConnectionManager

        mgr = ConnectionManager()
        patient_id = uuid4()
        ws_good = _FakeWebSocket()
        ws_bad = _RaisingWebSocket()
        await mgr.connect(patient_id, ws_good)
        await mgr.connect(patient_id, ws_bad)

        await mgr.publish(patient_id, {"type": "broadcast"})

        assert ws_good.received == [{"type": "broadcast"}]
        # The bad socket was removed from the registry.
        assert await mgr.count(patient_id) == 1


class _FakeWebSocket:
    """Minimal in-memory WebSocket double for unit tests."""

    def __init__(self) -> None:
        self.received: list[dict] = []

    async def send_json(self, data: dict) -> None:
        self.received.append(data)


class _RaisingWebSocket:
    """Fake WS whose ``send_json`` always raises."""

    async def send_json(self, data: dict) -> None:  # type: ignore[override]
        raise RuntimeError("simulated broken client")


# =========================================================================
# T4.2 - WebSocket route tests
# =========================================================================


class TestWsRoute:
    """End-to-end tests of the ``/ws/patients/{patient_id}`` route.

    Uses ``starlette.testclient.TestClient`` so the WS runs through
    the real ASGI stack (route + manager + FastAPI dependency
    injection). The conftest autouse fixture (the override in this
    file) TRUNCATEs the DB between tests.

    Note: T4.2 does NOT wire the WS router into ``app.main`` (that is
    T4.3). To keep these tests self-contained, each test creates a
    minimal FastAPI app that includes ONLY the WS router. T4.3 tests
    use the full ``app.main`` for the end-to-end publish flow.
    """

    @staticmethod
    def _make_ws_app():
        """Return a minimal FastAPI app with the WS router included."""
        from fastapi import FastAPI
        from app.ws.routes import router as ws_router

        app = FastAPI()
        app.include_router(ws_router)
        return app

    def test_subscribed_on_connect(self) -> None:
        """REQ-WS-01: the first frame after connect is WsSubscribed."""
        app = self._make_ws_app()
        patient_id = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                frame = ws.receive_json()
                assert frame == {
                    "type": "subscribed",
                    "patient_id": str(patient_id),
                }

    def test_ping_pong(self) -> None:
        """A client WsPing is answered by a server WsPong."""
        app = self._make_ws_app()
        patient_id = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                ws.send_json({"type": "ping", "ts": 1})
                pong = ws.receive_json()
                assert pong["type"] == "pong"
                assert isinstance(pong["ts"], int)

    def test_close_on_mismatched_patient_id(self) -> None:
        """REQ-WS-05: a frame with a different patient_id closes with 4403."""
        app = self._make_ws_app()
        patient_a = uuid4()
        patient_b = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_a}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                ws.send_json(
                    {
                        "type": "ping",
                        "patient_id": str(patient_b),
                        "ts": 1,
                    }
                )
                with pytest.raises(WebSocketDisconnect) as exc_info:
                    ws.receive_json()
                assert exc_info.value.code == 4403

    def test_ping_without_patient_id_keeps_connection_open(self) -> None:
        """A WsPing frame (no patient_id field) does NOT close the connection."""
        app = self._make_ws_app()
        patient_id = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                ws.send_json({"type": "ping", "ts": 1})
                pong = ws.receive_json()
                assert pong["type"] == "pong"  # connection stayed open

    def test_malformed_json_frame_is_ignored(self) -> None:
        """A non-JSON frame is silently ignored; the connection stays open."""
        app = self._make_ws_app()
        patient_id = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                ws.send_text("not json at all")
                # A subsequent valid ping still gets a pong.
                ws.send_json({"type": "ping", "ts": 1})
                pong = ws.receive_json()
                assert pong["type"] == "pong"


# =========================================================================
# T4.3 - End-to-end publish flow (POST -> WS event)
# =========================================================================


def _valid_measurement(local_id: UUID | None = None) -> dict:
    """Build a single valid ``MeasurementBatch`` payload (dict form)."""
    return {
        "local_id": str(local_id or uuid4()),
        "timestamp": "2026-06-29T12:00:00+00:00",
        "heart_rate_bpm": 72,
        "spo2_percent": 98.0,
    }


class TestEndToEnd:
    """End-to-end tests: POST a measurement, assert the WS receives the event.

    Uses the full ``app.main`` app (T4.3 wires the WS router into
    main) so the publish hook in ``services.ingest`` fires against
    the real ``ConnectionManager`` singleton.
    """

    @staticmethod
    def _read_next_frame_bg(ws, queue_: queue.Queue) -> threading.Thread:
        """Read the next WS frame in a background thread; put it on ``queue_``.

        Returns the thread (daemon, started). The thread exits when
        the WS is closed (WebSocketDisconnect) or any other error.
        """
        def _reader() -> None:
            try:
                frame = ws.receive_json()
                queue_.put(("ok", frame))
            except Exception as exc:  # noqa: BLE001
                queue_.put(("err", exc))

        t = threading.Thread(target=_reader, daemon=True)
        t.start()
        return t

    def test_post_measurement_publishes_to_subscribed_ws(self) -> None:
        """REQ-WS-02: a POST measurement triggers a measurement.created event."""
        from app.main import app
        from app.ws.manager import manager

        patient_id = uuid4()
        # Use a patient_number that's stable per test (so the
        # auto-register path is exercised).
        patient_number = f"P-{patient_id.hex[:8]}"
        local_id = uuid4()

        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                # Sanity: the manager has this socket registered.
                import asyncio
                assert asyncio.get_event_loop().run_until_complete(
                    manager.count(patient_id)
                ) == 1

                # Start a background thread to read the next frame.
                q: queue.Queue = queue.Queue()
                self._read_next_frame_bg(ws, q)

                # POST a measurement (triggers auto-register on the
                # first call for this patient_number).
                response = client.post(
                    f"/api/v1/patients/{patient_id}/measurements",
                    json=[_valid_measurement(local_id)],
                    headers={"X-Patient-Number": patient_number},
                )
                assert response.status_code == 200, response.text

                # Wait for the WS frame (up to 2s).
                kind, payload = q.get(timeout=2.0)
                assert kind == "ok", f"reader failed: {payload!r}"
                assert payload["type"] == "measurement.created"
                assert payload["data"]["patient_id"] == str(patient_id)
                assert payload["data"]["local_id"] == str(local_id)
                assert payload["data"]["heart_rate_bpm"] == 72

    def test_post_with_no_subscribers_returns_200(self) -> None:
        """REQ-WS-02 scenario 3: POST with no open WS still returns 200."""
        from app.main import app

        patient_id = uuid4()
        patient_number = f"P-{patient_id.hex[:8]}"

        with TestClient(app) as client:
            # No WS open.
            response = client.post(
                f"/api/v1/patients/{patient_id}/measurements",
                json=[_valid_measurement()],
                headers={"X-Patient-Number": patient_number},
            )
            assert response.status_code == 200
            body = response.json()
            assert len(body["accepted_ids"]) == 1
            assert body["rejected"] == []

    def test_post_with_ibis_ms_publishes_ibis_ms_in_ws_payload(self) -> None:
        """WU-2.13 RED — REQ-WATCH-HR-IBI-13 S02.

        When a measurement carries ``ibis_ms=[800, 820]``, the WS
        broadcast ``measurement.created`` frame must include the
        same list under the ``data`` key. Currently the WS dict in
        ``services.ingest`` does not forward ``ibis_ms``, so the
        frame is missing the field (or carries ``None``).
        """
        from app.main import app

        patient_id = uuid4()
        patient_number = f"P-{patient_id.hex[:8]}"
        local_id = uuid4()
        item = _valid_measurement(local_id)
        item["ibis_ms"] = [800, 820]

        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                ws.receive_json()  # WsSubscribed

                q: queue.Queue = queue.Queue()
                self._read_next_frame_bg(ws, q)

                response = client.post(
                    f"/api/v1/patients/{patient_id}/measurements",
                    json=[item],
                    headers={"X-Patient-Number": patient_number},
                )
                assert response.status_code == 200, response.text

                kind, payload = q.get(timeout=2.0)
                assert kind == "ok", f"reader failed: {payload!r}"
                assert payload["type"] == "measurement.created"
                # The frame MUST carry ibis_ms with the same list.
                assert "ibis_ms" in payload["data"], (
                    f"ibis_ms missing from WS payload: {payload['data']!r}"
                )
                assert list(payload["data"]["ibis_ms"]) == [800, 820], (
                    f"expected ibis_ms=[800, 820], got "
                    f"{payload['data'].get('ibis_ms')!r}"
                )


# =========================================================================
# T4.4 - End-to-end WS suite covering all 5 REQ-WS-01..05
# =========================================================================
#
# The tests below are the final, named-after-requirements end-to-end
# suite. They use the real ``app.main`` so the publish hook in
# ``services.ingest`` fires against the real ``ConnectionManager``
# singleton. The earlier classes (TestManagerFanOut, TestWsRoute,
# TestEndToEnd) are the incremental unit / route / publish tests;
# this class is the comprehensive coverage that maps 1:1 to the
# REQ-WS-01..05 spec.


class TestEndToEndSuite:
    """Named-after-requirement end-to-end tests for REQ-WS-01..05."""

    @staticmethod
    def _read_frame_bg(ws, queue_: queue.Queue) -> threading.Thread:
        """Read one frame from ``ws`` in a background thread."""
        def _reader() -> None:
            try:
                queue_.put(("ok", ws.receive_json()))
            except Exception as exc:  # noqa: BLE001
                queue_.put(("err", exc))

        t = threading.Thread(target=_reader, daemon=True)
        t.start()
        return t

    def test_subscribed_on_connect(self) -> None:
        """REQ-WS-01: first frame after connect is ``{type: subscribed, patient_id}``."""
        from app.main import app

        patient_id = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                frame = ws.receive_json()
                assert frame == {
                    "type": "subscribed",
                    "patient_id": str(patient_id),
                }

    def test_publish_to_subscribers(self) -> None:
        """REQ-WS-02: two subscribers on the same patient both receive the event."""
        from app.main import app

        patient_id = uuid4()
        patient_number = f"P-{patient_id.hex[:8]}"

        with TestClient(app) as client:
            # Open TWO WS to the same patient.
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws1, client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws2:
                ws1.receive_json()  # WsSubscribed
                ws2.receive_json()  # WsSubscribed

                # Start a reader on each.
                q1: queue.Queue = queue.Queue()
                q2: queue.Queue = queue.Queue()
                self._read_frame_bg(ws1, q1)
                self._read_frame_bg(ws2, q2)

                # POST a measurement (auto-registers the patient).
                response = client.post(
                    f"/api/v1/patients/{patient_id}/measurements",
                    json=[_valid_measurement()],
                    headers={"X-Patient-Number": patient_number},
                )
                assert response.status_code == 200

                # Both subscribers should receive the same event.
                for q, label in [(q1, "ws1"), (q2, "ws2")]:
                    kind, payload = q.get(timeout=2.0)
                    assert kind == "ok", f"{label} reader failed: {payload!r}"
                    assert payload["type"] == "measurement.created"
                    assert payload["data"]["patient_id"] == str(patient_id)

    def test_publish_isolation(self) -> None:
        """REQ-WS-03: WS for patient A does NOT receive patient B's event."""
        from app.main import app

        patient_a = uuid4()
        patient_b = uuid4()
        number_b = f"P-{patient_b.hex[:8]}"

        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_a}"
            ) as ws_a:
                ws_a.receive_json()  # WsSubscribed

                # Start a reader on A.
                q: queue.Queue = queue.Queue()
                self._read_frame_bg(ws_a, q)

                # POST a measurement for patient B.
                response = client.post(
                    f"/api/v1/patients/{patient_b}/measurements",
                    json=[_valid_measurement()],
                    headers={"X-Patient-Number": number_b},
                )
                assert response.status_code == 200

                # A should NOT receive any frame within 2 seconds.
                with pytest.raises(queue.Empty):
                    q.get(timeout=2.0)

    def test_ping_pong(self) -> None:
        """REQ-WS-04 scenario 1: client WsPing is answered by server WsPong."""
        from app.main import app

        patient_id = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                ws.send_json({"type": "ping", "ts": 1})
                pong = ws.receive_json()
                assert pong["type"] == "pong"
                assert isinstance(pong["ts"], int)

    def test_close_on_silence_closes_connection(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """REQ-WS-04 scenario 2: 60s of client silence closes the connection.

        The 30s/60s production windows are too slow for pytest. We
        override ``APP_WS_PING_TIMEOUT_S=0.1`` and
        ``APP_WS_PONG_GRACE_S=0.2`` via ``monkeypatch.setenv`` so the
        heartbeat fires within ~0.3s. The route reads the env var on
        every connection (see ``_ping_timeout_s`` / ``_pong_grace_s``
        in ``app.ws.routes``) so the override takes effect without a
        process restart.

        The server may send one or more ``{type: "ping"}`` frames
        before closing; we consume them and only assert the close
        after the silence window has elapsed.
        """
        import time

        monkeypatch.setenv("APP_WS_PING_TIMEOUT_S", "0.1")
        monkeypatch.setenv("APP_WS_PONG_GRACE_S", "0.2")

        from app.main import app

        patient_id = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_id}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                # Do NOT send any client frames. The heartbeat should
                # close the connection within ~0.5s with code 4401.
                # Consume any server pings and assert the close.
                deadline = time.monotonic() + 2.0
                while time.monotonic() < deadline:
                    try:
                        frame = ws.receive_json()
                        # Server pings are expected; ignore them.
                        assert frame.get("type") == "ping", frame
                    except WebSocketDisconnect as exc:
                        assert exc.code == 4401
                        return
                pytest.fail("Connection did not close within 2s")

    def test_close_on_mismatched_patient_id(self) -> None:
        """REQ-WS-05: a frame with a different patient_id closes with 4403."""
        from app.main import app

        patient_a = uuid4()
        patient_b = uuid4()
        with TestClient(app) as client:
            with client.websocket_connect(
                f"/ws/patients/{patient_a}"
            ) as ws:
                ws.receive_json()  # WsSubscribed
                ws.send_json(
                    {
                        "type": "ping",
                        "patient_id": str(patient_b),
                        "ts": 1,
                    }
                )
                with pytest.raises(WebSocketDisconnect) as exc_info:
                    ws.receive_json()
                assert exc_info.value.code == 4403

    def test_publish_to_no_subscribers_is_noop(self) -> None:
        """REQ-WS-02 scenario 3: POST with no open WS still returns 200."""
        from app.main import app

        patient_id = uuid4()
        patient_number = f"P-{patient_id.hex[:8]}"

        with TestClient(app) as client:
            # No WS open.
            response = client.post(
                f"/api/v1/patients/{patient_id}/measurements",
                json=[_valid_measurement()],
                headers={"X-Patient-Number": patient_number},
            )
            assert response.status_code == 200
            body = response.json()
            assert len(body["accepted_ids"]) == 1
            assert body["rejected"] == []
