#!/usr/bin/env bash
# Stops the lab. Keeps the test keys, salt, directory and databases, so the
# next start gives every fictional person the same NameID and archive identity.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
for name in ui api; do
  if [ -f "$STATE/$name.pid" ]; then
    pkill -P "$(cat "$STATE/$name.pid")" 2>/dev/null
    kill "$(cat "$STATE/$name.pid")" 2>/dev/null
    rm -f "$STATE/$name.pid"
  fi
done
pkill -f "spring-boot.run.profiles=identity-lab" 2>/dev/null
pkill -f "vite --mode identity-lab" 2>/dev/null
[ -f "$CREDS/secrets.env" ] && load_secrets
compose stop >/dev/null 2>&1
echo "Identity lab stopped (state kept; reset.sh deletes it)."
