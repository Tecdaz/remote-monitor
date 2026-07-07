"""Transactional batch ingest for ``uploadMeasurements``.

This module is the heart of REQ-INGEST-01..04 + REQ-INGEST-07 +
REQ-SCHEMA-04 + REQ-WS-02. The single function ``upload_measurements``
enforces the following invariants:

1. Per-item Pydantic validation (REQ-INGEST-01) — bad items are
   rejected individually, the good ones go through.
2. delete-after-echo (REQ-INGEST-02) — the ``BatchResponse`` is
   constructed AFTER ``session.commit()`` returns. If the commit
   fails, the caller sees a 5xx with empty ``accepted_ids`` and the
   watch keeps its local rows.
3. No partial commit (REQ-INGEST-03) — the whole batch lives inside
   one ``async with session.begin():`` block; any DB error rolls
   everything back, including the audit row.
4. Idempotency (REQ-INGEST-04) — ``INSERT ... ON CONFLICT (patient_id,
   local_id) DO NOTHING`` is the DB-level guard. The UNIQUE constraint
   added in PR2 (T2.3) is the source of truth; re-POSTed ``local_id``s
   are reported in ``accepted_ids`` (no-op-deduped) and produce no
   duplicate row.
5. Auto-register (REQ-INGEST-07) — when the X-Patient-Number does not
   match any ``PiiPatient`` row, a new patient is inserted in BOTH
   ``pii.patients`` and ``clinical.patients`` in the same transaction
   as the first measurement insert. A measurement insert failure
   rolls the patient registration back too.
6. WS publish AFTER commit (REQ-WS-02) — only when the commit
   succeeds do we call ``manager.publish(patient_id, ...)`` with a
   WsMeasurementEvent for the FIRST accepted measurement. The
   publish is best-effort: a WS failure does not invalidate the
   HTTP 200. Clients that miss the event can still re-fetch via
   ``GET /measurements`` (REQ-READ-01).

Implementation note: the PR2 0001_initial migration did not attach a
``server_default`` to ``clinical.measurements.id`` / ``received_at`` or
``audit.audit_log.id`` / ``ts`` (the columns are NOT NULL). Per
project rule, migrations are CLI-only and never hand-edited, so this
service sets those values explicitly in Python.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

from fastapi import HTTPException, status
from pydantic import ValidationError
from sqlalchemy import select, text
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import ClinicalMeasurement, ClinicalPatient, PiiPatient
from app.schemas import BatchResponse, MeasurementBatch, RejectedMeasurement
from app.services.audit import write_audit_log
from app.services.crypto import encrypt_patient_number
from app.ws.manager import manager

# Action string used for measurement.create audit rows. Stable value;
# the ``audit.audit_log.action`` column is intentionally unindexed (PoC).
_AUDIT_ACTION_MEASUREMENT_CREATE = "measurement.create"


async def _resolve_patient(
    session: AsyncSession,
    *,
    path_patient_id: UUID,
    patient_number: str,
    device_model: str,
    os_version: str,
) -> UUID:
    """Find the patient by ``patient_number`` or auto-register.

    If the X-Patient-Number resolves to an existing ``PiiPatient`` row,
    assert that its ``patient_id`` matches the path; otherwise raise
    403. If the X-Patient-Number is unknown, register a new patient
    using the path ``patient_id`` and the device headers.

    Implementation note: ``pgp_sym_encrypt`` is non-deterministic (it
    uses a random IV), so we cannot encrypt-and-compare. Instead the
    lookup uses ``pgp_sym_decrypt`` inside the WHERE clause. For a
    PoC with few patients this is fine; production should add a
    separate HMAC/digest lookup column.
    """
    from app.config import settings

    existing_id = (
        await session.execute(
            text(
                "SELECT patient_id FROM pii.patients "
                "WHERE pgp_sym_decrypt(patient_number, :key) = :plain"
            ),
            {"plain": patient_number, "key": settings.pii_encryption_key},
        )
    ).scalar_one_or_none()

    if existing_id is not None:
        if existing_id != path_patient_id:
            # X-Patient-Number resolves to a different patient_id than
            # the path parameter — header/path mismatch (REQ-INGEST-06).
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={
                    "detail": "X-Patient-Number does not match path patient_id",
                    "code": "patient_number_mismatch",
                },
            )
        return existing_id

    # First-time upload for this patient_number — auto-register in the
    # same transaction. Use the path patient_id as the new patient_id
    # so the watch's URL is meaningful.
    cipher = await encrypt_patient_number(session, patient_number)
    await session.execute(
        pg_insert(PiiPatient).values(
            patient_id=path_patient_id,
            patient_number=cipher,
        )
    )
    await session.execute(
        pg_insert(ClinicalPatient).values(
            patient_id=path_patient_id,
            device_model=device_model,
            os_version=os_version,
        )
    )
    return path_patient_id


async def upload_measurements(
    session: AsyncSession,
    *,
    path_patient_id: UUID,
    patient_number: str,
    raw_items: list[dict[str, Any]],
    device_model: str = "unknown",
    os_version: str = "unknown",
    actor: str = "watch",
) -> BatchResponse:
    """Persist a batch of measurements for ``patient_number``.

    ``raw_items`` is the list of dicts the watch sent. Each item is
    validated individually against ``MeasurementBatch``; invalid items
    land in ``BatchResponse.rejected`` with a human-readable reason
    (the watch keeps those local rows). The valid items are committed
    in a single transaction together with one ``audit.audit_log`` row.

    Returns ``BatchResponse`` ONLY after the commit succeeds. If the
    commit fails, the exception propagates and the caller returns 5xx
    with an empty ``BatchResponse``.
    """
    # --- 1. Per-item Pydantic validation (REQ-INGEST-01) ---------------
    valid_items: list[MeasurementBatch] = []
    rejected: list[RejectedMeasurement] = []
    for raw in raw_items:
        try:
            valid_items.append(MeasurementBatch.model_validate(raw))
        except ValidationError as exc:
            # Fall back to a fresh UUID if the bad payload omitted
            # local_id so the watch still has a stable handle to keep.
            raw_local = raw.get("local_id") if isinstance(raw, dict) else None
            local_id = raw_local if isinstance(raw_local, UUID) else uuid4()
            rejected.append(
                RejectedMeasurement(
                    local_id=local_id,
                    reason=f"validation error: {exc.errors()[0]['msg']}",
                )
            )

    # --- 2-5. Single transaction: resolve patient, insert, audit -------
    # Capture the per-item id so the WS publish (after commit) can
    # include the server-assigned ``id`` in the Measurement dict.
    item_ids: list[UUID] = []
    received_at: datetime | None = None
    async with session.begin():
        patient_id = await _resolve_patient(
            session,
            path_patient_id=path_patient_id,
            patient_number=patient_number,
            device_model=device_model,
            os_version=os_version,
        )

        # ON CONFLICT DO NOTHING — re-POSTed local_ids are silently
        # deduped. We still report ALL attempted local_ids as accepted
        # (REQ-INGEST-04 scenario 2: A, B, C -> accepted_ids = [A, B, C]).
        if valid_items:
            received_at = datetime.now(timezone.utc)
            item_ids = [uuid4() for _ in valid_items]
            insert_stmt = (
                pg_insert(ClinicalMeasurement)
                .values(
                    [
                        {
                            "id": item_ids[i],
                            "patient_id": patient_id,
                            "local_id": item.local_id,
                            "timestamp": item.timestamp,
                            "heart_rate_bpm": item.heart_rate_bpm,
                            "spo2_percent": item.spo2_percent,
                            "received_at": received_at,
                            "ibis_ms": item.ibis_ms,
                        }
                        for i, item in enumerate(valid_items)
                    ]
                )
                .on_conflict_do_nothing(
                    index_elements=["patient_id", "local_id"],
                )
            )
            await session.execute(insert_stmt)

        # Exactly one audit row per successful batch (REQ-SCHEMA-04).
        # The count is the number of items the watch ATTEMPTED to
        # persist (the deduplicated ones are still in accepted_ids).
        await write_audit_log(
            session,
            actor=actor,
            action=_AUDIT_ACTION_MEASUREMENT_CREATE,
            count=len(valid_items),
            context={
                "patient_id": str(patient_id),
                "local_ids": [str(item.local_id) for item in valid_items],
            },
        )

    # The commit succeeded — construct the response AFTER the
    # ``async with session.begin():`` block exits so a rollback never
    # produces a 2xx (REQ-INGEST-02).
    accepted_ids = [item.local_id for item in valid_items]

    # WS fan-out (REQ-WS-02). Best-effort: a failure here must not
    # invalidate the 200 response. We publish ONLY the first accepted
    # measurement in the batch (PoC choice — the spec allows "all" or
    # "first"; first is simpler and reduces WS traffic).
    if valid_items and received_at is not None and item_ids:
        first_item = valid_items[0]
        first_id = item_ids[0]
        measurement_dict: dict[str, Any] = {
            "id": str(first_id),
            "patient_id": str(patient_id),
            "local_id": str(first_item.local_id),
            "timestamp": first_item.timestamp.isoformat(),
            "heart_rate_bpm": first_item.heart_rate_bpm,
            "spo2_percent": (
                float(first_item.spo2_percent)
                if first_item.spo2_percent is not None
                else None
            ),
            "received_at": received_at.isoformat(),
            "ibis_ms": (
                list(first_item.ibis_ms)
                if first_item.ibis_ms is not None
                else None
            ),
        }
        try:
            await manager.publish(
                patient_id,
                {"type": "measurement.created", "data": measurement_dict},
            )
        except Exception as exc:  # noqa: BLE001
            # Log and swallow — the 200 is the contract, the WS push
            # is a courtesy.
            import structlog
            structlog.get_logger().warning(
                "ws publish failed; clients may miss event",
                patient_id=str(patient_id),
                error=str(exc),
            )

    return BatchResponse(accepted_ids=accepted_ids, rejected=rejected)
