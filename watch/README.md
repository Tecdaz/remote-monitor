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

This host does **not** have the Android toolchain (`java`, `javac`,
`kotlin`, `gradle`, `adb` are all absent). The `watch/` folder is
structural only at this point. Build verification is deferred to CI or
a workstation with Android Studio. The real Gradle project, version
catalog, manifests, and Health Services integration land in the
follow-up `scaffold-watch` change.

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
Android toolchain
Galaxy Watch 4
scaffold-watch

## Follow-up

The full module — `build.gradle.kts`, version catalog, AndroidManifest,
Health Services permissions, Room schema, sync layer, retry/backoff
policy, and unit tests for the `delete-after-echo` invariant — lands in
the `scaffold-watch` SDD change. This README is the placeholder for
that work.
