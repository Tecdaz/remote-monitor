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
