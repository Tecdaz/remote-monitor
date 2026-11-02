# Android collector app

Android application that reads the **Polar H10 band** over BLE and uploads
batches to the backend. The same APK runs on Wear OS watches, Android
phones and Android tablets; the host device only provides BLE, network and
UI. It does **not** read the watch's own sensors.

## Targets

- Platform: Android API 33+ (Polar BLE SDK 8.4.0 floor)
- Hosts: Wear OS watch, Android phone, Android tablet
- Language: Kotlin
- UI: Compose for Wear OS on watch hosts; Material 3 adaptive screens on
  phones/tablets
- Measurement device: Polar H10 (`POLAR_DEVICE_ID`, default `"Polar H10"`)

## Data collected

| Signal | Source | Cadence |
|---|---|---|
| BPM + RR (`heart_rate_bpm`, `ibis_ms`) | GATT Heart Rate Service | ~1 Hz |
| ECG (`ecg_uv`, `ecg_sample_rate_hz`) | Polar BLE SDK | 1 strip/s (10 s window) |

The Samsung watch fields (`spo2_percent`, `ibis_status`, `hr_status`,
`source`) and the Samsung Health Sensor SDK dependency were removed.

## Local storage

Room (v9) stores pending uploads. **Presence-in-table = pending upload**.
The app deletes a row only after the backend returns a 2xx whose
`accepted_ids` echoes its `local_id` (`delete-after-echo`). If the backend
is down, rows accumulate and the foreground service retries with backoff.

## Android toolchain

The host has JDK 21 + AGP 9.2.1 + Gradle 9.6.1. Build and test from
`watch/`:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Useful build flags:

```bash
# Backend URL baked into the APK
./gradlew :app:assembleDebug -PapiBaseUrl=http://192.168.1.42:8000/

# Band filter (default: "Polar H10")
./gradlew :app:assembleDebug -PpolarDeviceId=H10_ABC123
```

## Manifest/permissions

- BLE: `BLUETOOTH_SCAN` (neverForLocation) + `BLUETOOTH_CONNECT`
- Foreground service: `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- Notifications: `POST_NOTIFICATIONS`
- `android.hardware.type.watch` is optional; `android.hardware.bluetooth_le`
  is required.
- No `BODY_SENSORS` / `health.READ_*` permissions: the app does not read
  watch sensors.

## Known HIPAA gaps

These are documented gaps in the PoC build. None block the unit-test gate,
but each must close before handling production PHI.

| Gap | Mitigation status | Follow-up |
|---|---|---|
| **Plaintext DataStore** — the identity repository persists the bed number and backend patient UUID without encryption. | Open | Wire `EncryptedSharedPreferences` or a Tink-backed `DataStore`; rotate the key at first launch and on operator logout. |
| **Silent retention on reject** — the upload worker keeps a 2xx-rejected row in Room and the UI does not surface it. | Open | Add a "Rejected uploads" tile with a discard action. |
| **Foreground sync cadence** — 1 s polling keeps the radio warm. | Open | Move to a change-triggered upload while keeping the `delete-after-echo` invariant. |
| **Device filter is build-time** — a single `POLAR_DEVICE_ID` per APK. | Open | Add an in-app band picker for multi-band sites. |

## Follow-up

- The AGP 9.x escape hatch (`android.builtInKotlin=false`,
  `android.newDsl=false`, explicit `kotlin-android` plugin) must be removed
  before AGP 10 removes the flags.
- Room schema exports live in `app/schemas/`; commit every new version.
