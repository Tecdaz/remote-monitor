# watch/libs/

This directory is for binary dependencies that cannot be declared in the
version catalog (e.g., proprietary AARs that are not on Maven Central or
Google Maven). It honors the project rule "no hand-written dependency
files" because the binary artifacts here are fetched once via CLI
from their authoritative source and committed for reproducibility.

## Samsung Health Sensor SDK AAR (REQ-WATCH-12) — INSTALLED v1.4.1

The `BatchUploadWorkerTest` (PR 2, the merge gate) and the
`SamsungSpO2Provider` (PR 2 sensor layer) require the Samsung Health
Sensor SDK AAR. This AAR is proprietary and must be fetched from the
Samsung developer portal.

### Current state (2026-06-30)

| Field | Value |
|---|---|
| AAR file | `watch/libs/samsung-health-sensor-api-1.4.1.aar` |
| AAR version | `1.4.1` |
| SHA-256 | `893cd5d6564db0f304bf511a555c1d65ca6bccc8475fc979ff1d71d50680344c` |
| Size | 61,063 bytes |
| Build state | **active** — `implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))` is uncommented in `app/build.gradle.kts` |
| Source | https://developer.samsung.com/health/sensor (downloaded 2026-06-30 from `samsung-health-sensor-sdk-v1.4.1.zip`) |

### First install (already done on 2026-06-30)

If you need to redo the first install (e.g., fresh clone on another
machine), the steps are:

1. Sign in to https://developer.samsung.com/health/sensor with a Samsung
   developer account.
2. Download the latest SDK ZIP. The current series is `1.x`; the
   exact filename and version are not derivable from the portal's
   HTML without a browser session.
3. Extract and copy the AAR to `watch/libs/`:

   ```bash
   # from repo root
   unzip ~/Downloads/samsung-health-sensor-sdk-v1.4.1.zip -d /tmp/aar
   cp /tmp/aar/1.4.1/libs/samsung-health-sensor-api-1.4.1.aar watch/libs/
   sha256sum watch/libs/samsung-health-sensor-api-1.4.1.aar
   ```

4. Update the SHA-256 + version table in this file.
5. Uncomment the dependency line in `watch/app/build.gradle.kts`:

   ```kotlin
   implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))
   ```

6. Regenerate the lockfile and commit:

   ```bash
   ./gradlew :app:dependencies --write-locks
   git add watch/libs/samsung-health-sensor-api-1.4.1.aar watch/libs/README.md \
           watch/app/build.gradle.kts watch/app/gradle.lockfile
   git commit -m "feat(watch): add samsung health sensor SDK AAR (vX.Y.Z)"
   ```

### Upgrade (new Samsung SDK version)

1. Download the new SDK ZIP from the Samsung portal.
2. Replace the existing AAR:

   ```bash
   # from repo root
   rm watch/libs/samsung-health-sensor-api-*.aar
   unzip ~/Downloads/samsung-health-sensor-sdk-vX.Y.Z.zip -d /tmp/aar
   cp /tmp/aar/X.Y.Z/libs/samsung-health-sensor-api-X.Y.Z.aar watch/libs/
   sha256sum watch/libs/samsung-health-sensor-api-X.Y.Z.aar
   ```

3. Update `watch/app/build.gradle.kts` — both the comment block and
   the `implementation(files("..."))` line — to point at the new file.
4. Update the version table in this file (filename, version, SHA-256).
5. Regenerate the lockfile and commit (same `git add` + commit message
   as step 6 of the first install).

## Why committed (not fetched at build time)

- **Reproducibility**: build works without network access to the Samsung portal.
- **HIPAA-like auditability**: the exact AAR version + SHA is in git history.
- **CI determinism**: no fetch step that could fail or change the resolved binary.

The AAR is a binary artifact, not a dependency *manifest* (the manifest
remains in `gradle/libs.versions.toml` with coords only). The two are
separate concerns.
