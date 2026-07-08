# Wear UI Guidelines — On-Watch Verification Runbook

## 1. Purpose

The `wear-ui-guidelines` change (`stacked-to-main` chain: PR-1
icons → PR-2 nav → PR-3 home → PR-4 ongoing-activity+notification)
ships a paired Wear Ongoing Activity alongside the
`SyncForegroundService` notification so the watch-face shows a
heart icon + sync status text while the watch is syncing. This
runbook is the on-call / clinical-engineering operator's reference
for installing the merged APK, verifying the new behaviour on a
real Galaxy Watch 4, and recovering from a regression.

Design decisions are recorded in `sdd/wear-ui-guidelines/design`
(obs #450) and `sdd/wear-ui-guidelines/spec` (obs #448). For an
ADR-style review, read obs #450 first; the spec holds the
acceptance criteria that this runbook verifies.

The PR-4 strings (3 NEW + 1 modified ES) are LOCKED but
UNAPPROVED — they require clinical sign-off (DEBT-002 per
`sdd/wear-bed-picker-onboarding/archive-report` #433 §3.1)
before they can ship to a production build. Section 2 walks through
the DEBT-002 checklist; Section 3 walks through the on-watch
verification; Section 4 covers rollback.

## 2. DEBT-002 clinical copy sign-off checklist (PR-4 strings)

PR-4 ships 4 strings in `values/strings.xml` + `values-es/strings.xml`:

| Key | EN | ES | Status |
|-----|-----|-----|--------|
| `sync_notification_title` (NEW) | `Sync` | `Sincronizando` | LOCKED |
| `sync_notification_text` (MODIFIED — ES added) | `Monitoring patient` | `Monitoreando paciente` | LOCKED |
| `sync_status_short` (NEW) | `Sync` | `Sincronizando` | LOCKED |
| `sync_bed_body_format` (NEW) | `Bed %1$s · syncing` | `Cama %1$s · sincronizando` | LOCKED |

**Pre-condition for GA:** clinical stakeholder signs off on the
4 strings above. The PR-4 PR body calls this out as an explicit
merge precondition; the PR-3 PR body does the same for the 4
`home_*` HR vitals strings (same DEBT-002).

Sign-off workflow (per `sdd/wear-bed-picker-onboarding/archive-report`
#433 §3.1):

1. Open the PR-4 PR.
2. Ping the clinical stakeholder in the PR thread with the 4
   strings above + their Spanish translations.
3. Wait for an explicit `clinical_sign_off_completed` reply in the
   PR thread.
4. After the reply lands, the maintainer can merge to `main` and
   tag the release.

Until the sign-off reply lands, do NOT merge PR-4 to a released
build. The PR can be opened, reviewed, and CI-checked, but the
merge button stays disabled.

## 3. Manual on-watch Tier-1 verification

The PR-4 spec (#448 cap 5 + cap 2) requires on-watch verification —
JVM tests cover the guard semantics (see
`SyncForegroundServiceOngoingActivityTest`) but cannot exercise
the actual `OngoingActivity.apply` system call.

### 3.1 Prerequisites

- A Galaxy Watch 4 (or any Wear OS 5+ device) paired to the
  Android Studio ADB host.
- A paired bed (1..5) — complete the `wear-bed-picker-onboarding`
  onboarding flow first.
- Build and install the APK from the merged `main` branch (or
  `feature/wear-ui-guidelines/ongoing-activity` while PR-4 is
  under review):

  ```bash
  cd /home/santiago/repos/remote-monitor
  ./gradlew :app:assembleDebug \
    -PapiBaseUrl="https://<your-ngrok-or-lan-url>/"
  adb install -r watch/app/build/outputs/apk/debug/app-debug.apk
  ```

  Full prerequisites are in
  [`docs/local-testing.md`](local-testing.md).

### 3.2 Tier-1 happy path

1. Cold-launch the app
   (`adb shell am start -n com.remotemonitor.watch/.ui.MainActivity`).
2. Navigate to the **Home** screen. The `SyncForegroundService` is
   started by the `DisposableEffect(Unit)` in `MainActivity` (WARN-006
   invariant — UNTOUCHED by PR-4).
3. **Verify the FGS notification**: swipe down from the top of the
   watch-face. The persistent notification is titled `Sync`
   (EN) / `Sincronizando` (ES) and the body reads
   `Bed <N> · syncing` (EN) / `Cama <N> · sincronizando` (ES) where
   `<N>` is the paired bed number.
4. **Verify the watch-face icon**: return to the watch-face
   (press the side button). A small heart icon should appear on
   the watch-face, indicating the Ongoing Activity is paired
   with the FGS notification. The icon is the PR-1
   `ic_sync_heart` vector (white-on-transparent monochrome per
   Wear 5+ rules).
5. **Verify Spanish locale**: change the watch language to
   Español (Settings → System → Languages). Cold-restart the
   app. Repeat steps 2-4 — the title, body, and watch-face
   status text must all read in Spanish.
6. **Verify swipe-to-dismiss on the FGS notification**: open the
   notification shade, swipe the FGS notification. The
   `onGoing(persistentlyShown = true)` flag should prevent the
   user from dismissing it; if the system does dismiss it, the
   OA is also removed (defense-in-depth teardown).

### 3.3 Tier-1 teardown verification

1. Force the FGS into the idle-stop branch: leave the watch
   alone for 10 minutes (the `IDLE_TIMEOUT_MS` constant in
   `SyncForegroundService`). After 10 minutes, the service
   calls `stopSelf()` and the OngoingActivity is removed.
2. **Verify OA removal**: return to the watch-face. The heart
   icon should be gone. Open the notification shade — the FGS
   notification is also gone.

### 3.4 Tier-1 known limitations

- The wear-ongoing 1.1.0 OA does NOT expose a
  `setProgress` / `setStopwatch` hook in this PR — the
  `sync_status_short` status text is static
  (`Sync` / `Sincronizando`). A future change could swap it
  for a `Status.StopwatchPart` to show "Syncing for 00:42"
  (would require a coroutine that calls `oa.update(ctx, status)`
  every second; out of scope for PR-4).
- The OA `setAnimatedIcon` is the PR-1
  `ic_sync_heart` vector. If the brand team ships a
  dedicated `ic_sync_animated` AVD-equipped drawable, replace
  only `watch/app/src/main/res/drawable/ic_sync_heart.xml`
  and re-run the PR-4 JVM test suite — no callers reference
  the path data directly.

## 4. Tier 2 (Responsive) + Tier 3 (Adaptive) follow-up — OUT OF SCOPE

Per the wear-os-app-theming exploration (#446), the home screen
meets Tier 1 (Ready) but Tier 2 (Responsive, 24+ languages) and
Tier 3 (Adaptive, dynamic font scaling) follow-ups are logged as
future work. PR-4 inherits the Tier 1 surface:

- Tier 2 requires a per-string ICU MessageFormat migration for
  plural-aware "syncing" / "sincronizando" copy (e.g. "Bed 3 · 1
  pending upload" vs "Bed 3 · 5 pending uploads"). Out of scope
  for PR-4 — the `sync_bed_body_format` slot is `%1$s` (the bed
  number), not a count.
- Tier 3 requires the OA status text to be readable at the
  watch's user-selected font scale. The current implementation
  uses the system default status text size; a future change
  would inject a `TextAppearance` override on the
  `Status.Builder`. Out of scope for PR-4.

Track these in a follow-up change (not part of `wear-ui-guidelines`).

## 5. Revert / rollback

PR-4 is the final commit in the `stacked-to-main` chain. To
revert, identify the merge commit on `main` for PR-4 and run:

```bash
git checkout main
git pull origin main
git revert -m 1 <merge-sha-of-pr-4>
git push origin main
```

The revert PR restores the prior FGS notification (English-only
`getString(R.string.app_name)` title + `Monitoring patient`
body, no Ongoing Activity). The `androidx.wear:wear-ongoing:1.1.0`
Gradle dep is removed cleanly by the revert (no other code paths
reference it). The `ic_sync_heart` drawable remains in place
(it's owned by PR-1, not PR-4) and is unused until PR-4 is
re-applied.

For a temporary rollback without removing the dep, comment out
the `publishOngoingActivity(bedNumber)` call in
`SyncForegroundService.onCreate` + `onStartCommand` and the
`removeOngoingActivity` call in `onDestroy` + the idle-stop
branch. The FGS continues to work — the watch-face icon is
absent but the sync loop + bed-aware body remain.

## 6. Files added / changed by PR-4

| File | Action | Notes |
|---|---|---|
| `watch/gradle/libs.versions.toml` | Modify | Added `wearOngoing = "1.1.0"` + `wear-ongoing` library row. |
| `watch/app/build.gradle.kts` | Modify | Added `implementation(libs.wear.ongoing)`. |
| `watch/app/gradle.lockfile` | Modify | Regenerated via `./gradlew :app:dependencies --write-locks`. |
| `watch/app/src/main/res/values/strings.xml` | Modify | 3 NEW strings (EN). |
| `watch/app/src/main/res/values-es/strings.xml` | Modify | 3 NEW strings (ES) + ES translation of `sync_notification_text`. |
| `watch/app/src/main/java/com/remotemonitor/watch/sync/SyncForegroundService.kt` | Modify | Migrated builder to `NotificationCompat.Builder`; added `bedNumber` param; added OA publish/teardown helpers + idempotent guard. |
| `watch/app/src/test/java/com/remotemonitor/watch/sync/SyncForegroundServiceOngoingActivityTest.kt` | Create | 3 Robolectric tests for the D7 guard semantics. |
| `docs/runbooks/ui-guidelines.md` | Create | This file. |

FGS hard constraints (`AndroidManifest.xml:74-77`) are
**unchanged** by PR-4: `foregroundServiceType="health"`, channel
id = `sync`, `exported="false"`. The `onGoing(persistentlyShown)`
flag is preserved; the `startForeground` call still uses the
health-typed FGS permission.
