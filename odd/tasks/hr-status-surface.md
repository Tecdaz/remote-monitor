# feat-watch-hr-status-surface — Surface `HEART_RATE_STATUS` through the data pipeline

## Goal

Capture the Samsung `HEART_RATE_STATUS` field (per-reading lifecycle status from
the `HEART_RATE_CONTINUOUS` tracker) and propagate it through every layer of
the pipeline so it lands in `clinical.measurements` on the backend. **No UI
change** (operator-facing HomeScreen keeps its current surface; this is a
data-collection / telemetry cycle).

## Why

The Samsung `HEART_RATE_CONTINUOUS` tracker exposes **4** fields
(`HEART_RATE`, `IBI_LIST`, `IBI_STATUS_LIST`, `HEART_RATE_STATUS`) but the
provider currently surfaces only the first three. `HEART_RATE_STATUS` carries
the **per-reading** lifecycle state — a single integer that summarises the
sensor's confidence for the whole beat-batch.

Samsung documents the values publicly in the API Reference
(`ValueKey.HeartRateSet.html`):

| Value | Meaning |
|------:|---------|
| `1` | Successful HR measurement |
| `0` | Initial state, OR a higher-priority sensor (e.g. BIA) is operating |
| `-2` | Wearable movement detected |
| `-3` | Wearable detached (off-wrist) |
| `-8` | PPG signal weak / user moved |
| `-10` | PPG signal too weak / too much motion |
| `-999` | Higher-priority sensor (BIA) operating |

The test comment in `SamsungHeartRateProviderTest.kt:653` claims "values are
undocumented by Samsung" — this is **factually incorrect**; the table is
public. That comment is corrected as part of this cycle.

This data is "of vital importance" per the operator (2026-09-23): it lets the
backend distinguish a real BPM from a degraded/paused reading without
inferring it from `IBI_STATUS_LIST` heuristics.

## Scope

### In

- Read `HEART_RATE_STATUS` from the SDK in `SamsungHeartRateProvider`.
- Add `hrStatus: Int?` to `HeartRateReading`.
- Add `hr_status: Int?` to `MeasurementEntity` (Room) — column
  `hr_status INTEGER NULL`.
- New Room migration `MIGRATION_5_6`.
- Add `hr_status: int | None` to backend Pydantic `MeasurementBatch` schema
  with a value-range validator (Samsung docs: values in the set
  {-999, -10, -8, -3, -2, 0, 1} + null).
- Add `hr_status: int | None` to backend `ClinicalMeasurement` model.
- New Alembic migration `add_hr_status_column`.
- Plumb the field through `backend/app/services/ingest.py` write-side.
- Plumb through `backend/app/routers/measurements.py` read-side (two
  endpoints that return `Measurement`).
- Plumb through `backend/app/services/ws.py` (or wherever the WS payload is
  built) so subscribers see it live.
- Update `contracts/openapi.yaml`, `contracts/data-models.md`,
  `contracts/websocket-types.ts`.

### Out

- **No UI change** in `HomeScreen` / `HomeViewModel` (operator decision
  2026-09-23: minimal scope; UI surfacing deferred to a later cycle).
- No gating of readings on `HEART_RATE_STATUS` (today's `BPM <= 0` skip
  rule stays; `STATUS != 1` is not used to reject rows — we keep every
  reading with whatever status the SDK gave us, so the backend gets the
  full picture for offline analysis).
- No change to the `feat-watch-samsung-hr-ibi` cycle's tests (we add new
  ones; we don't regress the existing green ones).

## Constraints

- Field name `hr_status` end-to-end (snake_case in Room + wire + Python +
  OpenAPI; camelCase `hrStatus` in Kotlin data classes).
- Backwards-compat: a watch on the old build sends NO `hr_status`; the
  backend treats it as `null`. New schema accepts it; old rows stay null.
- TDD mode in this repo is unknown / unset (no `sdd-init` cached). Use the
  project's existing test style: MockK on the watch, pytest-asyncio on the
  backend.
- Strict TDD is NOT enforced project-wide; we write a test for each new
  behavior we add and let it fail before the impl, but we don't run RED
  GREEN REFACTOR as a ceremony.

## Acceptance criteria

1. `SamsungHeartRateProvider` emits `hrStatus` reflecting what the SDK gave
   (verified by test).
2. `MeasurementEntity` carries `hr_status` and Room migration `5_6` adds
   the column without losing existing rows.
3. Backend `POST /measurements` accepts an item with `hr_status` (any of
   the 7 documented values or `null`); values outside that set are
   individually rejected with a clear reason (per-item rejection pattern
   established by `ibis_status`).
4. Backend `GET /measurements` and the WS payload include `hr_status`.
5. Old clients (no `hr_status` in body) still ingest cleanly; the column
   is null on the row.
6. Existing merge-gate tests stay green
   (`BatchUploadWorkerTest`, `SamsungHeartRateProviderTest`, `test_ingest`,
   `test_ws`, `test_migrations`).

## Applicable checks

- `./gradlew :watch:test` on the Android module.
- `pytest backend/tests/test_ingest.py backend/tests/test_migrations.py
  backend/tests/test_ws.py backend/tests/test_measurements_router.py`
  (whatever subset exists; verify via discovery at task time).
- `npx tsc --noEmit contracts/websocket-types.ts` if a TS consumer
  exists; otherwise skip.

## Tasks

| ID | Title | Files | Route |
|---|---|---|---|
| T1 | Sensor layer: read `HEART_RATE_STATUS`, add to `HeartRateReading` | `HeartRateSensor.kt`, `SamsungHeartRateProvider.kt`, `HeartRateReadingTest.kt`, `SamsungHeartRateProviderTest.kt` | inline |
| T2 | Orchestrator + Room: add `hr_status` column + migration `5_6` + entity pass-through | `MeasurementEntity.kt`, `AppDatabase.kt`, `SensorOrchestrator.kt`, `SensorOrchestratorTest.kt` | inline |
| T3 | Wire contract: `openapi.yaml` + `data-models.md` + `websocket-types.ts` | `contracts/openapi.yaml`, `contracts/data-models.md`, `contracts/websocket-types.ts` | inline |
| T4 | Backend schema: Pydantic `MeasurementBatch.hr_status` with validator | `backend/app/schemas/measurement.py`, `backend/tests/test_ingest.py` (new cases) | inline |
| T5 | Backend model + Alembic migration | `backend/app/models/measurement.py`, `backend/migrations/versions/add_hr_status_column.py`, `backend/tests/test_migrations.py` (new round-trip) | inline |
| T6 | Backend ingest + router + WS payload | `backend/app/services/ingest.py`, `backend/app/routers/measurements.py`, `backend/app/ws/manager.py` (or wherever the WS frame is built), `backend/tests/test_ingest.py`, `backend/tests/test_ws.py` | inline |
| T7 | Verification | run watch tests + backend tests; ensure green; commit any cleanup | inline |

## Work-unit commits

Per ODD guidance, each task closes with one commit on this feature branch
(`feat-watch-hr-status-surface`) with a Conventional Commits message and
tests + docs alongside the behavior. Push, PR creation, and merge remain
the user's decisions under ordinary repo policy.

| Task | Commit subject (Conventional Commits) |
|---|---|
| T1 | `feat(watch): surface HEART_RATE_STATUS in HeartRateReading` |
| T2 | `feat(watch): persist hr_status in Room via migration 5_6` |
| T3 | `docs(contracts): add hr_status to openapi + data-models + ws types` |
| T4 | `feat(backend): add hr_status to MeasurementBatch with validator` |
| T5 | `feat(backend): add hr_status column + alembic migration` |
| T6 | `feat(backend): propagate hr_status through ingest + read + ws` |
| T7 | `chore: verification report` |

## Evidence log

- 2026-09-23: Samsung `HEART_RATE_STATUS` values ARE documented (engram
  `samsung/heart-rate-status-values`). Discovery contradicts the existing
  comment in `SamsungHeartRateProviderTest.kt:653`.
- 2026-09-23: user decision — surface through full pipeline, no UI change.
- 2026-09-23: cycle closed.
  - 7 work-unit commits on `feat-watch-hr-status-surface`.
  - watch: 116 tests pass (`./gradlew :app:testDebugUnitTest`).
  - backend: 113 tests pass (`uv run pytest`, against a fresh
    `docker compose up -d postgres`).
  - migration round-trip verified locally (upgrade head -> downgrade
    to add_hr_status_column-1 -> column gone).
  - Room schema exported to v6 (new column visible in the JSON).

## Final commit list

1. `feat(watch): surface HEART_RATE_STATUS in HeartRateReading`
2. `feat(watch): persist hr_status in Room via migration 5_6`
3. `docs(contracts): add hr_status to openapi + data-models + ws types`
4. `feat(backend): add hr_status to MeasurementBatch with validator`
5. `feat(backend): add hr_status column + alembic migration`
6. `feat(backend): propagate hr_status through ingest + read + ws`
7. `test: verification pass for feat-watch-hr-status-surface`

## Next step

Push the branch and open a PR (user-driven; under ordinary repo policy).
UI surfacing (badge in HomeScreen) is a deferred cycle — the data is
collected and persisted, the operator can decide when to ship the
consumer side.
