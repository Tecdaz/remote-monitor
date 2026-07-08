# Bed-replace operator runbook

## 1. Purpose

The `wear-bed-picker-onboarding` change replaces the watch's old
"type a patient number" onboarding with a five-bed carousel. The
operator selects a bed; if it's free, the watch pairs immediately.
If it's occupied, a Spanish-language dialog asks the operator to
confirm a session replacement. This runbook is the on-call / clinical-
engineering operator's reference for installing the new APK, working
through the flow, and recovering from the three known misbehaviours.

Design decisions are recorded in
`sdd/wear-bed-picker-onboarding/design` (obs #419) and
`sdd/wear-bed-picker-onboarding/design-files` (obs #425). For an ADR-
style review, read obs #419 first; the design-files observation holds
the §11 literal diffs and §12 gate-findings audit trail.

## 2. What changed for operators

- The watch's onboarding screen now shows a five-bed carousel
  (circular badges, one per bed 1..5) instead of a text field.
- Occupied beds are tinted red and require a second confirmation
  (Spanish: `Aceptar`) before the active session is replaced.
- The `X-Patient-Number` header value is no longer operator-typed.
  The watch sends the bed plaintext (`"1"`..`"5"`); the backend derives
  the patient_number ciphertext deterministically.
- A `RE-emparejar` action on the **Home** screen routes paired-but-
  no-bed watches (legacy state) to the new **Repair required** screen.

## 3. Step-by-step operator workflow

1. Build and install the APK from the merged `main` branch (or
   `feature/wear-bed-picker-onboarding/runbook` while this PR is
   under review):

   ```bash
   cd /home/santiago/repos/remote-monitor
   ./gradlew :app:assembleDebug \
     -PapiBaseUrl="https://<your-ngrok-or-lan-url>/"
   adb install -r watch/app/build/outputs/apk/debug/app-debug.apk
   ```

   Full prerequisites are in [`docs/local-testing.md`](local-testing.md).

2. Power on the watch. Cold-launch the app
   (`adb shell am start -n com.remotemonitor.watch/.ui.MainActivity`).

3. The carousel renders five circular badges:
   - **Green** = the bed is free.
   - **Red** = the bed is currently occupied.

4. Swipe to your bed and tap its badge.
   - If **green**: the watch POSTs immediately, the snackbar
     `Emparejado a la cama N` appears, and you land on **Home**.
   - If **red**: the `Cama ocupada` dialog opens. Read the body text
     carefully — selecting **Aceptar** deactivates the active session
     on that bed.

5. On **Home**, the status line shows `Cama N · M subidas pendientes`.
   The watch now streams vitals to the backend.

## 4. Troubleshooting

**Q: The badge is green but tapping it shows "Cama ocupada" (or 409
`bed_now_occupied`).**
A: The snapshot was stale. Pull-to-refresh (or kill the app and
re-launch). Per design §7 row 2, the snapshot fetch lives in
`OnboardingScreen.kt`'s `LaunchedEffect(Unit)`; the watch re-fetches
on cold start only.

**Q: The watch is stuck on `Emparejado a la cama 3` and refuses to
re-pair.**
A: The DataStore is in a half-paired state from an interrupted POST.
Force-stop the app and clear DataStore:

```bash
adb shell am force-stop com.remotemonitor.watch
adb shell pm clear com.remotemonitor.watch
# Re-launch and re-pair from onboarding.
```

**Q: I tapped Aceptar but the home screen still shows the OLD bed
number.**
A: The atomic `persistPaired(...)` (D24) failed mid-write. Same
recovery as above: `pm clear` and re-pair. If it persists, capture
`adb logcat -d | grep IdentityRepositoryImpl` and file a bug — the
`S_persistPaired_writes_all_three_keys_in_one_edit_block` test should
have caught it.

**Q: `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.**
A: A previous build was signed with a different debug key. Uninstall
first: `adb uninstall com.remotemonitor.watch`.

## 5. ⚠ Carry-over: `NOT NULL` follow-up migration

The PR-2 migration
(`backend/migrations/versions/<rev>_add_bed_number_to_clinical_patients.py`)
adds `clinical.patients.bed_number SMALLINT` as **nullable** and
creates a partial UNIQUE index + CHECK constraint (D20). The `NOT NULL`
promotion is **deliberately deferred** — legacy NULL-active rows
cannot be backfilled deterministically without manual reconciliation
(see obs #425 §12.1).

**Before the next production migration deploys**, an operator MUST
run:

```sql
-- 1. Count legacy NULL-active rows that would block the NOT NULL.
SELECT COUNT(*) FROM clinical.patients
  WHERE is_active = true AND bed_number IS NULL;

-- 2. If the count is > 0, archive them (set is_active = false)
--    BEFORE running `alembic upgrade head` for the NOT NULL migration.
UPDATE clinical.patients SET is_active = false
  WHERE bed_number IS NULL;
```

This is a precaution, not part of the GA path.

## 6. ⚠ Carry-over: Spanish clinical copy review

The Spanish strings below are **locked** in the watch APK and need
clinical stakeholder sign-off BEFORE the next GA release. Each
citation is `watch/app/src/main/res/values-es/strings.xml:LINE`.

| Key | Spanish (locked) | Line |
|---|---|---|
| `onboarding_title` | `Selección de cama` | `15` |
| `onboarding_section_label` | `Selecciona la cama para este dispositivo` | `16` |
| `bed_free` | `Libre` | `17` |
| `bed_occupied` | `Ocupada` | `18` |
| `dialog_occupied_title` | `Cama ocupada` | `19` |
| `dialog_occupied_body` | `Esta cama ya tiene una sesión activa. Si continúas, se asignará una nueva sesión y la sesión actual quedará inactiva.` | `20` |
| `dialog_accept` | `Aceptar` | `21` |
| `dialog_cancel` | `Cancelar` | `22` |
| `snackbar_paired_to_bed` | `Emparejado a la cama %1$d` | `23` |
| `home_bed_label` | `Cama %1$d` | `27` |
| `home_status_label` | `Cama %1$d · %2$d subidas pendientes` | `28` |
| `action_repair` | `Re-emparejar` | `29` |

Sign-off checklist (clinical stakeholder):

- [ ] Each verb tense matches the on-screen action (impérativo for
      `Aceptar` / `Cancelar`).
- [ ] The `dialog_occupied_body` text accurately describes the
      side-effect: "la sesión actual quedará inactiva".
- [ ] The `home_status_label` pluralisation of `subidas pendientes`
      reads naturally for 1, 0, and ≥10 pending uploads.
- [ ] No medical jargon that a floor nurse wouldn't recognise.

## 7. Where the design lives

Every locked decision (D1–D33) is in
`sdd/wear-bed-picker-onboarding/design` (obs #419). The §11 literal
diffs (MeasurementsApi.kt, IdentityRepository interface/impl,
strings.xml, migration body) are in
`sdd/wear-bed-picker-onboarding/design-files` (obs #425). The §12
gate-findings audit trail is in the same observation.

For a guided E2E walkthrough on a real device, run
[`scripts/smoke_bed_replace.sh`](../scripts/smoke_bed_replace.sh)
after `scripts/start-test-stack.sh`.

## 8. Known limitations / out of scope

- Legacy `pii.patients.patient_number` UNIQUE was vestigial (non-
  deterministic `pgp_sym_encrypt`) and dropped in PR-2 (D3).
- `bed_number NOT NULL` follow-up migration is deferred — see §5.
- Spanish clinical copy review pending — see §6.
- No historical-session UI on the operator frontend. Per the proposal
  (§2 explicit out-of-scope), this change deactivates the prior
  session atomically but does not surface a "session history" view.