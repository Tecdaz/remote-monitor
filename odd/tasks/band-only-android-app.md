# band-only-android-app

Objective: make the Polar H10 band the only measurement source, remove every
Samsung-watch sensor artifact from code/data/docs, and make the Android
collector app run on Wear OS watches, Android phones and Android tablets.
Branch: `feat/band-only-android-app`. Delivery: work-unit commits on the
feature branch; push/PR/merge are the owner's decision. Test runners:
`backend` pytest (uv), `frontend` vitest + tsc (bun), `watch` Gradle unit tests.

## Specs

- **S1.** "De ahora en adelante se usara como objeto de medicion la banda."
  The Polar H10 is the sole measurement source. The Samsung Health Sensor SDK
  pipeline (heart rate, IBI, SpO2, ECG) is removed from the Android app,
  backend, contracts and frontend. No code path may produce or consume
  `samsung_watch` data.
- **S2.** "Elimina toda la informacion que recolecta el reloj."
  The watch-only fields `spo2_percent`, `ibis_status`, `hr_status` and the
  `source` discriminator are removed from the Room entity, REST/WS payloads,
  Pydantic models, SQLAlchemy model and frontend types. Band data keeps
  `heart_rate_bpm`, `ibis_ms`, `ecg_uv`, `ecg_sample_rate_hz`. Existing
  `clinical.measurements` rows with `source='samsung_watch'` are deleted by a
  migration; the four columns are dropped. Room migrates 8 -> 9 preserving
  pending band rows.
- **S3.** "actualiza la documentacion."
  README, `docs/funcionamiento-actual.md`, `docs/local-testing.md`,
  `docs/polar-validation-protocol.md`, `watch/README.md`, `contracts/*` and
  the Makefile stop describing the watch as a sensor collector and describe
  the band-only Android app (watch + phone + tablet).
- **S4.** "expande la app para poder ser usada en moviles y tablets android."
  The Android collector app runs on Wear OS watches, Android phones and
  Android tablets. It ships watch-optimized Compose screens and Material 3
  adaptive screens (2-3-4 column bed grid, large vitals card). The
  manifest no longer requires `android.hardware.type.watch`, makes BLE
  required, and uses a `connectedDevice` foreground service instead of
  `health`.
- **S5.** The band contract is: `PolarHrsProvider` (GATT HRS, BPM + RR) and
  `PolarEcgProvider` (Polar SDK, 130 Hz ECG snippets). `POLAR_DEVICE_ID`
  defaults to `"Polar H10"` so the app works without build flags; an explicit
  `-PpolarDeviceId=...` still overrides it.
- **S6.** Preserve `delete-after-echo`, the 1 s sync cadence, bed onboarding
  (1..5), occupied-bed replace flow, and the Spanish clinical UI copy.
- **S7.** Checks: backend pytest, frontend vitest + typecheck, Android
  `assembleDebug` + unit tests, Alembic downgrade/upgrade round-trip, and a
  static grep proving no Samsung/SpO2 watch field remains in product code.

## Tasks

| ID | Spec | Route | Status | Commit | Evidence |
|---|---|---|---|---|---|
| T1 | S1,S2,S3 | delegated | done | 8aff72d | backend 105 passed; migration head `remove_watch_only_fields`; four columns absent |
| T2 | S1,S2,S5 | inline | done | a178b64 | Room v9 schema exported; Android 105 tests passed |
| T3 | S4,S5,S6 | inline | done | a178b64 | Material 3 phone/tablet screens; manifest/FGS compiled into APK; Pixel 7 emulator shows 2 columns and a simulated 1600x2560 tablet shows 4 |
| T4 | S1,S2,S3 | delegated | done | 1f7571d | frontend 60 tests passed; tsc clean |
| T5 | S3 | inline | done | this docs commit | root README, docs, Makefile, scripts; watch/README lands with T2 (a178b64) |
| T6 | S7 | inline | done | — | backend 105, Android 105, frontend 60, assembleDebug, migration round-trip |

## Log

- **L1 (owner, verbatim):** "De ahora en adelante se usara como objeto de medicion la banda, Elimina toda la informacion que recolecta el reloj, actualiza la documentacion y expande la app para poder ser usada en moviles y tablets android"
- **L2:** Scope decision asked and answered by the owner: "App Android recolectora" — convert the Wear OS app into an Android app that runs on watch, phone and tablet, reading the Polar H10 via BLE on any device. Remove the whole Samsung pipeline. The web dashboard stays as-is except for removing watch-only fields/filters.
- **L4:** Emulator verification (Pixel 7, API 36): installed the debug APK, granted BLE/notification permissions, loaded five beds from the local backend, and confirmed the phone layout (2 columns, safe-area insets, no ActionBar). A simulated 1600x2560 @ 240 dpi tablet viewport rendered 4 columns. Screenshots are local artifacts under /tmp/opencode (not committed).
- **L3:** Implementation decisions: (a) delete historical `source='samsung_watch'` rows in the Alembic migration and drop the four columns; (b) Room v9 rebuilds the table to preserve pending band rows; (c) `POLAR_DEVICE_ID` defaults to `"Polar H10"` so no build flag is needed; (d) phone/tablet use Material 3 screens selected by `PackageManager.FEATURE_WATCH`, watch hosts keep the Wear screens; (e) foreground service type changes from `health` to `connectedDevice` and the health permissions are removed. Commits: 8aff72d (backend/contracts), a178b64 (Android), 1f7571d (frontend).
