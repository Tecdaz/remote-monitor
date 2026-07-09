"""Tests for the patient inactivity sweep (REQ-SWEEP-*).

Covers:

- ``sweep_once`` deactivates stale active patients and skips recent ones.
- ``FOR UPDATE SKIP LOCKED`` lets a sweep iteration skip rows locked by
  another transaction.
- Per-patient savepoint isolation: an audit failure rolls back only that
  patient; other patients in the same iteration still deactivate.
- WebSocket events are published after the outer transaction commits,
  so a publish failure does not roll back deactivations.
"""
from __future__ import annotations

import asyncio
import itertools
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, patch
from uuid import UUID, uuid4

import pytest
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import AsyncSessionLocal
from app.models import ClinicalPatient, PiiPatient
from app.schemas import BatchResponse
from app.services.audit import write_audit_log
from app.services.ingest import upload_measurements
from app.services.inactivity import sweep_once
from app.ws.manager import ConnectionManager

_bed_counter = itertools.count(1)


async def _insert_patient(
    session: AsyncSession,
    *,
    last_measurement_at: datetime,
    is_active: bool = True,
    patient_number_plain: str | None = None,
    bed_number: int | None = None,
) -> UUID:
    """Insert a patient row directly, bypassing HTTP/ingest."""
    from sqlalchemy.dialects.postgresql import insert as pg_insert

    from app.services.crypto import encrypt_patient_number

    pid = uuid4()
    plain = patient_number_plain or str(uuid4())[:8]
    bed = bed_number if bed_number is not None else next(_bed_counter)
    async with session.begin():
        cipher = await encrypt_patient_number(session, plain)
        await session.execute(
            pg_insert(PiiPatient).values(patient_id=pid, patient_number=cipher)
        )
        await session.execute(
            pg_insert(ClinicalPatient).values(
                patient_id=pid,
                device_model="unknown",
                os_version="unknown",
                is_active=is_active,
                bed_number=bed,
                last_measurement_at=last_measurement_at,
            )
        )
    return pid


class TestSweepOnce:
    """Direct tests of ``sweep_once`` against the real DB."""

    async def test_deactivates_stale_patient(self, session: AsyncSession) -> None:
        """A patient older than the threshold is deactivated."""
        stale = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=400),
        )

        deactivated = await sweep_once(
            session, threshold_s=300.0, manager=ConnectionManager()
        )
        assert deactivated == 1

        row = await session.execute(
            text("SELECT is_active FROM clinical.patients WHERE patient_id = :pid"),
            {"pid": stale},
        )
        assert row.scalar_one() is False

    async def test_skips_recent_patient(self, session: AsyncSession) -> None:
        """A patient newer than the threshold stays active."""
        recent = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=10),
        )

        deactivated = await sweep_once(
            session, threshold_s=300.0, manager=ConnectionManager()
        )
        assert deactivated == 0

        row = await session.execute(
            text("SELECT is_active FROM clinical.patients WHERE patient_id = :pid"),
            {"pid": recent},
        )
        assert row.scalar_one() is True

    async def test_skips_inactive_patient(self, session: AsyncSession) -> None:
        """Already-inactive patients are not selected and not touched."""
        inactive = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=400),
            is_active=False,
        )

        deactivated = await sweep_once(
            session, threshold_s=300.0, manager=ConnectionManager()
        )
        assert deactivated == 0

        row = await session.execute(
            text("SELECT is_active FROM clinical.patients WHERE patient_id = :pid"),
            {"pid": inactive},
        )
        assert row.scalar_one() is False

    async def test_audit_row_written_per_deactivation(
        self, session: AsyncSession
    ) -> None:
        """Each deactivation inserts one audit row."""
        stale = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=400),
        )

        await sweep_once(session, threshold_s=300.0, manager=ConnectionManager())

        audit_rows = (
            await session.execute(
                text(
                    "SELECT action, context FROM audit.audit_log "
                    "WHERE context->>'patient_id' = :pid"
                ),
                {"pid": str(stale)},
            )
        ).mappings().all()
        assert len(audit_rows) == 1
        assert audit_rows[0]["action"] == "patient.inactivity_deactivate"

    async def test_skip_locked_skips_concurrent_transaction(
        self, session: AsyncSession
    ) -> None:
        """A row locked by another tx is skipped by the sweep."""
        stale = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=400),
        )

        # Lock the row in a separate connection/session.
        async with AsyncSessionLocal() as locker:
            await locker.execute(
                text(
                    "SELECT patient_id FROM clinical.patients "
                    "WHERE patient_id = :pid FOR UPDATE"
                ),
                {"pid": stale},
            )
            try:
                # Sweep from the test session should see zero rows.
                deactivated = await sweep_once(
                    session, threshold_s=300.0, manager=ConnectionManager()
                )
                assert deactivated == 0

                row = await session.execute(
                    text(
                        "SELECT is_active FROM clinical.patients WHERE patient_id = :pid"
                    ),
                    {"pid": stale},
                )
                assert row.scalar_one() is True
            finally:
                await locker.rollback()

        # After the lock is released, the next sweep deactivates.
        deactivated = await sweep_once(
            session, threshold_s=300.0, manager=ConnectionManager()
        )
        assert deactivated == 1


class TestSweepErrorIsolation:
    """Audit savepoint and WS publish-after-commit behavior."""

    async def test_audit_failure_isolates_single_patient(
        self, session: AsyncSession
    ) -> None:
        """A failing audit insert rolls back only that patient's savepoint."""
        stale_a = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=400),
        )
        stale_b = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=500),
        )

        original_write_audit_log = write_audit_log

        async def _failing_audit(
            s: AsyncSession,
            *,
            actor: str,
            action: str,
            count: int,
            context: dict | None = None,
        ) -> None:
            if context is not None and context.get("patient_id") == str(stale_a):
                raise RuntimeError("simulated audit failure for stale_a")
            await original_write_audit_log(
                s,
                actor=actor,
                action=action,
                count=count,
                context=context,
            )

        with patch(
            "app.services.inactivity.write_audit_log",
            new=_failing_audit,
        ):
            deactivated = await sweep_once(
                session, threshold_s=300.0, manager=ConnectionManager()
            )
            # Only stale_b should have deactivated.
            assert deactivated == 1

        rows = (
            await session.execute(
                text("SELECT patient_id, is_active FROM clinical.patients")
            )
        ).mappings().all()
        by_id = {r["patient_id"]: r["is_active"] for r in rows}
        assert by_id[stale_a] is True
        assert by_id[stale_b] is False

        audit_rows = (
            await session.execute(
                text(
                    "SELECT context->>'patient_id' AS pid FROM audit.audit_log "
                    "WHERE action = 'patient.inactivity_deactivate'"
                )
            )
        ).mappings().all()
        audited_ids = {r["pid"] for r in audit_rows}
        assert str(stale_b) in audited_ids
        assert str(stale_a) not in audited_ids

    async def test_ws_publish_failure_does_not_roll_back(
        self, session: AsyncSession
    ) -> None:
        """A failing WS publish happens after commit; deactivation survives."""
        stale = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=400),
        )

        manager = ConnectionManager()
        manager.publish = AsyncMock(side_effect=RuntimeError("boom"))  # type: ignore[method-assign]

        deactivated = await sweep_once(session, threshold_s=300.0, manager=manager)
        assert deactivated == 1

        row = await session.execute(
            text("SELECT is_active FROM clinical.patients WHERE patient_id = :pid"),
            {"pid": stale},
        )
        assert row.scalar_one() is False

        manager.publish.assert_awaited_once_with(
            stale,
            {"type": "patient.deactivated", "data": {"patient_id": str(stale)}},
        )

    async def test_ws_publish_happens_after_commit(
        self, session: AsyncSession
    ) -> None:
        """Publish is only called after the outer transaction commits."""
        stale = await _insert_patient(
            session,
            last_measurement_at=datetime.now(timezone.utc) - timedelta(seconds=400),
        )

        manager = ConnectionManager()
        publish_calls: list[bool] = []

        async def _recording_publish(patient_id: UUID, message: dict) -> None:
            # If the transaction is not yet committed, the patient row
            # still reads as active from a separate connection.
            async with AsyncSessionLocal() as check:
                is_active = (
                    await check.execute(
                        text(
                            "SELECT is_active FROM clinical.patients "
                            "WHERE patient_id = :pid"
                        ),
                        {"pid": patient_id},
                    )
                ).scalar_one()
                publish_calls.append(is_active is False)

        manager.publish = _recording_publish  # type: ignore[method-assign]

        await sweep_once(session, threshold_s=300.0, manager=manager)

        assert publish_calls == [True]


class TestInactivityIntegration:
    """Cross-cutting tests with the ingest service."""

    async def test_ingest_advances_last_measurement_at(
        self, session: AsyncSession
    ) -> None:
        """An accepted batch refreshes last_measurement_at."""
        from sqlalchemy.dialects.postgresql import insert as pg_insert

        from app.services.crypto import encrypt_patient_number

        pid = uuid4()
        old_ts = datetime.now(timezone.utc) - timedelta(seconds=400)
        async with session.begin():
            cipher = await encrypt_patient_number(session, "1")
            await session.execute(
                pg_insert(PiiPatient).values(patient_id=pid, patient_number=cipher)
            )
            await session.execute(
                pg_insert(ClinicalPatient).values(
                    patient_id=pid,
                    device_model="watch",
                    os_version="1",
                    is_active=True,
                    bed_number=1,
                    last_measurement_at=old_ts,
                )
            )

        item = {
            "local_id": str(uuid4()),
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "heart_rate_bpm": 72,
            "spo2_percent": 98.0,
        }
        response = await upload_measurements(
            session,
            path_patient_id=pid,
            patient_number="1",
            raw_items=[item],
        )
        assert isinstance(response, BatchResponse)

        row = await session.execute(
            text(
                "SELECT last_measurement_at FROM clinical.patients "
                "WHERE patient_id = :pid"
            ),
            {"pid": pid},
        )
        new_ts = row.scalar_one()
        assert new_ts > old_ts
