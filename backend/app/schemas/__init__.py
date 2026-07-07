"""Pydantic v2 request/response models for the remote-monitor backend.

PR2: schemas only — no business logic, no DB access. The models mirror
``contracts/openapi.yaml#/components/schemas/*`` one-to-one. Extra
fields are forbidden to keep the contract strict.
"""
from __future__ import annotations

from app.schemas.bed import BedSnapshot
from app.schemas.common import MeasurementPage, Problem
from app.schemas.measurement import (
    BatchResponse,
    Measurement,
    MeasurementBatch,
    RejectedMeasurement,
)
from app.schemas.patient import Patient, RegisterPatientRequest

__all__ = [
    "BatchResponse",
    "BedSnapshot",
    "Measurement",
    "MeasurementBatch",
    "MeasurementPage",
    "Patient",
    "Problem",
    "RegisterPatientRequest",
    "RejectedMeasurement",
]
