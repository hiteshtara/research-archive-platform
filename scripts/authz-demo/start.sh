#!/usr/bin/env bash
# Synthetic identity demo - BU federation not connected.
# Starts a DISPOSABLE local Postgres (Docker), the API built with -Pauthz-demo,
# and the UI in Vite mode authz-demo. Invented data only; nothing leaves this machine.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/.authz-demo"
DB_CONTAINER=authz-demo-db
DB_PORT="${AUTHZ_DEMO_DB_PORT:-55432}"
API_PORT="${AUTHZ_DEMO_API_PORT:-8091}"
UI_PORT="${AUTHZ_DEMO_UI_PORT:-5199}"
mkdir -p "$STATE"

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running." >&2; exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  echo "Starting disposable Postgres on 127.0.0.1:$DB_PORT ..."
  docker run -d --name "$DB_CONTAINER" -e POSTGRES_USER=demo -e POSTGRES_PASSWORD=demo \
    -e POSTGRES_DB=authz_demo -p "127.0.0.1:$DB_PORT:5432" pgvector/pgvector:pg17 >/dev/null
  until docker exec "$DB_CONTAINER" pg_isready -U demo -d authz_demo >/dev/null 2>&1; do sleep 1; done
  sleep 2
fi

if [ "$(docker exec "$DB_CONTAINER" psql -U demo -d authz_demo -Atc "select count(*) from information_schema.schemata where schema_name='authz_demo'")" != "1" ]; then
  echo "Applying migrations and the synthetic seed ..."
  for f in $(ls "$ROOT"/database/migrations/V*.sql | sort -t V -k2 -n); do
    docker exec -i "$DB_CONTAINER" psql -q -v ON_ERROR_STOP=1 -U demo -d authz_demo < "$f" >/dev/null
  done
  docker exec -i "$DB_CONTAINER" psql -q -v ON_ERROR_STOP=1 -U demo -d authz_demo < "$ROOT/api/src/test/resources/authz/synthetic-seed.sql"
fi

echo "Starting API (profile authz-demo) on :$API_PORT ..."
(cd "$ROOT/api" && exec env AUTHZ_DEMO_DB_PORT="$DB_PORT" AUTHZ_DEMO_API_PORT="$API_PORT" AUTHZ_DEMO_UI_PORT="$UI_PORT" \
  mvn -B -ntp -q -Pauthz-demo spring-boot:run -Dspring-boot.run.profiles=authz-demo) \
  < /dev/null > "$STATE/api.log" 2>&1 &
echo $! > "$STATE/api.pid"
until curl -sf "http://127.0.0.1:$API_PORT/actuator/health" >/dev/null 2>&1; do
  if ! kill -0 "$(cat "$STATE/api.pid")" 2>/dev/null; then echo "API failed; see $STATE/api.log" >&2; exit 1; fi
  sleep 2
done

echo "Starting UI (mode authz-demo) on :$UI_PORT ..."
[ -d "$ROOT/ui/node_modules" ] || (cd "$ROOT/ui" && npm ci --silent)
(cd "$ROOT/ui" && exec env VITE_API_BASE_URL="http://localhost:$API_PORT" VITE_AI_ENABLED=false \
  npx vite --mode authz-demo --port "$UI_PORT" --strictPort) < /dev/null > "$STATE/ui.log" 2>&1 &
echo $! > "$STATE/ui.pid"
until curl -sf "http://localhost:$UI_PORT/" >/dev/null 2>&1; do sleep 1; done

echo
echo "Synthetic identity demo - BU federation not connected"
echo "  UI:  http://localhost:$UI_PORT/awards/search?q=SYNTHETIC"
echo "  Switch test users with the bar at the bottom of the page."
echo "  Stop with: scripts/authz-demo/stop.sh"
