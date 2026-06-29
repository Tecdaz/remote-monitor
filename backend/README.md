# backend

FastAPI ingest service + WebSocket broadcast hub. The backend receives
batched measurements from the watch, persists them in PostgreSQL, and
pushes live updates to connected frontends.

## Stack

- Web framework: FastAPI ≥ 0.138
- Runtime: Python 3.13+
- ORM: SQLAlchemy 2.0 (async)
- Migrations: Alembic
- Database: PostgreSQL ≥ 18
- Real-time: WebSocket broadcast endpoint (one channel per patient or
  one shared channel filtered client-side; decided in the follow-up
  spec)

## Compliance posture

HIPAA-like PoC posture, declared here as intent, not as implemented
control:

- encryption at-rest (Postgres pgcrypto or volume encryption),
- encryption in-transit (HTTPS / WSS only),
- audit log of access events,
- defined retention window,
- separation between PII and clinical data.

The actual encryption setup, audit-log wiring, and retention enforcement
land in the `scaffold-backend` follow-up.

## Dependency management — `uv` (not `pip`)

This host does not have `pip` or `pip3` installed. Backend contributors
must use `uv` for every Python dependency operation. Specifically:

- `uv venv` to create the virtualenv
- `uv add <name>` to add a runtime dependency
- `uv add --dev <name>` to add a test/dev dependency
- `uv run <command>` to run commands inside the venv
- `uv.lock` is committed

Do not reach for `pip`. The scaffold will not create a `pyproject.toml`
yet — that lands in the follow-up — but the rule stands from day one.

## Container runtime — `docker compose` v2 (not `docker-compose`)

This host has the Docker Engine v2 plugin (`docker compose …` with a
space, currently v5.2.0). It does **not** have the legacy standalone
`docker-compose` binary. The follow-up `docker-compose` change will
write a `docker-compose.yml`; sample commands will use the space form
exclusively. Many older tutorials still use the hyphenated
`docker-compose`; ignore them.

## Pinned tokens

The following strings appear on their own line so the scaffold's
acceptance grep can verify them literally.

FastAPI ≥ 0.138
Python 3.13
SQLAlchemy 2.0
Alembic
PostgreSQL ≥ 18
WebSocket
HIPAA
uv
docker compose
docker-compose
scaffold-backend

## Follow-up

The full backend module — `pyproject.toml`, app package, SQLAlchemy
models, Alembic migrations, ingest endpoint, WebSocket broadcast
endpoint, audit-log middleware, encryption-at-rest config, and
integration tests for the sync protocol — lands in the
`scaffold-backend` SDD change. This README is the placeholder for that
work.

## API contract

The backend reads `contracts/openapi.yaml` as source of truth.
Pydantic models are derived from the JSON Schemas in the OpenAPI
spec. The `delete-after-echo` invariant is enforced by the
`POST /api/v1/patients/{patient_id}/measurements` endpoint's
`accepted_ids` response — the watch deletes local Room rows only
for ids in that array.
