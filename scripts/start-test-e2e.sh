#!/usr/bin/env bash
# scripts/start-test-e2e.sh
#
# Full E2E test reset. One command, fresh everything.
#
#   1. Wipes the backend DB (via start-test-stack.sh).
#   2. Detects the wireless-adb watch and:
#      a. `pm clear com.remotemonitor.watch` (wipes Room, IdentityRepository, etc.)
#      b. Re-grants runtime permissions required by the health-type FGS.
#      c. Launches MainActivity.
#      d. Drives the onboarding UI: clears the input field, types an
#         auto-generated patient number, taps Continue.
#      e. Polls for the "Monitoring patient" confirmation screen.
#
# Result: in 20-30 s you have a fresh DB + a fresh watch app + a freshly
# registered patient + a running SyncForegroundService + batches flowing.
#
# Usage:
#   ./scripts/start-test-e2e.sh         # interactive (no -y flag pass-through)
#   ./scripts/start-test-e2e.sh -y      # non-interactive (CI / scripting)
#
# Notes:
#   - The watch step is best-effort. If no wireless-adb watch is connected,
#     the script logs a warning and continues — the backend wipe still matters.
#   - The patient number is auto-generated: P-TEST-<unix_timestamp>.
#   - UI elements are discovered by content-desc/text via `uiautomator dump`,
#     NOT hardcoded coordinates — robust against minor layout changes.
#   - Does NOT manage the ngrok tunnel — start it separately if needed:
#       ngrok http 8000 --domain=<your-reserved-domain>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# --- Args: pass through -y to start-test-stack.sh --------------------------
EXTRA_ARGS=()
for arg in "$@"; do
  case "$arg" in
    -y) EXTRA_ARGS+=(-y) ;;
    -h|--help)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Usage: $0 [-y]" >&2
      exit 2
      ;;
  esac
done

# --- Step 1: wipe backend DB via start-test-stack.sh ----------------------
echo "============================================================"
echo "STEP 1/3: Wipe backend DB + start stack"
echo "============================================================"
"$SCRIPT_DIR/start-test-stack.sh" "${EXTRA_ARGS[@]}"

# --- Helpers ----------------------------------------------------------------
log_step() { echo ""; echo "============================================================"; echo "$1"; echo "============================================================"; }

SERIAL=""

find_watch_serial() {
  # Pick the first wireless-adb transport (mDNS name ends in ._adb-tls-connect._tcp).
  # `adb devices -l` output format:
  #   <serial> <state> product:<x> model:<y> device:<z> transport_id:<n>
  # so state is field $2.
  SERIAL=$(adb devices -l 2>/dev/null \
    | awk '/_adb-tls-connect._tcp/ && $2 == "device" {print $1; exit}' \
    || true)
}

dump_ui() {
  local out="$1"
  adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb -s "$SERIAL" shell cat /sdcard/ui.xml > "$out" 2>/dev/null
}

# Wait up to N seconds for `predicate_cmd` to find a non-empty result.
# predicate_cmd receives the path to the dumped UI XML and must print something
# (the matching text) on stdout, or nothing.
wait_for_ui() {
  local timeout_s="$1"; shift
  local predicate="$1"; shift
  local out
  out="$(mktemp)"
  local elapsed=0
  while [ "$elapsed" -lt "$timeout_s" ]; do
    if dump_ui "$out"; then
      local result
      result="$(bash -c "$predicate" _ "$out" 2>/dev/null || true)"
      if [ -n "$result" ]; then
        rm -f "$out"
        printf '%s' "$result"
        return 0
      fi
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  rm -f "$out"
  return 1
}

# Find a node's center coordinates by content-desc OR text.
# Usage: tap_node "<predicate_substring>"  (e.g. "Patient number input")
tap_node() {
  local needle="$1"
  local out
  out="$(mktemp)"
  dump_ui "$out"
  local coords
  coords=$(python3 - "$out" "$needle" <<'PY'
import sys, xml.etree.ElementTree as ET, re
xml_path, needle = sys.argv[1], sys.argv[2]
tree = ET.parse(xml_path)
for n in tree.getroot().iter('node'):
    t = (n.get('text') or '').strip()
    cd = (n.get('content-desc') or '').strip()
    if needle in t or needle in cd:
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds',''))
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print(f'{(x1+x2)//2} {(y1+y2)//2}')
            sys.exit(0)
sys.exit(1)
PY
  )
  rm -f "$out"
  if [ -z "$coords" ]; then
    echo "  ERROR: node '$needle' not found in current UI" >&2
    return 1
  fi
  echo "  tap '$needle' @ ($coords)"
  adb -s "$SERIAL" shell input tap $coords
}

# Detect the ChargingAod overlay (the watch is on the charger). While this is
# the focused window, any non-system activity we `am start` is killed by
# timeout. Caller should bail with a clear message if this returns 0.
is_charging_aod_active() {
  adb -s "$SERIAL" shell dumpsys window 2>/dev/null \
    | awk '/mCurrentFocus=/ && /ChargingAod|SysUiActivity/ {found=1} END{exit !found}'
  # Note: SysUiActivity is the watchface, not necessarily charging AOD. We
  # return 0 only when we can confirm ChargingAod by content-desc.
  local out
  out="$(mktemp)"
  dump_ui "$out"
  local has_loading
  has_loading=$(python3 - "$out" <<'PY'
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
for n in tree.getroot().iter('node'):
    cd = (n.get('content-desc') or '').strip()
    if cd == 'Cargando' or cd == 'Loading':
        sys.exit(0)
sys.exit(1)
PY
  )
  rm -f "$out"
  return $has_loading
}

# --- Step 2: wipe + re-grant watch -----------------------------------------
log_step "STEP 2/3: Wipe watch app + re-grant runtime permissions"
find_watch_serial

if [ -z "$SERIAL" ]; then
  echo "WARN: no wireless-adb watch connected (looked for '_adb-tls-connect._tcp' transport)."
  echo "      Backend wipe is done. To complete the watch reset manually:"
  echo "        adb shell pm clear com.remotemonitor.watch"
  echo "        adb shell pm grant com.remotemonitor.watch android.permission.health.READ_HEART_RATE"
  echo "        adb shell pm grant com.remotemonitor.watch android.permission.BODY_SENSORS"
  echo "        adb shell pm grant com.remotemonitor.watch android.permission.POST_NOTIFICATIONS"
  echo "        adb shell am start -n com.remotemonitor.watch/.ui.MainActivity"
  echo ""
  echo "Final state: backend stack fresh. Watch not touched."
  exit 0
fi

echo "Found watch: $SERIAL"
adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | sed 's/^/  model: /'

echo ""
echo "  pm clear com.remotemonitor.watch"
adb -s "$SERIAL" shell pm clear com.remotemonitor.watch 2>&1 | sed 's/^/  /'

echo ""
echo "  Re-granting runtime permissions (required for FGS type=health):"
for p in \
  "android.permission.health.READ_HEART_RATE" \
  "android.permission.BODY_SENSORS" \
  "android.permission.POST_NOTIFICATIONS"
do
  adb -s "$SERIAL" shell pm grant com.remotemonitor.watch "$p" 2>&1 | sed "s|^|    $p -> |"
done

# --- Step 3: launch + drive onboarding --------------------------------------
log_step "STEP 3/3: Launch + drive onboarding"

# Bail out early if the ChargingAod overlay is up — the watch is on the charger
# and any activity we `am start` will be killed by timeout. Tell the user.
if is_charging_aod_active; then
  cat <<'EOF'
ERROR: The watch is on the charger (ChargingAod overlay is active).
       While the ChargingAod has focus, any non-system activity is killed by
       ActivityManager timeout (~2.3s). The UI automation below cannot drive
       the onboarding screen.

Backend wipe + watch pm clear + permission grant are already done (STEP 1+2).

To finish the watch reset manually:
  1. Take the watch off the charger (or wait until charging stops).
  2. Press the side button / tap the screen to wake it.
  3. From the launcher, tap the remote-monitor watch icon.
  4. The onboarding screen ("Pair your watch · Patient number input") will
     appear. Type a patient number (regex ^[A-Za-z0-9-]{4,32}$) and tap
     Continue.

EOF
  exit 1
fi

PATIENT_NUMBER="P-TEST-$(date +%s)"
echo "Auto-generated patient number: $PATIENT_NUMBER"
echo ""

echo "  am start -n com.remotemonitor.watch/.ui.MainActivity"
echo "    (KEYCODE_WAKEUP first — without it the screen is asleep and focus reverts to AOD)"
adb -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 1
adb -s "$SERIAL" shell am start -n com.remotemonitor.watch/.ui.MainActivity 2>&1 | sed 's/^/    /'

echo ""
echo "  waiting for onboarding screen (content-desc='Patient number input')..."
if ! wait_for_ui 15 "python3 -c \"import xml.etree.ElementTree as ET,sys; t=ET.parse(sys.argv[1]); print(any('Patient number input' in (n.get('content-desc') or '') for n in t.getroot().iter('node')))\""; then
  echo "  ERROR: onboarding screen did not appear in 15s." >&2
  exit 1
fi
echo "  onboarding visible."

echo ""
echo "  tapping patient number field"
tap_node "Patient number input"

echo "  clearing field (50x DEL)"
for _ in $(seq 1 50); do
  adb -s "$SERIAL" shell input keyevent KEYCODE_DEL >/dev/null 2>&1
done

echo "  typing patient number"
adb -s "$SERIAL" shell input text "$PATIENT_NUMBER" 2>&1 | sed 's/^/    /'

echo ""
echo "  tapping Continue"
tap_node "Continue"

echo ""
echo "  waiting for 'Monitoring patient' confirmation..."
if ! wait_for_ui 20 "python3 -c \"import xml.etree.ElementTree as ET,sys; t=ET.parse(sys.argv[1]); print(any('Monitoring patient' in (n.get('text') or '') for n in t.getroot().iter('node')))\""; then
  echo "  ERROR: 'Monitoring patient' did not appear in 20s." >&2
  exit 1
fi

echo ""
echo "============================================================"
echo "OK. E2E test env is fresh:"
echo "  - Backend DB: wiped, alembic at head"
echo "  - Watch: pm clear + runtime permissions granted"
echo "  - Patient: $PATIENT_NUMBER (auto-registered via onboarding UI)"
echo "  - FGS: should be running; sensor data should start flowing in 5-10s"
echo ""
echo "Monitor with:"
echo "  adb -s $SERIAL logcat | grep -E 'ServiceListener|okhttp.OkHttpClient'"
echo "  docker compose logs -f backend | grep measurements"
echo "============================================================"