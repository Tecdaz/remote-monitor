"""FastAPI dependencies for the ingest pipeline.

REQ-INGEST-06 mandates the ``X-Patient-Number`` header on every
``POST /api/v1/patients/{patient_id}/measurements`` call. The
``require_patient_number_header`` dependency enforces header presence
and returns the value; the actual lookup-by-patient_number + mismatch
check against the path ``patient_id`` lives in
``app.services.ingest._resolve_patient`` (so it shares the same DB
transaction as the measurement inserts).
"""
from __future__ import annotations

from fastapi import Header, HTTPException, status


async def require_patient_number_header(
    x_patient_number: str | None = Header(None, alias="X-Patient-Number"),
) -> str:
    """Return the ``X-Patient-Number`` header value or raise 403.

    Missing header -> 403 ``missing_patient_number``. Header value
    mismatch with the path ``patient_id`` -> 403
    ``patient_number_mismatch`` (raised later by
    ``app.services.ingest`` after a DB lookup).
    """
    if not x_patient_number:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail={
                "detail": "Missing X-Patient-Number header",
                "code": "missing_patient_number",
            },
        )
    return x_patient_number
