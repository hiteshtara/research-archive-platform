#!/usr/bin/env bash
# EXPLICIT lab reset: deletes the containers, the directory, both databases,
# all test keys and the persistent-ID salt. Every fictional person gets new
# NameIDs and new simulated-Cognito profiles on the next start.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
"$(dirname "${BASH_SOURCE[0]}")/stop.sh" >/dev/null
[ -f "$CREDS/secrets.env" ] && load_secrets
compose down -v --remove-orphans >/dev/null 2>&1
rm -rf "$STATE"
echo "Identity lab reset: containers, volumes, keys and salt removed."
