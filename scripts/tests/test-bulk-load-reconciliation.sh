#!/usr/bin/env bash
# shellcheck disable=SC2034,SC2329
#
# SC2034 (appears unused) / SC2329 (never invoked) are false positives
# throughout this file: static analysis can't follow the dynamic
# `source "$SCRIPTS_DIR/run-award-attachment-loader.sh"` call below, so
# it doesn't see that these globals (IMAGE_URI, NEW_REVISION_ARN,
# UPLOAD, BUCKET, etc.) and function overrides (run_ecs_task,
# build_and_register_task_definition) are read/called by that sourced
# script, not by this file directly.
set -euo pipefail

# Regression test for a real incident: the --bulk-load runner resumed
# after a SAML-expiry interruption. Batch 18 had actually completed its
# upload in AWS, but the local state file still said
# upload_status=NOT_REQUESTED (the crash happened before that field was
# ever updated - see state_append_batch's comment in
# scripts/run-award-attachment-loader.sh for why NOT_REQUESTED, a value
# meant only for runs that never upload at all, could be mistaken for
# "nothing to do here"). On restart, the runner created batch 19 without
# reconciling batch 18 first, and never credited batch 18's 5,000 files
# to processed_files (30000 instead of the correct 35000).
#
# This test sources the real script (which only defines functions and
# creates TMP_DIR when sourced - see the executed-directly guard at its
# own end) and fakes every ECS/Docker call, so it runs in well under a
# second with no AWS/Docker access required. It reproduces the exact
# reported state (batch 18 stuck, batch 19 already created) and asserts
# reconcile_incomplete_batches corrects it - crediting batch 18's files
# exactly once and letting batch 19 complete normally - rather than
# recreating a batch or double/under-counting.
#
# Usage: scripts/tests/test-bulk-load-reconciliation.sh

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

TMP_TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_TEST_DIR"' EXIT

# shellcheck source=/dev/null
source "$SCRIPTS_DIR/run-award-attachment-loader.sh"

# --- Fake ECS layer, defined AFTER sourcing the real script so these
# definitions win (function definitions later in the process replace
# earlier ones with the same name - sourcing after would silently
# restore the real run_ecs_task/build_and_register_task_definition,
# undoing the override). Real per-batch state transitions (mirroring
# etl_batch.status: CREATED -> READY (after --load-batch) -> PROCESSING
# -> COMPLETED (after --upload)) so a --show-batch call reflects
# whatever has actually "happened" to that batch_id so far in this test
# run - not a static canned response. Batch 18 starts pre-seeded as
# already COMPLETED in AWS (the exact live state the real incident's
# batch 18 was actually in, despite what the local, pre-fix state file
# said) - batch 19 starts CREATED (freshly created, never yet touched),
# same as a real fresh batch.
echo "COMPLETED" > "$TMP_TEST_DIR/live-status-18"

live_status_file() {
  echo "$TMP_TEST_DIR/live-status-$1"
}

run_ecs_task() {
  local verb="${OVERRIDE_ARGS[0]}"
  TASK_LOG_FILE="$TMP_TEST_DIR/fake-task-$RANDOM.log"

  case "$verb" in
    --show-batch)
      local batch_id="${OVERRIDE_ARGS[1]}" status_file live_status
      status_file="$(live_status_file "$batch_id")"
      live_status="CREATED"
      [[ -f "$status_file" ]] && live_status="$(cat "$status_file")"
      local pending=5000 uploaded=0 failed=0
      if [[ "$live_status" == "READY" || "$live_status" == "COMPLETED" ]]; then
        pending=0
      fi
      if [[ "$live_status" == "COMPLETED" ]]; then
        uploaded=5000
      fi
      echo "{\"message\": \"batch_id=$batch_id status=$live_status total_files=5000 metadata_loaded=5000 pending=$pending uploading=0 uploaded=$uploaded failed=$failed missing_source_content=0 missing_metadata=0\"}" \
        > "$TASK_LOG_FILE"
      TASK_EXIT_CODE=0
      ;;
    --load-batch)
      local batch_id="${OVERRIDE_ARGS[1]}"
      echo "READY" > "$(live_status_file "$batch_id")"
      echo "{\"message\": \"Bounded metadata load for batch_id=$batch_id\"}" > "$TASK_LOG_FILE"
      TASK_EXIT_CODE=0
      ;;
    --upload)
      local batch_id="${OVERRIDE_ARGS[2]}"
      echo "COMPLETED" > "$(live_status_file "$batch_id")"
      echo "{\"message\": \"Upload complete for batch_id=$batch_id\"}" > "$TASK_LOG_FILE"
      TASK_EXIT_CODE=0
      ;;
    --create-batch)
      echo "ERROR: test expected no new batch to be created before batch 18/19 were reconciled/completed" >&2
      TASK_EXIT_CODE=1
      ;;
    *)
      echo "unexpected verb in fake run_ecs_task: $verb" >&2
      TASK_EXIT_CODE=1
      ;;
  esac
}

build_and_register_task_definition() {
  IMAGE_URI="fake-image:test"
  NEW_REVISION_ARN="arn:aws:ecs:fake:task-definition/fake:1"
}

STATE_FILE="$TMP_TEST_DIR/state.json"
UPLOAD=true
IMAGE_URI_OVERRIDE=""
BUCKET=""
PREFIX=""
RETRY_FAILED=false
DRY_RUN=false
COMMON_OVERRIDE_ARGS=(--postgres-secret-id fake)

# Reproduce the exact corrupted state left behind by the incident:
# batch 18 completed in AWS but locally still shows upload_status
# NOT_REQUESTED, and batch 19 was already (incorrectly) created without
# reconciling batch 18 first. 30000 = 6 earlier, correctly-completed
# 5000-file batches (not individually modeled here - only the two
# batches relevant to this regression matter).
jq -n '{
  total_target: 40000,
  batch_size: 5000,
  upload: true,
  image_uri: "fake-image:test",
  task_definition_arn: "arn:aws:ecs:fake:task-definition/fake:1",
  processed_files: 30000,
  status: "IN_PROGRESS",
  batches: [
    {batch_id: 18, requested_size: 5000, selected_count: 5000, load_status: "COMPLETED", upload_status: "NOT_REQUESTED"},
    {batch_id: 19, requested_size: 5000, selected_count: 5000, load_status: "PENDING", upload_status: "PENDING"}
  ]
}' > "$STATE_FILE"

echo "=== Running run_bulk_load against the reproduced incident state ==="
run_bulk_load 40000 5000

echo ""
echo "=== Assertions ==="
assert_eq "batch 18's upload_status is corrected to COMPLETED" \
  "COMPLETED" "$(jq -r '.batches[] | select(.batch_id == 18) | .upload_status' "$STATE_FILE")"
assert_eq "batch 19's load_status is COMPLETED" \
  "COMPLETED" "$(jq -r '.batches[] | select(.batch_id == 19) | .load_status' "$STATE_FILE")"
assert_eq "batch 19's upload_status is COMPLETED" \
  "COMPLETED" "$(jq -r '.batches[] | select(.batch_id == 19) | .upload_status' "$STATE_FILE")"
assert_eq "processed_files reaches exactly 40000 (30000 + batch 18's 5000 + batch 19's 5000, no double/under-count)" \
  "40000" "$(jq -r '.processed_files' "$STATE_FILE")"
assert_eq "no batch 20 was created (total_target reached exactly at batch 19)" \
  "2" "$(jq -r '.batches | length' "$STATE_FILE")"
assert_eq "final run status is COMPLETED" \
  "COMPLETED" "$(jq -r '.status' "$STATE_FILE")"

echo ""
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL ASSERTIONS PASSED"
  exit 0
else
  echo "$FAILURES ASSERTION(S) FAILED" >&2
  exit 1
fi
