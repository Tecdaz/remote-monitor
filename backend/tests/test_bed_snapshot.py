"""Tests for ``GET /api/v1/beds`` (REQ-READ-04, T2.4).

Covers:

- The snapshot returns 5 entries, one per hardcoded bed 1..5.
- Occupancy state reflects the partial-UNIQUE + is_active=true view
  of ``clinical.patients``.
- The response is consistent under concurrent updates (a snapshot
  read interleaved with a deactivate never returns inconsistent
  state — at most one active row per bed holds at any commit
  boundary).

These tests exercise the production HTTP route through the
ASGI-in-process ``client`` fixture from ``tests/conftest.py``.
"""
from __future__ import annotations

import asyncio
from uuid import UUID, uuid4

import pytest
from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.services.crypto import encrypt_patient_number


async def _seed_active_session(
    session: AsyncSession, *, bed_number: int
) -> UUID:
    """Insert a single active session for ``bed_number`` directly via
    the DB (bypasses the router — the snapshot is a read endpoint, so
    there's no need to go through the production path)."""
    from sqlalchemy.dialects.postgresql import insert as pg_insert
    from app.models import ClinicalPatient, PiiPatient

    pid = uuid4()
    async with session.begin():
        cipher = await encrypt_patient_number(session, str(bed_number))
        await session.execute(
            pg_insert(PiiPatient).values(patient_id=pid, patient_number=cipher)
        )
        await session.execute(
            pg_insert(ClinicalPatient).values(
                patient_id=pid,
                device_model="samsung SM-R870",
                os_version="16 (API 36)",
                is_active=True,
                bed_number=bed_number,
            )
        )
    return pid


class TestBedSnapshot:
    """REQ-READ-04: GET /api/v1/beds."""

    async def test_returns_five_beds_with_occupancy(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """Seed 3 active sessions across beds 1/2/4; assert GET returns
        5 entries; occupied beds have ``is_occupied=true`` and a
        non-null ``current_patient_id``; free beds have
        ``is_occupied=false`` and ``current_patient_id=null``."""
        # 3 active rows + 1 deactivated (bed 2's prior) + 0 for bed 5.
        bed1_pid = await _seed_active_session(session, bed_number=1)
        bed2_pid = await _seed_active_session(session, bed_number=2)
        bed4_pid = await _seed_active_session(session, bed_number=4)
        # Deactivate a prior session on bed 2; it must NOT appear as
        # occupied in the snapshot.
        await session.execute(
            text(
                "INSERT INTO clinical.patients "
                "(patient_id, device_model, os_version, is_active, bed_number) "
                "VALUES (gen_random_uuid(), 'x', 'y', false, 2)"
            )
        )
        await session.commit()

        response = await client.get("/api/v1/beds")
        assert response.status_code == 200
        body = response.json()
        assert len(body) == 5, f"expected 5 beds, got {len(body)}"

        by_bed = {entry["bed_number"]: entry for entry in body}
        # Hardcoded 1..5
        assert sorted(by_bed.keys()) == [1, 2, 3, 4, 5]

        # Beds 1, 2, 4 are occupied with the right patient UUIDs.
        assert by_bed[1]["is_occupied"] is True
        assert UUID(by_bed[1]["current_patient_id"]) == bed1_pid
        assert by_bed[2]["is_occupied"] is True
        assert UUID(by_bed[2]["current_patient_id"]) == bed2_pid
        assert by_bed[4]["is_occupied"] is True
        assert UUID(by_bed[4]["current_patient_id"]) == bed4_pid

        # Beds 3 and 5 are free.
        for free in (3, 5):
            assert by_bed[free]["is_occupied"] is False, (
                f"bed {free} should be free: {by_bed[free]}"
            )
            assert by_bed[free]["current_patient_id"] is None, (
                f"bed {free} current_patient_id should be null: {by_bed[free]}"
            )

    async def test_snapshot_empty_when_no_sessions(
        self, client: AsyncClient
    ) -> None:
        """With an empty backend, the snapshot returns 5 free beds."""
        response = await client.get("/api/v1/beds")
        assert response.status_code == 200
        body = response.json()
        assert len(body) == 5
        for entry in body:
            assert entry["is_occupied"] is False
            assert entry["current_patient_id"] is None

    async def test_snapshot_consistent_under_concurrent_updates(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """Fire GET while simultaneously UPDATE-ing a session's
        ``is_active=false``; assert the response is either pre-update
        (snapshot stale) or post-update (snapshot fresh) but never
        inconsistent (e.g. a bed appearing both occupied and free
        in the same response).

        The partial UNIQUE ``ux_clinical_patients_active_bed`` plus
        the snapshot's single-statement LEFT JOIN guarantee the
        consistency surface: at any commit boundary, the snapshot
        reflects a coherent set of at-most-one-active-per-bed
        states. The race we exercise here is a session in flight
        (uncommitted UPDATE) — the snapshot reading from another
        connection sees a consistent state because PostgreSQL's
        READ COMMITTED isolation level applies the snapshot to
        each statement independently.
        """
        # Seed an active session on bed 3.
        await _seed_active_session(session, bed_number=3)
        await session.commit()

        async def do_deactivate() -> None:
            """Deactivate bed 3 from a fresh session."""
            from app.db import AsyncSessionLocal
            async with AsyncSessionLocal() as s:
                async with s.begin():
                    await s.execute(
                        text(
                            "UPDATE clinical.patients "
                            "SET is_active = false "
                            "WHERE bed_number = 3 AND is_active = true"
                        )
                    )

        # Fire the snapshot read concurrently with the deactivate.
        # Repeat the race a handful of times; the invariant must hold
        # for every iteration.
        for _ in range(5):
            # Re-activate bed 3 (the prior deactivate may have committed).
            await session.execute(
                text(
                    "UPDATE clinical.patients "
                    "SET is_active = true "
                    "WHERE bed_number = 3"
                )
            )
            await session.commit()

            # Race: read the snapshot while a deactivate is in flight.
            snapshot_task = asyncio.create_task(client.get("/api/v1/beds"))
            deactivate_task = asyncio.create_task(do_deactivate())
            snapshot_resp, _ = await asyncio.gather(
                snapshot_task, deactivate_task
            )
            assert snapshot_resp.status_code == 200
            body = snapshot_resp.json()
            by_bed = {entry["bed_number"]: entry for entry in body}

            bed3 = by_bed[3]
            # Invariant: (is_occupied == (current_patient_id is not None)).
            # NEVER both occupied+null or free+uuid.
            assert bed3["is_occupied"] == (
                bed3["current_patient_id"] is not None
            ), f"inconsistent snapshot entry: {bed3}"

            # And the rest of the beds are coherent.
            for n, entry in by_bed.items():
                if n == 3:
                    continue
                assert entry["is_occupied"] is False
                assert entry["current_patient_id"] is None
