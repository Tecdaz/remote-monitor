"""Patients HTTP routes (REQ-READ-02, registerPatient, getPatient,
deactivatePatient-reserved).

Four endpoints:

- ``POST /api/v1/patients`` \u2014 ``registerPatient``. Pre-registers a
  patient. 201 on success, 409 on duplicate ``patient_number``.
- ``GET /api/v1/patients`` \u2014 ``listPatients``. Active patients only.
- ``GET /api/v1/patients/{patient_id}`` \u2014 ``getPatient``. 404 if unknown.
- ``DELETE /api/v1/patients/{patient_id}`` \u2014 ``deactivatePatient``.
  Reserved URL; returns 501.

The watch usually auto-registers on the first ``uploadMeasurements``
(REQ-INGEST-07). ``POST /patients`` is the explicit pre-registration
path for admin onboarding.
"""
from __future__ import annotations

from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, Path, status
from sqlalchemy import text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db import get_session
from app.models import ClinicalPatient, PiiPatient
from app.schemas import Patient, PatientRegistration
from app.services.crypto import encrypt_patient_number

router = APIRouter(tags=["patients"])


# --- helpers ---------------------------------------------------------------


async def _decrypt_patient_number(session: AsyncSession, cipher: bytes) -> str:
    """Decrypt a single ``pii.patients.patient_number`` ciphertext."""
    result = await session.execute(
        text("SELECT pgp_sym_decrypt(:cipher, :key)"),
        {"cipher": cipher, "key": settings.pii_encryption_key},
    )
    return result.scalar_one()


def _row_to_patient(patient_id: UUID, patient_number: str, row) -> Patient:
    """Build a ``Patient`` from a ``clinical.patients`` row + decrypted
    ``patient_number``.
    """
    return Patient(
        patient_id=patient_id,
        patient_number=patient_number,
        device_model=row.device_model,
        os_version=row.os_version,
        created_at=row.created_at,
        is_active=row.is_active,
    )


# --- POST /patients --------------------------------------------------------


@router.post(
    "/api/v1/patients",
    response_model=Patient,
    status_code=status.HTTP_201_CREATED,
    responses={409: {"description": "Patient number already registered"}},
)
async def register_patient(
    payload: PatientRegistration,
    session: AsyncSession = Depends(get_session),
) -> Patient:
    """Pre-register a patient explicitly. The watch can also auto-
    register on first upload; this endpoint is for admin onboarding.
    """
    # Pre-check for duplicates. ``pgp_sym_encrypt`` is non-deterministic
    # (random IV per call) so the UNIQUE(patient_number) constraint on
    # the bytea ciphertext CANNOT detect duplicate plaintexts; the
    # check must compare on the decrypted value. There is a small
    # TOCTOU window before the insert below, acceptable for the PoC.
    existing = (
        await session.execute(
            text(
                "SELECT patient_id FROM pii.patients "
                "WHERE pgp_sym_decrypt(patient_number, :key) = :plain"
            ),
            {"plain": payload.patient_number, "key": settings.pii_encryption_key},
        )
    ).scalar_one_or_none()
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "detail": "Patient number already registered",
                "code": "duplicate_patient_number",
            },
        )

    new_id = uuid4()
    # The pre-check above already autobegun a transaction. Use that
    # same transaction for the inserts; an explicit ``begin()`` would
    # raise ``InvalidRequestError`` here.
    cipher = await encrypt_patient_number(session, payload.patient_number)
    try:
        await session.execute(
            pg_insert(PiiPatient).values(
                patient_id=new_id, patient_number=cipher
            )
        )
    except IntegrityError as exc:
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={
                "detail": "Patient number already registered",
                "code": "duplicate_patient_number",
            },
        ) from exc
    await session.execute(
        pg_insert(ClinicalPatient).values(
            patient_id=new_id,
            device_model=payload.device_model,
            os_version=payload.os_version,
        )
    )
    await session.commit()

    # Read back the server-assigned created_at. The session is now
    # post-commit; reading is safe.
    row = (
        await session.execute(
            text(
                "SELECT device_model, os_version, created_at, is_active "
                "FROM clinical.patients WHERE patient_id = :pid"
            ),
            {"pid": new_id},
        )
    ).one()
    return _row_to_patient(new_id, payload.patient_number, row)


# --- GET /patients ---------------------------------------------------------


@router.get("/api/v1/patients")
async def list_patients(
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Return active patients only (REQ-READ-02)."""
    rows = (
        await session.execute(
            text(
                "SELECT cp.patient_id, "
                "pgp_sym_decrypt(pp.patient_number, :key) AS patient_number, "
                "cp.device_model, cp.os_version, cp.created_at, cp.is_active "
                "FROM clinical.patients cp "
                "JOIN pii.patients pp ON cp.patient_id = pp.patient_id "
                "WHERE cp.is_active = true "
                "ORDER BY cp.created_at DESC"
            ),
            {"key": settings.pii_encryption_key},
        )
    ).mappings().all()
    items = [
        Patient(
            patient_id=r["patient_id"],
            patient_number=r["patient_number"],
            device_model=r["device_model"],
            os_version=r["os_version"],
            created_at=r["created_at"],
            is_active=r["is_active"],
        )
        for r in rows
    ]
    return {"items": items}


# --- GET /patients/{patient_id} --------------------------------------------


@router.get(
    "/api/v1/patients/{patient_id}",
    response_model=Patient,
    responses={404: {"description": "Patient not found"}},
)
async def get_patient(
    patient_id: UUID = Path(...),
    session: AsyncSession = Depends(get_session),
) -> Patient:
    """Return a single patient (active OR deactivated). 404 if unknown."""
    rows = (
        await session.execute(
            text(
                "SELECT cp.patient_id, "
                "pgp_sym_decrypt(pp.patient_number, :key) AS patient_number, "
                "cp.device_model, cp.os_version, cp.created_at, cp.is_active "
                "FROM clinical.patients cp "
                "JOIN pii.patients pp ON cp.patient_id = pp.patient_id "
                "WHERE cp.patient_id = :pid"
            ),
            {"pid": patient_id, "key": settings.pii_encryption_key},
        )
    ).mappings().all()
    if not rows:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"detail": "Patient not found", "code": "patient_not_found"},
        )
    r = rows[0]
    return Patient(
        patient_id=r["patient_id"],
        patient_number=r["patient_number"],
        device_model=r["device_model"],
        os_version=r["os_version"],
        created_at=r["created_at"],
        is_active=r["is_active"],
    )


# --- DELETE /patients/{patient_id} -----------------------------------------


@router.delete(
    "/api/v1/patients/{patient_id}",
    status_code=status.HTTP_501_NOT_IMPLEMENTED,
    responses={501: {"description": "Not implemented"}},
)
async def deactivate_patient(
    patient_id: UUID = Path(...),
) -> None:
    """Deactivate a patient (RESERVED). Out of scope for this change;
    URL locked to reserve the routing surface.
    """
    raise HTTPException(
        status_code=status.HTTP_501_NOT_IMPLEMENTED,
        detail={
            "detail": "Deactivate patient is reserved and not yet implemented",
            "code": "not_implemented",
        },
    )
