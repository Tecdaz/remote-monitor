# wear-bed-picker-onboarding developer Makefile
#
# Single source of truth for the local dev workflow on a Galaxy Watch 4.
# All targets read backend/.env.test and watch/.env.test (both gitignored).
# Update those files first if you change ngrok URL or watch IP/port.

# --- Shell safety ---------------------------------------------------------
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.SHELLFLAGS := -eu -o pipefail -c

# --- File locations -------------------------------------------------------
BACKEND_ENV_FILE := backend/.env.test
WATCH_ENV_FILE   := watch/.env.test
APK              := watch/app/build/outputs/apk/debug/app-debug.apk
APP_PKG          := com.remotemonitor.watch
NGROK_LOG        := /tmp/opencode/ngrok-logs/ngrok.log

# --- Helpers (read env.test into Make vars) -------------------------------
# Use $(call env-key,FILENAME,KEY) → returns empty string if missing.
define env-key
$(shell grep -E "^$(2)=" $(1) 2>/dev/null | head -1 | cut -d= -f2-)
endef

WATCH_IP        := $(call env-key,$(WATCH_ENV_FILE),watchIP)
WATCH_PORT      := $(call env-key,$(WATCH_ENV_FILE),watchPort)
NGROK_URL       := $(call env-key,$(BACKEND_ENV_FILE),NGROK_PUBLIC_URL)
NGROK_AUTHTOKEN := $(call env-key,$(BACKEND_ENV_FILE),NGROK_AUTHTOKEN)

# Strip protocol + trailing slash from ngrok url for the `--domain=` flag.
# Use python here so Make's quoting doesn't fight bash parameter expansion.
NGROK_DOMAIN    := $(shell python3 -c 'import sys,sysconfig; u=sys.argv[1]; s=u.strip().split("://",1)[-1]; print(s[:-1] if s.endswith("/") else s)' "$(NGROK_URL)")

ADB_TARGET := $(if $(WATCH_IP):$(WATCH_PORT),$(WATCH_IP):$(WATCH_PORT),)
ADB := adb $(if $(ADB_TARGET),-s $(ADB_TARGET),)

# --- Default target -------------------------------------------------------
.DEFAULT_GOAL := help

# --- Help -----------------------------------------------------------------
.PHONY: help
help:
	@echo "wear-bed-picker-onboarding developer Makefile"
	@echo
	@echo "Source of truth: backend/.env.test (NGROK_*) and watch/.env.test (watchIP, watchPort)."
	@echo
	@echo "Lifecycle:"
	@echo "  make up                 docker compose up -d (postgres + backend + frontend)"
	@echo "  make rebuild            rebuild backend+frontend images, up -d, run migrations"
	@echo "  make down               docker compose down"
	@echo "  make ngrok-up           start ngrok tunnel in background (logs: $(NGROK_LOG))"
	@echo "  make ngrok-down         kill the ngrok tunnel"
	@echo
	@echo "Watch:"
	@echo "  make pair               instructions to pair a new watch (wireless adb)"
	@echo "  make connect            connect adb to the watchIP:watchPort in env.test"
	@echo "  make status             show adb devices + docker + ngrok + backend health"
	@echo "  make shell              adb shell into the connected watch"
	@echo
	@echo "Build / install / run:"
	@echo "  make build              build debug APK with ngrok URL baked in"
	@echo "  make install            install -r APK on the connected watch"
	@echo "  make run                wake screen + launch the app"
	@echo "  make build-install-run  build + install + run, in order"
	@echo
	@echo "Reset / clean:"
	@echo "  make backend-db-clean   TRUNCATE every table outside public (preserves schema)"
	@echo "  make watch-clean        pm clear $(APP_PKG)"
	@echo "  make watch-grant        pm grant sensor permissions (after pm clear)"
	@echo "  make watch-clean-all    watch-clean + watch-grant"
	@echo
	@echo "Tests:"
	@echo "  make test               run BOTH backend pytest + watch gradle tests"
	@echo "  make test-backend       pytest backend/tests"
	@echo "  make test-watch         ./gradlew :app:testDebugUnitTest"
	@echo
	@echo "Frontend:"
	@echo "  make frontend-install   bun install (first time or after package.json changes)"
	@echo "  make frontend-dev       bun run dev (local dev server, no Docker)"
	@echo "  make frontend-build     bun run build (local build check)"
	@echo "  make frontend-typecheck bunx tsc --noEmit"
	@echo "  make frontend-up        docker compose up -d frontend (container)"
	@echo "  make frontend-logs      docker compose logs -f frontend"
	@echo
	@echo "Convenience:"
	@echo "  make demo               up + ngrok-up + build-install-run"
	@echo "  make demo-clean         backend-db-clean + watch-clean-all + build-install-run"
	@echo
	@echo "All targets assume docker + adb + (ngrok | ngrok URL present in env.test) are available."

# --- Lifecycle ------------------------------------------------------------
.PHONY: up down rebuild
up:
	docker compose up -d
	@echo
	@echo "Backend ready:  http://localhost:8000"
	@echo "Frontend ready: http://localhost:3000"
	@echo "Postgres ready: localhost:5432"

# Rebuild the backend + frontend images with the current source tree and
# (re)start the containers. Necessary when local code changed but `make up`
# alone would reuse the cached image from a previous build.
#
# Also runs `alembic upgrade head` inside the backend container — the
# backend image itself does NOT auto-apply migrations on startup (see the
# Dockerfile's CMD), so any new migration lands here.
#
# Postgres is left running (volume untouched) so persisted clinical data
# survives across rebuilds. Use `make backend-db-clean` if you also want
# to wipe the DB.
rebuild:
	docker compose build backend frontend
	docker compose up -d backend frontend
	@echo
	@echo "Waiting for backend to be ready..."
	@for i in 1 2 3 4 5 6 7 8 9 10; do \
		if curl -sS --max-time 2 http://localhost:8000/api/v1/readyz >/dev/null 2>&1; then \
			echo "Backend is ready."; \
			break; \
		fi; \
		sleep 2; \
	done
	docker exec remote-monitor-backend-1 bash -lc "cd /app && uv run alembic upgrade head"
	@echo
	@echo "Rebuild complete. Backend: http://localhost:8000  Frontend: http://localhost:3000"

down:
	docker compose down

.PHONY: ngrok-up ngrok-down
ngrok-up:
	@if [ -z "$(NGROK_AUTHTOKEN)" ] || [ -z "$(NGROK_DOMAIN)" ]; then \
		echo "ERROR: NGROK_AUTHTOKEN or NGROK_PUBLIC_URL missing in $(BACKEND_ENV_FILE)"; \
		exit 1; \
	fi
	@mkdir -p $$(dirname $(NGROK_LOG))
	@if pgrep -x ngrok >/dev/null 2>&1; then \
		echo "ngrok already running (pid $$(pgrep -x ngrok | head -1))"; \
	else \
		echo "Starting ngrok in background (logs: $(NGROK_LOG))..."; \
		NGROK_AUTHTOKEN='$(NGROK_AUTHTOKEN)' nohup ngrok http 8000 \
			--domain=$(NGROK_DOMAIN) \
			--log=$(NGROK_LOG) \
			>/dev/null 2>&1 & \
		sleep 3; \
		echo "ngrok started (pid $$(pgrep -x ngrok | head -1))"; \
	fi
	@echo "Public URL: $(NGROK_URL)"

ngrok-down:
	-pkill -x ngrok 2>/dev/null
	@echo "ngrok stopped (if it was running)."

# --- Watch ----------------------------------------------------------------
.PHONY: pair connect status shell
pair:
	@echo "Pair a new Galaxy Watch 4 (wireless adb):"
	@echo "  1. On the watch: Settings → Developer options → Wireless debugging"
	@echo "  2. Tap 'Pair device with pairing code'"
	@echo "  3. Note the IP:port and pairing code shown on the watch"
	@echo
	@echo "Then run (host shell, not Make):"
	@echo "  adb pair <IP>:<port>"
	@echo "  # enter the pairing code when prompted"
	@echo
	@echo "Then update watch/.env.test:"
	@echo "  watchIP=<IP>"
	@echo "  watchPort=<port>"
	@echo
	@echo "Then: make connect"

connect:
	@if [ -z "$(WATCH_IP)" ] || [ -z "$(WATCH_PORT)" ]; then \
		echo "ERROR: watch/.env.test does not have watchIP and watchPort."; \
		echo "Run 'make pair' first, then edit $(WATCH_ENV_FILE)."; \
		exit 1; \
	fi
	@echo "Connecting to $(ADB_TARGET)..."
	-adb disconnect $(ADB_TARGET) 2>/dev/null
	adb kill-server 2>/dev/null
	sleep 1
	adb start-server 2>&1 | head -1
	sleep 1
	adb connect $(ADB_TARGET)
	@echo
	adb devices -l

status:
	@echo "=== docker ==="
	@docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>&1 | head -10 || echo "(docker unavailable)"
	@echo
	@echo "=== adb devices ==="
	@adb devices -l 2>&1
	@echo
	@echo "=== ngrok ==="
	@if pgrep -x ngrok >/dev/null 2>&1; then \
		pgrep -ax ngrok | head -1; \
	else \
		echo "(ngrok not running)"; \
	fi
	@echo
	@echo "=== backend health ==="
	@curl -sS --max-time 3 -w "HTTP %{http_code}\n" http://localhost:8000/api/v1/beds 2>&1 | head -3 || echo "(backend unreachable)"

shell:
	adb $(if $(ADB_TARGET),-s $(ADB_TARGET),) shell

# --- Build / install / run ------------------------------------------------
.PHONY: build install run build-install-run
build:
	@if [ -z "$(NGROK_URL)" ]; then \
		echo "ERROR: NGROK_PUBLIC_URL missing in $(BACKEND_ENV_FILE)"; \
		exit 1; \
	fi
	@echo "Building APK with apiBaseUrl=$(NGROK_URL)"
	cd watch && ./gradlew :app:assembleDebug -PapiBaseUrl="$(NGROK_URL)" --no-daemon
	@echo "Built: $(APK)"

install:
	@if [ ! -f $(APK) ]; then \
		echo "ERROR: $(APK) not found. Run 'make build' first."; \
		exit 1; \
	fi
	$(ADB) install -r $(APK)

run:
	@if [ -z "$(ADB_TARGET)" ]; then \
		echo "ERROR: no watch connected. Run 'make connect' first."; \
		exit 1; \
	fi
	$(ADB) shell input keyevent KEYCODE_WAKEUP 2>/dev/null
	$(ADB) shell am start -W -n $(APP_PKG)/.ui.MainActivity
	@echo "Launched $(APP_PKG)/.ui.MainActivity on $(ADB_TARGET)"

build-install-run: build install run

# --- Reset / clean --------------------------------------------------------
.PHONY: backend-db-clean watch-clean watch-grant watch-clean-all
backend-db-clean:
	@echo "WARNING: this DROPs all schemas + alembic_version in the backend Postgres"
	@echo "         and reapplies all migrations. Local-only (uses docker exec)."
	@read -p "Continue? (yes/no): " confirm && [ "$$confirm" = "yes" ] || { echo "Aborted"; exit 1; }
	docker exec remote-monitor-postgres-1 psql -U postgres -d remote_monitor \
		-c "DROP SCHEMA IF EXISTS pii CASCADE; DROP SCHEMA IF EXISTS clinical CASCADE; DROP SCHEMA IF EXISTS audit CASCADE; DROP TABLE IF EXISTS alembic_version;"
	docker exec remote-monitor-backend-1 bash -lc "cd /app && uv run alembic upgrade head"
	@echo "Backend DB clean + migrations reapplied."

watch-clean:
	@if [ -z "$(ADB_TARGET)" ]; then \
		echo "ERROR: no watch connected. Run 'make connect' first."; \
		exit 1; \
	fi
	$(ADB) shell pm clear $(APP_PKG)

watch-grant:
	@if [ -z "$(ADB_TARGET)" ]; then \
		echo "ERROR: no watch connected. Run 'make connect' first."; \
		exit 1; \
	fi
	$(ADB) shell pm grant $(APP_PKG) android.permission.health.READ_HEART_RATE
	$(ADB) shell pm grant $(APP_PKG) android.permission.health.READ_OXYGEN_SATURATION
	$(ADB) shell pm grant $(APP_PKG) android.permission.POST_NOTIFICATIONS
	@echo "Sensor + notification permissions granted."

watch-clean-all: watch-clean watch-grant
	@echo "Watch DataStore reset + sensor permissions granted."

# --- Tests ----------------------------------------------------------------
.PHONY: test test-backend test-watch
test-backend:
	@echo "=== backend pytest ==="
	cd backend && uv run pytest -v

test-watch:
	@echo "=== watch gradle test ==="
	cd watch && ./gradlew :app:testDebugUnitTest --no-daemon

test: test-backend test-watch
	@echo
	@echo "All tests passed."

# --- Frontend --------------------------------------------------------------
.PHONY: frontend-install frontend-dev frontend-build frontend-typecheck \
        frontend-up frontend-logs

frontend-install:
	cd frontend && bun install

frontend-dev:
	cd frontend && bun run dev

frontend-build:
	cd frontend && bun run build

frontend-typecheck:
	cd frontend && bunx tsc --noEmit

frontend-up:
	docker compose up -d --build frontend
	@echo
	@echo "Frontend ready: http://localhost:3000"

frontend-logs:
	docker compose logs -f frontend

# --- Convenience ----------------------------------------------------------
.PHONY: demo demo-clean
demo: up ngrok-up build-install-run
	@echo
	@echo "Demo ready."
	@echo "  Public URL: $(NGROK_URL)"
	@echo "  Watch: $(ADB_TARGET)"
	@echo "  App: $(APP_PKG)"
	@echo
	@echo "Useful next steps:"
	@echo "  make status         (overall state)"
	@echo "  make run            (relaunch)"
	@echo "  make watch-clean    (reset DataStore only)"
	@echo "  make backend-db-clean (reset backend only)"

demo-clean: backend-db-clean watch-clean-all build-install-run
	@echo
	@echo "Full reset complete. Demo is up."