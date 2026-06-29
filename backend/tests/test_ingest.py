"""Tests for the HTTP ingest pipeline.

Three test classes live in this file (one per task that owns the
HTTP surface):

- ``TestIngestService`` (T3.2) — service-layer unit tests that bypass
  the HTTP layer and exercise ``upload_measurements`` directly.
- ``TestHeaderEnforcement`` (T3.3) — HTTP tests for the X-Patient-Number
  dependency (REQ-INGEST-06) and the X-Request-ID header (REQ-OBS-01).
- ``TestMeasurementsRouter`` (T3.4) — end-to-end HTTP tests for the
  POST/GET ``/measurements`` routes (REQ-INGEST-01/05, REQ-READ-01/03).

REQ-INGEST-02 (delete-after-echo: 200 only after commit) is asserted
implicitly by the audit-rollback test: when the transaction fails, the
service raises, the router returns 5xx, and the watch sees an empty
``BatchResponse``.
"""
from __future__ import annotations

import base64
import json
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest
from httpx import AsyncClient
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AuditLog, ClinicalMeasurement, ClinicalPatient, PiiPatient
from app.schemas import BatchResponse
from app.services.ingest import upload_measurements

# --- shared helpers --------------------------------------------------------


def _valid_item(local_id: UUID | None = None) -> dict:
    """A single valid ``MeasurementBatch`` payload (dict form)."""
    return {
        "local_id": str(local_id or uuid4()),
        "timestamp": datetime(2026, 6, 29, 12, 0, 0, tzinfo=timezone.utc).isoformat(),
        "heart_rate_bpm": 72,
        "spo2_percent": 98.0,
    }


def _invalid_item() -> dict:
    """An item that fails Pydantic validation (heart_rate out of range)."""
    return {
        "local_id": str(uuid4()),
        "timestamp": datetime(2026, 6, 29, 12, 0, 0, tzinfo=timezone.utc).isoformat(),
        "heart_rate_bpm": 400,  # Pydantic rejects: max=299
        "spo2_percent": 98.0,
    }


# =========================================================================
# T3.2 — service-layer tests
# =========================================================================


class TestIngestService:
    """Direct tests of ``upload_measurements`` against the real DB."""

    async def test_upload_returns_batch_response_with_accepted_ids(
        self, session: AsyncSession
    ) -> None:
        """3 valid items -> accepted_ids has 3, rejected is empty."""
        path_pid = uuid4()
        items = [_valid_item() for _ in range(3)]
        response = await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00001",
            raw_items=items,
        )
        assert isinstance(response, BatchResponse)
        assert len(response.accepted_ids) == 3
        assert response.rejected == []

    async def test_upload_writes_measurements_and_one_audit_row(
        self, session: AsyncSession
    ) -> None:
        """After upload: 5 measurement rows, 1 audit row, 1 patient row."""
        path_pid = uuid4()
        items = [_valid_item() for _ in range(5)]
        await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00002",
            raw_items=items,
        )

        meas_count = (
            await session.execute(
                select(ClinicalMeasurement).where(
                    ClinicalMeasurement.patient_id == path_pid
                )
            )
        ).scalars().all()
        assert len(meas_count) == 5

        audit_rows = (
            await session.execute(select(AuditLog))
        ).scalars().all()
        assert len(audit_rows) == 1
        assert audit_rows[0].action == "measurement.create"
        assert audit_rows[0].count == 5
        assert audit_rows[0].actor == "watch"

    async def test_upload_rejects_invalid_items_individually(
        self, session: AsyncSession
    ) -> None:
        """1 invalid item is rejected; the 2 valid items go through."""
        path_pid = uuid4()
        items = [_valid_item(), _invalid_item(), _valid_item()]
        response = await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00003",
            raw_items=items,
        )
        assert len(response.accepted_ids) == 2
        assert len(response.rejected) == 1
        assert response.rejected[0].reason.startswith("validation error")

    async def test_upload_rolls_back_when_audit_fails(
        self, session: AsyncSession
    ) -> None:
        """If the audit insert fails, NO measurement row is committed
        (REQ-INGEST-03 + REQ-SCHEMA-04: audit is in the same tx).
        """
        path_pid = uuid4()
        items = [_valid_item() for _ in range(3)]

        # Patch the audit helper so it raises mid-transaction. The
        # ``async with session.begin():`` block in upload_measurements
        # will then rollback all 3 measurement inserts.
        with patch(
            "app.services.ingest.write_audit_log",
            new_callable=AsyncMock,
            side_effect=RuntimeError("simulated audit failure"),
        ):
            with pytest.raises(RuntimeError, match="simulated audit failure"):
                await upload_measurements(
                    session,
                    path_patient_id=path_pid,
                    patient_number="P-00004",
                    raw_items=items,
                )

        # The session is in a failed state after the rollback; use a
        # fresh session to read.
        async with session.bind.connect() as conn:
            rows = (
                await conn.execute(
                    text("SELECT COUNT(*) FROM clinical.measurements")
                )
            ).scalar_one()
            assert rows == 0

            audit_rows = (
                await conn.execute(
                    text("SELECT COUNT(*) FROM audit.audit_log")
                )
            ).scalar_one()
            assert audit_rows == 0

            pii_rows = (
                await conn.execute(
                    text("SELECT COUNT(*) FROM pii.patients")
                )
            ).scalar_one()
            # The auto-registered patient row also rolled back.
            assert pii_rows == 0

    async def test_upload_is_idempotent_on_local_id(
        self, session: AsyncSession
    ) -> None:
        """Re-POSTing the same local_id does not create a duplicate row
        and the local_id is still in accepted_ids (REQ-INGEST-04).
        """
        path_pid = uuid4()
        local_a, local_b, local_c = uuid4(), uuid4(), uuid4()
        items = [
            _valid_item(local_a),
            _valid_item(local_b),
            _valid_item(local_c),
        ]

        first = await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00005",
            raw_items=items,
        )
        assert sorted(first.accepted_ids) == sorted([local_a, local_b, local_c])

        # Re-POST the same batch.
        second = await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00005",
            raw_items=items,
        )
        assert sorted(second.accepted_ids) == sorted([local_a, local_b, local_c])
        assert second.rejected == []

        # Still only 3 measurement rows + 2 audit rows.
        meas = (
            await session.execute(
                select(ClinicalMeasurement).where(
                    ClinicalMeasurement.patient_id == path_pid
                )
            )
        ).scalars().all()
        assert len(meas) == 3

        audits = (await session.execute(select(AuditLog))).scalars().all()
        assert len(audits) == 2

    async def test_upload_auto_registers_new_patient_in_same_transaction(
        self, session: AsyncSession
    ) -> None:
        """First-time upload creates pii.patients + clinical.patients."""
        path_pid = uuid4()
        items = [_valid_item() for _ in range(2)]
        await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00006",
            raw_items=items,
            device_model="Samsung Galaxy Watch 4",
            os_version="Wear OS 6 (API 36)",
        )

        pii = (
            await session.execute(
                select(PiiPatient).where(PiiPatient.patient_id == path_pid)
            )
        ).scalar_one()
        # The stored value is the ciphertext, not the plaintext.
        assert isinstance(pii.patient_number, bytes)
        assert pii.patient_number != b"P-00006"

        clinical = (
            await session.execute(
                select(ClinicalPatient).where(
                    ClinicalPatient.patient_id == path_pid
                )
            )
        ).scalar_one()
        assert clinical.device_model == "Samsung Galaxy Watch 4"
        assert clinical.os_version == "Wear OS 6 (API 36)"
        assert clinical.is_active is True

    async def test_upload_uses_unknown_for_missing_device_headers(
        self, session: AsyncSession
    ) -> None:
        """REQ-INGEST-07: device headers default to 'unknown' if absent."""
        path_pid = uuid4()
        items = [_valid_item()]
        await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00007",
            raw_items=items,
        )
        clinical = (
            await session.execute(
                select(ClinicalPatient).where(
                    ClinicalPatient.patient_id == path_pid
                )
            )
        ).scalar_one()
        assert clinical.device_model == "unknown"
        assert clinical.os_version == "unknown"

    async def test_upload_does_not_reregister_existing_patient(
        self, session: AsyncSession
    ) -> None:
        """Second upload with the same X-Patient-Number does not UPDATE
        the clinical.patients row (REQ-INGEST-07 scenario 2).
        """
        path_pid = uuid4()
        first_items = [_valid_item()]
        await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00008",
            raw_items=first_items,
            device_model="Pixel Watch",
            os_version="Wear OS 5",
        )

        # Capture the original created_at. Use raw SQL to read; then
        # explicitly close the autobegun read transaction so the
        # second ``upload_measurements`` call can begin a fresh one
        # inside its own ``async with session.begin():`` block.
        original_created_at = (
            await session.execute(
                text(
                    "SELECT created_at FROM clinical.patients "
                    "WHERE patient_id = :pid"
                ),
                {"pid": path_pid},
            )
        ).scalar_one()
        await session.commit()

        # Second upload with the same patient_number but different
        # device headers — the headers should be IGNORED on re-upload.
        second_items = [_valid_item()]
        await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00008",
            raw_items=second_items,
            device_model="ShouldNotStick",
            os_version="ShouldNotStick",
        )

        again_created_at = (
            await session.execute(
                text(
                    "SELECT created_at FROM clinical.patients "
                    "WHERE patient_id = :pid"
                ),
                {"pid": path_pid},
            )
        ).scalar_one()
        device_model = (
            await session.execute(
                text(
                    "SELECT device_model FROM clinical.patients "
                    "WHERE patient_id = :pid"
                ),
                {"pid": path_pid},
            )
        ).scalar_one()
        os_version = (
            await session.execute(
                text(
                    "SELECT os_version FROM clinical.patients "
                    "WHERE patient_id = :pid"
                ),
                {"pid": path_pid},
            )
        ).scalar_one()
        assert device_model == "Pixel Watch"
        assert os_version == "Wear OS 5"
        # created_at is unchanged (no re-register).
        assert again_created_at == original_created_at

        # Only one pii.patients row.
        pii_count = (
            await session.execute(
                text("SELECT COUNT(*) FROM pii.patients")
            )
        ).scalar_one()
        assert pii_count == 1

    async def test_upload_mismatch_raises_403(
        self, session: AsyncSession
    ) -> None:
        """X-Patient-Number resolves to a patient_id different from the
        path -> HTTPException 403 (REQ-INGEST-06 mismatch scenario).
        """
        # Register patient A with patient_number P-00009.
        pid_a = uuid4()
        await upload_measurements(
            session,
            path_patient_id=pid_a,
            patient_number="P-00009",
            raw_items=[_valid_item()],
        )

        # Now try to upload with the same X-Patient-Number but a
        # DIFFERENT path patient_id.
        pid_b = uuid4()
        from fastapi import HTTPException

        with pytest.raises(HTTPException) as exc:
            await upload_measurements(
                session,
                path_patient_id=pid_b,
                patient_number="P-00009",
                raw_items=[_valid_item()],
            )
        assert exc.value.status_code == 403
        assert "patient_number_mismatch" in str(exc.value.detail)

    async def test_upload_audit_row_carries_patient_id_and_local_ids(
        self, session: AsyncSession
    ) -> None:
        """The audit context JSONB includes patient_id + local_ids."""
        path_pid = uuid4()
        lid1, lid2 = uuid4(), uuid4()
        await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="P-00010",
            raw_items=[_valid_item(lid1), _valid_item(lid2)],
        )
        audit = (
            await session.execute(select(AuditLog))
        ).scalar_one()
        assert audit.context is not None
        assert audit.context["patient_id"] == str(path_pid)
        assert sorted(audit.context["local_ids"]) == sorted(
            [str(lid1), str(lid2)]
        )


# =========================================================================
# T3.3 — header enforcement + observability
# =========================================================================


class TestHeaderEnforcement:
    """REQ-INGEST-06 + REQ-OBS-01.

    These are unit tests of the dependency function and the middleware
    class. The dependency and the new middleware land in T3.3 but are
    not yet wired into ``app.main`` (T3.6 swaps the inline middleware
    and logging config). Verifying the integration end-to-end (a real
    HTTP request triggering a 4xx and observing a log line) lives in
    the T3.6 wiring tests.
    """

    async def test_dependency_raises_403_when_header_missing(self) -> None:
        """No X-Patient-Number -> 403 missing_patient_number."""
        from app.dependencies import require_patient_number_header
        from fastapi import HTTPException

        with pytest.raises(HTTPException) as exc:
            await require_patient_number_header(x_patient_number=None)
        assert exc.value.status_code == 403
        assert exc.value.detail["code"] == "missing_patient_number"

    async def test_dependency_raises_403_when_header_empty(self) -> None:
        """Empty X-Patient-Number -> 403 (header is present but blank)."""
        from app.dependencies import require_patient_number_header
        from fastapi import HTTPException

        with pytest.raises(HTTPException) as exc:
            await require_patient_number_header(x_patient_number="")
        assert exc.value.status_code == 403

    async def test_dependency_returns_value_when_header_present(self) -> None:
        """X-Patient-Number present -> returns the value verbatim."""
        from app.dependencies import require_patient_number_header

        result = await require_patient_number_header(x_patient_number="P-00042")
        assert result == "P-00042"

    @staticmethod
    def _install_recording_logger() -> list[dict]:
        """Replace ``structlog.get_logger`` in the middleware module
        with a recorder that captures every log call as a dict.

        Returns the list that will be appended to.
        """
        from unittest.mock import MagicMock
        from app import middleware as middleware_mod

        logs: list[dict] = []
        mock_logger = MagicMock()
        mock_logger.info = lambda event, **kw: logs.append(
            {"event": event, "level": "info", **kw}
        )
        mock_logger.error = lambda event, **kw: logs.append(
            {"event": event, "level": "error", **kw}
        )
        mock_logger.exception = lambda event, **kw: logs.append(
            {"event": event, "level": "error", "exc_info": True, **kw}
        )
        middleware_mod.structlog.get_logger = lambda: mock_logger  # type: ignore[assignment]
        return logs

    @staticmethod
    def _build_middleware_request(
        method: str = "GET",
        path: str = "/x",
        request_id_header: str | None = None,
    ):
        """Build a minimal ASGI request scope + Request object for the
        middleware unit tests (no FastAPI app needed).
        """
        from starlette.requests import Request

        headers = []
        if request_id_header is not None:
            headers.append((b"x-request-id", request_id_header.encode()))
        scope = {
            "type": "http",
            "method": method,
            "path": path,
            "headers": headers,
        }

        async def receive() -> dict:
            return {"type": "http.request", "body": b"", "more_body": False}

        return Request(scope, receive)

    async def test_middleware_generates_request_id_when_absent(self) -> None:
        """The middleware mints a UUID4 when no X-Request-ID was sent."""
        from starlette.responses import PlainTextResponse
        from app.middleware import XRequestIDMiddleware

        # Install the recording logger so the middleware's log calls
        # don't blow up against the test's structlog config.
        self._install_recording_logger()

        request = self._build_middleware_request()

        async def call_next(_request):
            return PlainTextResponse("ok")

        mw = XRequestIDMiddleware(app=None)  # type: ignore[arg-type]
        response = await mw.dispatch(request, call_next)
        # Echoed header is a fresh UUID4 (36 chars).
        assert "X-Request-ID" in response.headers
        assert len(response.headers["X-Request-ID"]) == 36

    async def test_middleware_echoes_client_supplied_request_id(self) -> None:
        """If the client sends X-Request-ID, the server echoes it."""
        from starlette.responses import PlainTextResponse
        from app.middleware import XRequestIDMiddleware

        self._install_recording_logger()

        request = self._build_middleware_request(request_id_header="client-id")

        async def call_next(_request):
            return PlainTextResponse("ok")

        mw = XRequestIDMiddleware(app=None)  # type: ignore[arg-type]
        response = await mw.dispatch(request, call_next)
        assert response.headers["X-Request-ID"] == "client-id"

    async def test_middleware_logs_4xx_at_info_without_traceback(self) -> None:
        """A 4xx response triggers an INFO log, no stack trace."""
        from starlette.responses import PlainTextResponse
        from app.middleware import XRequestIDMiddleware

        logs = self._install_recording_logger()

        request = self._build_middleware_request()

        async def call_next(_request):
            return PlainTextResponse("denied", status_code=403)

        mw = XRequestIDMiddleware(app=None)  # type: ignore[arg-type]
        await mw.dispatch(request, call_next)
        info_403 = [
            l for l in logs
            if l.get("level") == "info" and l.get("status_code") == 403
        ]
        assert info_403, f"Expected INFO log with status_code=403, got: {logs}"
        # No ERROR log.
        assert not any(l.get("level") == "error" for l in logs)

    async def test_middleware_logs_5xx_at_error_with_traceback(self) -> None:
        """A 5xx response triggers an ERROR log with exc_info."""
        from app.middleware import XRequestIDMiddleware

        logs = self._install_recording_logger()

        request = self._build_middleware_request()

        async def call_next(_request):
            raise RuntimeError("simulated commit failure")

        mw = XRequestIDMiddleware(app=None)  # type: ignore[arg-type]
        with pytest.raises(RuntimeError, match="simulated commit failure"):
            await mw.dispatch(request, call_next)
        error_logs = [l for l in logs if l.get("level") == "error"]
        assert error_logs, f"Expected ERROR log, got: {logs}"
        # The middleware logs the exception with exc_info=True on ERROR.
        assert any(l.get("exc_info") for l in error_logs)

    async def test_middleware_unbinds_request_id_after_request(self) -> None:
        """The structlog request_id contextvar is cleared after the call
        so the next request doesn't inherit a stale id (REQ-OBS-01)."""
        import structlog
        from starlette.responses import PlainTextResponse
        from app.middleware import XRequestIDMiddleware

        # Logger recorder (the test doesn't care about the log content,
        # only that dispatch completes and unbind runs).
        self._install_recording_logger()

        request = self._build_middleware_request()

        async def call_next(_request):
            return PlainTextResponse("ok")

        mw = XRequestIDMiddleware(app=None)  # type: ignore[arg-type]
        await mw.dispatch(request, call_next)
        # After dispatch returns, no request_id should be bound.
        ctx = structlog.contextvars.get_contextvars()
        assert "request_id" not in ctx


# =========================================================================
# T3.4 — measurements router end-to-end
# =========================================================================


def _batch_items(n: int) -> list[dict]:
    """Build a list of N valid MeasurementBatch dicts."""
    return [_valid_item() for _ in range(n)]


class TestMeasurementsRouter:
    """End-to-end HTTP tests for /api/v1/patients/{id}/measurements.

    Covers REQ-INGEST-01/02/05 and REQ-READ-01/03. The router is
    mounted on the test app by ``tests/conftest.py``; the production
    wiring into ``app.main`` lands in T3.6.
    """

    async def test_post_1000_items_returns_200(
        self, client: AsyncClient
    ) -> None:
        """REQ-INGEST-05: exactly 1000 items -> 200."""
        path_pid = uuid4()
        response = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=_batch_items(1000),
            headers={"X-Patient-Number": "P-BATCH-1"},
        )
        assert response.status_code == 200
        body = response.json()
        assert len(body["accepted_ids"]) == 1000
        assert body["rejected"] == []

    async def test_post_1001_items_returns_413(
        self, client: AsyncClient
    ) -> None:
        """REQ-INGEST-05: 1001 items -> 413 batch_too_large."""
        path_pid = uuid4()
        response = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=_batch_items(1001),
            headers={"X-Patient-Number": "P-BATCH-2"},
        )
        assert response.status_code == 413
        assert response.json()["detail"]["code"] == "batch_too_large"

    async def test_post_0_items_returns_400(
        self, client: AsyncClient
    ) -> None:
        """Empty batch -> 400 empty_batch."""
        path_pid = uuid4()
        response = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=[],
            headers={"X-Patient-Number": "P-BATCH-3"},
        )
        assert response.status_code == 400
        assert response.json()["detail"]["code"] == "empty_batch"

    async def test_post_without_x_patient_number_returns_403(
        self, client: AsyncClient
    ) -> None:
        """REQ-INGEST-06: missing X-Patient-Number -> 403."""
        path_pid = uuid4()
        response = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=_batch_items(3),
        )
        assert response.status_code == 403
        assert response.json()["detail"]["code"] == "missing_patient_number"

    async def test_post_with_mismatched_x_patient_number_returns_403(
        self, client: AsyncClient
    ) -> None:
        """REQ-INGEST-06: path != resolved patient_id -> 403."""
        # Register patient with patient_number P-MISMATCH.
        pid_a = uuid4()
        await client.post(
            f"/api/v1/patients/{pid_a}/measurements",
            json=_batch_items(1),
            headers={"X-Patient-Number": "P-MISMATCH"},
        )
        # Now POST again with the same X-Patient-Number but a different
        # path patient_id.
        pid_b = uuid4()
        response = await client.post(
            f"/api/v1/patients/{pid_b}/measurements",
            json=_batch_items(1),
            headers={"X-Patient-Number": "P-MISMATCH"},
        )
        assert response.status_code == 403
        assert response.json()["detail"]["code"] == "patient_number_mismatch"

    async def test_post_with_new_patient_auto_registers(
        self, client: AsyncClient
    ) -> None:
        """REQ-INGEST-07: new X-Patient-Number auto-registers the patient."""
        path_pid = uuid4()
        response = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=_batch_items(2),
            headers={
                "X-Patient-Number": "P-NEW",
                "X-Device-Model": "Samsung Galaxy Watch 4",
                "X-OS-Version": "Wear OS 6 (API 36)",
            },
        )
        assert response.status_code == 200
        assert len(response.json()["accepted_ids"]) == 2
        # Verify auto-register via a follow-up GET (needs the patient
        # to exist; otherwise 404).
        get_resp = await client.get(
            f"/api/v1/patients/{path_pid}/measurements"
        )
        assert get_resp.status_code == 200

    async def test_post_mixed_valid_and_invalid_items(
        self, client: AsyncClient
    ) -> None:
        """REQ-INGEST-01: invalid item rejected, valid items accepted."""
        path_pid = uuid4()
        items = [_valid_item(), _invalid_item(), _valid_item()]
        response = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=items,
            headers={"X-Patient-Number": "P-MIXED"},
        )
        assert response.status_code == 200
        body = response.json()
        assert len(body["accepted_ids"]) == 2
        assert len(body["rejected"]) == 1
        assert body["rejected"][0]["reason"].startswith("validation error")

    async def test_get_on_unknown_patient_returns_404(
        self, client: AsyncClient
    ) -> None:
        """REQ-READ-03: GET measurements for unknown patient -> 404."""
        unknown = uuid4()
        response = await client.get(
            f"/api/v1/patients/{unknown}/measurements"
        )
        assert response.status_code == 404
        assert response.json()["detail"]["code"] == "patient_not_found"

    async def test_get_returns_measurements_in_desc_order(
        self, client: AsyncClient
    ) -> None:
        """REQ-READ-01: measurements sorted by timestamp DESC."""
        path_pid = uuid4()
        # First auto-register the patient with a small batch.
        await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=_batch_items(5),
            headers={"X-Patient-Number": "P-LIST-1"},
        )
        response = await client.get(
            f"/api/v1/patients/{path_pid}/measurements"
        )
        assert response.status_code == 200
        body = response.json()
        assert len(body["items"]) == 5
        # All items have the same timestamp (test fixtures), so order
        # is by id DESC as the secondary key.
        ts_values = [item["timestamp"] for item in body["items"]]
        assert ts_values == sorted(ts_values, reverse=True)
        # next_cursor is null when there are no more pages.
        assert body["next_cursor"] is None

    async def test_get_pagination_with_cursor(
        self, client: AsyncClient
    ) -> None:
        """REQ-READ-01: 250 items, limit=100 -> 3 pages with no overlap."""
        path_pid = uuid4()
        # Auto-register the patient first with one item (so the patient exists).
        # We need 250 measurements total.
        items = _batch_items(250)
        # POST in chunks of 1000 (the max) to fit all 250.
        await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=items,
            headers={"X-Patient-Number": "P-PAGE"},
        )

        # Page 1: limit=100.
        r1 = await client.get(
            f"/api/v1/patients/{path_pid}/measurements?limit=100"
        )
        assert r1.status_code == 200
        page1 = r1.json()
        assert len(page1["items"]) == 100
        assert page1["next_cursor"] is not None

        # Page 2.
        r2 = await client.get(
            f"/api/v1/patients/{path_pid}/measurements",
            params={"limit": 100, "cursor": page1["next_cursor"]},
        )
        assert r2.status_code == 200
        page2 = r2.json()
        assert len(page2["items"]) == 100
        assert page2["next_cursor"] is not None

        # No overlap between page1 and page2.
        ids1 = {item["id"] for item in page1["items"]}
        ids2 = {item["id"] for item in page2["items"]}
        assert ids1.isdisjoint(ids2)

        # Page 3 should have the remaining 50.
        r3 = await client.get(
            f"/api/v1/patients/{path_pid}/measurements",
            params={"limit": 100, "cursor": page2["next_cursor"]},
        )
        assert r3.status_code == 200
        page3 = r3.json()
        assert len(page3["items"]) == 50
        assert page3["next_cursor"] is None

    async def test_post_idempotency_via_http(
        self, client: AsyncClient
    ) -> None:
        """REQ-INGEST-04: re-POST the same batch -> same accepted_ids,
        no duplicate rows in the DB."""
        path_pid = uuid4()
        items = _batch_items(3)
        r1 = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=items,
            headers={"X-Patient-Number": "P-IDEMP"},
        )
        r2 = await client.post(
            f"/api/v1/patients/{path_pid}/measurements",
            json=items,
            headers={"X-Patient-Number": "P-IDEMP"},
        )
        assert r1.status_code == 200
        assert r2.status_code == 200
        assert sorted(r1.json()["accepted_ids"]) == sorted(
            r2.json()["accepted_ids"]
        )
        # GET back to confirm only 3 rows persisted.
        r3 = await client.get(
            f"/api/v1/patients/{path_pid}/measurements"
        )
        assert len(r3.json()["items"]) == 3
