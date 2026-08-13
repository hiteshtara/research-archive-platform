#!/usr/bin/env bash
#
# Read-only project context recovery for the Research Archive Platform.
#
# Assembles the authoritative facts a new/context-lost Claude session
# needs to continue work without guessing or substituting the wrong
# environment (e.g. treating local Postgres as authoritative, or
# concluding dev RDS is unreachable just because a local tunnel isn't).
#
# This script NEVER modifies files, databases, AWS resources, Git state,
# credentials, or infrastructure. It never launches, stops, registers,
# updates, or deletes anything. It never reads or prints secret values.
#
# Usage:
#   scripts/restore-project-context.sh                # offline report
#   scripts/restore-project-context.sh --aws           # + read-only AWS inspection
#   scripts/restore-project-context.sh --oracle        # + staging Oracle --test only
#   scripts/restore-project-context.sh --output FILE   # write to FILE instead of stdout
#
# --aws: verifies AWS identity (expects account 770203350335) and
#   inspects the ECS ETL cluster/loader task definition/recent tasks/log
#   group - describe/list calls only, never a write API, never a secret
#   value. Every AWS call explicitly passes `--profile bu-nprd --region
#   us-east-1` - this script NEVER relies on ambient default credentials
#   (a bare `aws` call can silently resolve to whatever account happens
#   to be the default, which has been a real personal AWS account before,
#   not the BU dev account). If the `bu-nprd` profile isn't configured or
#   its credentials are expired, reports that `buaws` is needed and
#   continues with the offline report only. If the resolved account is
#   not 770203350335, AWS verification stops immediately (no ECS/task
#   calls) and the offline report still completes.
#
# --oracle: locates the existing Keychain-backed staging runner under
#   ~/projects/bu-huron-data-exchange/scripts and runs only its --test
#   option. Never queries production automatically, never touches the
#   Keychain password itself (the runner does that internally).
#
# Safety: `set -euo pipefail`, no `eval`, no destructive Git commands, no
# AWS write APIs, no secret reads, no printing of environment variables.
# Missing optional tools (aws, python3, jq, the Oracle runner) produce a
# warning line and the report continues - they never abort the whole
# report.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MODE_AWS=false
MODE_ORACLE=false
OUTPUT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --aws) MODE_AWS=true; shift ;;
    --oracle) MODE_ORACLE=true; shift ;;
    --output)
      OUTPUT_FILE="${2:-}"
      [[ -z "$OUTPUT_FILE" ]] && { echo "ERROR: --output requires a path" >&2; exit 1; }
      shift 2
      ;;
    -h|--help)
      sed -n '2,33p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "ERROR: Unknown argument: $1 (see --help)" >&2
      exit 1
      ;;
  esac
done

# Never write into the repository - transient branch/task info doesn't
# belong in version control. An explicit --output path outside the repo
# is fine; the default is stdout.
if [[ -n "$OUTPUT_FILE" ]]; then
  case "$(cd "$(dirname "$OUTPUT_FILE")" 2>/dev/null && pwd)/$(basename "$OUTPUT_FILE")" in
    "$ROOT_DIR"/*)
      echo "ERROR: refusing to write --output inside the repository ($OUTPUT_FILE)" >&2
      exit 1
      ;;
  esac
  exec > "$OUTPUT_FILE"
fi

have() { command -v "$1" >/dev/null 2>&1; }

section() { echo; echo "## $1"; echo; }
subsection() { echo; echo "### $1"; echo; }
warn_missing() { echo "_(skipped: \`$1\` not available)_"; }

echo "# Research Archive Platform — Project Context"
echo
echo "Generated (this run): $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo
echo "This is a point-in-time snapshot, not a live value store. Treat"
echo "anything under \"Current implementation state\" as verified only as"
echo "of the date recorded there, not as an ongoing guarantee."

# --- Project identity -------------------------------------------------

section "Project identity"

if have git && git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "- Repository root: \`$ROOT_DIR\`"
  echo "- Current branch: \`$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)\`"
  echo "- Current commit: \`$(git rev-parse HEAD 2>/dev/null || echo unknown)\`"
  REMOTE_URL="$(git remote get-url origin 2>/dev/null || echo 'none configured')"
  echo "- Remote (origin): \`$REMOTE_URL\`"

  subsection "Working tree"
  if [[ -n "$(git status --short 2>/dev/null || true)" ]]; then
    echo '```'
    git status --short
    echo '```'
  else
    echo "Clean."
  fi

  subsection "Recent commits (last 10)"
  echo '```'
  git log --oneline -10 2>/dev/null || echo "(no commits)"
  echo '```'

  subsection "Unpushed local commits"
  UPSTREAM="$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)"
  if [[ -n "$UPSTREAM" ]]; then
    UNPUSHED_COUNT="$(git rev-list --count "${UPSTREAM}..HEAD" 2>/dev/null || echo 0)"
    if [[ "$UNPUSHED_COUNT" -gt 0 ]]; then
      echo "$UNPUSHED_COUNT commit(s) ahead of \`$UPSTREAM\` (most recent 10):"
      echo '```'
      git log "${UPSTREAM}..HEAD" --oneline -10 2>/dev/null
      echo '```'
    else
      echo "None - up to date with \`$UPSTREAM\`."
    fi
  else
    echo "No upstream tracking branch configured - cannot determine unpushed commits automatically. Compare manually against \`origin/main\` if needed."
  fi

  subsection "Worktrees (metadata only)"
  echo '```'
  git worktree list 2>/dev/null || echo "(none)"
  echo '```'

  subsection "Stashes (metadata only)"
  STASHES="$(git stash list 2>/dev/null || true)"
  if [[ -n "$STASHES" ]]; then
    echo '```'
    echo "$STASHES"
    echo '```'
  else
    echo "None."
  fi
else
  warn_missing "git (or not inside a git work tree)"
fi

echo
echo "Current date: $(date -u +"%Y-%m-%d")"

# --- Mandatory project memory ------------------------------------------

section "Mandatory project memory"

echo "Full policy detail lives in \`CLAUDE.md\`; the load-bearing facts:"
echo
cat <<'FACTS'
- AWS dev RDS is authoritative; local PostgreSQL is NOT valid for
  dev-data reconciliation or ETL-completeness checks.
- ECS Fargate (cluster `research-archive-platform-dev-etl`, task family
  `research-archive-platform-dev-loader`) is the supported path to dev
  RDS - not a local tunnel.
- A missing local bastion/tunnel does NOT imply ECS-to-RDS is
  unreachable - they are separate paths; ECS already works without one.
- The local SSM tunnel route (`scripts/start-db-tunnel.sh` /
  `api/scripts/dev.sh`) has been removed as unsupported - do not
  recreate it or substitute a personal bastion/EC2 instance without
  explicit approval.
- Oracle staging research runs from the BU VPN-connected Mac via the
  Keychain-backed read-only runner in the separate sibling project
  `~/projects/bu-huron-data-exchange/scripts` (`kc_staging_query.py`).
- Production Oracle access (`kc_prod_readonly_query.py`) requires
  explicit authorization - never query it automatically.
FACTS

extract_section() {
  local file="$1" header="$2"
  if [[ ! -f "$file" ]]; then
    echo "_${file} not found_"
    return
  fi
  awk -v h="## $header" '
    index($0, h) == 1 {found=1; print; next}
    found && /^## / {exit}
    found {print}
  ' "$file"
}

subsection "CLAUDE.md: \"Authoritative data location\" section (verbatim)"
extract_section "CLAUDE.md" "Authoritative data location: AWS RDS, not local Postgres"

subsection "Relevant runbooks/docs present"
for doc in \
  docs/runbooks/LOCAL_SETUP.md \
  docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md \
  docs/runbooks/TROUBLESHOOTING.md \
  docs/operations/AWS_TROUBLESHOOTING_RUNBOOK.md \
  docs/ORACLE_STAGING_CONNECTIVITY.md \
  docs/architecture/AWARD_CUSTOM_DATA_DESIGN.md
do
  if [[ -f "$doc" ]]; then
    echo "- \`$doc\`"
  else
    echo "- \`$doc\` — MISSING (expected but not found; do not assume its guidance still applies)"
  fi
done

# --- Current implementation state ---------------------------------------

section "Current implementation state"

STATE_FILE="docs/project-memory/CURRENT_STATE.md"
echo "Source of truth: \`$STATE_FILE\` (hand-maintained, displayed"
echo "verbatim below - update that file as milestones change, not this"
echo "script)."

if [[ -f "$STATE_FILE" ]]; then
  echo
  echo '---'
  cat "$STATE_FILE"
  echo '---'
else
  echo
  echo "_\`$STATE_FILE\` not found — no milestone/state record to report._"
fi

subsection "Commit containment check (derived from $STATE_FILE, not hard-coded)"
if have git && [[ -f "$STATE_FILE" ]]; then
  COMMIT_HASHES="$(grep -oE '\`[0-9a-f]{7,40}\`' "$STATE_FILE" 2>/dev/null | tr -d '`' | sort -u || true)"
  if [[ -n "$COMMIT_HASHES" ]]; then
    while IFS= read -r hash; do
      [[ -z "$hash" ]] && continue
      if ! git cat-file -e "${hash}^{commit}" 2>/dev/null; then
        echo "- \`$hash\`: not found in local git history"
        continue
      fi
      IN_BRANCH="no"
      git merge-base --is-ancestor "$hash" HEAD 2>/dev/null && IN_BRANCH="yes"
      ON_REMOTE="unknown (no local \`origin/main\` ref - run \`git fetch\`)"
      if git rev-parse --verify origin/main >/dev/null 2>&1; then
        if git merge-base --is-ancestor "$hash" origin/main 2>/dev/null; then
          ON_REMOTE="yes"
        else
          ON_REMOTE="no"
        fi
      fi
      echo "- \`$hash\`: in current branch=$IN_BRANCH, on origin/main=$ON_REMOTE"
    done <<< "$COMMIT_HASHES"
  else
    echo "_No commit hashes found in \`$STATE_FILE\`._"
  fi
else
  warn_missing "git, or $STATE_FILE is missing"
fi

# --- Optional: AWS verification ------------------------------------------
#
# Every real AWS call in this section goes through aws_bu(), which always
# passes --profile "$AWS_PROFILE_NAME" --region "$AWS_REGION_NAME"
# explicitly - never a bare `aws` call relying on whatever the ambient
# default credential chain happens to resolve to. Wrapped in a function
# (not an inline if-block) specifically so account-mismatch can `return`
# immediately and skip every subsequent ECS/task call, rather than just
# printing a warning and continuing anyway.

readonly AWS_PROFILE_NAME="bu-nprd"
readonly AWS_REGION_NAME="us-east-1"
readonly EXPECTED_AWS_ACCOUNT="770203350335"

aws_bu() {
  aws --profile "$AWS_PROFILE_NAME" --region "$AWS_REGION_NAME" "$@"
}

run_aws_verification() {
  if ! have aws; then
    warn_missing "aws CLI"
    return 0
  fi

  # Refuse to proceed at all if the named profile isn't even configured -
  # never let a subsequent bare/misconfigured call silently fall through
  # to the AWS CLI's own default-credential-chain behavior.
  if ! aws configure list-profiles 2>/dev/null | grep -qx "$AWS_PROFILE_NAME"; then
    echo "AWS profile \`$AWS_PROFILE_NAME\` is not configured on this Mac."
    echo "Run \`buaws\` to set it up, then re-run with \`--aws\`. Continuing"
    echo "with the offline report only (nothing below this point could be"
    echo "verified live)."
    return 0
  fi

  local identity_json=""
  if ! identity_json="$(aws_bu sts get-caller-identity --output json 2>&1)"; then
    echo "AWS credentials for profile \`$AWS_PROFILE_NAME\` are missing or"
    echo "expired. Run \`buaws\` to refresh, then re-run with \`--aws\`."
    echo "Continuing with the offline report only (nothing below this point"
    echo "could be verified live)."
    return 0
  fi

  local account="unknown" caller_arn="unknown"
  if have python3; then
    account="$(echo "$identity_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("Account","unknown"))' 2>/dev/null || echo unknown)"
    caller_arn="$(echo "$identity_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("Arn","unknown"))' 2>/dev/null || echo unknown)"
  else
    warn_missing "python3 (cannot parse identity JSON)"
  fi

  echo "- AWS profile: \`$AWS_PROFILE_NAME\` (region \`$AWS_REGION_NAME\`)"
  echo "- AWS account: \`$account\` (expected \`$EXPECTED_AWS_ACCOUNT\`)"
  echo "- Caller ARN: \`$caller_arn\`"

  if [[ "$account" != "$EXPECTED_AWS_ACCOUNT" ]]; then
    echo
    echo "**STOPPING AWS verification here: the \`$AWS_PROFILE_NAME\` profile"
    echo "resolved account \`$account\`, not the expected BU dev account"
    echo "\`$EXPECTED_AWS_ACCOUNT\`. No further AWS calls will be made this"
    echo "run.** Re-run \`buaws\` and confirm the \`$AWS_PROFILE_NAME\` profile"
    echo "itself is correctly configured before retrying."
    return 0
  fi

  subsection "ECS ETL cluster"
  aws_bu ecs describe-clusters \
    --clusters research-archive-platform-dev-etl \
    --query 'clusters[0].{status:status,runningTasks:runningTasksCount,activeServices:activeServicesCount}' \
    --output json 2>/dev/null || echo "_could not describe cluster_"

  subsection "Latest loader task definition"
  # --max-items triggers CLI pagination, which appends a NextToken
  # line (literally "None" when there isn't one) even in --output
  # text mode - take only the first line.
  local latest_taskdef
  latest_taskdef="$(aws_bu ecs list-task-definitions \
    --family-prefix research-archive-platform-dev-loader \
    --sort DESC --max-items 1 \
    --query 'taskDefinitionArns[0]' --output text 2>/dev/null | head -1 || true)"
  if [[ -n "$latest_taskdef" && "$latest_taskdef" != "None" ]]; then
    echo "- ARN: \`$latest_taskdef\`"
    aws_bu ecs describe-task-definition \
      --task-definition "$latest_taskdef" \
      --query 'taskDefinition.containerDefinitions[0].{image:image,logGroup:logConfiguration.options."awslogs-group"}' \
      --output json 2>/dev/null || echo "_could not describe task definition_"
  else
    echo "_could not resolve latest task definition_"
  fi

  subsection "Recent tasks"
  echo "Running:"
  aws_bu ecs list-tasks --cluster research-archive-platform-dev-etl \
    --query 'taskArns' --output json 2>/dev/null || echo "_could not list running tasks_"
  echo "Recently stopped (up to 5):"
  aws_bu ecs list-tasks --cluster research-archive-platform-dev-etl \
    --desired-status STOPPED --max-items 5 \
    --query 'taskArns' --output json 2>/dev/null || echo "_could not list stopped tasks_"
}

if $MODE_AWS; then
  section "AWS verification (--aws) — read-only, describe/list calls only"
  run_aws_verification
fi

# --- Optional: Oracle verification ---------------------------------------

if $MODE_ORACLE; then
  section "Oracle verification (--oracle) — staging --test only"

  # Overridable for tests only - real usage always defaults to the real
  # sibling project.
  RUNNER_DIR="${RESTORE_CONTEXT_ORACLE_RUNNER_DIR:-$HOME/projects/bu-huron-data-exchange}"
  RUNNER="$RUNNER_DIR/scripts/kc_staging_query.py"
  PYTHON_BIN="$RUNNER_DIR/.venv/bin/python"

  if [[ ! -f "$RUNNER" ]]; then
    echo "Staging runner not found at \`$RUNNER\` — nothing to verify."
    echo "(Production runner \`kc_prod_readonly_query.py\` is never run"
    echo "automatically by this script regardless.)"
  elif [[ ! -x "$PYTHON_BIN" ]]; then
    echo "Runner venv not found at \`$PYTHON_BIN\`. Set it up per the"
    echo "runner's own instructions (\`uv venv .venv && uv pip install"
    echo "--python .venv/bin/python oracledb\`), then retry."
  else
    echo "Running \`kc_staging_query.py --test\` only. This script never"
    echo "requests, retrieves, displays, or stores the Keychain password"
    echo "itself - the runner resolves it internally."
    echo
    TEST_OUTPUT=""
    if TEST_OUTPUT="$("$PYTHON_BIN" "$RUNNER" --test 2>&1)"; then
      echo '```'
      echo "$TEST_OUTPUT"
      echo '```'
    else
      echo "Connection test failed - likely not on BU VPN, or the macOS"
      echo "Keychain entry (service \`bu-kuali-stg\`, account \`KCOEUS\`) is"
      echo "missing. Output:"
      echo '```'
      echo "$TEST_OUTPUT"
      echo '```'
    fi
  fi
fi

echo
echo "---"
echo "End of report."
