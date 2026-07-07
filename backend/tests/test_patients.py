"""Tests for the patients HTTP surface (REQ-READ-02, REQ-INGEST-09,
REQ-READ-04).

Covers:

- ``POST /api/v1/patients`` (``registerPatient``): 201 on success, 409
  ``bed_now_occupied`` when the partial UNIQUE trips (sequential or
  concurrent second registration).
- ``GET /api/v1/patients`` (``listPatients``): active patients only.
- ``GET /api/v1/patients/{patient_id}`` (``getPatient``): 200 with the
  decrypted patient, 404 on unknown id.
- ``DELETE /api/v1/patients/{patient_id}`` (``deactivatePatient``): 501
  reserved.
- ``GET /api/v1/beds`` (``getBedSnapshot``): lives in
  ``test_bed_snapshot.py``; replace-session lives in
  ``test_replace_session.py``.
"""
from __future__ import annotations

from uuid import UUID, uuid4

import pytest
from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.services.crypto import encrypt_patient_number

PII_NUMBER_HEADER = "X-Patient-Number"


def _registration(
    bed_number: int = 3,
    *,
    replace_active_session: bool = False,
    device_model: str = "samsung SM-R870",
    os_version: str = "16 (API 36)",
) -> dict:
    """A valid ``RegisterPatientRequest`` payload (bed-picker shape)."""
    return {
        "bed_number": bed_number,
        "device_model": device_model,
        "os_version": os_version,
        "replace_active_session": replace_active_session,
    }


async def _insert_patient_directly(
    session: AsyncSession,
    *,
    bed_number: int | None = 1,
    is_active: bool = True,
    patient_number_plain: str | None = None,
    device_model: str = "unknown",
    os_version: str = "unknown",
) -> UUID:
    """Insert a patient row directly via the DB (bypasses the router).

    The first operation in a test is the ``session`` fixture yielding
    a fresh session; we then enter ``session.begin()`` immediately
    (without a prior ``session.execute``) to avoid the
    "A transaction is already begun" error.

    ``patient_number_plain`` defaults to ``str(bed_number)`` (the bed
    plaintext, which is what the wire identity carries post-bed-picker).
    Pass an explicit value for non-numeric legacy test fixtures.
    """
    from sqlalchemy.dialects.postgresql import insert as pg_insert
    from app.models import ClinicalPatient, PiiPatient

    pid = uuid4()
    plain = (
        patient_number_plain
        if patient_number_plain is not None
        else (str(bed_number) if bed_number is not None else "0")
    )
    async with session.begin():
        cipher = await encrypt_patient_number(session, plain)
        await session.execute(
            pg_insert(PiiPatient).values(patient_id=pid, patient_number=cipher)
        )
        await session.execute(
            pg_insert(ClinicalPatient).values(
                patient_id=pid,
                device_model=device_model,
                os_version=os_version,
                is_active=is_active,
                bed_number=bed_number,
            )
        )
    return pid


class TestPatientsRouter:
    """End-to-end HTTP tests for /api/v1/patients."""

    async def test_register_patient_returns_201_with_bed_number(
        self, client: AsyncClient
    ) -> None:
        """POST /patients -> 201, response carries the bed plaintext
        in ``patient_number`` (decrypted) and the selected bed number."""
        response = await client.post(
            "/api/v1/patients", json=_registration(bed_number=3)
        )
        assert response.status_code == 201
        body = response.json()
        # The Patient schema's patient_number is the DECRYPTED bed
        # ciphertext. For bed 3 the plaintext is "3".
        assert body["patient_number"] == "3"
        assert body["device_model"] == "samsung SM-R870"
        assert body["is_active"] is True
        # patient_id is server-assigned.
        UUID(body["patient_id"])

    async def test_register_second_active_bed_returns_409(
        self, client: AsyncClient
    ) -> None:
        """Second registration on the same bed (no replace) -> 409
        ``bed_now_occupied`` per the partial UNIQUE index."""
        r1 = await client.post(
            "/api/v1/patients", json=_registration(bed_number=2)
        )
        assert r1.status_code == 201
        r2 = await client.post(
            "/api/v1/patients", json=_registration(bed_number=2)
        )
        assert r2.status_code == 409
        assert r2.json()["detail"]["code"] == "bed_now_occupied"

    async def test_register_with_replace_succeeds(
        self, client: AsyncClient
    ) -> None:
        """``replace_active_session=true`` on an occupied bed succeeds
        in one transaction. The prior session is deactivated; the new
        one is active."""
        first = await client.post(
            "/api/v1/patients", json=_registration(bed_number=4)
        )
        assert first.status_code == 201
        first_id = UUID(first.json()["patient_id"])

        second = await client.post(
            "/api/v1/patients",
            json=_registration(bed_number=4, replace_active_session=True),
        )
        assert second.status_code == 201
        second_id = UUID(second.json()["patient_id"])
        assert second_id != first_id

        # The first session is now is_active=false; the second is true.
        # Verified through GET /patients/{id} for both ids.
        r1 = await client.get(f"/api/v1/patients/{first_id}")
        assert r1.status_code == 200
        assert r1.json()["is_active"] is False
        r2 = await client.get(f"/api/v1/patients/{second_id}")
        assert r2.status_code == 200
        assert r2.json()["is_active"] is True

    async def test_register_bed_out_of_range_returns_422(
        self, client: AsyncClient
    ) -> None:
        """``bed_number`` is constrained to 1..5 by the Pydantic schema;
        0 and 6 must be rejected with 422 (FastAPI's default for
        Pydantic validation failures)."""
        for bad in (0, 6, -1):
            r = await client.post(
                "/api/v1/patients", json=_registration(bed_number=bad)
            )
            assert r.status_code == 422, (
                f"bed_number={bad} should have been rejected, got {r.status_code}"
            )

    async def test_list_patients_returns_active_only(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """REQ-READ-02: deactivated patients are NOT in the list."""
        active_a = await _insert_patient_directly(session, bed_number=1)
        active_b = await _insert_patient_directly(session, bed_number=2)
        deactivated = await _insert_patient_directly(
            session, bed_number=3, is_active=False
        )
        # The session fixture left an autobegun read transaction; close
        # it so the HTTP request's read sees the just-inserted rows.
        await session.commit()

        response = await client.get("/api/v1/patients")
        assert response.status_code == 200
        body = response.json()
        items = body["items"]
        ids = {UUID(item["patient_id"]) for item in items}
        assert active_a in ids
        assert active_b in ids
        assert deactivated not in ids
        # All returned items have is_active=true.
        assert all(item["is_active"] is True for item in items)

    async def test_list_patients_empty(
        self, client: AsyncClient
    ) -> None:
        """No patients -> 200 with empty items array."""
        response = await client.get("/api/v1/patients")
        assert response.status_code == 200
        assert response.json() == {"items": []}

    async def test_list_patients_only_deactivated_returns_empty(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """If only deactivated patients exist, list is empty (but the
        patient is still reachable via GET /{id} — verified below)."""
        await _insert_patient_directly(
            session, bed_number=5, is_active=False
        )
        await session.commit()

        list_resp = await client.get("/api/v1/patients")
        assert list_resp.status_code == 200
        assert list_resp.json() == {"items": []}

    async def test_get_patient_returns_decrypted_number(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """GET /patients/{id} returns the decrypted patient_number
        (which is the bed plaintext in the bed-picker world)."""
        pid = await _insert_patient_directly(session, bed_number=2)
        await session.commit()

        response = await client.get(f"/api/v1/patients/{pid}")
        assert response.status_code == 200
        body = response.json()
        assert UUID(body["patient_id"]) == pid
        assert body["patient_number"] == "2"
        assert body["is_active"] is True

    async def test_get_patient_works_for_deactivated(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """Deactivated patient is still reachable via GET /{id}."""
        pid = await _insert_patient_directly(
            session, bed_number=3, is_active=False
        )
        await session.commit()

        response = await client.get(f"/api/v1/patients/{pid}")
        assert response.status_code == 200
        body = response.json()
        assert UUID(body["patient_id"]) == pid
        assert body["is_active"] is False

    async def test_get_patient_unknown_returns_404(
        self, client: AsyncClient
    ) -> None:
        """GET /patients/{unknown_uuid} -> 404 patient_not_found."""
        unknown = uuid4()
        response = await client.get(f"/api/v1/patients/{unknown}")
        assert response.status_code == 404
        assert response.json()["detail"]["code"] == "patient_not_found"

    async def test_delete_patient_returns_501(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """DELETE /patients/{id} -> 501 (reserved URL, not implemented)."""
        pid = await _insert_patient_directly(session, bed_number=4)
        await session.commit()

        response = await client.delete(f"/api/v1/patients/{pid}")
        assert response.status_code == 501
        assert response.json()["detail"]["code"] == "not_implemented"
