#!/usr/bin/env bash
# Stops the synthetic identity demo and removes its disposable database.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/.authz-demo"
for name in ui api; do
  if [ -f "$STATE/$name.pid" ]; then
    pkill -P "$(cat "$STATE/$name.pid")" 2>/dev/null
    kill "$(cat "$STATE/$name.pid")" 2>/dev/null
    rm -f "$STATE/$name.pid"
  fi
done
pkill -f "spring-boot:run.*authz-demo" 2>/dev/null
pkill -f "vite --mode authz-demo" 2>/dev/null
docker rm -f authz-demo-db >/dev/null 2>&1
echo "Synthetic identity demo stopped; disposable database removed."
