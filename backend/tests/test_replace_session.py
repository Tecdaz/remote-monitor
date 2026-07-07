"""Tests for the transactional replace-session path (REQ-INGEST-09,
D16, T2.5).

Covers:

- Concurrent GREEN-path registrations on the same bed: exactly one
  returns 201, the rest return 409 ``bed_now_occupied``. The
  ``asyncio.Barrier(5)`` (D29) synchronizes the coroutine starts so
  PostgreSQL sees real concurrency (not the asyncio scheduler
  serializing them).
- Atomic REPLACE: a single replace-mode POST deactivates the prior
  active session and inserts a new one in one transaction. The
  invariant is "at most one active session per bed" at every commit
  boundary.
- REPLACE + concurrent READ: the prior deactivate and the new insert
  are visible together; the partial UNIQUE prevents a second
  concurrent REPLACE from succeeding.

These tests use the production ``client`` fixture (httpx ASGI in-
process) and a real PostgreSQL instance — partial UNIQUE indexes
are PG-specific and the test infrastructure is configured for a real
DB per ``tests/conftest.py``.
"""
from __future__ import annotations

import asyncio
from uuid import UUID

import pytest
from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


def _registration(bed_number: int, *, replace: bool = False) -> dict:
    return {
        "bed_number": bed_number,
        "device_model": "samsung SM-R870",
        "os_version": "16 (API 36)",
        "replace_active_session": replace,
    }


class TestReplaceSession:
    """REQ-INGEST-09 + D16 + D29: transactional replace semantics."""

    async def test_concurrent_green_post_returns_409(
        self, client: AsyncClient
    ) -> None:
        """5 concurrent POSTs to bed 1 with ``replace_active_session=false``
        must yield exactly 1 × 201 and 4 × 409 ``bed_now_occupied``.
        ZERO 500s (D29 asyncio.Barrier prevents scheduler serialization
        that would otherwise mask the race).
        """
        n = 5
        barrier = asyncio.Barrier(n)

        async def post_bed_one() -> int:
            await barrier.wait()
            resp = await client.post(
                "/api/v1/patients", json=_registration(bed_number=1)
            )
            return resp.status_code

        results = await asyncio.gather(*[post_bed_one() for _ in range(n)])
        statuses = sorted(results)
        assert statuses.count(201) == 1, (
            f"expected exactly 1 × 201, got {statuses!r}"
        )
        assert statuses.count(409) == 4, (
            f"expected exactly 4 × 409, got {statuses!r}"
        )
        assert 500 not in statuses, (
            f"no 500 leaks expected; got {statuses!r}"
        )

    async def test_atomic_replace_holds_invariant(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """``replace_active_session=true`` succeeds; subsequent reads
        show the new session active, the old one inactive, and the
        audit log has both a ``patient.replace.deactivate`` and a
        ``patient.replace.insert`` row (T2.9 — HIPAA-like posture)."""
        # Seed the first session.
        first = await client.post(
            "/api/v1/patients", json=_registration(bed_number=2)
        )
        assert first.status_code == 201
        first_id = UUID(first.json()["patient_id"])
        await session.commit()

        # Replace it.
        second = await client.post(
            "/api/v1/patients",
            json=_registration(bed_number=2, replace=True),
        )
        assert second.status_code == 201
        second_id = UUID(second.json()["patient_id"])
        assert second_id != first_id
        await session.commit()

        # Verify the new session is active via GET /patients/{id}.
        get_new = await client.get(f"/api/v1/patients/{second_id}")
        assert get_new.status_code == 200
        assert get_new.json()["is_active"] is True
        assert get_new.json()["patient_number"] == "2"

        # Verify the prior session is now inactive.
        get_old = await client.get(f"/api/v1/patients/{first_id}")
        assert get_old.status_code == 200
        assert get_old.json()["is_active"] is False

        # Verify the snapshot sees exactly ONE active row for bed 2.
        snapshot = await client.get("/api/v1/beds")
        assert snapshot.status_code == 200
        by_bed = {e["bed_number"]: e for e in snapshot.json()}
        assert by_bed[2]["is_occupied"] is True
        assert UUID(by_bed[2]["current_patient_id"]) == second_id

        # Verify the audit log emitted both rows (T2.9). Use a fresh
        # connection because the session fixture already committed
        # but we want a clean read.
        async with session.bind.connect() as conn:
            deactivate_rows = (
                await conn.execute(
                    text(
                        "SELECT context FROM audit.audit_log "
                        "WHERE action = 'patient.replace.deactivate'"
                    )
                )
            ).mappings().all()
            insert_rows = (
                await conn.execute(
                    text(
                        "SELECT context FROM audit.audit_log "
                        "WHERE action = 'patient.replace.insert'"
                    )
                )
            ).mappings().all()

        assert len(deactivate_rows) == 1, (
            f"expected 1 deactivate audit row, got {len(deactivate_rows)}"
        )
        assert deactivate_rows[0]["context"]["bed_number"] == 2
        assert first_id == UUID(
            deactivate_rows[0]["context"]["deactivated_patient_ids"][0]
        )

        assert len(insert_rows) == 1, (
            f"expected 1 insert audit row, got {len(insert_rows)}"
        )
        assert insert_rows[0]["context"]["bed_number"] == 2
        assert insert_rows[0]["context"]["new_patient_id"] == str(second_id)

    async def test_replace_session_succeeds_with_barrier(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """Fire concurrent REPLACE + read; the invariant "at most one
        active session per bed" holds at every observable state.

        Sequence:
        - Seed the initial session.
        - Fire 3 concurrent REPLACE-mode POSTs + 1 concurrent snapshot
          read; assert the snapshot never observes BOTH a stale
          ``is_occupied=true`` with a different patient_id AND a
          conflicting new insert in flight.
        - After the burst, exactly one active session exists.
        """
        # Seed.
        first = await client.post(
            "/api/v1/patients", json=_registration(bed_number=4)
        )
        assert first.status_code == 201
        first_id = UUID(first.json()["patient_id"])
        await session.commit()

        n_replaces = 3
        barrier = asyncio.Barrier(n_replaces + 1)

        async def do_replace() -> int:
            await barrier.wait()
            resp = await client.post(
                "/api/v1/patients",
                json=_registration(bed_number=4, replace=True),
            )
            return resp.status_code

        async def do_read() -> dict:
            await barrier.wait()
            resp = await client.get("/api/v1/beds")
            assert resp.status_code == 200
            return resp.json()

        tasks = [asyncio.create_task(do_replace()) for _ in range(n_replaces)]
        tasks.append(asyncio.create_task(do_read()))
        results = await asyncio.gather(*tasks)
        statuses = results[:n_replaces]
        snapshot = results[n_replaces]

        # Exactly one REPLACE must win (201) and the rest must collide
        # on the partial UNIQUE (409). The snapshot is a read and
        # always succeeds.
        assert sorted(statuses).count(201) == 1, (
            f"expected 1 winning REPLACE, got {statuses!r}"
        )
        assert sorted(statuses).count(409) == n_replaces - 1, (
            f"expected {n_replaces - 1} losing REPLACEs, got {statuses!r}"
        )

        # The snapshot must be internally consistent: at most one
        # active row per bed, and the bed-4 entry's
        # ``current_patient_id`` (if non-null) is a valid UUID.
        by_bed = {e["bed_number"]: e for e in snapshot}
        bed4 = by_bed[4]
        if bed4["is_occupied"]:
            UUID(bed4["current_patient_id"])  # must parse
        else:
            assert bed4["current_patient_id"] is None

        # After the burst: exactly ONE active row for bed 4.
        await session.commit()  # ensure prior tx are settled
        active_count = (
            await session.execute(
                text(
                    "SELECT COUNT(*) FROM clinical.patients "
                    "WHERE bed_number = 4 AND is_active = true"
                )
            )
        ).scalar_one()
        assert active_count == 1, (
            f"expected exactly 1 active row for bed 4, got {active_count}"
        )

        # The original first_id is now inactive.
        first_now = (
            await session.execute(
                text(
                    "SELECT is_active FROM clinical.patients "
                    "WHERE patient_id = :pid"
                ),
                {"pid": first_id},
            )
        ).scalar_one()
        assert first_now is False
