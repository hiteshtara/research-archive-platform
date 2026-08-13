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

# write_fake_aws DIR CALL_LOG: writes a parameterized fake `aws` binary
# to DIR/aws, logging every full argv line to CALL_LOG. Every real call
# this script makes (except `aws configure list-profiles`, which takes
# no --profile/--region - it's how the profile's existence is discovered
# in the first place) is expected to arrive as
# `--profile bu-nprd --region us-east-1 <subcommand> ...` - the fake
# strips exactly that fixed 4-token prefix before matching, so tests
# assert against the real subcommand rather than hand-parsing the whole
# argv each time. Behavior is controlled by env vars read at call time:
#   FAKE_AWS_PROFILES        space-separated profiles `configure
#                             list-profiles` should report (default: bu-nprd)
#   FAKE_AWS_IDENTITY_EXIT   exit code for sts get-caller-identity (default: 0)
#   FAKE_AWS_ACCOUNT         Account value in the identity JSON (default: 770203350335)
write_fake_aws() {
  local dir="$1" call_log="$2"
  mkdir -p "$dir"
  : > "$call_log"
  cat > "$dir/aws" <<FAKE_AWS_EOF
#!/usr/bin/env bash
echo "\$*" >> "$call_log"

profiles="\${FAKE_AWS_PROFILES:-bu-nprd}"
identity_exit="\${FAKE_AWS_IDENTITY_EXIT:-0}"
account="\${FAKE_AWS_ACCOUNT:-770203350335}"

args=("\$@")
if [[ "\${args[0]:-}" == "configure" && "\${args[1]:-}" == "list-profiles" ]]; then
  for p in \$profiles; do echo "\$p"; done
  exit 0
fi

# Every other allowed call must arrive with the fixed 4-token
# --profile bu-nprd --region us-east-1 prefix - anything else (a bare
# call, a different profile/region, or a call this script shouldn't be
# making at all) is rejected outright, the same way a real misconfigured
# or malicious call would be caught.
if [[ "\${args[0]:-}" != "--profile" || "\${args[1]:-}" != "bu-nprd" \\
      || "\${args[2]:-}" != "--region" || "\${args[3]:-}" != "us-east-1" ]]; then
  echo "FAKE AWS: call missing the required --profile bu-nprd --region us-east-1 prefix: \$*" >&2
  exit 1
fi

case "\${args[4]:-} \${args[5]:-}" in
  "sts get-caller-identity")
    if [[ "\$identity_exit" != "0" ]]; then
      echo "An error occurred (ExpiredToken) when calling the GetCallerIdentity operation" >&2
      exit "\$identity_exit"
    fi
    echo "{\\"Account\\":\\"\$account\\",\\"Arn\\":\\"arn:aws:sts::\$account:assumed-role/fake/test\\"}"
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
  chmod +x "$dir/aws"
}

FAKE_AWS_DIR="$TMP_TEST_DIR/fake-aws-bin"
AWS_CALL_LOG="$TMP_TEST_DIR/aws-calls.log"
write_fake_aws "$FAKE_AWS_DIR" "$AWS_CALL_LOG"

AWS_OUTPUT="$(PATH="$FAKE_AWS_DIR:/usr/bin:/bin" "$TARGET" --aws 2>&1)"
AWS_EXIT=$?
assert_eq "--aws mode exits 0 against the fake allowlisted aws" "0" "$AWS_EXIT"
assert_contains "--aws mode reports the expected account" "$AWS_OUTPUT" "770203350335"

DISALLOWED_FOUND="no"
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  case "$line" in
    "configure list-profiles"*)
      ;;
    "--profile bu-nprd --region us-east-1 sts get-caller-identity"*| \
    "--profile bu-nprd --region us-east-1 ecs describe-clusters"*| \
    "--profile bu-nprd --region us-east-1 ecs list-task-definitions"*| \
    "--profile bu-nprd --region us-east-1 ecs describe-task-definition"*| \
    "--profile bu-nprd --region us-east-1 ecs list-tasks"*)
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

# --- Test 2b: every single AWS call carries the correct profile/region --

EVERY_CALL_HAS_PROFILE="yes"
CALL_COUNT=0
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  CALL_COUNT=$((CALL_COUNT + 1))
  case "$line" in
    "configure list-profiles"*)
      # The one legitimate exception - discovering whether the profile
      # exists at all necessarily happens before it can be passed.
      ;;
    "--profile bu-nprd --region us-east-1 "*)
      ;;
    *)
      EVERY_CALL_HAS_PROFILE="no: $line"
      ;;
  esac
done < "$AWS_CALL_LOG"
assert_eq "every non-discovery AWS call carries --profile bu-nprd --region us-east-1" "yes" "$EVERY_CALL_HAS_PROFILE"
if [[ "$CALL_COUNT" -lt 2 ]]; then
  echo "FAIL: sanity check - expected multiple AWS calls in the log, got $CALL_COUNT" >&2
  FAILURES=$((FAILURES + 1))
fi

# --- Test 2c: default/personal credentials are never used ---------------
#
# A bare `aws` call (no --profile at all) would silently fall through to
# the ambient default credential chain - exactly the bug being fixed.
# The fake `aws` above already rejects any call missing the exact
# --profile bu-nprd --region us-east-1 prefix (other than the profile-
# discovery call), so a passing Test 2/2b run already proves this - this
# test additionally asserts no call is missing --profile outright, using
# a fake that would happily succeed for a bare call so a regression
# can't hide behind the strict fake's own rejection above.
LENIENT_AWS_DIR="$TMP_TEST_DIR/fake-aws-lenient-bin"
LENIENT_CALL_LOG="$TMP_TEST_DIR/aws-calls-lenient.log"
mkdir -p "$LENIENT_AWS_DIR"
: > "$LENIENT_CALL_LOG"
cat > "$LENIENT_AWS_DIR/aws" <<LENIENT_AWS_EOF
#!/usr/bin/env bash
echo "\$*" >> "$LENIENT_CALL_LOG"
case "\$*" in
  *"configure list-profiles"*) echo "bu-nprd" ;;
  *"sts get-caller-identity"*) echo '{"Account":"770203350335","Arn":"arn:test"}' ;;
  *"ecs describe-clusters"*) echo '{"status":"ACTIVE"}' ;;
  *"ecs list-task-definitions"*) echo "arn:test"; echo "None" ;;
  *"ecs describe-task-definition"*) echo '{}' ;;
  *"ecs list-tasks"*) echo '[]' ;;
  *) exit 1 ;;
esac
LENIENT_AWS_EOF
chmod +x "$LENIENT_AWS_DIR/aws"

PATH="$LENIENT_AWS_DIR:/usr/bin:/bin" "$TARGET" --aws >/dev/null 2>&1 || true

NO_BARE_CALL_FOUND="yes"
while IFS= read -r line; do
  [[ -z "$line" ]] && continue
  case "$line" in
    "configure list-profiles"*) ;;
    "--profile bu-nprd --region us-east-1 "*) ;;
    *) NO_BARE_CALL_FOUND="no: found a call without the profile/region prefix: $line" ;;
  esac
done < "$LENIENT_CALL_LOG"
assert_eq "no AWS call is ever made without --profile bu-nprd --region us-east-1 (default credentials never used)" "yes" "$NO_BARE_CALL_FOUND"

# --- Test 2d: account mismatch stops further AWS inspection -------------

MISMATCH_AWS_DIR="$TMP_TEST_DIR/fake-aws-mismatch-bin"
MISMATCH_CALL_LOG="$TMP_TEST_DIR/aws-calls-mismatch.log"
write_fake_aws "$MISMATCH_AWS_DIR" "$MISMATCH_CALL_LOG"

MISMATCH_OUTPUT="$(FAKE_AWS_ACCOUNT="589744711110" PATH="$MISMATCH_AWS_DIR:/usr/bin:/bin" "$TARGET" --aws 2>&1)"
MISMATCH_EXIT=$?
assert_eq "account mismatch still exits 0 (report completes)" "0" "$MISMATCH_EXIT"
assert_contains "account mismatch reports the wrong resolved account" "$MISMATCH_OUTPUT" "589744711110"
assert_contains "account mismatch explicitly says AWS verification stopped" "$MISMATCH_OUTPUT" "STOPPING AWS verification"
assert_contains "account mismatch report still reaches the end" "$MISMATCH_OUTPUT" "End of report."

MISMATCH_CALLS="$(cat "$MISMATCH_CALL_LOG")"
assert_contains "account mismatch still calls sts get-caller-identity" "$MISMATCH_CALLS" "sts get-caller-identity"
assert_not_contains "account mismatch never calls ecs describe-clusters" "$MISMATCH_CALLS" "ecs describe-clusters"
assert_not_contains "account mismatch never calls ecs list-task-definitions" "$MISMATCH_CALLS" "ecs list-task-definitions"
assert_not_contains "account mismatch never calls ecs list-tasks" "$MISMATCH_CALLS" "ecs list-tasks"

# --- Test 2e: expired/missing credentials produce a clear buaws warning -

EXPIRED_AWS_DIR="$TMP_TEST_DIR/fake-aws-expired-bin"
EXPIRED_CALL_LOG="$TMP_TEST_DIR/aws-calls-expired.log"
write_fake_aws "$EXPIRED_AWS_DIR" "$EXPIRED_CALL_LOG"

EXPIRED_OUTPUT="$(FAKE_AWS_IDENTITY_EXIT="254" PATH="$EXPIRED_AWS_DIR:/usr/bin:/bin" "$TARGET" --aws 2>&1)"
EXPIRED_EXIT=$?
assert_eq "expired credentials still exits 0 (report completes)" "0" "$EXPIRED_EXIT"
assert_contains "expired credentials tell the user to run buaws" "$EXPIRED_OUTPUT" "buaws"
assert_contains "expired credentials explicitly mention expired/missing" "$EXPIRED_OUTPUT" "expired"
assert_contains "expired credentials report still reaches the end" "$EXPIRED_OUTPUT" "End of report."

EXPIRED_CALLS="$(cat "$EXPIRED_CALL_LOG")"
assert_not_contains "expired credentials never reach ecs describe-clusters" "$EXPIRED_CALLS" "ecs describe-clusters"

# --- Test 2f: missing bu-nprd profile also produces a clear buaws warning

NOPROFILE_AWS_DIR="$TMP_TEST_DIR/fake-aws-noprofile-bin"
NOPROFILE_CALL_LOG="$TMP_TEST_DIR/aws-calls-noprofile.log"
write_fake_aws "$NOPROFILE_AWS_DIR" "$NOPROFILE_CALL_LOG"

NOPROFILE_OUTPUT="$(FAKE_AWS_PROFILES="default some-other-profile" PATH="$NOPROFILE_AWS_DIR:/usr/bin:/bin" "$TARGET" --aws 2>&1)"
NOPROFILE_EXIT=$?
assert_eq "missing bu-nprd profile still exits 0 (report completes)" "0" "$NOPROFILE_EXIT"
assert_contains "missing bu-nprd profile tells the user to run buaws" "$NOPROFILE_OUTPUT" "buaws"
assert_contains "missing bu-nprd profile names the profile explicitly" "$NOPROFILE_OUTPUT" "bu-nprd"
assert_contains "missing bu-nprd profile report still reaches the end" "$NOPROFILE_OUTPUT" "End of report."

NOPROFILE_CALLS="$(cat "$NOPROFILE_CALL_LOG")"
assert_not_contains "missing bu-nprd profile never calls sts get-caller-identity" "$NOPROFILE_CALLS" "sts get-caller-identity"
assert_not_contains "missing bu-nprd profile never calls ecs describe-clusters" "$NOPROFILE_CALLS" "ecs describe-clusters"

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
