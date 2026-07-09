"""Periodic patient inactivity sweep.

The background task deactivates active patients whose
``last_measurement_at`` is older than the configured threshold. It is
wired into the FastAPI lifespan in ``app.main``.

Design invariants:

- Concurrency-safe selection via ``FOR UPDATE SKIP LOCKED``.
- Each deactivation + audit write is wrapped in a nested savepoint so
  an audit failure rolls back only that patient.
- The outer transaction commits before WebSocket events are published;
  a publish failure must never roll back a deactivation.
- ``sweep_once()`` accepts an injectable session so tests can call it
  directly without the periodic loop.
"""
from __future__ import annotations

import asyncio
import logging
from uuid import UUID

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.db import AsyncSessionLocal
from app.services.audit import write_audit_log
from app.ws.manager import ConnectionManager, manager

logger = logging.getLogger(__name__)

# Audit action string for deactivations triggered by the sweep.
_AUDIT_ACTION_INACTIVITY_DEACTIVATE = "patient.inactivity_deactivate"


async def sweep_once(
    session: AsyncSession,
    threshold_s: float,
    *,
    manager: ConnectionManager,
) -> int:
    """Deactivate stale active patients and publish WS events.

    Returns the number of patients deactivated in this iteration.
    """
    # Select stale active rows, skipping rows locked by other
    # transactions (REQ-SWEEP-03).
    result = await session.execute(
        text(
            "SELECT patient_id "
            "FROM clinical.patients "
            "WHERE is_active = true "
            "  AND last_measurement_at < now() - (:threshold * interval '1 second') "
            "FOR UPDATE SKIP LOCKED"
        ),
        {"threshold": threshold_s},
    )
    stale_ids = [row.patient_id for row in result]

    deactivated: list[UUID] = []
    for patient_id in stale_ids:
        try:
            async with session.begin_nested():
                await session.execute(
                    text(
                        "UPDATE clinical.patients "
                        "SET is_active = false "
                        "WHERE patient_id = :pid"
                    ),
                    {"pid": patient_id},
                )
                await write_audit_log(
                    session,
                    actor="system",
                    action=_AUDIT_ACTION_INACTIVITY_DEACTIVATE,
                    count=1,
                    context={"patient_id": str(patient_id)},
                )
            deactivated.append(patient_id)
        except Exception:  # noqa: BLE001
            logger.warning(
                "Failed to deactivate patient %s due to nested tx/audit failure; "
                "rolling back that patient only",
                patient_id,
                exc_info=True,
            )

    # Commit the outer transaction BEFORE publishing WS events so a
    # publish failure cannot roll back the deactivation (REQ-SWEEP-05).
    await session.commit()

    for patient_id in deactivated:
        try:
            await manager.publish(
                patient_id,
                {"type": "patient.deactivated", "data": {"patient_id": str(patient_id)}},
            )
        except Exception:  # noqa: BLE001
            logger.warning(
                "WS publish failed after deactivation; clients may miss event",
                extra={"patient_id": str(patient_id)},
                exc_info=True,
            )

    return len(deactivated)


async def _sweep_loop(
    threshold_s: float,
    manager: ConnectionManager,
) -> None:
    """Infinite asyncio task that runs ``sweep_once`` periodically.

    The sleep interval is ``min(threshold_s / 2, 30.0)`` seconds.
    """
    interval = min(threshold_s / 2.0, 30.0)
    while True:
        try:
            async with AsyncSessionLocal() as session:
                await sweep_once(session, threshold_s, manager=manager)
        except Exception:  # noqa: BLE001
            logger.exception("Inactivity sweep iteration failed")
        await asyncio.sleep(interval)


def start_sweep_task(
    threshold_s: float = settings.patient_inactivity_threshold_s,
    manager: ConnectionManager = manager,
) -> asyncio.Task:
    """Create and return the background sweep asyncio task."""
    return asyncio.create_task(_sweep_loop(threshold_s, manager))
