#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> Loading environment..."
if command -v direnv >/dev/null 2>&1; then
    eval "$(direnv export bash)"
fi

echo "==> Starting SSM database tunnel..."
./scripts/start-db-tunnel.sh &
TUNNEL_PID=$!

cleanup() {
    echo
    echo "Stopping database tunnel..."
    kill "$TUNNEL_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "Waiting for database tunnel..."
until nc -z localhost "${POSTGRES_PORT:-15432}" >/dev/null 2>&1; do
    sleep 1
done

echo "==> Starting Spring Boot API..."
cd api
exec mvn spring-boot:run -Dspring-boot.run.profiles=local
