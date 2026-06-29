"""Measurements HTTP routes (REQ-INGEST-01..05, REQ-READ-01, REQ-READ-03).

Two endpoints:

- ``POST /api/v1/patients/{patient_id}/measurements`` \u2014
  ``uploadMeasurements``. The ``X-Patient-Number`` header is enforced
  by ``require_patient_number_header``; the body is a 1-1000 item
  list of ``MeasurementBatch`` dicts; per-item Pydantic validation
  happens inside the service so bad items are rejected individually
  rather than failing the whole batch.
- ``GET /api/v1/patients/{patient_id}/measurements`` \u2014
  ``listMeasurements``. Cursor-paginated (DESC by ``(timestamp, id)``
  for stable keyset pagination). 404 with ``Problem`` if the patient
  is unknown.
"""
from __future__ import annotations

import base64
from datetime import datetime
from typing import Any
from uuid import UUID

from fastapi import APIRouter, Body, Depends, Header, HTTPException, Path, status
from sqlalchemy import select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.dependencies import require_patient_number_header
from app.models import ClinicalMeasurement, ClinicalPatient
from app.schemas import BatchResponse, Measurement, MeasurementPage
from app.services import ingest as ingest_service

router = APIRouter(tags=["measurements"])

MAX_BATCH_SIZE = 1000
DEFAULT_LIMIT = 100
MAX_LIMIT = 1000


# --- cursor helpers --------------------------------------------------------


def _encode_cursor(ts: datetime, row_id: UUID) -> str:
    """Opaque cursor: base64url of ``f"{ts.isoformat()}|{row_id}"``."""
    raw = f"{ts.isoformat()}|{row_id}".encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _decode_cursor(cursor: str) -> tuple[datetime, UUID]:
    padding = "=" * (-len(cursor) % 4)
    raw = base64.urlsafe_b64decode(cursor + padding).decode()
    ts_str, id_str = raw.split("|", 1)
    return datetime.fromisoformat(ts_str), UUID(id_str)


# --- POST ------------------------------------------------------------------


@router.post(
    "/api/v1/patients/{patient_id}/measurements",
    response_model=BatchResponse,
    responses={
        403: {"description": "Missing or mismatched X-Patient-Number"},
        413: {"description": "Batch exceeds 1000 items"},
    },
)
async def upload_measurements(
    patient_id: UUID = Path(...),
    body: list[dict[str, Any]] = Body(
        ...,
        description="1-1000 MeasurementBatch items (per-item validation in service).",
    ),
    patient_number: str = Depends(require_patient_number_header),
    device_model: str = Header("unknown", alias="X-Device-Model"),
    os_version: str = Header("unknown", alias="X-OS-Version"),
    session: AsyncSession = Depends(get_session),
) -> BatchResponse:
    """Persist a batch of measurements; return the ``local_id``s that
    the watch is allowed to delete (delete-after-echo).
    """
    n = len(body)
    if n == 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"detail": "Batch must contain at least 1 item", "code": "empty_batch"},
        )
    if n > MAX_BATCH_SIZE:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail={
                "detail": f"Batch exceeds {MAX_BATCH_SIZE} items",
                "code": "batch_too_large",
            },
        )
    return await ingest_service.upload_measurements(
        session=session,
        path_patient_id=patient_id,
        patient_number=patient_number,
        raw_items=body,
        device_model=device_model,
        os_version=os_version,
    )


# --- GET -------------------------------------------------------------------


@router.get(
    "/api/v1/patients/{patient_id}/measurements",
    response_model=MeasurementPage,
    responses={404: {"description": "Patient not found"}},
)
async def list_measurements(
    patient_id: UUID = Path(...),
    cursor: str | None = None,
    limit: int = DEFAULT_LIMIT,
    session: AsyncSession = Depends(get_session),
) -> MeasurementPage:
    """Return a DESC-sorted, cursor-paginated page of measurements.

    Keyset pagination: the cursor encodes the last item's
    ``(timestamp, id)`` and the next page returns rows strictly
    ``< (timestamp, id)`` from the cursor \u2014 stable under duplicate
    timestamps because ``id`` is the tiebreaker.
    """
    if limit < 1 or limit > MAX_LIMIT:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={
                "detail": f"limit must be between 1 and {MAX_LIMIT}",
                "code": "invalid_limit",
            },
        )

    # 404 if the patient is unknown (REQ-READ-03).
    patient_exists = (
        await session.execute(
            select(ClinicalPatient.patient_id).where(
                ClinicalPatient.patient_id == patient_id
            )
        )
    ).scalar_one_or_none()
    if patient_exists is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"detail": "Patient not found", "code": "patient_not_found"},
        )

    stmt = select(ClinicalMeasurement).where(
        ClinicalMeasurement.patient_id == patient_id
    )
    if cursor:
        try:
            cursor_ts, cursor_id = _decode_cursor(cursor)
        except (ValueError, base64.binascii.Error, UnicodeDecodeError) as exc:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"detail": "Invalid cursor", "code": "invalid_cursor"},
            ) from exc
        stmt = stmt.where(
            tuple_(ClinicalMeasurement.timestamp, ClinicalMeasurement.id)
            < tuple_(cursor_ts, cursor_id)
        )
    # limit + 1 trick: if we get more than ``limit`` rows, there's a
    # next page; the extra row is dropped from the response.
    stmt = stmt.order_by(
        ClinicalMeasurement.timestamp.desc(),
        ClinicalMeasurement.id.desc(),
    ).limit(limit + 1)
    rows = (await session.execute(stmt)).scalars().all()
    has_more = len(rows) > limit
    page = rows[:limit]

    items = [
        Measurement(
            id=row.id,
            patient_id=row.patient_id,
            local_id=row.local_id,
            timestamp=row.timestamp,
            received_at=row.received_at,
            heart_rate_bpm=row.heart_rate_bpm,
            spo2_percent=row.spo2_percent,
        )
        for row in page
    ]
    next_cursor: str | None = None
    if has_more and page:
        last = page[-1]
        next_cursor = _encode_cursor(last.timestamp, last.id)
    return MeasurementPage(items=items, next_cursor=next_cursor)
