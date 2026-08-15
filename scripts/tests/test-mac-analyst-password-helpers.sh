#!/usr/bin/env bash
set -uo pipefail

# Regression tests for scripts/mac-show-analyst-password.sh and
# scripts/mac-generate-analyst-password.sh. Fully offline: a fake `aws`
# binary and fake `pbcopy`/`pbpaste` binaries (backed by a plain file
# standing in for "the clipboard") stand in for the real ones via PATH
# injection, so these tests never call live AWS and never touch the
# real macOS clipboard - see the 2026-08-15 incident these two scripts
# were hardened against (docs/runbooks/CLOUDSHELL_ANALYSIS.md).
#
# Usage: scripts/tests/test-mac-analyst-password-helpers.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SHOW_TARGET="$SCRIPTS_DIR/mac-show-analyst-password.sh"
GENERATE_TARGET="$SCRIPTS_DIR/mac-generate-analyst-password.sh"

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
    echo "FAIL: $description (expected output NOT to contain a secret value)" >&2
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

FIXTURE_PASSWORD="aB3xQ9zK7mP2wR8tY1uV4nJ6hL0sD5fG"  # 32 chars, test-only
FIXTURE_HOST="rds.example.com"
FIXTURE_PORT="5432"
FIXTURE_DBNAME="research_archive"
FIXTURE_USERNAME="archive_analyst"
CANARY_CLIPBOARD_CONTENT="unrelated-1237-char-sql-canary-do-not-overwrite"

# --- fake `aws` --------------------------------------------------------
#
# Behavior controlled by env vars read at call time:
#   FAKE_AWS_ACCOUNT           Account in the sts identity response (default 770203350335)
#   FAKE_AWS_IDENTITY_EXIT     exit code for sts get-caller-identity (default 0)
#   FAKE_AWS_IDENTITY_STDERR   stderr text to emit on a successful identity call
#   FAKE_SSM_VALUE             text ssm get-parameter returns as Parameter.Value
#   FAKE_SSM_GET_EXIT          exit code for ssm get-parameter (default 0)
#   FAKE_SSM_STDERR            stderr text to emit on an otherwise-successful get-parameter -
#                               this is the exact shape of the original 2026-08-15 bug
#   FAKE_SSM_PUT_EXIT          exit code for ssm put-parameter (default 0)
#   FAKE_PUT_PARAMETER_CAPTURE_FILE  where put-parameter's --value file:// content is copied to
write_fake_aws() {
  local dir="$1"
  mkdir -p "$dir"
  cat > "$dir/aws" <<'FAKE_AWS_EOF'
#!/usr/bin/env bash
args=("$@")
case "${args[0]:-} ${args[1]:-}" in
  "sts get-caller-identity")
    if [ -n "${FAKE_AWS_IDENTITY_STDERR:-}" ]; then
      echo "$FAKE_AWS_IDENTITY_STDERR" >&2
    fi
    if [ "${FAKE_AWS_IDENTITY_EXIT:-0}" != "0" ]; then
      echo "An error occurred (ExpiredToken) when calling GetCallerIdentity" >&2
      exit "${FAKE_AWS_IDENTITY_EXIT:-1}"
    fi
    echo "{\"Account\":\"${FAKE_AWS_ACCOUNT:-770203350335}\",\"Arn\":\"arn:aws:sts::${FAKE_AWS_ACCOUNT:-770203350335}:assumed-role/fake/test\"}"
    exit 0
    ;;
  "ssm get-parameter")
    if [ -n "${FAKE_SSM_STDERR:-}" ]; then
      echo "$FAKE_SSM_STDERR" >&2
    fi
    if [ "${FAKE_SSM_GET_EXIT:-0}" != "0" ]; then
      echo "An error occurred (ParameterNotFound)" >&2
      exit "${FAKE_SSM_GET_EXIT:-1}"
    fi
    printf '%s' "${FAKE_SSM_VALUE:-}"
    exit 0
    ;;
  "ssm put-parameter")
    if [ "${FAKE_SSM_PUT_EXIT:-0}" != "0" ]; then
      echo "An error occurred (AccessDenied)" >&2
      exit "${FAKE_SSM_PUT_EXIT:-1}"
    fi
    for a in "${args[@]}"; do
      case "$a" in
        file://*)
          [ -n "${FAKE_PUT_PARAMETER_CAPTURE_FILE:-}" ] && cp "${a#file://}" "$FAKE_PUT_PARAMETER_CAPTURE_FILE"
          ;;
      esac
    done
    exit 0
    ;;
  *)
    echo "FAKE AWS: unexpected/unhandled call: $*" >&2
    exit 1
    ;;
esac
FAKE_AWS_EOF
  chmod +x "$dir/aws"
}

# --- fake `pbcopy` / `pbpaste` ------------------------------------------
#
# Back a fake clipboard with a plain file (FAKE_CLIPBOARD_FILE) instead
# of the real macOS pasteboard - the real one is never touched by these
# tests. FAKE_PBCOPY_EXIT_CODE simulates a pbcopy failure without
# writing anything. FAKE_PBPASTE_OVERRIDE, if SET (even to empty),
# makes pbpaste report that value regardless of the file's real
# content - used to simulate a clipboard that silently didn't take the
# copy, proving the scripts' own verification step catches it.
write_fake_clipboard_tools() {
  local dir="$1"
  mkdir -p "$dir"
  cat > "$dir/pbcopy" <<'FAKE_PBCOPY_EOF'
#!/usr/bin/env bash
if [ "${FAKE_PBCOPY_EXIT_CODE:-0}" != "0" ]; then
  cat >/dev/null
  exit "${FAKE_PBCOPY_EXIT_CODE:-1}"
fi
cat > "$FAKE_CLIPBOARD_FILE"
exit 0
FAKE_PBCOPY_EOF
  chmod +x "$dir/pbcopy"

  cat > "$dir/pbpaste" <<'FAKE_PBPASTE_EOF'
#!/usr/bin/env bash
if [ -n "${FAKE_PBPASTE_OVERRIDE+x}" ]; then
  printf '%s' "$FAKE_PBPASTE_OVERRIDE"
  exit 0
fi
[ -f "$FAKE_CLIPBOARD_FILE" ] && cat "$FAKE_CLIPBOARD_FILE"
exit 0
FAKE_PBPASTE_EOF
  chmod +x "$dir/pbpaste"
}

reset_fake_env() {
  unset FAKE_AWS_ACCOUNT FAKE_AWS_IDENTITY_EXIT FAKE_AWS_IDENTITY_STDERR
  unset FAKE_SSM_VALUE FAKE_SSM_GET_EXIT FAKE_SSM_STDERR FAKE_SSM_PUT_EXIT
  unset FAKE_PUT_PARAMETER_CAPTURE_FILE
  unset FAKE_PBCOPY_EXIT_CODE FAKE_PBPASTE_OVERRIDE
  export FAKE_AWS_ACCOUNT="770203350335"
}

valid_analyst_json() {
  printf '{"password":"%s","host":"%s","port":"%s","dbname":"%s","username":"%s"}' \
    "$FIXTURE_PASSWORD" "$FIXTURE_HOST" "$FIXTURE_PORT" "$FIXTURE_DBNAME" "$FIXTURE_USERNAME"
}

# run_target_with_stdin TARGET BIN_DIR OUT_FILE CLIPBOARD_FILE
#   Runs TARGET with stdin from /dev/null (for failure paths that exit
#   before any interactive read prompt). Returns the exit code via $?.
run_target_no_stdin() {
  local target="$1" bin_dir="$2" out_file="$3"
  PATH="$bin_dir:/usr/bin:/bin" "$target" </dev/null >"$out_file" 2>&1
  return $?
}

# --- show-password: failure paths (all exit before any read prompt) -----

test_show_missing_parameter() {
  local dir="$TMP_TEST_DIR/show-missing-param"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_SSM_GET_EXIT="254"

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: missing SSM parameter exits nonzero" "1" "$exit_code"
  assert_contains "show-password: missing SSM parameter mentions the fetch failure" "$(cat "$out")" "Could not fetch"
  assert_eq "show-password: missing SSM parameter never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_show_invalid_json() {
  local dir="$TMP_TEST_DIR/show-invalid-json"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_SSM_VALUE='not valid json{{{'

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: invalid JSON exits nonzero" "1" "$exit_code"
  assert_contains "show-password: invalid JSON is reported as unusable" "$(cat "$out")" "unusable"
  assert_eq "show-password: invalid JSON never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_show_missing_password_field() {
  local dir="$TMP_TEST_DIR/show-missing-field"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_SSM_VALUE='{"host":"rds.example.com","port":"5432","dbname":"research_archive","username":"archive_analyst"}'

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: missing password field exits nonzero" "1" "$exit_code"
  assert_contains "show-password: missing password field names 'password'" "$(cat "$out")" "password"
  assert_eq "show-password: missing password field never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_show_blank_password() {
  local dir="$TMP_TEST_DIR/show-blank-password"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_SSM_VALUE='{"password":"","host":"rds.example.com","port":"5432","dbname":"research_archive","username":"archive_analyst"}'

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: blank password exits nonzero" "1" "$exit_code"
  assert_eq "show-password: blank password never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_show_aws_identity_failure() {
  local dir="$TMP_TEST_DIR/show-identity-fail"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_AWS_IDENTITY_EXIT="254"

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: AWS identity failure exits nonzero" "1" "$exit_code"
  assert_contains "show-password: AWS identity failure is reported" "$(cat "$out")" "Could not resolve AWS identity"
  assert_eq "show-password: AWS identity failure never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_show_account_mismatch() {
  local dir="$TMP_TEST_DIR/show-account-mismatch"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_AWS_ACCOUNT="589744711110"

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: account mismatch exits nonzero" "1" "$exit_code"
  assert_contains "show-password: account mismatch names the wrong account" "$(cat "$out")" "589744711110"
  assert_eq "show-password: account mismatch never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_show_pbcopy_failure() {
  local dir="$TMP_TEST_DIR/show-pbcopy-fail"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_SSM_VALUE="$(valid_analyst_json)"
  export FAKE_PBCOPY_EXIT_CODE="1"

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: pbcopy failure exits nonzero" "1" "$exit_code"
  assert_contains "show-password: pbcopy failure is reported" "$(cat "$out")" "pbcopy failed"
  assert_not_contains "show-password: pbcopy failure never prints the password" "$(cat "$out")" "$FIXTURE_PASSWORD"
  assert_eq "show-password: pbcopy failure never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_show_clipboard_verification_mismatch() {
  local dir="$TMP_TEST_DIR/show-verify-mismatch"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  export FAKE_SSM_VALUE="$(valid_analyst_json)"
  export FAKE_PBPASTE_OVERRIDE="something-else-entirely"

  local out="$dir/out.log"
  run_target_no_stdin "$SHOW_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "show-password: clipboard verification mismatch exits nonzero" "1" "$exit_code"
  assert_contains "show-password: clipboard verification mismatch is reported" "$(cat "$out")" "verification failed"
  assert_not_contains "show-password: verification mismatch never prints the password" "$(cat "$out")" "$FIXTURE_PASSWORD"
}

test_show_stderr_noise_on_success_is_ignored() {
  # This is the exact regression shape of the live 2026-08-15 incident:
  # a successful AWS call (exit 0) that also writes to stderr must never
  # have that stderr text silently merged into the JSON stdout gets
  # parsed as.
  local dir="$TMP_TEST_DIR/show-stderr-noise"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  export FAKE_SSM_VALUE="$(valid_analyst_json)"
  export FAKE_SSM_STDERR="Note: a future version of the AWS CLI will change this behavior"
  export FAKE_AWS_IDENTITY_STDERR="Warning: credentials will expire soon"

  local fifo="$dir/stdin_fifo"
  local out="$dir/out.log"
  mkfifo "$fifo"
  exec 9<>"$fifo"
  PATH="$bin:/usr/bin:/bin" "$SHOW_TARGET" <"$fifo" >"$out" 2>&1 &
  local pid=$!
  local waited=0
  while [ "$waited" -lt 50 ]; do
    grep -q "Password copied" "$out" 2>/dev/null && break
    sleep 0.1
    waited=$((waited + 1))
  done
  printf '\n' >&9
  wait "$pid"
  local exit_code=$?
  exec 9>&-
  rm -f "$fifo"

  assert_eq "show-password: stderr noise on a successful call still exits 0" "0" "$exit_code"
  assert_contains "show-password: stderr noise on success still reaches the psql banner" "$(cat "$out")" "Password copied"
}

# --- show-password: happy path (exact clipboard content, no trailing --
# --- newline, clearing on completion) -----------------------------------

test_show_happy_path() {
  local dir="$TMP_TEST_DIR/show-happy"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  export FAKE_SSM_VALUE="$(valid_analyst_json)"

  local fifo="$dir/stdin_fifo"
  local out="$dir/out.log"
  mkfifo "$fifo"
  exec 9<>"$fifo"
  PATH="$bin:/usr/bin:/bin" "$SHOW_TARGET" <"$fifo" >"$out" 2>&1 &
  local pid=$!

  local waited=0
  while [ "$waited" -lt 50 ]; do
    grep -q "Password copied" "$out" 2>/dev/null && break
    sleep 0.1
    waited=$((waited + 1))
  done

  local mid_content="" mid_bytes="0"
  [ -f "$FAKE_CLIPBOARD_FILE" ] && mid_content="$(cat "$FAKE_CLIPBOARD_FILE")"
  [ -f "$FAKE_CLIPBOARD_FILE" ] && mid_bytes="$(wc -c < "$FAKE_CLIPBOARD_FILE" | tr -d ' ')"

  printf '\n' >&9
  wait "$pid"
  local exit_code=$?
  exec 9>&-
  rm -f "$fifo"

  assert_eq "show-password: happy path exits 0" "0" "$exit_code"
  assert_eq "show-password: clipboard holds exactly the fetched password" "$FIXTURE_PASSWORD" "$mid_content"
  assert_eq "show-password: clipboard has no trailing newline (32 bytes)" "32" "$mid_bytes"
  assert_not_contains "show-password: never prints the password to output" "$(cat "$out")" "$FIXTURE_PASSWORD"
  assert_contains "show-password: prints the non-secret psql command" "$(cat "$out")" "user=$FIXTURE_USERNAME"

  local post_content=""
  [ -f "$FAKE_CLIPBOARD_FILE" ] && post_content="$(cat "$FAKE_CLIPBOARD_FILE")"
  assert_eq "show-password: clipboard is cleared after Enter" "" "$post_content"
}

test_show_happy_path_clears_on_interrupt() {
  # SIGTERM after the copy (simulating Ctrl-C while waiting at the
  # "press Enter" prompt) must still clear the clipboard via the
  # EXIT/INT/TERM trap - not only the normal-completion path above.
  local dir="$TMP_TEST_DIR/show-interrupt"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  export FAKE_SSM_VALUE="$(valid_analyst_json)"

  local fifo="$dir/stdin_fifo"
  local out="$dir/out.log"
  mkfifo "$fifo"
  exec 9<>"$fifo"
  PATH="$bin:/usr/bin:/bin" "$SHOW_TARGET" <"$fifo" >"$out" 2>&1 &
  local pid=$!

  local waited=0
  while [ "$waited" -lt 50 ]; do
    grep -q "Password copied" "$out" 2>/dev/null && break
    sleep 0.1
    waited=$((waited + 1))
  done

  kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null
  exec 9>&-
  rm -f "$fifo"

  local post_content=""
  [ -f "$FAKE_CLIPBOARD_FILE" ] && post_content="$(cat "$FAKE_CLIPBOARD_FILE")"
  assert_eq "show-password: clipboard is cleared after SIGTERM (interruption)" "" "$post_content"
}

# --- generate-password: failure paths -----------------------------------

test_generate_aws_identity_failure() {
  local dir="$TMP_TEST_DIR/generate-identity-fail"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_AWS_IDENTITY_EXIT="254"

  local out="$dir/out.log"
  run_target_no_stdin "$GENERATE_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "generate-password: AWS identity failure exits nonzero" "1" "$exit_code"
  assert_eq "generate-password: AWS identity failure never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_generate_put_parameter_failure() {
  local dir="$TMP_TEST_DIR/generate-put-fail"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_SSM_PUT_EXIT="1"

  local out="$dir/out.log"
  run_target_no_stdin "$GENERATE_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "generate-password: ssm put-parameter failure exits nonzero" "1" "$exit_code"
  assert_contains "generate-password: put-parameter failure is reported" "$(cat "$out")" "put-parameter failed"
  assert_eq "generate-password: put-parameter failure never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_generate_pbcopy_failure() {
  local dir="$TMP_TEST_DIR/generate-pbcopy-fail"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  printf '%s' "$CANARY_CLIPBOARD_CONTENT" > "$FAKE_CLIPBOARD_FILE"
  export FAKE_PBCOPY_EXIT_CODE="1"

  local out="$dir/out.log"
  run_target_no_stdin "$GENERATE_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "generate-password: pbcopy failure exits nonzero" "1" "$exit_code"
  assert_contains "generate-password: pbcopy failure is reported" "$(cat "$out")" "pbcopy failed"
  assert_eq "generate-password: pbcopy failure never touches the clipboard" "$CANARY_CLIPBOARD_CONTENT" "$(cat "$FAKE_CLIPBOARD_FILE")"
}

test_generate_clipboard_verification_mismatch() {
  local dir="$TMP_TEST_DIR/generate-verify-mismatch"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  export FAKE_PBPASTE_OVERRIDE="something-else-entirely"

  local out="$dir/out.log"
  run_target_no_stdin "$GENERATE_TARGET" "$bin" "$out"
  local exit_code=$?

  assert_eq "generate-password: clipboard verification mismatch exits nonzero" "1" "$exit_code"
  assert_contains "generate-password: clipboard verification mismatch is reported" "$(cat "$out")" "verification failed"
}

# --- generate-password: happy path (env-var delivery, clipboard, ------
# --- clearing) ------------------------------------------------------------

test_generate_happy_path() {
  local dir="$TMP_TEST_DIR/generate-happy"
  local bin="$dir/bin"
  mkdir -p "$bin"
  write_fake_aws "$bin"
  write_fake_clipboard_tools "$bin"
  reset_fake_env
  export FAKE_CLIPBOARD_FILE="$dir/clipboard"
  local capture_file="$dir/put-parameter-value.json"
  export FAKE_PUT_PARAMETER_CAPTURE_FILE="$capture_file"

  local fifo="$dir/stdin_fifo"
  local out="$dir/out.log"
  mkfifo "$fifo"
  exec 9<>"$fifo"
  PATH="$bin:/usr/bin:/bin" "$GENERATE_TARGET" <"$fifo" >"$out" 2>&1 &
  local pid=$!

  local waited=0
  while [ "$waited" -lt 50 ]; do
    grep -q "Stored in SSM" "$out" 2>/dev/null && break
    sleep 0.1
    waited=$((waited + 1))
  done

  local mid_content="" mid_bytes="0"
  [ -f "$FAKE_CLIPBOARD_FILE" ] && mid_content="$(cat "$FAKE_CLIPBOARD_FILE")"
  [ -f "$FAKE_CLIPBOARD_FILE" ] && mid_bytes="$(wc -c < "$FAKE_CLIPBOARD_FILE" | tr -d ' ')"

  printf '\n' >&9
  wait "$pid"
  local exit_code=$?
  exec 9>&-
  rm -f "$fifo"

  assert_eq "generate-password: happy path exits 0" "0" "$exit_code"
  assert_eq "generate-password: clipboard has no trailing newline (32 bytes)" "32" "$mid_bytes"
  assert_not_contains "generate-password: never prints the generated password" "$(cat "$out")" "$mid_content"

  local post_content=""
  [ -f "$FAKE_CLIPBOARD_FILE" ] && post_content="$(cat "$FAKE_CLIPBOARD_FILE")"
  assert_eq "generate-password: clipboard is cleared after Enter" "" "$post_content"

  # --- Generator environment-variable delivery ---------------------------
  # Verifies the RDS_HOST/RDS_PORT/RDS_DBNAME/ARCHIVE_ANALYST_PASSWORD
  # env-prefix assignment actually reached the python3 JSON-construction
  # step correctly - this is the exact defect being fixed (values were
  # previously passed as dead positional arguments the script's own
  # python3 code never read). Field checks only - the password value
  # itself is never echoed to test output.
  if [ -s "$capture_file" ]; then
    DELIVERY_CHECK="$(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
checks = [
    data.get("host") == "research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com",
    data.get("port") == 5432,
    data.get("dbname") == "research_archive",
    data.get("username") == "archive_analyst",
    isinstance(data.get("password"), str) and len(data["password"]) == 32,
    data.get("password") == sys.argv[2],
]
print("OK" if all(checks) else "MISMATCH")
' "$capture_file" "$mid_content" 2>/dev/null)"
    assert_eq "generate-password: env-prefix delivered host/port/dbname/username/password correctly to python3" "OK" "$DELIVERY_CHECK"
  else
    echo "FAIL: generate-password: ssm put-parameter was never called (no captured JSON)" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

# --- run everything -------------------------------------------------------

test_show_missing_parameter
test_show_invalid_json
test_show_missing_password_field
test_show_blank_password
test_show_aws_identity_failure
test_show_account_mismatch
test_show_pbcopy_failure
test_show_clipboard_verification_mismatch
test_show_stderr_noise_on_success_is_ignored
test_show_happy_path
test_show_happy_path_clears_on_interrupt

test_generate_aws_identity_failure
test_generate_put_parameter_failure
test_generate_pbcopy_failure
test_generate_clipboard_verification_mismatch
test_generate_happy_path

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All tests passed."
  exit 0
else
  echo "$FAILURES test(s) failed." >&2
  exit 1
fi
