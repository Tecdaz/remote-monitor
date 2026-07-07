#!/usr/bin/env bash
# scripts/start-test-stack.sh
#
# Canonical way to start the backend for local testing.
#
#   ALWAYS wipes the postgres volume (data + schema) and re-runs migrations
#   so the DB starts in a known clean state. Use this instead of
#   `docker compose up -d --build` when you want a fresh test DB.
#
# Safety guards:
#   1. Refuses to run unless backend/.env APP_PII_ENCRYPTION_KEY matches the
#      local-dev sentinel. Production-style envs will trip the guard.
#   2. Asks for confirmation before wiping (skip with -y for CI / scripting).
#
# Usage:
#   ./scripts/start-test-stack.sh         # interactive confirm
#   ./scripts/start-test-stack.sh -y      # non-interactive (CI / scripts)
#
# Does NOT manage the ngrok tunnel — start that separately if you need it:
#   ngrok http 8000 --domain=<your-reserved-domain>

set -euo pipefail

# Resolve project root (one level up from this script).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

# --- Safety guard -----------------------------------------------------------
EXPECTED_KEY="dev-only-encryption-key-not-for-prod-9c0a7f3e2b1d4a5f"
ACTUAL_KEY=""
if [ -f backend/.env ]; then
  ACTUAL_KEY=$(grep '^APP_PII_ENCRYPTION_KEY=' backend/.env | cut -d= -f2- || true)
fi
if [ "$ACTUAL_KEY" != "$EXPECTED_KEY" ]; then
  echo "ERROR: backend/.env APP_PII_ENCRYPTION_KEY does not match the local-dev sentinel." >&2
  echo "  expected: $EXPECTED_KEY" >&2
  echo "  got     : ${ACTUAL_KEY:-<unset>}" >&2
  echo "This script is for local-dev / test only. Refusing to wipe a non-dev env." >&2
  exit 1
fi

# --- Args -------------------------------------------------------------------
SKIP_CONFIRM=0
while getopts "y" opt; do
  case "$opt" in
    y) SKIP_CONFIRM=1 ;;
    *) echo "Usage: $0 [-y]" >&2; exit 2 ;;
  esac
done

if [ "$SKIP_CONFIRM" -eq 0 ]; then
  echo "About to WIPE the local postgres volume and rebuild the backend."
  echo "Volume 'remote-monitor_postgres_data' and all clinical / pii data will be LOST."
  read -r -p "Continue? [y/N] " reply
  echo
  if ! [[ "$reply" =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
  fi
fi

# --- Wipe + rebuild ---------------------------------------------------------
echo "==> docker compose down -v (wipes postgres volume)"
docker compose down -v

echo "==> docker compose up -d --build"
docker compose up -d --build

# --- Wait for postgres healthy ---------------------------------------------
# We grep for "(healthy)" in `docker compose ps` output instead of parsing
# `docker compose ps --format json`, whose shape differs between compose v2
# minor versions (object vs array).
echo "==> waiting for postgres healthy"
HEALTHY=0
for _ in $(seq 1 30); do
  if docker compose ps postgres 2>/dev/null | grep -q "(healthy)"; then
    HEALTHY=1
    break
  fi
  sleep 1
done
if [ "$HEALTHY" -ne 1 ]; then
  echo "ERROR: postgres did not become healthy in 30s." >&2
  docker compose ps postgres >&2
  docker compose logs --tail=30 postgres >&2
  exit 1
fi

# --- Wait for backend readyz ------------------------------------------------
echo "==> waiting for backend /api/v1/readyz"
READY=0
for _ in $(seq 1 30); do
  CODE=$(curl -s --max-time 2 -o /dev/null -w "%{http_code}" http://127.0.0.1:8000/api/v1/readyz || true)
  if [ "$CODE" = "200" ]; then
    READY=1
    break
  fi
  sleep 1
done
if [ "$READY" -ne 1 ]; then
  echo "ERROR: backend /api/v1/readyz did not return 200 in 30s (last code: $CODE)." >&2
  docker compose logs --tail=30 backend >&2
  exit 1
fi

# --- Run migrations ---------------------------------------------------------
echo "==> alembic upgrade head"
(
  cd backend
  APP_DATABASE_URL="postgresql+asyncpg://postgres:postgres@localhost:5432/remote_monitor" \
    uv run alembic upgrade head
)

# --- Done -------------------------------------------------------------------
echo ""
echo "OK. Stack ready at http://127.0.0.1:8000  (direct) / via your ngrok URL (tunnel)."
echo "DB: pii.patients empty, alembic at head."
echo "ngrok is NOT managed by this script — start it separately if needed."