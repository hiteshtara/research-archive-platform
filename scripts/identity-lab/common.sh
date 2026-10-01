# Shared settings for the identity lab scripts (sourced, not executed).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAB="$ROOT/identity-lab"
STATE="$ROOT/.identity-lab"            # git-ignored: test keys, salt, logs
CREDS="$STATE/creds"
API_PORT="${LAB_API_PORT:-8092}"
UI_PORT="${LAB_UI_PORT:-5198}"
compose() {
  docker compose -p identity-lab --project-directory "$LAB" \
    -f "$LAB/core/compose.yml" -f "$LAB/archive/compose.archive.yml" "$@"
}
load_secrets() {
  set -a; . "$CREDS/secrets.env"; set +a
  export LAB_CREDS_DIR="$CREDS" LAB_UI_ORIGIN="http://localhost:$UI_PORT"
}
