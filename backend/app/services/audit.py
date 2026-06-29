"""``audit.audit_log`` write helpers (REQ-SCHEMA-04).

Every successful mutation that touches PII or clinical data MUST write
exactly one row to ``audit.audit_log`` in the SAME transaction as the
mutation. If the audit insert fails, the mutation rolls back; the
watch never sees a success response for an un-audited write.

Implementation note: ``audit.audit_log.id`` and ``ts`` are NOT NULL but
have no ``server_default`` in the PR2 0001_initial migration. Per
project rule, migrations are CLI-only and never hand-edited, so this
helper sets both in Python.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from uuid import uuid4

from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AuditLog


async def write_audit_log(
    session: AsyncSession,
    *,
    actor: str,
    action: str,
    count: int,
    context: dict[str, Any] | None = None,
) -> None:
    """Append a single ``audit.audit_log`` row in the current transaction.

    The caller is responsible for committing the enclosing transaction
    — this helper does NOT call ``session.commit()`` itself.
    """
    stmt = pg_insert(AuditLog).values(
        id=uuid4(),
        actor=actor,
        action=action,
        count=count,
        ts=datetime.now(timezone.utc),
        context=context,
    )
    await session.execute(stmt)
