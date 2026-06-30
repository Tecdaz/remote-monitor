# Local testing on a real Galaxy Watch 4

End-to-end playbook for running the watch app on hardware against the
local backend, with or without ngrok. Targets the `watch-app` change
(PRs #1, #2, #3 merged on `feat/watch-app-pr2`) and the `backend-api`
change (PRs #1..#4b merged on `main`).

## 0. Prerequisites

- Docker (Compose v2: `docker compose ...`)
- JDK 21 (Android build)
- Android SDK with API 36 + build-tools 36.1.0 (`ANDROID_HOME` set)
- `ngrok` v3 (`~/.local/bin/ngrok` on this host, or `snap install ngrok`)
- A Galaxy Watch 4 with Wear OS 6 (or any Wear OS 5+ device for a UI smoke test)
- `uv` (Python dependency manager for the backend)
- A free ngrok account (https://dashboard.ngrok.com/signup) if you want
  to test without WiFi

## 1. Backend: docker compose + migrations

```bash
# 1.1. Copy the env template
cp backend/.env.test.example backend/.env.test
# Edit backend/.env.test to set APP_PII_ENCRYPTION_KEY (any random
# 32+ char string for local dev). Leave NGROK_AUTHTOKEN empty if
# you don't have an ngrok account yet.

# 1.2. docker-compose.yml already wires APP_PII_ENCRYPTION_KEY from
# the host's backend/.env (not from .env.test). Symlink it:
ln -sf .env.test backend/.env

# 1.3. Bring up the stack
cd /home/santiago/repos/remote-monitor
docker compose up -d --build
sleep 5
docker compose ps
# Both `backend-1` and `postgres-1` should show "Up" / "healthy".

# 1.4. Smoke test
curl -s http://127.0.0.1:8000/api/v1/readyz
# Expected: {"status":"ready"}

# 1.5. Run migrations (alembic is NOT auto-applied in the container;
# the Dockerfile only installs the app source, not the migrations
# folder. Run them from the host against the docker postgres).
cd backend
APP_DATABASE_URL="postgresql+asyncpg://postgres:postgres@localhost:5432/remote_monitor" \
  uv run alembic upgrade head
# Expected: INFO  [alembic.runtime.migration] Running upgrade  -> a9754bb20edf, initial schema
```

## 2. ngrok tunnel (optional, but recommended for real-device testing)

```bash
# 2.1. One-time: authenticate
ngrok config add-authtoken $(grep '^NGROK_AUTHTOKEN=' backend/.env.test | cut -d= -f2-)

# 2.2. Start the tunnel (keep this terminal open)
ngrok http 8000
# Note the printed URL: https://<random>.ngrok-free.app
# Update backend/.env.test so the next watch build picks it up:
echo "NGROK_PUBLIC_URL=https://<random>.ngrok-free.app/" >> backend/.env.test
```

The free-tier URL rotates on every restart. Without ngrok, use a direct
LAN IP in step 3 (`-PapiBaseUrl=http://192.168.1.X:8000/`).

## 3. Watch APK with the right URL

The `API_BASE_URL` is injected at build time via `-PapiBaseUrl=...` so
the same source can be tested against any backend (emulator loopback,
local LAN IP, ngrok HTTPS, production).

```bash
cd /home/santiago/repos/remote-monitor
git checkout feat/watch-app-pr2   # the chain tip with PRs #1..#3 merged
cd watch
./gradlew :app:assembleDebug -PapiBaseUrl="$(grep '^NGROK_PUBLIC_URL=' ../backend/.env.test | cut -d= -f2-)"
# Or for direct LAN:
# ./gradlew :app:assembleDebug -PapiBaseUrl="http://192.168.1.X:8000/"

# Verify the URL is baked into the APK:
$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer dex code \
  --class com.remotemonitor.watch.BuildConfig \
  app/build/outputs/apk/debug/app-debug.apk | grep API_BASE_URL
# Expected: .field public static final API_BASE_URL = "https://<your-url>/"
```

APK lands at `watch/app/build/outputs/apk/debug/app-debug.apk` (~44MB).

## 4. adb wireless pairing (Galaxy Watch 4)

On the watch:
1. **Settings → System → About** → tap **Build number** 7 times to
   enable Developer options.
2. **Settings → Developer options**:
   - Enable **ADB debugging**
   - Enable **Wireless debugging**
3. Tap **Wireless debugging** → note the **IP:Port** and
   **Pairing code** shown on screen.

On the host:
```bash
adb pair <watch-ip>:5555         # type the pairing code when prompted
adb connect <watch-ip>:5555
adb devices                      # confirm:  <watch-ip>:5555    device
```

## 5. Install + open

```bash
adb install -r watch/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.remotemonitor.watch/.ui.MainActivity
```

What you should see (cold launch):
- **Onboarding screen**: a text field labelled for the patient number
  and a "Continue" button. The button is disabled until the value
  matches `^[A-Za-z0-9-]{4,32}$`.
- Type `P-00042` (the OpenAPI canonical example) and tap Continue.
  The app calls `POST /api/v1/patients` against the build-time URL.
  On success, you land on **Home** with the status string
  `Monitoring patient P-00042 · 0 pending uploads` (the count is
  0 because Room is empty).
- After a few seconds the SyncForegroundService runs in background
  every 5s; with no Samsung AAR (placeholder) and no sensor data,
  it has nothing to upload, so the home screen stays at 0 pending.

## 6. Verify the watch is actually talking to your backend

In another terminal:
```bash
cd /home/santiago/repos/remote-monitor
docker compose logs -f backend
# Watch for: "POST /api/v1/patients HTTP/1.1 200 OK"
```

Or for a 1-shot check that the watch's network is reaching the
backend:
```bash
# On the watch, after typing P-00042 and tapping Continue:
adb logcat -d | grep -iE "okhttp|retrofit|remotemonitor" | tail -30
```

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `adb pair` says "Connection refused" | Watch firewall or wrong IP | Confirm watch is on the same WiFi; re-tap "Wireless debugging" to refresh the IP |
| `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A previous build was signed with a different key | `adb uninstall com.remotemonitor.watch` first, then `adb install` |
| Continue button stays disabled with `P-00042` | Old APK with the pre-fix regex | Re-build from `feat/watch-app-pr2` or later; the current regex is `^[A-Za-z0-9-]{4,32}$` |
| Backend returns 422 (Unprocessable Entity) | The watch's `MeasurementsApi` request body doesn't match the backend's Pydantic schema | Check `backend/app/schemas/measurements.py` and `watch/app/src/main/java/com/remotemonitor/watch/api/MeasurementsApi.kt`; both should be in sync with `contracts/openapi.yaml` |
| Backend returns 500 with "Wrong key or corrupt data" | `APP_PII_ENCRYPTION_KEY` doesn't match what was used to encrypt existing rows | `docker compose down -v && docker compose up -d` to wipe the DB, then re-run migrations and re-create the patient |
| Watch can't reach ngrok URL over LTE | ngrok free tier has bandwidth limits and may rate-limit cellular traffic | Wait a minute and retry, or fall back to a local LAN IP |
| `gradle :app:assembleDebug` fails with AGP/Kotlin version mismatch | Old toolchain on the host | Update `local.properties` to point at a fresh SDK; confirm JDK 21 is on PATH |
| The app on the watch crashes immediately | Stale dex cache or `BuildConfig` mismatch | `adb shell pm clear com.remotemonitor.watch` then `adb install -r ...` |

## 8. Cleaning up

```bash
# Stop the tunnel
pkill -f "ngrok http"

# Stop the backend stack (preserves the DB volume)
cd /home/santiago/repos/remote-monitor
docker compose down

# Stop and wipe the DB
docker compose down -v

# Uninstall from the watch
adb uninstall com.remotemonitor.watch

# Switch back to your work branch
cd /home/santiago/repos/remote-monitor
git checkout feat/watch-app-pr3
git stash pop  # restore any uncommitted .atl changes
```
