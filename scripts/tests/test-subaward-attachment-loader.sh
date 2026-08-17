#!/usr/bin/env bash
# shellcheck disable=SC2034,SC2329,SC2030,SC2031
#
# SC2034 (appears unused) / SC2329 (never invoked) are false positives
# throughout this file: static analysis can't follow the dynamic
# `source "$SCRIPTS_DIR/run-subaward-attachment-loader.sh"` call below,
# so it doesn't see that the globals and function overrides defined here
# are read/called by that sourced script, not by this file directly. See
# scripts/tests/test-bulk-load-reconciliation.sh for the identical,
# already-established pattern this file follows.
#
# SC2030/SC2031 (subshell-local env var modification) are intentional
# throughout: every test deliberately exports AWS_PROFILE/
# POSTGRES_SECRET_ID/ORACLE_SECRET_ID/FAKE_* only inside its own `( ... )`
# subshell, precisely so each test case is isolated and one test's
# environment can never leak into the next.
set -euo pipefail

# Fully mocked tests for scripts/run-subaward-attachment-loader.sh - no
# real AWS, Docker, or Terraform call anywhere in this file. Sources the
# real script (which only defines functions when sourced - see the
# executed-directly guard at its own end) and overrides every function
# that would otherwise touch AWS/Docker/Terraform
# (verify_aws_identity's own `aws` call, resolve_project_configuration's
# `terraform output` calls, build_and_register_task_definition,
# run_ecs_task), so every test here runs in well under a second.
#
# Usage: scripts/tests/test-subaward-attachment-loader.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

FAILURES=0

assert_eq() {
  local description="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected '$expected', got '$actual')" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

assert_contains() {
  local description="$1" haystack="$2" needle="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected output to contain '$needle')" >&2
    echo "--- actual output ---" >&2
    echo "$haystack" >&2
    echo "---------------------" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

assert_not_zero() {
  local description="$1" actual="$2"
  if [[ "$actual" != "0" ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected a non-zero exit code, got 0)" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

assert_zero() {
  local description="$1" actual="$2"
  if [[ "$actual" == "0" ]]; then
    echo "PASS: $description"
  else
    echo "FAIL: $description (expected exit code 0, got $actual)" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

# shellcheck source=/dev/null
source "$SCRIPTS_DIR/run-subaward-attachment-loader.sh"

# --- Fake AWS/Docker/Terraform layer, defined AFTER sourcing the real
# script so these definitions win (see run-award-attachment-loader.sh's
# own test file for why sourcing order matters here). ---------------

FAKE_ACCOUNT_ID="770203350335"
BUILD_AND_REGISTER_CALLED=0
RUN_ECS_TASK_CALLED=0

build_and_register_task_definition() {
  BUILD_AND_REGISTER_CALLED=$((BUILD_AND_REGISTER_CALLED + 1))
  IMAGE_URI="${IMAGE_URI_OVERRIDE:-fake-built-image:test}"
  NEW_REVISION_ARN="arn:aws:ecs:fake:task-definition/fake:1"
}

run_ecs_task() {
  RUN_ECS_TASK_CALLED=$((RUN_ECS_TASK_CALLED + 1))
  TASK_EXIT_CODE="${FAKE_TASK_EXIT_CODE:-0}"
}

# Fakes `aws sts get-caller-identity` only - every other `aws` subcommand
# used by verify_aws_identity/run_ecs_task/build_and_register_task_definition
# is never reached in these tests because build_and_register_task_definition/
# run_ecs_task are themselves overridden above; this exists purely so
# verify_aws_identity's own real implementation (unmodified, exercised
# for real here) has something to call.
aws() {
  if [[ "$1" == "sts" && "$2" == "get-caller-identity" ]]; then
    printf '{"Account": "%s", "Arn": "arn:aws:iam::%s:user/test"}' \
      "${FAKE_STS_ACCOUNT_ID:-$FAKE_ACCOUNT_ID}" "${FAKE_STS_ACCOUNT_ID:-$FAKE_ACCOUNT_ID}"
    return 0
  fi
  echo "unexpected aws call in test: $*" >&2
  return 1
}

# Fakes `terraform output` so resolve_project_configuration never
# touches real Terraform state.
terraform() {
  if [[ "$1" == "output" && "$2" == "-raw" ]]; then
    case "$3" in
      documents_bucket_name) echo "fake-bucket" ;;
      loader_security_group_id) echo "sg-fake" ;;
      loader_ecr_repository_url) echo "fake.ecr/repo" ;;
      *) echo "unexpected terraform output -raw: $3" >&2; return 1 ;;
    esac
    return 0
  fi
  if [[ "$1" == "output" && "$2" == "-json" && "$3" == "private_subnet_ids" ]]; then
    echo '["subnet-fake1","subnet-fake2"]'
    return 0
  fi
  echo "unexpected terraform call in test: $*" >&2
  return 1
}

# --- Test helpers -----------------------------------------------------

# Runs the full argv-parse-through-command-construction pipeline in a
# subshell (parse_and_validate_args calls `exit` directly on invalid
# input, so this must never run in the current shell) and captures
# combined stdout+stderr plus the exit code, without ever reaching
# build_and_register_task_definition/run_ecs_task unless the caller
# wants it to (those are separately invoked by dispatch, tested below).
run_parse_only() {
  (
    export AWS_PROFILE="${TEST_AWS_PROFILE:-fake-profile}"
    export POSTGRES_SECRET_ID="${TEST_POSTGRES_SECRET_ID:-arn:aws:secretsmanager:fake:postgres}"
    export ORACLE_SECRET_ID="${TEST_ORACLE_SECRET_ID:-arn:aws:secretsmanager:fake:oracle}"
    parse_and_validate_args "$@"
    echo "PARSE_OK"
  ) 2>&1
}

# --- 1. Dry-run command for one code -----------------------------------

test_dry_run_command_for_one_code() {
  local output command_json
  output="$(
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    parse_and_validate_args --dry-run --subaward-code SYNTHETIC-SUBAWARD-A --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    build_command_array
    build_overrides_json
    echo "$OVERRIDES_JSON"
  )"
  command_json="$(echo "$output" | jq -c '.containerOverrides[0].command')"
  assert_eq "dry-run/one-code: command array" \
    '["python","attachment_orchestrator.py","--bucket","fake-bucket","--modules","subaward","--ecs","--dry-run","--subaward-code","SYNTHETIC-SUBAWARD-A"]' \
    "$command_json"
}

# --- 2. Run command for multiple codes ---------------------------------

test_run_command_for_multiple_codes() {
  local output command_json
  output="$(
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    parse_and_validate_args --run --subaward-code SYNTHETIC-SUBAWARD-A --subaward-code SYNTHETIC-SUBAWARD-B --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    build_command_array
    build_overrides_json
    echo "$OVERRIDES_JSON"
  )"
  command_json="$(echo "$output" | jq -c '.containerOverrides[0].command')"
  assert_eq "run/multi-code: command array (no --dry-run, both codes present)" \
    '["python","attachment_orchestrator.py","--bucket","fake-bucket","--modules","subaward","--ecs","--subaward-code","SYNTHETIC-SUBAWARD-A","--subaward-code","SYNTHETIC-SUBAWARD-B"]' \
    "$command_json"
}

# --- 3. Repeated values preserved exactly once, in order ---------------

test_repeated_values_preserved() {
  local output codes_json expected
  output="$(
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    parse_and_validate_args --run \
      --subaward-code CODE-1 --subaward-code CODE-2 --subaward-code CODE-3 --subaward-code CODE-4 \
      --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    build_command_array
    build_overrides_json
    echo "$OVERRIDES_JSON"
  )"
  # Extract every value immediately following a --subaward-code token,
  # in order - proves each of the 4 requested codes appears, in the
  # order given, with none lost, none duplicated, none merged.
  codes_json="$(echo "$output" | jq -c '
    .containerOverrides[0].command as $c
    | [range(0; $c | length) | select($c[.] == "--subaward-code") | $c[. + 1]]
  ')"
  expected='["CODE-1","CODE-2","CODE-3","CODE-4"]'
  assert_eq "repeated --subaward-code values survive exactly once, in order" "$expected" "$codes_json"
}

# --- 4. Missing scope rejected ------------------------------------------

test_missing_scope_rejected() {
  local output rc
  set +e
  output="$(run_parse_only --run --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "unscoped invocation (no --subaward-code, no --all-subawards) is rejected" "$rc"
  assert_contains "unscoped rejection error message" "$output" "--all-subawards"
}

# --- 5. --all-subawards requires explicit use ---------------------------

test_all_subawards_requires_explicit_flag() {
  local output rc command_json
  # Positive case: --all-subawards alone (no --subaward-code) is valid
  # and produces a command with NO --subaward-code token at all.
  output="$(
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    parse_and_validate_args --run --all-subawards --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    build_command_array
    build_overrides_json
    echo "$OVERRIDES_JSON"
  )"
  command_json="$(echo "$output" | jq -c '.containerOverrides[0].command')"
  assert_eq "--all-subawards alone: no --subaward-code in the command" \
    '["python","attachment_orchestrator.py","--bucket","fake-bucket","--modules","subaward","--ecs"]' \
    "$command_json"

  # Negative case, restated for clarity alongside the positive one:
  # omitting BOTH flags is never silently treated as --all-subawards.
  set +e
  output="$(run_parse_only --dry-run --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "omitting both --subaward-code and --all-subawards is never implicit full-population" "$rc"
}

# --- 6. Conflicting scope flags rejected ---------------------------------

test_conflicting_scope_flags_rejected() {
  local output rc
  set +e
  output="$(run_parse_only --run --subaward-code SYNTHETIC-SUBAWARD-A --all-subawards --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "--subaward-code combined with --all-subawards is rejected" "$rc"
  assert_contains "conflicting-scope error message" "$output" "cannot be combined"
}

# --- 7. Account mismatch rejected ----------------------------------------

test_account_mismatch_rejected() {
  local rc
  BUILD_AND_REGISTER_CALLED=0
  RUN_ECS_TASK_CALLED=0
  set +e
  (
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    export FAKE_STS_ACCOUNT_ID="999999999999"
    parse_and_validate_args --dry-run --subaward-code SYNTHETIC-SUBAWARD-A --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    verify_aws_identity
  ) > /tmp/test-subaward-loader-account-mismatch.out 2>&1
  rc=$?
  set -e
  assert_not_zero "AWS_PROFILE resolving to the wrong account is rejected" "$rc"
  assert_contains "account-mismatch error names both accounts" \
    "$(cat /tmp/test-subaward-loader-account-mismatch.out)" "999999999999"
  assert_eq "account mismatch: build_and_register_task_definition never called" "0" "$BUILD_AND_REGISTER_CALLED"
  assert_eq "account mismatch: run_ecs_task never called" "0" "$RUN_ECS_TASK_CALLED"
  rm -f /tmp/test-subaward-loader-account-mismatch.out
}

# --- 8. Missing image rejected --------------------------------------------

test_missing_image_rejected() {
  local output rc
  set +e
  output="$(run_parse_only --run --subaward-code SYNTHETIC-SUBAWARD-A)"; rc=$?
  set -e
  assert_not_zero "missing both --image-uri and --build-image is rejected" "$rc"
  assert_contains "missing-image error message" "$output" "--build-image"

  set +e
  output="$(run_parse_only --run --subaward-code SYNTHETIC-SUBAWARD-A --image-uri fake:uri --build-image)"; rc=$?
  set -e
  assert_not_zero "--image-uri combined with --build-image is rejected" "$rc"
  assert_contains "conflicting-image error message" "$output" "mutually exclusive"
}

# --- 9. Command JSON cannot lose or merge arguments ------------------------

test_command_json_cannot_lose_or_merge_arguments() {
  local output element_count command_json
  output="$(
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    parse_and_validate_args --run --subaward-code "CODE A" --subaward-code 'CODE;rm -rf /' --subaward-code 'CODE"quote' --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    build_command_array
    build_overrides_json
    echo "$OVERRIDES_JSON"
  )"
  command_json="$(echo "$output" | jq -c '.containerOverrides[0].command')"
  element_count="$(echo "$output" | jq '.containerOverrides[0].command | length')"
  # 6 fixed tokens (python, attachment_orchestrator.py, --bucket,
  # fake-bucket, --modules, subaward, --ecs) + 2 per code * 3 codes.
  assert_eq "command array element count matches exactly (no merging into one token)" "13" "$element_count"
  # Each adversarial value (spaces, a shell metacharacter, an embedded
  # double quote) must survive as its OWN distinct array element,
  # proving jq - not shell string concatenation - owns the boundaries.
  assert_eq "adversarial value with a space survives as one distinct element" \
    "true" "$(echo "$output" | jq '.containerOverrides[0].command | any(. == "CODE A")')"
  assert_eq "adversarial value with a shell metacharacter survives as one distinct element" \
    "true" "$(echo "$output" | jq '.containerOverrides[0].command | any(. == "CODE;rm -rf /")')"
  assert_eq "adversarial value with an embedded double quote survives as one distinct element" \
    "true" "$(echo "$output" | jq '.containerOverrides[0].command | any(. == "CODE\"quote")')"
}

# --- 10. Nonzero ECS task exit propagates ----------------------------------

test_nonzero_task_exit_propagates() {
  local rc
  set +e
  (
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    export FAKE_TASK_EXIT_CODE=42
    parse_and_validate_args --run --subaward-code SYNTHETIC-SUBAWARD-A --image-uri fake:uri
    BUCKET="fake-bucket"
    dispatch
  ) > /dev/null 2>&1
  rc=$?
  set -e
  assert_eq "a nonzero container exit code propagates as this script's own exit code" "42" "$rc"
}

test_zero_task_exit_propagates() {
  local rc
  set +e
  (
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    export FAKE_TASK_EXIT_CODE=0
    parse_and_validate_args --dry-run --subaward-code SYNTHETIC-SUBAWARD-A --image-uri fake:uri
    BUCKET="fake-bucket"
    dispatch
  ) > /dev/null 2>&1
  rc=$?
  set -e
  assert_eq "a zero container exit code propagates as this script's own exit code" "0" "$rc"
}

# --- 11. No AWS mutation occurs before validation completes ---------------

test_no_mutation_before_validation_completes() {
  local scenarios=(
    "--dry-run"                                                     # missing scope entirely
    "--dry-run --subaward-code SYNTHETIC-SUBAWARD-A --all-subawards" # conflicting scope
    "--dry-run --subaward-code SYNTHETIC-SUBAWARD-A"                 # missing image choice
    "--dry-run --run --subaward-code SYNTHETIC-SUBAWARD-A --image-uri fake:uri" # conflicting operation
  )
  local scenario
  for scenario in "${scenarios[@]}"; do
    BUILD_AND_REGISTER_CALLED=0
    RUN_ECS_TASK_CALLED=0
    set +e
    (
      # shellcheck disable=SC2086
      run_parse_only $scenario > /dev/null 2>&1
    )
    set -e
    assert_eq "no mutation before validation ('$scenario'): build_and_register_task_definition never called" \
      "0" "$BUILD_AND_REGISTER_CALLED"
    assert_eq "no mutation before validation ('$scenario'): run_ecs_task never called" \
      "0" "$RUN_ECS_TASK_CALLED"
  done
}

# --- 12. --batch-id command construction ---------------------------------

test_batch_id_command_with_expect_file_count() {
  local output command_json
  output="$(
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    parse_and_validate_args --run --subaward-code SYNTHETIC-SUBAWARD-A \
      --batch-id 218 --expect-file-count 13 --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    build_command_array
    build_overrides_json
    echo "$OVERRIDES_JSON"
  )"
  command_json="$(echo "$output" | jq -c '.containerOverrides[0].command')"
  assert_eq "--batch-id + --expect-file-count: command array" \
    '["python","attachment_orchestrator.py","--bucket","fake-bucket","--modules","subaward","--ecs","--subaward-code","SYNTHETIC-SUBAWARD-A","--batch-id","218","--expect-file-count","13"]' \
    "$command_json"
}

test_batch_id_command_without_expect_file_count() {
  local output command_json
  output="$(
    export AWS_PROFILE=fake-profile POSTGRES_SECRET_ID=arn:pg ORACLE_SECRET_ID=arn:ora
    parse_and_validate_args --run --subaward-code SYNTHETIC-SUBAWARD-A \
      --batch-id 218 --image-uri fake:uri
    BUCKET="fake-bucket"
    CONTAINER_NAME="loader"
    build_command_array
    build_overrides_json
    echo "$OVERRIDES_JSON"
  )"
  command_json="$(echo "$output" | jq -c '.containerOverrides[0].command')"
  assert_eq "--batch-id alone (no --expect-file-count): command array has no --expect-file-count token" \
    '["python","attachment_orchestrator.py","--bucket","fake-bucket","--modules","subaward","--ecs","--subaward-code","SYNTHETIC-SUBAWARD-A","--batch-id","218"]' \
    "$command_json"
}

# --- 13. --batch-id validation: fails closed before any AWS call ---------

test_batch_id_requires_run_not_dry_run() {
  local output rc
  set +e
  output="$(run_parse_only --dry-run --subaward-code SYNTHETIC-SUBAWARD-A --batch-id 218 --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "--batch-id combined with --dry-run is rejected" "$rc"
  assert_contains "batch-id/dry-run rejection error message" "$output" "requires --run"
}

test_batch_id_requires_at_least_one_subaward_code() {
  local output rc
  set +e
  output="$(run_parse_only --run --batch-id 218 --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "--batch-id without any --subaward-code is rejected" "$rc"
  assert_contains "batch-id/unscoped rejection error message" "$output" "--subaward-code"
}

test_batch_id_rejects_all_subawards() {
  local output rc
  set +e
  output="$(run_parse_only --run --all-subawards --batch-id 218 --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "--batch-id combined with --all-subawards is rejected" "$rc"
  assert_contains "batch-id/all-subawards rejection error message" "$output" "--all-subawards"
}

test_batch_id_must_be_a_positive_integer() {
  local output rc
  set +e
  output="$(run_parse_only --run --subaward-code SYNTHETIC-SUBAWARD-A --batch-id "218; rm -rf /" --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "a non-numeric --batch-id is rejected" "$rc"
  assert_contains "non-numeric batch-id rejection error message" "$output" "positive integer"
}

test_expect_file_count_must_be_a_non_negative_integer() {
  local output rc
  set +e
  output="$(run_parse_only --run --subaward-code SYNTHETIC-SUBAWARD-A --batch-id 218 --expect-file-count nope --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "a non-numeric --expect-file-count is rejected" "$rc"
  assert_contains "non-numeric expect-file-count rejection error message" "$output" "non-negative integer"
}

test_expect_file_count_requires_batch_id() {
  local output rc
  set +e
  output="$(run_parse_only --run --subaward-code SYNTHETIC-SUBAWARD-A --expect-file-count 13 --image-uri fake:uri)"; rc=$?
  set -e
  assert_not_zero "--expect-file-count without --batch-id is rejected" "$rc"
  assert_contains "expect-file-count-without-batch-id rejection error message" "$output" "only valid together with --batch-id"
}

test_no_mutation_before_validation_completes_for_batch_id() {
  local scenarios=(
    "--dry-run --subaward-code SYNTHETIC-SUBAWARD-A --batch-id 218 --image-uri fake:uri" # dry-run + batch-id
    "--run --batch-id 218 --image-uri fake:uri"                                          # unscoped batch-id
    "--run --all-subawards --batch-id 218 --image-uri fake:uri"                          # all-subawards + batch-id
    "--run --subaward-code SYNTHETIC-SUBAWARD-A --batch-id notanumber --image-uri fake:uri" # bad batch-id
  )
  local scenario
  for scenario in "${scenarios[@]}"; do
    BUILD_AND_REGISTER_CALLED=0
    RUN_ECS_TASK_CALLED=0
    set +e
    (
      # shellcheck disable=SC2086
      run_parse_only $scenario > /dev/null 2>&1
    )
    set -e
    assert_eq "no mutation before validation ('$scenario'): build_and_register_task_definition never called" \
      "0" "$BUILD_AND_REGISTER_CALLED"
    assert_eq "no mutation before validation ('$scenario'): run_ecs_task never called" \
      "0" "$RUN_ECS_TASK_CALLED"
  done
}

# --- Run everything -----------------------------------------------------

test_dry_run_command_for_one_code
test_run_command_for_multiple_codes
test_repeated_values_preserved
test_missing_scope_rejected
test_all_subawards_requires_explicit_flag
test_conflicting_scope_flags_rejected
test_account_mismatch_rejected
test_missing_image_rejected
test_command_json_cannot_lose_or_merge_arguments
test_nonzero_task_exit_propagates
test_zero_task_exit_propagates
test_no_mutation_before_validation_completes
test_batch_id_command_with_expect_file_count
test_batch_id_command_without_expect_file_count
test_batch_id_requires_run_not_dry_run
test_batch_id_requires_at_least_one_subaward_code
test_batch_id_rejects_all_subawards
test_batch_id_must_be_a_positive_integer
test_expect_file_count_must_be_a_non_negative_integer
test_expect_file_count_requires_batch_id
test_no_mutation_before_validation_completes_for_batch_id

echo ""
if [[ "$FAILURES" -eq 0 ]]; then
  echo "All tests passed."
  exit 0
else
  echo "$FAILURES test(s) failed."
  exit 1
fi
