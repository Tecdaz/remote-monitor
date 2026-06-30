# watch

Wear OS 6 patient monitor. The watch app collects heart rate and
blood-oxygen measurements, stores them locally, and uploads them in
batches to the backend.

## Target

- Platform: Wear OS 6 (API 36)
- Reference device: Samsung Galaxy Watch 4
- Language: Kotlin
- UI: Compose for Wear OS

## Sensor stack

- Health Services for heart rate (BPM) and SpO2 collection
- Room for local measurement storage
- HTTPS batch uploads to the backend

## Critical invariant — `delete-after-echo`

The watch only deletes a Room row after the backend acknowledges the
upload with a 2xx response that echoes the IDs back. If the backend is
down, measurements accumulate in Room and the watch retries with
backoff. This is the highest-risk correctness boundary in the system;
it is the first test contract to land in the spec for this component.

## Android toolchain status

The host running this build has the Android toolchain installed
(JDK 21 + AGP 9.2.1 + Gradle 9.6.1). The `watch/` folder builds with
`./gradlew :app:assembleDebug` and runs `./gradlew :app:testDebugUnitTest`
as the unit-test gate.

## Known HIPAA gaps

The following are documented gaps in the current PoC build. None block
the CI merge gate, but each is a follow-up that must close before
this app handles production PHI.

| Gap | REQ | Mitigation status | Follow-up |
|-----|-----|-------------------|-----------|
| **Plaintext DataStore** — `IdentityRepository` persists the operator-typed patient number and the backend-issued patient UUID in DataStore Preferences without encryption. A device that is rooted, lost, or backed up to a non-encrypted cloud leaks both fields. | REQ-WATCH-20 | Open | Wire `EncryptedSharedPreferences` or a `Tink`-backed `DataStore<Preferences>`; add a `MasterKey` rotated at first launch and on operator logout. |
| **Silent retention on reject** — the `BatchUploadWorker` accepts a 2xx with `rejected: [{local_id, reason}]` and keeps the rejected row in Room. The home screen never surfaces the rejection, so a malformed measurement is silently retained until the operator clears it. | REQ-WATCH-05 S05.2 | Open | Add a "Rejected uploads" tile in the home screen that lists the latest N rows; provide a one-tap "Discard" action that deletes the row from Room. |
| **Samsung SDK lock-in** — the SpO2 sensor path is implemented against the Samsung Health Sensor SDK (`com.samsung.android.service.health.tracking`). On non-Samsung watches (or Samsung watches without the proprietary service), `NullSpO2Provider` substitutes null SpO2 readings. | REQ-WATCH-02, 03 | Open | Add a Qualcomm / generic SpO2 sensor path; gate SpO2 features by an OEM allow-list rather than silently degrading. |
| **5-second sync cadence** — the foreground service polls the Room queue every 5 seconds. On a Wear OS 6 device, this keeps the radio warm and draws measurable battery; it is PoC-acceptable, not production-acceptable. | REQ-WATCH-07 | Open | Switch to a WorkManager `PeriodicWorkRequest` (15-min minimum on Wear OS), with on-demand triggers when the Room count changes. |
| **Placeholder Samsung AAR** — `watch/libs/samsung-health-tracking.aar` is committed as a stub (the real binary is not public on Maven). `implementation(files("libs/samsung-health-tracking.aar"))` in `app/build.gradle.kts` is intentionally commented out. | REQ-WATCH-12 | Manual | The real AAR must be downloaded from https://developer.samsung.com/health/sensor and dropped in at the same path; the SHA-256 must be recorded in `watch/libs/README.md`. The version catalog entry stays group:artifact only. |

## Pinned tokens

The following strings appear on their own line so the scaffold's
acceptance grep can verify them literally.

Wear OS 6
API 36
Health Services
Room
delete-after-echo
Kotlin
Compose for Wear OS
Galaxy Watch 4
HIPAA
Plaintext DataStore
Samsung SDK

## Follow-up

- HIPAA gap closure: see the table above.
- AGP 9.x escape-hatch (`android.builtInKotlin=false`,
  `android.newDsl=false`, explicit `kotlin-android` plugin) must be
  removed before AGP 10.0 removes the flags. KSP 2.3.9 does not yet
  support `android.sourceSets` natively on AGP 9.x.
