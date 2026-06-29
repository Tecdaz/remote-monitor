# remote-monitor

Monorepo for a remote patient monitoring proof of concept. Three components
share one repository so the watch, backend, and dashboard can evolve as
siblings: change one, learn from the next.

## Components

- **watch/** — Wear OS 6 patient monitor. Collects heart rate and SpO2
  with Health Services, stores measurements locally in Room, and uploads
  them to the backend in batches.
- **backend/** — FastAPI ingest + WebSocket broadcast. Receives
  measurements from the watch, persists them in PostgreSQL, and pushes
  updates to connected frontends.
- **frontend/** — TanStack real-time dashboard. Subscribes to the
  backend WebSocket and renders live patient state without manual
  refresh.

## Locked stack

The three components are pinned to specific versions. These are the
versions this monorepo plans for; per-component follow-up changes will
materialise the actual manifests.

- watch: Wear OS 6 (API 36)
- backend: FastAPI ≥ 0.138, Python 3.13+
- frontend: TanStack Start + TanStack Query ≥ 5.101

## Pinned versions

The following strings appear on their own line so the scaffold's
acceptance grep can verify the pins literally. Read the prose above for
the human explanation.

remote-monitor
watch
backend
frontend
Wear OS 6
FastAPI ≥ 0.138
TanStack Start
TanStack Query ≥ 5.101
HIPAA
WebSocket
delete-after-echo
scaffold-backend
scaffold-frontend
scaffold-watch
contracts
docker-compose

## Compliance posture

This project follows a HIPAA-like posture as a **proof-of-concept
claim**, not as an implemented control. The intent is:

- encryption at-rest (Postgres pgcrypto or volume encryption) and
  in-transit (HTTPS / WSS),
- an audit log of access events,
- a defined retention window,
- separation between PII and clinical data.

The encryption, cert work, audit-log wiring, and retention enforcement
land in the per-component follow-up changes. Do not treat this scaffold
as having implemented any compliance control.

## Real-time transport

The backend and the frontend communicate over a WebSocket bidirectional
channel. The watch talks to the backend over plain HTTPS batch uploads;
the dashboard receives measurements and alerts in real time without
polling.

## Auth posture (PoC)

There is **no user login** anywhere in the system. The watch
identifies itself with a device token that is the patient number the
operator typed in. The dashboard opens directly to the live view. This
is acceptable for a proof of concept only.

## Critical invariant — `delete-after-echo`

The watch only deletes a locally stored measurement after the backend
acknowledges the upload with a 2xx response that echoes the IDs back.
If the backend is unreachable, measurements accumulate in the local
Room store and the watch retries with backoff. **Never** delete a Room
row optimistically — a backend outage must not lose data.

## Follow-up changes

This scaffold only creates folders and documentation. The real work
lives in these follow-up SDD changes, in rough dependency order:

- scaffold-watch
- scaffold-backend
- scaffold-frontend
- contracts
- docker-compose

Each of those changes is a separate reviewable work unit with its own
spec, design, and verification phase.

## API contract

The wire contract between the watch, backend, and frontend lives in
`contracts/`. It is the source of truth — Pydantic models on the
backend and TypeScript types on the frontend are derived from it
(or hand-written from it for the WebSocket).

- **D1**: OpenAPI 3.1.x for the REST spec
- **D2**: `contracts/openapi.yaml` is the source of truth
- **D3**: Hand-written TypeScript types for the WebSocket protocol
- **D4**: Per-patient WebSocket URL — the URL is the subscription
- **D5**: Client-generated `local_id` (UUID v4) as the idempotency key
