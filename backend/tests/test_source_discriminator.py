"""polar-h10-validation T3: per-row ``source`` discriminator.

Write-side (Pydantic ``MeasurementBatch`` -> ingest service ->
``clinical.measurements.source``) and read-side (``Measurement`` schema
carries it for GET + WS).

Run: ``uv run pytest backend/tests/test_source_discriminator.py``
(requires postgres, same as ``test_ingest.py``).
"""
from __future__ import annotations

from datetime import datetime, timezone
from uuid import UUID, uuid4

import pytest
from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import ClinicalMeasurement
from app.schemas import Measurement, MeasurementBatch
from app.services.ingest import upload_measurements


def _item(source: str | None = None) -> dict:
    item: dict = {
        "local_id": str(uuid4()),
        "timestamp": datetime(2026, 6, 29, 12, 0, 0, tzinfo=timezone.utc).isoformat(),
        "heart_rate_bpm": 72,
        "spo2_percent": 98.0,
    }
    if source is not None:
        item["source"] = source
    return item


class TestSourceSchema:
    def test_polar_source_accepted(self) -> None:
        batch = MeasurementBatch.model_validate(_item("polar_h10"))
        assert batch.source == "polar_h10"

    def test_missing_source_defaults_to_samsung(self) -> None:
        batch = MeasurementBatch.model_validate(_item())
        assert batch.source == "samsung_watch"

    def test_unknown_source_rejected(self) -> None:
        with pytest.raises(ValidationError):
            MeasurementBatch.model_validate(_item("chest_strap_x"))


class TestSourceIngest:
    async def test_upload_persists_polar_source(
        self, session: AsyncSession
    ) -> None:
        """A batch with ``source=polar_h10`` stores it on the row."""
        path_pid = uuid4()
        items = [_item("polar_h10")]
        response = await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="1",
            raw_items=items,
        )
        assert len(response.accepted_ids) == 1
        assert response.rejected == []

        row = (
            await session.execute(
                select(ClinicalMeasurement).where(
                    ClinicalMeasurement.patient_id == path_pid
                )
            )
        ).scalars().one()
        assert row.source == "polar_h10"

    async def test_upload_without_source_defaults_to_samsung(
        self, session: AsyncSession
    ) -> None:
        """Backwards-compat: old watch builds send no ``source``."""
        path_pid = uuid4()
        response = await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="2",
            raw_items=[_item()],
        )
        assert len(response.accepted_ids) == 1

        row = (
            await session.execute(
                select(ClinicalMeasurement).where(
                    ClinicalMeasurement.patient_id == path_pid
                )
            )
        ).scalars().one()
        assert row.source == "samsung_watch"

    async def test_upload_rejects_unknown_source_per_item(
        self, session: AsyncSession
    ) -> None:
        """Unknown ``source`` is a per-item rejection, like ``hr_status``."""
        path_pid = uuid4()
        good = _item("polar_h10")
        bad = _item("chest_strap_x")
        response = await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="3",
            raw_items=[good, bad],
        )
        assert len(response.accepted_ids) == 1
        assert len(response.rejected) == 1
        assert "source" in response.rejected[0].reason

    async def test_read_model_carries_source(
        self, session: AsyncSession
    ) -> None:
        """The read schema (GET + WS) surfaces ``source`` per item."""
        path_pid = uuid4()
        await upload_measurements(
            session,
            path_patient_id=path_pid,
            patient_number="4",
            raw_items=[_item("polar_h10")],
        )
        row = (
            await session.execute(
                select(ClinicalMeasurement).where(
                    ClinicalMeasurement.patient_id == path_pid
                )
            )
        ).scalars().one()
        measurement = Measurement(
            id=row.id,
            patient_id=row.patient_id,
            local_id=row.local_id,
            timestamp=row.timestamp,
            received_at=row.received_at,
            heart_rate_bpm=row.heart_rate_bpm,
            spo2_percent=(
                float(row.spo2_percent) if row.spo2_percent is not None else None
            ),
            ibis_ms=row.ibis_ms,
            ibis_status=row.ibis_status,
            hr_status=row.hr_status,
            source=row.source,
        )
        assert measurement.source == "polar_h10"
