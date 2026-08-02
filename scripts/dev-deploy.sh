#!/bin/bash
#
# One-command developer deploy for the Research Archive Platform.
#
# Runs, in order: environment verification -> backend tests -> frontend
# lint/build -> API Docker build/push/ECS deploy (delegates to the
# existing guarded ops/deploy-api.sh rather than re-implementing its
# account-safety logic) -> live /actuator/health check -> Amplify UI
# build verification -> authenticated API smoke checks (only if Cognito
# test credentials are present in the environment) -> a PASS/FAIL/SKIP
# report.
#
# Callable from any directory - it resolves its own location and cds
# internally, so both of these work:
#   /path/to/research-archive-platform/scripts/dev-deploy.sh
#   cd ~/somewhere/else && ~/projects/research-archive-platform/scripts/dev-deploy.sh
#
# See docs/DEVELOPER_DEPLOYMENT.md for the full workflow explanation.
#
# SAFETY
#   - The AWS account is resolved fresh every run from the active
#     credentials and checked against EXPECTED_ACCOUNT_ID (770203350335)
#     before anything mutating happens - same discipline as
#     ops/deploy-api.sh, which this script delegates the actual
#     build/push/deploy to rather than duplicating.
#   - Credentials are never hardcoded. AWS auth comes entirely from the
#     caller's existing environment/profile; Cognito test credentials
#     (optional, for step 7) come only from COGNITO_TEST_USERNAME /
#     COGNITO_TEST_PASSWORD env vars the developer sets themselves.
#   - Secrets and tokens are never printed. The Cognito access token is
#     held only in a shell variable used inline in curl's Authorization
#     header - it is never echoed, logged, or included in the report.
#
# Flags
#   --check-only     Steps 1-3 only (verify/backend/frontend). No
#                    Docker build, no ECS deploy, no Amplify, no push.
#   --skip-backend   Skip backend tests + API Docker/ECS deploy.
#   --skip-frontend  Skip frontend lint/build + Amplify wait.
#   --no-push        Do not `git push` before checking Amplify.
#   --full           Ignore change detection; always run both legs.
#   -h, --help       Print this header and exit.

set -uo pipefail

# --- Resolve paths so this script works from any cwd --------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
API_DIR="$REPO_ROOT/api"
UI_DIR="$REPO_ROOT/ui"
TF_DIR="$REPO_ROOT/terraform/environments/dev"

REGION="us-east-1"
EXPECTED_ACCOUNT_ID="${EXPECTED_ACCOUNT_ID:-770203350335}"

CHECK_ONLY=false
SKIP_BACKEND=false
SKIP_FRONTEND=false
NO_PUSH=false
FULL_RUN=false

for arg in "$@"; do
  case "$arg" in
    --check-only) CHECK_ONLY=true ;;
    --skip-backend) SKIP_BACKEND=true ;;
    --skip-frontend) SKIP_FRONTEND=true ;;
    --no-push) NO_PUSH=true ;;
    --full) FULL_RUN=true ;;
    -h|--help)
      sed -n '2,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg (see --help)" >&2
      exit 1
      ;;
  esac
done

START_EPOCH="$(date +%s)"

# --- Result tracking + trap-based report, so the report always prints --
# even on an early abort, not only on a clean finish.

RESULTS=()

record() {
  # record "Step name" STATUS "detail"
  RESULTS+=("$1|$2|$3")
}

log()  { echo "-- $*"; }
warn() { echo "WARNING: $*" >&2; }
err()  { echo "ERROR: $*" >&2; }

resolve_terraform_output() {
  # Prefers the live Terraform output (the single source of truth for
  # these resource identifiers - see ops/AWS_OPERATIONS.md's existing
  # "terraform output -raw amplify_app_id" convention); falls back to a
  # known-good literal if state isn't reachable (e.g. no S3 read access
  # from the current session) so the script still runs.
  local name="$1"
  local fallback="$2"
  local value
  if value="$(cd "$TF_DIR" && terraform output -raw "$name" 2>/dev/null)" && [ -n "$value" ]; then
    echo "$value"
  else
    echo "$fallback"
  fi
}

EXITING=false

abort() {
  # abort "Step name" "message" - records a FAIL and stops the script.
  # Never called more than once per run; the EXIT trap prints the
  # accumulated report regardless of where this fires.
  record "$1" "FAIL" "$2"
  err "$2"
  err "Stopping - see report below for what completed before this."
  EXITING=true
  exit 1
}

final_report() {
  local exit_code=$?
  local end_epoch
  end_epoch="$(date +%s)"
  local duration=$(( end_epoch - START_EPOCH ))

  echo ""
  echo "========================================"
  echo "dev-deploy.sh report"
  echo "========================================"
  printf "%-28s %-8s %s\n" "STEP" "STATUS" "DETAIL"
  printf "%-28s %-8s %s\n" "----" "------" "------"

  local overall_fail=false
  local entry name status detail
  for entry in "${RESULTS[@]:-}"; do
    [ -z "$entry" ] && continue
    IFS='|' read -r name status detail <<< "$entry"
    printf "%-28s %-8s %s\n" "$name" "$status" "$detail"
    [ "$status" = "FAIL" ] && overall_fail=true
  done

  echo ""
  echo "Git SHA:       ${GIT_SHA:-unknown}"
  echo "Image tag:     ${IMAGE_TAG:-not built this run}"
  echo "Duration:      ${duration}s"

  if [ "$overall_fail" = true ] && [ "$exit_code" -eq 0 ]; then
    exit_code=1
  fi

  echo ""
  if [ "$exit_code" -eq 0 ] && [ "$overall_fail" = false ]; then
    echo "Result: PASS"
  else
    echo "Result: FAIL"
  fi

  exit "$exit_code"
}

trap final_report EXIT

# =========================================================================
# Step 1: Verify environment
# =========================================================================

log "Step 1/8: Verifying environment..."

# Resolved first, before any check that might abort, so the report is
# maximally informative even on an early failure.
GIT_BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
GIT_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
GIT_DIRTY=""
if [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then
  GIT_DIRTY=" (working tree has uncommitted changes)"
fi

MISSING_TOOLS=()
for tool in aws docker mvn npm git python3 curl; do
  command -v "$tool" >/dev/null 2>&1 || MISSING_TOOLS+=("$tool")
done
if [ "${#MISSING_TOOLS[@]}" -gt 0 ]; then
  abort "Environment" "Missing required tools: ${MISSING_TOOLS[*]}"
fi

if ! docker info >/dev/null 2>&1; then
  abort "Environment" "Docker daemon is not running (required for the API image build)."
fi

if ! CALLER_IDENTITY_JSON="$(aws sts get-caller-identity --output json 2>&1)"; then
  abort "Environment" "Could not resolve AWS identity: $CALLER_IDENTITY_JSON"
fi

ACCOUNT_ID="$(echo "$CALLER_IDENTITY_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Account"])')"
CALLER_ARN="$(echo "$CALLER_IDENTITY_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Arn"])')"

if [ "$ACCOUNT_ID" != "$EXPECTED_ACCOUNT_ID" ]; then
  abort "Environment" "Resolved AWS account ($ACCOUNT_ID) != expected ($EXPECTED_ACCOUNT_ID). Refusing to continue - wrong account. Set EXPECTED_ACCOUNT_ID to override intentionally."
fi

CONFIGURED_REGION="$(aws configure get region 2>/dev/null || true)"
CONFIGURED_REGION="${CONFIGURED_REGION:-${AWS_REGION:-${AWS_DEFAULT_REGION:-}}}"
if [ -n "$CONFIGURED_REGION" ] && [ "$CONFIGURED_REGION" != "$REGION" ]; then
  abort "Environment" "Configured AWS region ($CONFIGURED_REGION) != expected ($REGION)."
fi

if [ -n "${COGNITO_TEST_USERNAME:-}" ] && [ -z "${COGNITO_TEST_PASSWORD:-}" ]; then
  abort "Environment" "COGNITO_TEST_USERNAME is set but COGNITO_TEST_PASSWORD is not - set both or neither."
fi
if [ -z "${COGNITO_TEST_USERNAME:-}" ] && [ -n "${COGNITO_TEST_PASSWORD:-}" ]; then
  abort "Environment" "COGNITO_TEST_PASSWORD is set but COGNITO_TEST_USERNAME is not - set both or neither."
fi

echo "  Account:  $ACCOUNT_ID (expected $EXPECTED_ACCOUNT_ID) - OK"
echo "  Region:   ${CONFIGURED_REGION:-<unset, will pass --region $REGION explicitly>} - OK"
echo "  Caller:   $CALLER_ARN"
echo "  Branch:   $GIT_BRANCH"
echo "  Commit:   $GIT_SHA$GIT_DIRTY"

if [ -n "$GIT_DIRTY" ]; then
  warn "Uncommitted changes present. The Docker image build (step 4) will"
  warn "include them, but Amplify (step 6) only ever builds from what is"
  warn "already pushed to the remote branch - uncommitted UI changes will"
  warn "NOT appear in the Amplify build until they are committed and pushed."
fi

record "Environment" "PASS" "account $ACCOUNT_ID, region $REGION, $GIT_BRANCH@$GIT_SHA"

# --- Change detection (nice-to-have): skip a leg with no changes -------

resolve_diff_range() {
  local upstream
  if upstream="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null)"; then
    echo "${upstream}...HEAD"
  elif git -C "$REPO_ROOT" rev-parse HEAD~1 >/dev/null 2>&1; then
    echo "HEAD~1...HEAD"
  else
    echo ""
  fi
}

BACKEND_CHANGED=1
FRONTEND_CHANGED=1

if [ "$FULL_RUN" = false ]; then
  DIFF_RANGE="$(resolve_diff_range)"
  if [ -n "$DIFF_RANGE" ]; then
    CHANGED_FILES="$(git -C "$REPO_ROOT" diff --name-only $DIFF_RANGE 2>/dev/null || true)"
  else
    CHANGED_FILES=""
    warn "No git history to diff against (first commit?) - treating everything as changed."
  fi
  # Uncommitted local changes count too, so an un-pushed WIP edit isn't
  # silently skipped.
  CHANGED_FILES="$CHANGED_FILES
$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null | awk '{print $2}')"

  if [ -n "$DIFF_RANGE" ] || [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then
    if echo "$CHANGED_FILES" | grep -qE '^(api/|database/migrations/)'; then
      BACKEND_CHANGED=1
    else
      BACKEND_CHANGED=0
    fi
    if echo "$CHANGED_FILES" | grep -qE '^ui/'; then
      FRONTEND_CHANGED=1
    else
      FRONTEND_CHANGED=0
    fi
  fi

  if [ "$BACKEND_CHANGED" -eq 0 ] && [ "$FRONTEND_CHANGED" -eq 0 ] && [ "$CHECK_ONLY" = false ]; then
    log "No backend or UI changes detected since the last push - nothing to deploy."
    log "Pass --full to force a full run anyway."
    record "Backend" "SKIP" "no api/ changes detected"
    record "Frontend" "SKIP" "no ui/ changes detected"
    exit 0
  fi
fi

# =========================================================================
# Step 2: Backend tests
# =========================================================================

RUN_BACKEND=true
if [ "$SKIP_BACKEND" = true ]; then
  RUN_BACKEND=false
  record "Backend tests" "SKIP" "--skip-backend"
elif [ "$FULL_RUN" = false ] && [ "$BACKEND_CHANGED" -eq 0 ]; then
  RUN_BACKEND=false
  record "Backend tests" "SKIP" "no api/ changes detected"
fi

if [ "$RUN_BACKEND" = true ]; then
  log "Step 2/8: Running backend tests (mvn test)..."
  BACKEND_TEST_LOG="$(mktemp)"
  if (cd "$API_DIR" && mvn test 2>&1 | tee "$BACKEND_TEST_LOG"); then
    TEST_SUMMARY="$(grep -oE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' "$BACKEND_TEST_LOG" | tail -1)"
    rm -f "$BACKEND_TEST_LOG"
    record "Backend tests" "PASS" "${TEST_SUMMARY:-mvn test succeeded}"
  else
    rm -f "$BACKEND_TEST_LOG"
    abort "Backend tests" "mvn test failed - fix failing tests before deploying."
  fi
else
  log "Step 2/8: Skipping backend tests ($([ "$SKIP_BACKEND" = true ] && echo "--skip-backend" || echo "no backend changes"))."
fi

# =========================================================================
# Step 3: Frontend lint + build
# =========================================================================

RUN_FRONTEND=true
if [ "$SKIP_FRONTEND" = true ]; then
  RUN_FRONTEND=false
  record "Frontend build" "SKIP" "--skip-frontend"
elif [ "$FULL_RUN" = false ] && [ "$FRONTEND_CHANGED" -eq 0 ]; then
  RUN_FRONTEND=false
  record "Frontend build" "SKIP" "no ui/ changes detected"
fi

if [ "$RUN_FRONTEND" = true ]; then
  log "Step 3/8: Frontend lint + build..."

  if [ ! -d "$UI_DIR/node_modules" ] || [ "$UI_DIR/package-lock.json" -nt "$UI_DIR/node_modules" ]; then
    log "  Installing frontend dependencies (npm install)..."
    if ! (cd "$UI_DIR" && npm install); then
      abort "Frontend build" "npm install failed."
    fi
  else
    log "  node_modules is up to date - skipping npm install."
  fi

  if ! (cd "$UI_DIR" && npm run lint); then
    abort "Frontend build" "npm run lint failed."
  fi

  if ! (cd "$UI_DIR" && npm run build); then
    abort "Frontend build" "npm run build failed."
  fi

  if ! (cd "$UI_DIR" && npm run test); then
    abort "Frontend build" "npm run test failed."
  fi

  record "Frontend build" "PASS" "install/lint/build/test succeeded"
else
  log "Step 3/8: Skipping frontend build ($([ "$SKIP_FRONTEND" = true ] && echo "--skip-frontend" || echo "no frontend changes"))."
fi

if [ "$CHECK_ONLY" = true ]; then
  log "--check-only: stopping after verify/backend/frontend. No deploy performed."
  exit 0
fi

# =========================================================================
# Steps 4-5: API Docker build/push + ECS deploy
# (delegated to ops/deploy-api.sh - see that script's own header for why
# account safety lives there and isn't duplicated here)
# =========================================================================

IMAGE_TAG=""
TASK_DEFINITION_ARN=""

if [ "$RUN_BACKEND" = true ]; then
  log "Step 4-5/8: Building/pushing the API image and deploying to ECS..."

  DEPLOY_LOG="$(mktemp)"
  if (cd "$REPO_ROOT" && "$REPO_ROOT/ops/deploy-api.sh" 2>&1 | tee "$DEPLOY_LOG"); then
    DEPLOY_EXIT=0
  else
    DEPLOY_EXIT=1
  fi

  IMAGE_TAG="$(grep -m1 '^Image tag:' "$DEPLOY_LOG" | sed 's/^Image tag:[[:space:]]*//')"
  TASK_DEFINITION_ARN="$(grep -m1 '^Task definition:' "$DEPLOY_LOG" | sed 's/^Task definition:[[:space:]]*//')"
  rm -f "$DEPLOY_LOG"

  if [ "$DEPLOY_EXIT" -ne 0 ]; then
    abort "API deploy (ECS)" "ops/deploy-api.sh failed - see output above."
  fi

  record "API deploy (ECS)" "PASS" "task def ${TASK_DEFINITION_ARN:-unknown}"

  # --- Health check --------------------------------------------------

  log "Verifying /actuator/health..."
  API_URL="$(resolve_terraform_output api_url "https://api-dev.app-nprd.aws-cloud.bu.edu")"

  HEALTH_OK=false
  for attempt in 1 2 3 4 5 6; do
    HEALTH_BODY="$(curl -sS --max-time 10 "${API_URL}/actuator/health" 2>/dev/null || true)"
    if echo "$HEALTH_BODY" | grep -q '"status":"UP"'; then
      HEALTH_OK=true
      break
    fi
    log "  Health check attempt $attempt/6 not UP yet, retrying in 10s..."
    sleep 10
  done

  if [ "$HEALTH_OK" = true ]; then
    record "API health check" "PASS" "$API_URL/actuator/health -> UP"
  else
    abort "API health check" "$API_URL/actuator/health did not report UP after 6 attempts. Last body: $HEALTH_BODY"
  fi
else
  log "Step 4-5/8: Skipping API Docker/ECS deploy (backend not deployed this run)."
fi

# =========================================================================
# Step 6: Amplify
# =========================================================================

if [ "$RUN_FRONTEND" = true ]; then
  log "Step 6/8: Amplify..."

  AMPLIFY_APP_ID="$(resolve_terraform_output amplify_app_id "d288p9gmoteftb")"
  AMPLIFY_BRANCH="main"

  # Amplify only ever builds from what's already on the remote branch -
  # never from local disk - so a rebuild only makes sense when there is
  # something new to push. We deliberately do NOT fall back to a manual
  # `aws amplify start-job` when there's nothing new: forcing a rebuild
  # of a commit Amplify has already built is a wasted build, not a real
  # deploy (see docs/DEVELOPER_DEPLOYMENT.md's Amplify step).
  PUSHED=false
  AHEAD="0"
  if [ "$NO_PUSH" = false ]; then
    AHEAD="$(git -C "$REPO_ROOT" rev-list '@{u}'..HEAD 2>/dev/null | wc -l | tr -d ' ')"
    AHEAD="${AHEAD:-0}"
    if [ "$AHEAD" != "0" ]; then
      log "  Pushing $AHEAD commit(s) so Amplify's webhook picks them up..."
      for remote in $(git -C "$REPO_ROOT" remote); do
        if ! git -C "$REPO_ROOT" push "$remote" "$GIT_BRANCH"; then
          abort "Amplify" "git push $remote $GIT_BRANCH failed."
        fi
      done
      PUSHED=true
    fi
  fi

  if [ "$PUSHED" = false ]; then
    if [ "$NO_PUSH" = true ]; then
      log "  --no-push: not pushing, and not waiting on Amplify - push yourself"
      log "  and re-run (or drop --no-push) to verify the build."
      record "Amplify build" "SKIP" "--no-push"
    else
      log "  Nothing new to push - Amplify already has whatever is on the remote."
      log "  (ui/ changes were detected, but only in uncommitted local files -"
      log "  commit them and re-run for Amplify to ever see them.)"
      record "Amplify build" "SKIP" "nothing new pushed"
    fi
  else
    log "  Waiting for the webhook-triggered build to appear..."
    JOB_ID=""
    for attempt in 1 2 3 4 5 6; do
      JOB_ID="$(aws amplify list-jobs --app-id "$AMPLIFY_APP_ID" --branch-name "$AMPLIFY_BRANCH" \
        --region "$REGION" --max-results 5 \
        --query "jobSummaries[?commitId=='$(git -C "$REPO_ROOT" rev-parse HEAD)'].jobId | [0]" \
        --output text 2>/dev/null || true)"
      [ -n "$JOB_ID" ] && [ "$JOB_ID" != "None" ] && break
      log "    Not visible yet (attempt $attempt/6), retrying in 10s..."
      sleep 10
    done

    if [ -z "$JOB_ID" ] || [ "$JOB_ID" = "None" ]; then
      abort "Amplify" "Pushed successfully, but no Amplify job appeared for $AMPLIFY_APP_ID/$AMPLIFY_BRANCH within a minute."
    fi

    log "  Waiting for Amplify job $JOB_ID to finish..."
    AMPLIFY_STATUS=""
    for attempt in $(seq 1 60); do
      AMPLIFY_STATUS="$(aws amplify get-job --app-id "$AMPLIFY_APP_ID" --branch-name "$AMPLIFY_BRANCH" \
        --job-id "$JOB_ID" --region "$REGION" --query 'job.summary.status' --output text 2>/dev/null || echo "ERROR")"
      case "$AMPLIFY_STATUS" in
        SUCCEED|FAILED|CANCELLED) break ;;
      esac
      sleep 15
    done

    if [ "$AMPLIFY_STATUS" = "SUCCEED" ]; then
      record "Amplify build" "PASS" "job $JOB_ID"
    else
      warn "Amplify build did not succeed (status: $AMPLIFY_STATUS). Build log URLs:"
      aws amplify get-job --app-id "$AMPLIFY_APP_ID" --branch-name "$AMPLIFY_BRANCH" \
        --job-id "$JOB_ID" --region "$REGION" \
        --query 'job.steps[*].[stepName,logUrl]' --output text 2>/dev/null | while read -r step_name log_url; do
          echo "  --- $step_name ---"
          curl -sS --max-time 15 "$log_url" 2>/dev/null || echo "  (could not fetch log)"
        done
      abort "Amplify build" "job $JOB_ID ended with status $AMPLIFY_STATUS."
    fi
  fi
else
  log "Step 6/8: Skipping Amplify (frontend not deployed this run)."
fi

# =========================================================================
# Step 7: API verification
# =========================================================================

log "Step 7/8: API verification..."

API_URL="$(resolve_terraform_output api_url "https://api-dev.app-nprd.aws-cloud.bu.edu")"

HEALTH_BODY="$(curl -sS --max-time 10 "${API_URL}/actuator/health" 2>/dev/null || true)"
if echo "$HEALTH_BODY" | grep -q '"status":"UP"'; then
  record "GET /actuator/health" "PASS" "UP"
else
  record "GET /actuator/health" "FAIL" "did not return UP"
fi

if [ -n "${COGNITO_TEST_USERNAME:-}" ] && [ -n "${COGNITO_TEST_PASSWORD:-}" ]; then
  log "  Cognito test credentials found - running authenticated checks..."

  USER_POOL_ID="$(resolve_terraform_output cognito_user_pool_id "us-east-1_VJ4ekQ27c")"
  CLIENT_ID="$(resolve_terraform_output cognito_client_id "seqgmc8sccr22sq8lcafqjcpb")"

  # Token is held only in this variable - never echoed, logged, or
  # included in the report.
  TOKEN="$(aws cognito-idp admin-initiate-auth \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$CLIENT_ID" \
    --auth-flow ADMIN_USER_PASSWORD_AUTH \
    --auth-parameters "USERNAME=${COGNITO_TEST_USERNAME},PASSWORD=${COGNITO_TEST_PASSWORD}" \
    --region "$REGION" \
    --query 'AuthenticationResult.AccessToken' \
    --output text 2>/dev/null || true)"

  if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
    warn "Could not obtain a Cognito access token - skipping authenticated API checks."
    record "GET /api/v1/awards/search" "SKIP" "Cognito auth failed"
    record "GET /api/v1/awards/{id}/summary" "SKIP" "Cognito auth failed"
    record "GET /api/v1/awards/{n}/hierarchy" "SKIP" "Cognito auth failed"
    record "GET /api/v1/awards/{id}/versions" "SKIP" "Cognito auth failed"
  else
    SEARCH_BODY="$(curl -sS --max-time 10 -H "Authorization: Bearer $TOKEN" \
      "${API_URL}/api/v1/awards/search?size=1")"
    if echo "$SEARCH_BODY" | python3 -c 'import json,sys; json.load(sys.stdin)' >/dev/null 2>&1; then
      record "GET /api/v1/awards/search" "PASS" "200"
      TEST_AWARD_ID="$(echo "$SEARCH_BODY" | python3 -c 'import json,sys; d=json.load(sys.stdin)["content"]; print(d[0]["awardId"] if d else "")')"
      TEST_AWARD_NUMBER="$(echo "$SEARCH_BODY" | python3 -c 'import json,sys; d=json.load(sys.stdin)["content"]; print(d[0]["awardNumber"] if d else "")')"
    else
      record "GET /api/v1/awards/search" "FAIL" "non-JSON or error response"
      TEST_AWARD_ID=""
      TEST_AWARD_NUMBER=""
    fi

    if [ -n "$TEST_AWARD_ID" ]; then
      SUMMARY_CODE="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $TOKEN" \
        "${API_URL}/api/v1/awards/${TEST_AWARD_ID}/summary")"
      [ "$SUMMARY_CODE" = "200" ] && record "GET /api/v1/awards/{id}/summary" "PASS" "200" \
        || record "GET /api/v1/awards/{id}/summary" "FAIL" "HTTP $SUMMARY_CODE"

      VERSIONS_CODE="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $TOKEN" \
        "${API_URL}/api/v1/awards/${TEST_AWARD_ID}/versions")"
      [ "$VERSIONS_CODE" = "200" ] && record "GET /api/v1/awards/{id}/versions" "PASS" "200" \
        || record "GET /api/v1/awards/{id}/versions" "FAIL" "HTTP $VERSIONS_CODE"
    else
      record "GET /api/v1/awards/{id}/summary" "SKIP" "no award returned by search"
      record "GET /api/v1/awards/{id}/versions" "SKIP" "no award returned by search"
    fi

    if [ -n "$TEST_AWARD_NUMBER" ]; then
      HIERARCHY_CODE="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $TOKEN" \
        "${API_URL}/api/v1/awards/${TEST_AWARD_NUMBER}/hierarchy")"
      [ "$HIERARCHY_CODE" = "200" ] && record "GET /api/v1/awards/{n}/hierarchy" "PASS" "200" \
        || record "GET /api/v1/awards/{n}/hierarchy" "FAIL" "HTTP $HIERARCHY_CODE"
    else
      record "GET /api/v1/awards/{n}/hierarchy" "SKIP" "no award returned by search"
    fi
  fi
  unset TOKEN
else
  log "  No COGNITO_TEST_USERNAME/COGNITO_TEST_PASSWORD in environment - skipping"
  log "  authenticated checks (health check above still ran). This script never"
  log "  guesses or prompts for a password."
  record "GET /api/v1/awards/search" "SKIP" "no Cognito test credentials in environment"
  record "GET /api/v1/awards/{id}/summary" "SKIP" "no Cognito test credentials in environment"
  record "GET /api/v1/awards/{n}/hierarchy" "SKIP" "no Cognito test credentials in environment"
  record "GET /api/v1/awards/{id}/versions" "SKIP" "no Cognito test credentials in environment"
fi

log "Step 8/8: Done - see report below."

# final_report runs automatically via the EXIT trap.
