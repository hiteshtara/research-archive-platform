#!/usr/bin/env bash
# Local SAML integration - Cognito simulated: automated checks against the
# RUNNING lab (start it first with scripts/identity-lab/start.sh).
#   test.sh            end-to-end SAML/API tests (pytest)
#   test.sh --browser  also the Chromium walkthrough (needs: uv run --with playwright playwright install chromium)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/identity-lab/archive/tests"
uv run -q --no-project --with pytest --with requests --with pyjwt --with cryptography pytest -q
if [ "${1:-}" = "--browser" ]; then
  uv run -q --no-project --with playwright python browser_walkthrough.py "$ROOT/.identity-lab/walkthrough"
fi
cd "$ROOT/api"
mvn -B -ntp -q test -Dtest='DemoClassesAbsentFromDefaultBuildTest'
echo "Identity lab checks passed. (Lab login is not complete authorization; see README.)"
