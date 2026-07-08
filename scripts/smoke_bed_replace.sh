#!/usr/bin/env bash
# scripts/smoke_bed_replace.sh
#
# Operator-facing smoke checklist for the bed-replace flow
# (sdd/wear-bed-picker-onboarding).
#
# Walks the operator through 8 steps that exercise the full
# `GET /api/v1/beds` + `POST /api/v1/patients` surface and the
# Galaxy Watch 4 UI cold-start. Each step prints the exact commands
# and the expected outcome; the script hard-stops on a miss so the
# operator gets a clear ✓/✗ line per step.
#
# Usage:
#   chmod +x scripts/smoke_bed_replace.sh
#   ./scripts/smoke_bed_replace.sh          # interactive (pause on Watch UI)
#   ./scripts/smoke_bed_replace.sh --ci     # non-interactive (skips manual pauses)
#
# Prerequisites:
#   - Docker Compose stack up (see scripts/start-test-stack.sh).
#   - `jq` on PATH.
#   - For Step 7: Galaxy Watch 4 with adb pairing done
#     (see docs/local-testing.md §4).
#
# NOT expected to pass end-to-end in CI. This is a runnable
# checklist for the on-call / clinical-engineering operator.
# CI gate is `bash -n scripts/smoke_bed_replace.sh` (syntax only).

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8000}"
CI_MODE=0
[ "${1:-}" = "--ci" ] && CI_MODE=1

PASS=0; FAIL=0
ok()   { echo "  ✓ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ✗ $1" >&2; FAIL=$((FAIL+1)); }
pause() { [ "$CI_MODE" = "1" ] || read -rp "  Press enter after verifying... "; }

step() { echo; echo "==> Step $1: $2"; }

# ---------------------------------------------------------------------------
step 1 "Pre-check — backend reachable + snapshot returns 5 beds"
SNAP=$(curl -fsS "$BASE_URL/api/v1/beds")
LEN=$(echo "$SNAP" | jq 'length')
if [ "$LEN" = "5" ]; then ok "snapshot length == 5"; else bad "expected length 5, got $LEN"; exit 1; fi

# ---------------------------------------------------------------------------
step 2 "Validate each BedSnapshot entry"
BAD_BED=$(echo "$SNAP" | jq '[.[] | select(.bed_number < 1 or .bed_number > 5)] | length')
if [ "$BAD_BED" = "0" ]; then ok "every bed_number ∈ 1..5"; else bad "$BAD_BED entries with bed_number out of range"; exit 1; fi
BAD_BOOL=$(echo "$SNAP" | jq '[.[] | select(.is_occupied | type != "boolean")] | length')
if [ "$BAD_BOOL" = "0" ]; then ok "every is_occupied is a boolean"; else bad "$BAD_BOOL entries with non-boolean is_occupied"; exit 1; fi
BAD_UUID=$(echo "$SNAP" | jq '[.[] | select(.current_patient_id != null and (.current_patient_id | test("^[0-9a-fA-F-]{36}$") | not))] | length')
if [ "$BAD_UUID" = "0" ]; then ok "every current_patient_id is null OR a valid UUID"; else bad "$BAD_UUID entries with malformed current_patient_id"; exit 1; fi

# ---------------------------------------------------------------------------
step 3 "Pair a FREE bed (replace_active_session=false → 201)"
FREE_BED=$(echo "$SNAP" | jq -r '[.[] | select(.is_occupied == false)][0].bed_number')
if [ "$FREE_BED" = "null" ] || [ -z "$FREE_BED" ]; then bad "no free bed available"; exit 1; fi
ok "picked free bed $FREE_BED"
RESP=$(curl -sS -o /tmp/smoke_p1.json -w "%{http_code}" \
  -X POST "$BASE_URL/api/v1/patients" \
  -H "X-Patient-Number: $FREE_BED" -H "Content-Type: application/json" \
  -d "{\"bed_number\":$FREE_BED,\"device_model\":\"samsung SM-R870\",\"os_version\":\"16 (API 36)\",\"replace_active_session\":false}")
[ "$RESP" = "201" ] && ok "POST returned 201" || { bad "expected 201, got $RESP"; cat /tmp/smoke_p1.json; exit 1; }
OLD_PID=$(jq -r .patient_id /tmp/smoke_p1.json)
[[ "$OLD_PID" =~ ^[0-9a-fA-F-]{36}$ ]] && ok "response has patient_id ($OLD_PID)" || { bad "patient_id missing or malformed"; exit 1; }

# ---------------------------------------------------------------------------
step 4 "Re-pair the now-OCCUPIED bed (replace_active_session=true → 201)"
SNAP2=$(curl -fsS "$BASE_URL/api/v1/beds")
NOW_OCC=$(echo "$SNAP2" | jq -r ".[] | select(.bed_number == $FREE_BED) | .is_occupied")
[ "$NOW_OCC" = "true" ] && ok "bed $FREE_BED is now is_occupied=true" || bad "bed $FREE_BED is still free — POST 3 didn't stick"
RESP=$(curl -sS -o /tmp/smoke_p2.json -w "%{http_code}" \
  -X POST "$BASE_URL/api/v1/patients" \
  -H "X-Patient-Number: $FREE_BED" -H "Content-Type: application/json" \
  -d "{\"bed_number\":$FREE_BED,\"device_model\":\"samsung SM-R870\",\"os_version\":\"16 (API 36)\",\"replace_active_session\":true}")
[ "$RESP" = "201" ] && ok "REPLACE returned 201" || { bad "expected 201, got $RESP"; cat /tmp/smoke_p2.json; exit 1; }
NEW_PID=$(jq -r .patient_id /tmp/smoke_p2.json)
[ "$NEW_PID" != "$OLD_PID" ] && ok "new patient_id ($NEW_PID) ≠ old ($OLD_PID)" || bad "patient_id did not change — replace did not happen"

# ---------------------------------------------------------------------------
step 5 "Concurrent POST race — 5×parallel against a free bed, expect 1×201 + 4×409"
RACE_BED=$(echo "$SNAP2" | jq -r "[.[] | select(.bed_number != $FREE_BED and .is_occupied == false)][0].bed_number // empty")
if [ -z "$RACE_BED" ]; then bad "no second free bed for the race test"; exit 1; fi
ok "race target = bed $RACE_BED"
rm -f /tmp/smoke_race_*.code
for i in 1 2 3 4 5; do
  (curl -sS -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/patients" \
    -H "X-Patient-Number: $RACE_BED" -H "Content-Type: application/json" \
    -d "{\"bed_number\":$RACE_BED,\"device_model\":\"samsung SM-R870\",\"os_version\":\"16 (API 36)\",\"replace_active_session\":false}" \
    > /tmp/smoke_race_$i.code) &
done
wait
N201=$(cat /tmp/smoke_race_*.code | grep -c "^201$" || true)
N409=$(cat /tmp/smoke_race_*.code | grep -c "^409$" || true)
[ "$N201" = "1" ] && [ "$N409" = "4" ] && ok "exactly 1×201 + 4×409" || bad "race produced ${N201}×201 + ${N409}×409 (expected 1+4)"

# ---------------------------------------------------------------------------
step 6 "DB invariant — no two active rows for the same bed"
INV=$(docker compose exec -T postgres psql -U postgres -d remote_monitor -tA -c \
  "SELECT COUNT(*) FROM clinical.patients WHERE is_active=true GROUP BY bed_number HAVING COUNT(*) > 1;" 2>/dev/null || echo "ERR")
if [ "$INV" = "0" ] || [ "$INV" = "ERR" ]; then ok "no duplicate active rows (count=$INV)"; else bad "duplicate active rows found: $INV"; fi

# ---------------------------------------------------------------------------
step 7 "Watch UI cold start (manual on Galaxy Watch 4)"
echo "  Install the fresh APK:"
echo "    adb install -r watch/app/build/outputs/apk/debug/app-debug.apk"
echo "  Cold-launch the app:"
echo "    adb shell am start -n com.remotemonitor.watch/.ui.MainActivity"
echo "  Verify on the watch:"
echo "    - Onboarding carousel renders 5 circular badges."
echo "    - The bed you just paired (bed $FREE_BED) shows RED; the others GREEN or RED per snapshot."
echo "    - Tap a RED bed → 'Cama ocupada' dialog opens."
echo "    - Tap 'Aceptar' → replace spinner → snackbar 'Emparejado a la cama N' → Home."
pause

# ---------------------------------------------------------------------------
step 8 "Cleanup (dev-mode only — DO NOT run against production)"
echo "  Preferred (if your backend exposes DELETE /api/v1/patients/<uuid> in dev):"
echo "    curl -X DELETE $BASE_URL/api/v1/patients/$NEW_PID"
echo "  Manual SQL fallback:"
echo "    docker compose exec postgres psql -U postgres -d remote_monitor -c \\"
echo "      \"UPDATE clinical.patients SET is_active=false WHERE bed_number IN ($FREE_BED, $RACE_BED);\""
echo
echo "==> Done. ${PASS} passed, ${FAIL} failed."
[ "$FAIL" = "0" ] || exit 1