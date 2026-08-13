#!/usr/bin/env bash
set -euo pipefail

# Regression tests for scripts/restore-project-context.sh. Fast,
# offline-by-default, no real AWS/Oracle access required - a fake `aws`
# binary and a fake Oracle runner directory stand in for the real ones so
# the "only allowlisted read-only calls" and "only --test" guarantees are
# actually exercised, not just asserted in prose.
#
# Usage: scripts/tests/test-restore-project-context.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET="$SCRIPTS_DIR/restore-project-context.sh"

FAILURES=0

assert_contains() {
  local description="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected output to contain: $needle)" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

assert_not_contains() {
  local description="$1" haystack="$2" needle="$3"
  if [[ "$haystack" != *"$needle"* ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected output NOT to contain: $needle)" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

assert_eq() {
  local description="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected '$expected', got '$actual')" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

TMP_TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_TEST_DIR"' EXIT

# --- Test 1: offline mode works without AWS credentials -----------------

OFFLINE_OUTPUT="$(env -i PATH="/usr/bin:/bin" HOME="$HOME" "$TARGET" 2>&1)"
OFFLINE_EXIT=$?
assert_eq "offline mode exits 0 with no AWS env at all" "0" "$OFFLINE_EXIT"
assert_contains "offline mode reaches end of report" "$OFFLINE_OUTPUT" "End of report."
assert_contains "offline mode still shows project identity" "$OFFLINE_OUTPUT" "## Project identity"

# --- Test 2: --aws invokes only allowlisted read-only AWS commands ------

FAKE_AWS_DIR="$TMP_TEST_DIR/fake-aws-bin"
mkdir -p "$FAKE_AWS_DIR"
AWS_CALL_LOG="$TMP_TEST_DIR/aws-calls.log"
: > "$AWS_CALL_LOG"

cat > "$FAKE_AWS_DIR/aws" <<FAKE_AWS_EOF
#!/usr/bin/env bash
echo "\$*" >> "$AWS_CALL_LOG"
case "\$1 \$2" in
  "sts get-caller-identity")
    echo '{"Account":"770203350335","Arn":"arn:aws:sts::770203350335:assumed-role/fake/test"}'
    ;;
  "ecs describe-clusters")
    echo '{"status":"ACTIVE","runningTasks":0,"activeServices":0}'
    ;;
  "ecs list-task-definitions")
    echo "arn:aws:ecs:us-east-1:770203350335:task-definition/research-archive-platform-dev-loader:1"
    echo "None"
    ;;
  "ecs describe-task-definition")
    echo '{"image":"fake-image:tag","logGroup":"/ecs/fake"}'
    ;;
  "ecs list-tasks")
    echo '[]'
    ;;
  *)
    echo "FAKE AWS: unexpected/disallowed call: \$*" >&2
    exit 1
    ;;
esac
FAKE_AWS_EOF
chmod +x "$FAKE_AWS_DIR/aws"

AWS_OUTPUT="$(PATH="$FAKE_AWS_DIR:/usr/bin:/bin" "$TARGET" --aws 2>&1)"
AWS_EXIT=$?
assert_eq "--aws mode exits 0 against the fake allowlisted aws" "0" "$AWS_EXIT"
assert_contains "--aws mode reports the expected account" "$AWS_OUTPUT" "770203350335"

DISALLOWED_FOUND="no"
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  case "$line" in
    "sts get-caller-identity"*|"ecs describe-clusters"*|"ecs list-task-definitions"*|"ecs describe-task-definition"*|"ecs list-tasks"*)
      ;;
    *)
      DISALLOWED_FOUND="yes: $line"
      ;;
  esac
done < "$AWS_CALL_LOG"
assert_eq "--aws mode called only allowlisted read-only aws subcommands" "no" "$DISALLOWED_FOUND"

assert_not_contains "--aws mode never calls run-task" "$(cat "$AWS_CALL_LOG")" "run-task"
assert_not_contains "--aws mode never calls register-task-definition" "$(cat "$AWS_CALL_LOG")" "register-task-definition"
assert_not_contains "--aws mode never calls secretsmanager" "$(cat "$AWS_CALL_LOG")" "secretsmanager"
assert_not_contains "--aws mode never calls update-service" "$(cat "$AWS_CALL_LOG")" "update-service"

# --- Test 3: --oracle invokes only the staging runner's --test ----------

FAKE_ORACLE_DIR="$TMP_TEST_DIR/bu-huron-data-exchange"
mkdir -p "$FAKE_ORACLE_DIR/scripts" "$FAKE_ORACLE_DIR/.venv/bin"
ORACLE_CALL_LOG="$TMP_TEST_DIR/oracle-calls.log"
: > "$ORACLE_CALL_LOG"

cat > "$FAKE_ORACLE_DIR/scripts/kc_staging_query.py" <<'FAKE_ORACLE_EOF'
import sys
print(" ".join(sys.argv[1:]))
FAKE_ORACLE_EOF

cat > "$FAKE_ORACLE_DIR/.venv/bin/python" <<FAKE_PYTHON_EOF
#!/usr/bin/env bash
echo "\$*" >> "$ORACLE_CALL_LOG"
exec python3 "\$@"
FAKE_PYTHON_EOF
chmod +x "$FAKE_ORACLE_DIR/.venv/bin/python"

ORACLE_OUTPUT="$(RESTORE_CONTEXT_ORACLE_RUNNER_DIR="$FAKE_ORACLE_DIR" PATH="/usr/bin:/bin" "$TARGET" --oracle 2>&1)"
ORACLE_EXIT=$?
assert_eq "--oracle mode exits 0 against the fake staging runner" "0" "$ORACLE_EXIT"
assert_contains "--oracle mode still completes the full report" "$ORACLE_OUTPUT" "End of report."

ORACLE_CALL_ARGS="$(cat "$ORACLE_CALL_LOG")"
assert_contains "--oracle mode invoked the staging runner script" "$ORACLE_CALL_ARGS" "kc_staging_query.py"
assert_contains "--oracle mode passed exactly --test" "$ORACLE_CALL_ARGS" "--test"
assert_not_contains "--oracle mode never passed --sql" "$ORACLE_CALL_ARGS" "--sql"
assert_not_contains "--oracle mode never passed --file" "$ORACLE_CALL_ARGS" "--file"
assert_not_contains "--oracle mode never invokes the prod runner" "$ORACLE_CALL_ARGS" "kc_prod_readonly_query"

# --- Test 4: secret-looking environment values never appear -------------

CANARY="sk-canary-super-secret-do-not-leak-4f8a2b"
CANARY_OUTPUT="$(env -i PATH="/usr/bin:/bin" HOME="$HOME" \
  POSTGRES_PASSWORD="$CANARY" ORACLE_PASSWORD="$CANARY" AWS_SECRET_ACCESS_KEY="$CANARY" \
  "$TARGET" 2>&1)"
assert_not_contains "canary secret value never appears in offline output" "$CANARY_OUTPUT" "$CANARY"

# --- Test 5: missing optional tools warn instead of aborting -------------

NO_PY_DIR="$TMP_TEST_DIR/no-python-bin"
mkdir -p "$NO_PY_DIR"
for tool in git bash sed awk head tail cat date grep; do
  real="$(command -v "$tool" 2>/dev/null || true)"
  [[ -n "$real" ]] && ln -sf "$real" "$NO_PY_DIR/$tool"
done
# Deliberately do NOT link python3 - simulates it being unavailable.
NO_PY_OUTPUT="$(PATH="$NO_PY_DIR" AWS_PROFILE=bu-nprd "$TARGET" --aws 2>&1)"
NO_PY_EXIT=$?
assert_eq "missing python3 does not abort the whole report" "0" "$NO_PY_EXIT"
assert_contains "missing python3 produces a skip warning, not a crash" "$NO_PY_OUTPUT" "End of report."

# --- Test 6: output content requirements --------------------------------

assert_contains "output identifies local PostgreSQL as non-authoritative" \
  "$OFFLINE_OUTPUT" "local PostgreSQL is NOT valid for"
assert_contains "output identifies ECS Fargate as the supported dev RDS path" \
  "$OFFLINE_OUTPUT" "ECS Fargate"
assert_contains "output distinguishes historical/point-in-time facts from live verification" \
  "$OFFLINE_OUTPUT" "point-in-time snapshot"
assert_contains "output warns the removed tunnel is unsupported" \
  "$OFFLINE_OUTPUT" "has been removed as unsupported"

# --- Test 7: --output refuses to write inside the repository ------------

INSIDE_REPO_EXIT=0
(cd "$SCRIPTS_DIR/.." && "$TARGET" --output "./should-not-exist-$$.md") >/dev/null 2>&1 || INSIDE_REPO_EXIT=$?
assert_eq "--output inside the repo is refused (nonzero exit)" "1" "$INSIDE_REPO_EXIT"
[[ -f "$SCRIPTS_DIR/../should-not-exist-$$.md" ]] && { echo "FAIL: refused --output still created a file" >&2; FAILURES=$((FAILURES + 1)); }

echo
if [[ "$FAILURES" -eq 0 ]]; then
  echo "All tests passed."
  exit 0
else
  echo "$FAILURES test(s) failed." >&2
  exit 1
fi
