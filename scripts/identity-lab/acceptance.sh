#!/usr/bin/env bash
# Award authorization acceptance pass against the RUNNING lab (real Shibboleth login,
# simulated Cognito, real API enforcement). Writes JSON + Markdown to .identity-lab/acceptance/.
#   acceptance.sh              APPROVED policy (P3 FAMILY_WIDE, P6 sub-units only by grant flag, P4 PI/MPI/COI)
#   acceptance.sh --alternatives   also restarts the API once with the PREVIOUS demo settings
#                                  (PER_VERSION, EXACT_LEAD_UNIT, PI/MPI/COI) for comparison, then restores it
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
cd "$LAB/archive/tests"
PY="uv run -q --no-project --with requests python"
$PY award_acceptance.py --policy default
restart_api() {
  pkill -f "spring-boot.run.profiles=identity-lab" 2>/dev/null || true
  while curl -sf "http://127.0.0.1:$API_PORT/actuator/health" >/dev/null 2>&1; do sleep 1; done
  rm -f "$STATE/api.pid"
  LAB_POLICY_ENV="$1" "$ROOT/scripts/identity-lab/start.sh" >/dev/null
}
if [ "${1:-}" = "--alternatives" ]; then
  restart_api "LAB_VERSION_SCOPE=PER_VERSION LAB_DEPARTMENT_MATCH=EXACT_LEAD_UNIT LAB_RESEARCH_STAFF_ROLES=PI,MPI,COI"
  $PY award_acceptance.py --policy alternative || true
  restart_api ""
fi
$PY render_matrix.py "$STATE/acceptance"
