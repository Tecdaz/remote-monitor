# watch/libs/

This directory is for binary dependencies that cannot be declared in the
version catalog (e.g., proprietary AARs that are not on Maven Central or
Google Maven). It honors the project rule "no hand-written dependency
files" because the binary artifacts here are fetched once via CLI
from their authoritative source and committed for reproducibility.

## Samsung Health Sensor SDK AAR (REQ-WATCH-12) — PLACEHOLDER

The `BatchUploadWorkerTest` (PR 2, the merge gate) and the
`SamsungSpO2Provider` (PR 2 sensor layer) require the Samsung Health Sensor
SDK AAR. This AAR is proprietary and must be fetched from the Samsung
developer portal.

### Current state (sdd-apply batch, 2026-06-30)

| Field | Value |
|---|---|
| AAR file | `watch/libs/samsung-health-tracking.aar` (PLACEHOLDER) |
| Real AAR version | UNKNOWN — see "Manual install" below |
| SHA-256 (placeholder) | `47d10e7aedbeb4ced69ef4b9d67b0c51e9c08e178b41234374d83dbb37866239` |
| Build state | AAR dependency line is **commented out** in `app/build.gradle.kts`. Build works without the AAR. |
| Real SDK | TBD — to be installed manually by a user with Samsung developer access. |

### Manual install (one-time, requires Samsung account)

1. Sign in to https://developer.samsung.com/health/sensor with a Samsung
   developer account.
2. Download the latest `samsung-health-tracking` AAR (current series is
   `1.x`; the exact filename and version are not derivable from the
   portal's HTML without a browser session).
3. Replace the placeholder:

   ```bash
   # from repo root
   rm watch/libs/samsung-health-tracking.aar
   cp ~/Downloads/samsung-health-tracking-*.aar watch/libs/samsung-health-tracking.aar
   sha256sum watch/libs/samsung-health-tracking.aar
   ```

4. Update the SHA-256 + version table in this file.
5. Uncomment the dependency line in `watch/app/build.gradle.kts`:

   ```kotlin
   implementation(files("libs/samsung-health-tracking.aar"))
   ```

6. Regenerate the lockfile and commit:

   ```bash
   ./gradlew :app:dependencies --write-locks
   git add watch/libs/samsung-health-tracking.aar watch/libs/README.md \
           watch/app/build.gradle.kts watch/app/gradle.lockfile
   git commit -m "feat(watch): add samsung health sensor SDK AAR (vX.Y.Z)"
   ```

### Why the sdd-apply agent could not download the AAR

A direct `curl` to `https://developer.samsung.com/health/sensor` returns
an HTML landing page (HTTP 200, ~93 KB) — not the AAR. The portal
requires a logged-in browser session and a license click-through. The
sdd-apply agent does not have credentials and the URL is not derivable
without rendering the page's JavaScript.

A placeholder binary was committed to keep the path tracked. The build
does not depend on it (the dependency line is commented out), so PR 2
remains green without the real AAR. PR 3 or a follow-up must add the
real AAR before `SamsungSpO2Provider` (T-WATCH-27) is exercised on
device.

## Why committed (not fetched at build time)

- **Reproducibility**: build works without network access to the Samsung portal.
- **HIPAA-like auditability**: the exact AAR version + SHA is in git history.
- **CI determinism**: no fetch step that could fail or change the resolved binary.

The AAR is a binary artifact, not a dependency *manifest* (the manifest
remains in `gradle/libs.versions.toml` with coords only). The two are
separate concerns.
