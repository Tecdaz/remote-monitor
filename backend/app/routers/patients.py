"""Patients HTTP routes (REQ-READ-02, registerPatient, getPatient,
deactivatePatient-reserved, getBedSnapshot, REQ-INGEST-09).

Endpoints:

- ``POST /api/v1/patients`` — ``registerPatient``. Pre-registers a
  patient against a selected bed (1..5). 201 on success, 409
  ``bed_now_occupied`` when the partial UNIQUE trips (concurrent
  second registration or GREEN path against an active bed).
- ``GET /api/v1/patients`` — ``listPatients``. Active patients only.
- ``GET /api/v1/patients/{patient_id}`` — ``getPatient``. 404 if unknown.
- ``DELETE /api/v1/patients/{patient_id}`` — ``deactivatePatient``.
  Reserved URL; returns 501.
- ``GET /api/v1/beds`` — ``getBedSnapshot`` (REQ-READ-04). Returns
  five ``BedSnapshot`` entries (hardcoded 1..5).

The watch usually auto-registers on the first ``uploadMeasurements``
(REQ-INGEST-07). ``POST /patients`` is the explicit pre-registration
path the watch calls AFTER the operator confirms the bed selection in
the picker dialog.
"""
from __future__ import annotations

from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, HTTPException, Path, status
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db import get_session
from app.models import ClinicalPatient, PiiPatient
from app.schemas import BedSnapshot, Patient, RegisterPatientRequest
from app.services.audit import write_audit_log
from app.services.crypto import encrypt_patient_number

router = APIRouter(tags=["patients"])

# Hardcoded bed universe for the PoC. A future non-PoC build would
# back this with a `bed` table; today it is a constant.
_BED_RANGE: tuple[int, ...] = (1, 2, 3, 4, 5)

# Partial-UNIQUE constraint name on ``clinical.patients`` (D11). The
# IntegrityError catch in the register handler matches on this name to
# map concurrent-second-registration / occupied-bed attempts to 409
# ``bed_now_occupied`` instead of leaking a 500.
_BED_OCCUPIED_CONSTRAINT = "ux_clinical_patients_active_bed"

# Audit action strings (stable values; the ``audit.audit_log.action``
# column is intentionally unindexed for the PoC).
_AUDIT_ACTION_REPLACE_DEACTIVATE = "patient.replace.deactivate"
_AUDIT_ACTION_REPLACE_INSERT = "patient.replace.insert"


# --- helpers ---------------------------------------------------------------


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
        last_measurement_at=row.last_measurement_at,
    )


# --- POST /patients --------------------------------------------------------


@router.post(
    "/api/v1/patients",
    response_model=Patient,
    status_code=status.HTTP_201_CREATED,
    responses={
        409: {
            "description": (
                "Bed is already occupied (code: \"bed_now_occupied\"). "
                "Caller can retry by re-fetching the snapshot and either "
                "picking another bed or setting "
                "`replace_active_session: true` (atomic replace)."
            )
        }
    },
)
async def register_patient(
    payload: RegisterPatientRequest,
    session: AsyncSession = Depends(get_session),
) -> Patient:
    """Register a patient against a selected bed (1..5).

    GREEN path (``replace_active_session=False`` default): the partial
    UNIQUE index ``ux_clinical_patients_active_bed`` enforces "at most
    one active session per bed". A second concurrent attempt, or a
    non-replace attempt against an active bed, trips the partial
    UNIQUE and is mapped to ``409 bed_now_occupied``.

    REPLACE path (``replace_active_session=True``): a single DB
    transaction first UPDATEs the prior active session's
    ``is_active=false`` for the same ``bed_number``, then INSERTs a
    fresh ``pii.patients`` + ``clinical.patients`` pair. A concurrent
    second REPLACE trips the partial UNIQUE on the INSERT and is
    mapped to ``409 bed_now_occupied`` (no 500 leak).
    """
    bed_number = payload.bed_number
    new_patient_id = uuid4()
    new_clinical: ClinicalPatient | None = None

    # Use explicit try/except + commit (NOT ``async with session.begin():``).
    # The previous shape of the handler relied on the autobegun
    # transaction from the request-scoped session and explicitly called
    # ``session.rollback()`` in the catch, then ``session.commit()`` at
    # the end. We keep that pattern here because the partial-UNIQUE
    # IntegrityError is best caught at the same level as the rollback
    # call: chaining the IntegrityError as ``__cause__`` of an
    # HTTPException and re-raising it through ``async with
    # session.begin():``'s context-manager __aexit__ can cause
    # SQLAlchemy to re-raise the original IntegrityError on the way out
    # instead of the intended HTTPException.
    try:
        if payload.replace_active_session:
            # REQ-INGEST-09: atomically deactivate the prior active
            # session for this bed, then insert the new pair. Both
            # writes commit together (or roll back together).
            deactivate_result = await session.execute(
                text(
                    "UPDATE clinical.patients "
                    "SET is_active = false "
                    "WHERE bed_number = :n AND is_active = true "
                    "RETURNING patient_id"
                ),
                {"n": bed_number},
            )
            deactivated_ids = [row.patient_id for row in deactivate_result]
            if deactivated_ids:
                await write_audit_log(
                    session,
                    actor="watch",
                    action=_AUDIT_ACTION_REPLACE_DEACTIVATE,
                    count=len(deactivated_ids),
                    context={
                        "bed_number": bed_number,
                        "deactivated_patient_ids": [
                            str(pid) for pid in deactivated_ids
                        ],
                    },
                )

        # Encrypt the bed plaintext (e.g. ``"3"``) into the bytea
        # ciphertext column. We do NOT skip the encrypt on
        # REPLACE; the new patient row gets a fresh ciphertext
        # (non-deterministic pgp_sym_encrypt, that's fine).
        cipher = await encrypt_patient_number(session, str(bed_number))
        new_pii = PiiPatient(patient_id=new_patient_id, patient_number=cipher)
        session.add(new_pii)
        # Flush so ``new_pii.patient_id`` and ``created_at`` are
        # populated by the server defaults BEFORE the clinical
        # row references them.
        await session.flush()
        new_clinical = ClinicalPatient(
            patient_id=new_pii.patient_id,
            device_model=payload.device_model,
            os_version=payload.os_version,
            is_active=True,
            bed_number=bed_number,
        )
        session.add(new_clinical)
        await session.flush()

        if payload.replace_active_session:
            # Audit the new active row AFTER both inserts have
            # flushed (so we can record the server-assigned UUID).
            await write_audit_log(
                session,
                actor="watch",
                action=_AUDIT_ACTION_REPLACE_INSERT,
                count=1,
                context={
                    "bed_number": bed_number,
                    "new_patient_id": str(new_pii.patient_id),
                },
            )
    except IntegrityError as exc:
        await session.rollback()
        # asyncpg-translated IntegrityErrors do NOT carry a psycopg2-style
        # ``diag`` attribute; the constraint name is only in the message.
        # Parse it as a substring match. The partial UNIQUE index name
        # is the canonical signal for "this bed is now occupied" — any
        # other UNIQUE/CHECK violation is a real bug and must surface
        # as a 500.
        message = str(exc.orig) if exc.orig is not None else str(exc)
        if _BED_OCCUPIED_CONSTRAINT in message:
            # Concurrent second registration, or a non-replace
            # attempt against an active bed.
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail={
                    "detail": "Bed is now occupied",
                    "code": "bed_now_occupied",
                },
            )
        # Other IntegrityErrors (real bugs — schema mismatch,
        # unexpected UNIQUE violation) must surface as 500 so the
        # operator sees a real failure rather than a misleading
        # "bed occupied".
        raise
    else:
        await session.commit()

    # Read back the server-assigned created_at via a fresh query
    # (mirrors the previous implementation's pattern; safe post-commit).
    assert new_clinical is not None  # for type checkers
    row = (
        await session.execute(
            text(
                "SELECT device_model, os_version, created_at, is_active, "
                "last_measurement_at FROM clinical.patients WHERE patient_id = :pid"
            ),
            {"pid": new_patient_id},
        )
    ).one()
    return _row_to_patient(new_patient_id, str(bed_number), row)


# --- GET /beds -------------------------------------------------------------


@router.get(
    "/api/v1/beds",
    response_model=list[BedSnapshot],
    responses={503: {"description": "backend degraded"}},
)
async def get_bed_snapshot(
    session: AsyncSession = Depends(get_session),
) -> list[BedSnapshot]:
    """Per-bed occupancy snapshot (REQ-READ-04).

    Returns five ``BedSnapshot`` entries (one per bed in the hardcoded
    1..5 range). The query is a single SELECT with a 5-row VALUES
    CTE LEFT JOIN'd to ``clinical.patients`` (no N+1). Under
    ``is_active = true`` filtering + the partial UNIQUE index, the
    snapshot is consistent under concurrent updates: the partial
    UNIQUE ensures at most one active row per bed, so the LEFT JOIN
    never matches more than one row per bed slot.
    """
    rows = (
        await session.execute(
            text(
                "SELECT b.n AS bed_number, "
                "       (cp.patient_id IS NOT NULL) AS is_occupied, "
                "       cp.patient_id AS current_patient_id "
                "FROM (VALUES (1), (2), (3), (4), (5)) AS b(n) "
                "LEFT JOIN clinical.patients cp "
                "  ON cp.bed_number = b.n AND cp.is_active = true "
                "ORDER BY b.n"
            )
        )
    ).mappings().all()

    # Sanity check: the snapshot is always length 5 (the CTE is
    # fixed; if it ever returns a different length, the underlying
    # CTE was edited and the tests should scream loudly).
    if len(rows) != len(_BED_RANGE):
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "detail": "Snapshot query returned unexpected row count",
                "code": "snapshot_shape_error",
            },
        )

    return [
        BedSnapshot(
            bed_number=int(r["bed_number"]),
            is_occupied=bool(r["is_occupied"]),
            current_patient_id=r["current_patient_id"],
        )
        for r in rows
    ]


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
                "cp.device_model, cp.os_version, cp.created_at, cp.is_active, "
                "cp.last_measurement_at "
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
            last_measurement_at=r["last_measurement_at"],
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
                "cp.device_model, cp.os_version, cp.created_at, cp.is_active, "
                "cp.last_measurement_at "
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
        last_measurement_at=r["last_measurement_at"],
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
