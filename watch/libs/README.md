# watch/libs/

This directory is for binary dependencies that cannot be declared in the
version catalog (e.g., proprietary AARs that are not on Maven Central or
Google Maven). It honors the project rule "no handwritees archivos de
dependencias" because the binary artifacts here are fetched once via CLI
from their authoritative source and committed for reproducibility.

## Samsung Health Sensor SDK AAR (REQ-WATCH-12, blocked)

The `BatchUploadWorkerTest` (PR 2, the merge gate) and the
`SamsungSpO2Provider` (PR 2 sensor layer) require the Samsung Health Sensor
SDK AAR. This AAR is proprietary and must be fetched from the Samsung
developer portal.

**Workflow** (T-WATCH-12):

1. Download the AAR from https://developer.samsung.com/health/sensor
   (requires a Samsung developer account).
2. Place it at `watch/libs/samsung-health-tracking.aar`.
3. Compute the SHA-256: `sha256sum watch/libs/samsung-health-tracking.aar`
4. Update this README with the version + SHA.
5. In `watch/app/build.gradle.kts`, uncomment the line:
   ```kotlin
   implementation(files("libs/samsung-health-tracking.aar"))
   ```
6. Regenerate the lockfile: `./gradlew :app:dependencies --write-locks`
7. Commit: `feat(watch): add samsung health sensor SDK AAR (vX.Y.Z)`

## Why committed (not fetched at build time)

- **Reproducibility**: build works without network access to the Samsung portal.
- **HIPAA-like auditability**: the exact AAR version + SHA is in git history.
- **CI determinism**: no fetch step that could fail or change the resolved binary.

The AAR is a binary artifact, not a dependency *manifest* (the manifest
remains in `gradle/libs.versions.toml` with coords only). The two are
separate concerns.
