"""Tests for the patients HTTP surface (REQ-READ-02).

Covers:

- ``POST /api/v1/patients`` (``registerPatient``): 201 on success, 409
  on duplicate ``patient_number``.
- ``GET /api/v1/patients`` (``listPatients``): active patients only.
- ``GET /api/v1/patients/{patient_id}`` (``getPatient``): 200 with the
  decrypted patient, 404 on unknown id.
- ``DELETE /api/v1/patients/{patient_id}`` (``deactivatePatient``): 501
  reserved.
"""
from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

import pytest
from httpx import AsyncClient
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.services.crypto import encrypt_patient_number

PII_NUMBER_HEADER = "X-Patient-Number"


def _registration(patient_number: str = "P-00001") -> dict:
    return {
        "patient_number": patient_number,
        "device_model": "Samsung Galaxy Watch 4",
        "os_version": "Wear OS 6 (API 36)",
    }


async def _insert_patient_directly(
    session: AsyncSession,
    *,
    patient_number: str,
    is_active: bool = True,
    device_model: str = "unknown",
    os_version: str = "unknown",
) -> UUID:
    """Insert a patient row directly via the DB (bypasses the router).

    The first operation in a test is the ``session`` fixture yielding
    a fresh session; we then enter ``session.begin()`` immediately
    (without a prior ``session.execute``) to avoid the
    "A transaction is already begun" error.
    """
    from sqlalchemy.dialects.postgresql import insert as pg_insert
    from app.models import ClinicalPatient, PiiPatient

    pid = uuid4()
    async with session.begin():
        cipher = await encrypt_patient_number(session, patient_number)
        await session.execute(
            pg_insert(PiiPatient).values(patient_id=pid, patient_number=cipher)
        )
        await session.execute(
            pg_insert(ClinicalPatient).values(
                patient_id=pid,
                device_model=device_model,
                os_version=os_version,
                is_active=is_active,
            )
        )
    return pid


class TestPatientsRouter:
    """End-to-end HTTP tests for /api/v1/patients."""

    async def test_register_patient_returns_201_with_decrypted_number(
        self, client: AsyncClient
    ) -> None:
        """POST /patients -> 201, response carries decrypted patient_number."""
        response = await client.post(
            "/api/v1/patients", json=_registration("P-REG-1")
        )
        assert response.status_code == 201
        body = response.json()
        assert body["patient_number"] == "P-REG-1"
        assert body["device_model"] == "Samsung Galaxy Watch 4"
        assert body["is_active"] is True
        # patient_id is server-assigned.
        UUID(body["patient_id"])

    async def test_register_duplicate_patient_number_returns_409(
        self, client: AsyncClient
    ) -> None:
        """Same patient_number twice -> 409 duplicate_patient_number."""
        r1 = await client.post(
            "/api/v1/patients", json=_registration("P-DUP")
        )
        assert r1.status_code == 201
        r2 = await client.post(
            "/api/v1/patients", json=_registration("P-DUP")
        )
        assert r2.status_code == 409
        assert r2.json()["detail"]["code"] == "duplicate_patient_number"

    async def test_list_patients_returns_active_only(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """REQ-READ-02: deactivated patients are NOT in the list."""
        active_a = await _insert_patient_directly(
            session, patient_number="P-ACTIVE-A"
        )
        active_b = await _insert_patient_directly(
            session, patient_number="P-ACTIVE-B"
        )
        deactivated = await _insert_patient_directly(
            session, patient_number="P-DEACTIVATED", is_active=False
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
            session, patient_number="P-ONLY-DEACT", is_active=False
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
        """GET /patients/{id} returns the decrypted patient_number."""
        pid = await _insert_patient_directly(
            session, patient_number="P-GET-1"
        )
        await session.commit()

        response = await client.get(f"/api/v1/patients/{pid}")
        assert response.status_code == 200
        body = response.json()
        assert UUID(body["patient_id"]) == pid
        assert body["patient_number"] == "P-GET-1"
        assert body["is_active"] is True

    async def test_get_patient_works_for_deactivated(
        self,
        client: AsyncClient,
        session: AsyncSession,
    ) -> None:
        """Deactivated patient is still reachable via GET /{id}."""
        pid = await _insert_patient_directly(
            session, patient_number="P-DEACT", is_active=False
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
        pid = await _insert_patient_directly(
            session, patient_number="P-DEL-501"
        )
        await session.commit()

        response = await client.delete(f"/api/v1/patients/{pid}")
        assert response.status_code == 501
        assert response.json()["detail"]["code"] == "not_implemented"
