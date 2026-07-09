"""Application settings backed by pydantic-settings.

Values are read from environment variables prefixed with `APP_` (for
example `APP_DATABASE_URL`). A `.env` file is honoured if present so
local development can override defaults without exporting shell vars.
"""
from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Process-wide configuration for the backend service."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="APP_",
    )

    # Async DSN used by both the application engine and Alembic.
    # PR2 will rely on this for the 0001_initial migration.
    database_url: str = (
        "postgresql+asyncpg://postgres:postgres@localhost:5432/remote_monitor"
    )

    # Root log level for structlog + stdlib logging. PR3 will plumb this
    # into the request-scoped logger configured by `logging_config.py`.
    log_level: str = "INFO"

    # Symmetric key used by pgp_sym_encrypt for pii.patients.patient_number.
    # Empty by default; PR3 will read it from the environment and refuse
    # to start if it is missing in non-development environments.
    pii_encryption_key: str = ""

    # WebSocket heartbeat windows (PR4, REQ-WS-04). The server sends a
    # ``{type: "ping"}`` every ``ws_ping_timeout_s`` seconds; if no
    # client frame arrives within ``ws_pong_grace_s`` additional
    # seconds (i.e. ``ws_ping_timeout_s + ws_pong_grace_s`` total
    # silence), the connection is closed. Defaults match the
    # 30s/60s values from the spec. Tests can override via env var.
    ws_ping_timeout_s: float = 30.0
    ws_pong_grace_s: float = 60.0

    # Patient inactivity sweep threshold (seconds). Patients whose
    # last accepted measurement is older than this are deactivated
    # by the periodic background sweep.
    patient_inactivity_threshold_s: float = 300.0


settings = Settings()
