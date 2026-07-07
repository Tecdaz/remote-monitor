"""Patient request/response models (REQ-READ-01..03, REQ-INGEST-07,
REQ-SCHEMA-05, REQ-READ-04, REQ-INGEST-09).

Sourced from ``contracts/openapi.yaml#/components/schemas/{Patient,
RegisterPatientRequest, BedSnapshot, BedSnapshotList}`` and
``contracts/data-models.md``. The ``bed_number`` field is the wire
identity of a session after the bed-picker onboarding change; legacy
``PatientRegistration`` is REMOVED (replaced by
``RegisterPatientRequest``).
"""
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class Patient(BaseModel):
    """A registered patient. ``patient_number`` is the only PII field."""

    model_config = ConfigDict(extra="forbid")

    patient_id: UUID = Field(
        ...,
        description=(
            "Server-assigned opaque UUID. The only link from clinical "
            "records to PII."
        ),
    )
    patient_number: str = Field(
        ...,
        description=(
            "Encrypted bed number (PostgreSQL pgp_sym_encrypt of the "
            "bed number in 1..5). NOT an operator-typed identifier; do "
            "not display as plain text."
        ),
        # D32: a base64 OpenPGP-format ciphertext placeholder, NOT
        # the legacy ``"P-00042"`` example. Real ciphertext is
        # generated server-side via ``pgp_sym_encrypt``.
        examples=[
            "MIIBiQYJKoZIhvcNAQDo+0D+lpNbQEDdHJlYXRlZEBrE1lMrQECREMwBQYDK2VwBCMQOlhLVDc4WnB1djVxTEhNSjFQcmZQTlpMNlVrQVhxY3NQNFhmN2tVS2tGM1VxSlhVSmkrRmhKN0FLb0o3SFd6RHhLdlJZRHJLZkx4c1Q3Z2VBQ0FvM2JrR0tuZUFIZlpPMU9wNkhRK09wcG9WM2d2bXF1TGIya3RCVVZtVGh1YVk4PQECMB2jTFPleV9bscAtxA99JeUQiEYD6v6+arT3qUUEAQH/"
        ],
    )
    device_model: str = Field(..., description="Watch hardware model.")
    os_version: str = Field(..., description="Watch OS version string.")
    created_at: datetime = Field(
        ...,
        description="Server-assigned registration timestamp.",
    )
    is_active: bool = Field(
        ...,
        description="Soft-delete flag. `false` means deactivated.",
    )


class RegisterPatientRequest(BaseModel):
    """Payload for ``POST /api/v1/patients``.

    The watch posts the operator-selected bed number (1..5) plus device
    metadata. ``replace_active_session`` opts into the atomic replace
    transaction (REQ-INGEST-09): when ``true``, any active session on
    the same bed is deactivated and a new one is created in the same
    DB transaction; when ``false`` (default), the request is refused
    with ``409 bed_now_occupied`` if the bed is already active.
    """

    model_config = ConfigDict(extra="forbid")

    bed_number: int = Field(
        ...,
        ge=1,
        le=5,
        description="Selected bed in 1..5 (PoC hardcoded range).",
        examples=[3],
    )
    device_model: str = Field(
        ...,
        description="Watch hardware model.",
        examples=["samsung SM-R870"],
    )
    os_version: str = Field(
        ...,
        description="Watch OS version string.",
        examples=["16 (API 36)"],
    )
    replace_active_session: bool = Field(
        default=False,
        description=(
            "When `true`, atomically deactivates the active session "
            "on the same bed and creates a new one. When `false` "
            "(default), the request is refused with `409 "
            "bed_now_occupied` if the bed is currently active."
        ),
    )
