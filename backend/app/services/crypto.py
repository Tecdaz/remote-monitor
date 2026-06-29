"""pgp_sym_encrypt / pgp_sym_decrypt helpers for ``pii.patients.patient_number``.

REQ-SCHEMA-01 mandates at-rest encryption of the only PII field. The
DB is the source of truth — these helpers do NOT cache results. Each
call is one round-trip to PostgreSQL and uses the pgcrypto extension
that the 0001_initial migration (PR2 T2.3) installs.

The key is read from ``settings.pii_encryption_key`` and passed as a
parameter to the SQL function so it is never interpolated into the
query string (defence against key/log leakage).

These helpers MUST be called inside a request-scoped session; the
``AsyncSession`` is the only thing tying the call to the rest of the
transaction. ``session.commit()`` is the caller's responsibility.
"""
from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings

# The bytea column stores raw ciphertext. pgp_sym_encrypt returns bytea;
# pgp_sym_decrypt returns text. We coerce to str on decrypt to match
# the Pydantic ``Patient.patient_number: str`` contract.


async def encrypt_patient_number(session: AsyncSession, plain: str) -> bytes:
    """Encrypt ``plain`` with the configured symmetric key.

    Returns the raw pgcrypto ciphertext as ``bytes`` ready to store in
    the ``pii.patients.patient_number`` (bytea) column. Does NOT commit.
    """
    result = await session.execute(
        text("SELECT pgp_sym_encrypt(:plain, :key)"),
        {"plain": plain, "key": settings.pii_encryption_key},
    )
    ciphertext: bytes = result.scalar_one()
    return ciphertext


async def decrypt_patient_number(session: AsyncSession, cipher: bytes) -> str:
    """Decrypt a ``pii.patients.patient_number`` ciphertext to plaintext.

    Returns the original ``str`` (e.g. ``"P-00042"``). Does NOT commit.
    """
    result = await session.execute(
        text("SELECT pgp_sym_decrypt(:cipher, :key)"),
        {"cipher": cipher, "key": settings.pii_encryption_key},
    )
    plaintext: str = result.scalar_one()
    return plaintext
