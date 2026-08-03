#!/usr/bin/env bash
set -euo pipefail

# Build/push the Award Attachment loader image, register a new task-
# definition revision from it, run the loader as a one-off Fargate task
# on the existing research-archive-platform-dev-loader ECS task family
# (see terraform/modules/ecs/main.tf - this script never modifies
# Terraform or Terraform-managed state), wait for it to finish, stream
# its CloudWatch logs, and exit with the task container's own exit code.
#
# This does NOT run automatically - it must be invoked explicitly, and
# every AWS call it makes is a real one (image push, task-definition
# registration, ECS task launch). See
# docs/AWARD_ATTACHMENT_ECS_EXECUTION.md before running it against a
# real environment.
#
# Required environment (no safe defaults - this script refuses to guess
# them):
#   ECR_REPOSITORY_URI   - ECR repository URI for the loader image. Not
#                            required if --image-uri is given (see below).
#   SUBNET_IDS            - comma-separated private subnet IDs for the
#                            Fargate task (same VPC as the loader task's
#                            security group)
#   SECURITY_GROUP_ID      - the loader task's security group ID
#   POSTGRES_SECRET_ID      - Secrets Manager ARN/name for the PostgreSQL
#                              secret (an identifier, not a credential -
#                              see docs/AWARD_ATTACHMENT_ECS_EXECUTION.md).
#                              Not required for --migrate-only's own
#                              validation, but the loader needs it for
#                              every --ecs invocation including that one.
#
# Required for every --ecs invocation EXCEPT --migrate-only and
# --show-upload-status (neither touches Oracle):
#   ORACLE_SECRET_ID   - Secrets Manager ARN/name for the Oracle secret
#
# Optional environment (sensible defaults matching the current
# Terraform naming convention, or simply omitted if not set - the loader
# falls back to its own POSTGRES_HOST/PORT/DB env vars or
# AWARD_ATTACHMENT_BUCKET_NAME defaults when these aren't passed through):
#   AWS_REGION         (default: us-east-1)
#   PROJECT_NAME        (default: research-archive-platform)
#   ENVIRONMENT         (default: dev)
#   CLUSTER_NAME        (default: ${PROJECT_NAME}-${ENVIRONMENT}-etl)
#   TASK_FAMILY         (default: ${PROJECT_NAME}-${ENVIRONMENT}-loader)
#   POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB  - only needed as a fallback
#     for whichever of host/port/dbname the PostgreSQL secret doesn't
#     include itself
#   AWARD_ATTACHMENT_BUCKET_NAME   - documents bucket name, passed
#                          through as a plain (non-secret) container
#                          environment override. Deliberately NOT
#                          DATA_BUCKET_NAME - that is a different,
#                          IRB-only bucket (see
#                          docs/AWARD_ATTACHMENT_ECS_EXECUTION.md).
#
# None of POSTGRES_USER, POSTGRES_PASSWORD, ORACLE_USER, ORACLE_PASSWORD,
# or ORACLE_DSN are ever read or passed through by this script - in --ecs
# mode those always come from Secrets Manager, resolved by the loader
# process itself at runtime, never from an environment override.
#
# Usage:
#   scripts/run-award-attachment-loader.sh [--dry-run] [--upload] \
#       [--migrate-only] [--show-upload-status] [--load-file-id N] \
#       [--limit N] [--file-id N] [--retry-failed] [--bucket NAME] \
#       [--prefix PREFIX] [--image-uri URI] \
#       [--create-batch N] [--include-already-uploaded] \
#       [--load-batch BATCH_ID] [--show-batch BATCH_ID] \
#       [--batch-id BATCH_ID] \
#       [--bulk-load TOTAL_FILES] [--bulk-batch-size N] [--state-file PATH]
#
# --image-uri <full-ecr-image-uri>: reuse an already-built-and-pushed
#   image instead of building/pushing a new one. When given, this script
#   never invokes `docker build`, `docker login`, `aws ecr
#   get-login-password`, or `docker push` - it registers a new task-
#   definition revision directly from the supplied image URI. Useful for
#   re-running against an image that was already validated, without
#   rebuilding it (and without needing a local Docker daemon at all).
#
# --show-upload-status (requires --file-id): read-only PostgreSQL
#   diagnostic - reports archive.attachment_object's upload-related
#   columns for exactly that file_id (or logs clearly that no metadata
#   has been loaded for it), then exits 0. Never reads a BLOB, never
#   writes to PostgreSQL, never uploads to S3. Lets you inspect upload
#   state from inside the ECS task without connecting to private RDS
#   directly.
#
# --load-file-id N: bounded, idempotent metadata load for exactly one
#   physical FILE_ID (and its reference rows only) - fixes "Oracle has
#   this file, but archive.attachment_object doesn't, so --upload
#   --file-id selects zero candidates." UPSERTs (never truncates or
#   replaces the full tables), preserves an existing row's upload state,
#   never reads a BLOB, never uploads to S3. Requires ORACLE_SECRET_ID
#   (unlike --migrate-only/--show-upload-status, this reads Oracle).
#   Combine with --dry-run to see inserted/updated/unchanged/missing
#   counts without persisting anything.
#
# --load-file-ids FILE_ID,FILE_ID,...: the plural form of --load-file-id -
#   bounded, idempotent metadata load for exactly the given comma-separated
#   set of physical FILE_IDs (and their reference rows only), all in one
#   transaction. For backfilling a known, specific set of file_ids in one
#   pass - e.g. the exact set a --diff-award-attachments run proved were
#   never loaded - instead of one task invocation per file_id. Same
#   guarantees and ORACLE_SECRET_ID requirement as --load-file-id.
#
# --create-batch N (optionally with --include-already-uploaded): select
#   exactly N distinct physical file_ids from Oracle, in stable ascending
#   file_id order, and persist that exact membership as a new batch
#   (archive.etl_batch/etl_batch_item - the generic ETL batch framework,
#   see docs/ETL_BATCH_FRAMEWORK.md). Requires ORACLE_SECRET_ID. Never
#   reads a BLOB, never touches S3.
#
# --load-batch BATCH_ID: idempotent metadata load for exactly this
#   batch's recorded membership - the batch equivalent of --load-file-id.
#   Requires ORACLE_SECRET_ID. Never reads a BLOB, never touches S3.
#
# --show-batch BATCH_ID: read-only status report for one batch (requested
#   size, status, and file counts by metadata-load/upload state). Unlike
#   every other --ecs mode except --migrate-only/--show-upload-status,
#   this does NOT require ORACLE_SECRET_ID - it's PostgreSQL-only.
#
# --list-awards-with-attachments: developer aid, not a production
#   feature - read-only report of every Award version (award_number,
#   award_id, title) that has at least one archive.award_attachment
#   row, with its attachment count, sorted highest-count first. Same as
#   --show-batch, this is PostgreSQL-only and does NOT require
#   ORACLE_SECRET_ID. Runs on the existing loader task definition, so no
#   dedicated bastion host is needed just to find a real award_id worth
#   opening in the UI's Attachments tab.
#
# --diff-award-attachments AWARD_ID: investigation aid, not a production
#   feature - read-only side-by-side comparison of Oracle's
#   KCOEUS.AWARD_ATTACHMENT rows for exactly this award_id against
#   archive.award_attachment, explaining per-row why any Oracle-only row
#   hasn't been archived yet (never targeted by a batch, targeted but
#   not completed, or a genuine upsert gap). Unlike --list-awards-with-
#   attachments/--show-batch, this DOES require ORACLE_SECRET_ID (it
#   reads Oracle, via a targeted bind-variable filter - not a
#   full-table scan). Never writes anything.
#
# --batch-id BATCH_ID (only valid with --upload): restrict the upload run
#   to exactly this batch's membership, instead of every PENDING/
#   UPLOADING (+FAILED with --retry-failed) row. Mutually exclusive with
#   --file-id and with --create-batch/--load-batch/--show-batch.
#
# --bulk-load TOTAL_FILES: long-running bulk backfill of up to
#   TOTAL_FILES physical files, orchestrated as repeated
#   --create-batch/--load-batch(/--upload --batch-id, with --upload)
#   cycles of --bulk-batch-size files each (default 5000), reusing the
#   SAME already-built image and already-registered task-definition
#   revision for every batch - the image is built/pushed and the task
#   definition registered exactly ONCE per invocation (or reused
#   entirely via --image-uri / a resumed --state-file), never once per
#   batch. Each batch is created, loaded, and verified (--show-batch)
#   before moving to the next; --create-batch's own default behavior
#   (exclude already-UPLOADED file_ids) means a bulk run never reloads
#   already-uploaded files. Progress is persisted to --state-file after
#   every batch, so re-running the exact same --bulk-load/--state-file
#   after an interruption or the first failure resumes the incomplete
#   batch in place - it does not restart the whole run or create a
#   duplicate batch. Stops immediately (exit 1) on the first batch that
#   fails, after saving state; stops successfully early if Oracle's
#   candidate pool (file_ids not yet already-uploaded) is exhausted
#   before reaching TOTAL_FILES. Requires ORACLE_SECRET_ID (like
#   --create-batch/--load-batch, it reads Oracle) and cannot be combined
#   with any other verb (--upload/--bucket/--prefix/--retry-failed/
#   --dry-run remain valid alongside it, applying to every batch's load/
#   upload step). Prints a final summary (batches run, files processed,
#   elapsed time) on completion.
#
# --bulk-batch-size N (with --bulk-load, default 5000): file count per
#   batch.
#
# --state-file PATH (with --bulk-load, default
#   /tmp/${PROJECT_NAME}-${ENVIRONMENT}-bulk-load-state.json): where
#   progress is persisted/read from for resume. A state file only
#   resumes a run for the exact same --bulk-load TOTAL_FILES it was
#   created with - pass a different --state-file (or remove the old
#   one) to start an unrelated bulk run without accidentally continuing
#   a prior one.
#
# Examples:
#   # Bootstrap a fresh database (apply migrations, validate schema, exit):
#   POSTGRES_SECRET_ID=arn:...:postgres \
#     scripts/run-award-attachment-loader.sh --migrate-only
#
#   # One-file validation, read-only, no PostgreSQL/S3 writes:
#   scripts/run-award-attachment-loader.sh --file-id 9001 --dry-run
#
#   # Inspect PostgreSQL upload state for one file, read-only:
#   POSTGRES_SECRET_ID=arn:...:postgres \
#     scripts/run-award-attachment-loader.sh --show-upload-status --file-id 9001
#
#   # Load metadata for exactly one physical file, so a subsequent
#   # --upload --file-id has something to find:
#   POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#     scripts/run-award-attachment-loader.sh --load-file-id 1
#
#   # Batch upload of up to 100 pending/uploading files:
#   scripts/run-award-attachment-loader.sh --upload --limit 100
#
#   # Recovery after an interrupted run - retry FAILED rows too:
#   scripts/run-award-attachment-loader.sh --upload --retry-failed
#
#   # Reuse an already-pushed image instead of building a new one:
#   scripts/run-award-attachment-loader.sh --migrate-only \
#       --image-uri 770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader:20260731T005343Z-b0d475d
#
#   # Deterministic 10-file batch workflow - see
#   # docs/AWARD_ATTACHMENT_ECS_EXECUTION.md for the full sequence:
#   scripts/run-award-attachment-loader.sh --create-batch 10
#   scripts/run-award-attachment-loader.sh --show-batch 1
#   scripts/run-award-attachment-loader.sh --load-batch 1
#   scripts/run-award-attachment-loader.sh --show-batch 1
#   scripts/run-award-attachment-loader.sh --upload --batch-id 1 --bucket my-bucket
#   scripts/run-award-attachment-loader.sh --show-batch 1
#
#   # Find a real award_id to open in the UI's Attachments tab, without a
#   # bastion host (PostgreSQL-only, no ORACLE_SECRET_ID needed):
#   POSTGRES_SECRET_ID=arn:...:postgres \
#     scripts/run-award-attachment-loader.sh --list-awards-with-attachments --limit 25
#
#   # Explain why one Award has fewer archived attachments than Oracle
#   # shows (reads Oracle, so ORACLE_SECRET_ID is required here):
#   POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#     scripts/run-award-attachment-loader.sh --diff-award-attachments 1833767
#
#   # Backfill a known, specific set of file_ids in one pass (e.g. the
#   # exact file_ids --diff-award-attachments proved were never loaded):
#   POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#     scripts/run-award-attachment-loader.sh --load-file-ids 5993,5994,5995
#
#   # Bulk-backfill up to 200,000 files, 5,000 per batch, metadata-load
#   # only (add --upload to also upload each batch to S3):
#   POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#   ECR_REPOSITORY_URI=770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader \
#     scripts/run-award-attachment-loader.sh --bulk-load 200000
#
#   # Re-running the exact same command (same --state-file) after an
#   # interruption or failure resumes in place instead of restarting:
#   POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#   ECR_REPOSITORY_URI=770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader \
#     scripts/run-award-attachment-loader.sh --bulk-load 200000 --state-file /tmp/my-bulk-run.json

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Parses argv and validates it into the global variables every other
# function reads, then creates TMP_DIR. Split out (rather than left as
# top-level script code) so this file can be `source`d - e.g. by a test
# script that overrides run_ecs_task/build_and_register_task_definition
# and calls run_bulk_load/reconcile_incomplete_batches directly - without
# argv parsing or any of these env-var requirements firing. See the
# bottom of this file for the guard that calls this (and dispatch) only
# when the script is executed directly, never when sourced.
parse_and_validate_args() {
AWS_REGION="${AWS_REGION:-us-east-1}"
PROJECT_NAME="${PROJECT_NAME:-research-archive-platform}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
CLUSTER_NAME="${CLUSTER_NAME:-${PROJECT_NAME}-${ENVIRONMENT}-etl}"
TASK_FAMILY="${TASK_FAMILY:-${PROJECT_NAME}-${ENVIRONMENT}-loader}"
LOG_GROUP="${LOG_GROUP:-/ecs/${PROJECT_NAME}-${ENVIRONMENT}-loader}"
CONTAINER_NAME="loader"

: "${SUBNET_IDS:?SUBNET_IDS is not set - comma-separated private subnet IDs for the Fargate task}"
: "${SECURITY_GROUP_ID:?SECURITY_GROUP_ID is not set - the loader task\'s security group ID}"
: "${POSTGRES_SECRET_ID:?POSTGRES_SECRET_ID is not set - Secrets Manager ARN/name for the PostgreSQL secret (an identifier, never a credential)}"

FILE_ID=""
LOAD_FILE_ID=""
LOAD_FILE_IDS=""
LIMIT=""
RETRY_FAILED=false
DRY_RUN=false
UPLOAD=false
MIGRATE_ONLY=false
SHOW_UPLOAD_STATUS=false
BUCKET=""
PREFIX=""
IMAGE_URI_OVERRIDE=""
CREATE_BATCH=""
INCLUDE_ALREADY_UPLOADED=false
LOAD_BATCH=""
SHOW_BATCH=""
BATCH_ID=""
LIST_AWARDS_WITH_ATTACHMENTS=false
DIFF_AWARD_ATTACHMENTS=""
BULK_LOAD=""
BULK_BATCH_SIZE=5000
STATE_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file-id) FILE_ID="$2"; shift 2 ;;
    --load-file-id) LOAD_FILE_ID="$2"; shift 2 ;;
    --load-file-ids) LOAD_FILE_IDS="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --retry-failed) RETRY_FAILED=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --upload) UPLOAD=true; shift ;;
    --migrate-only) MIGRATE_ONLY=true; shift ;;
    --show-upload-status) SHOW_UPLOAD_STATUS=true; shift ;;
    --bucket) BUCKET="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --image-uri) IMAGE_URI_OVERRIDE="$2"; shift 2 ;;
    --create-batch) CREATE_BATCH="$2"; shift 2 ;;
    --include-already-uploaded) INCLUDE_ALREADY_UPLOADED=true; shift ;;
    --load-batch) LOAD_BATCH="$2"; shift 2 ;;
    --show-batch) SHOW_BATCH="$2"; shift 2 ;;
    --batch-id) BATCH_ID="$2"; shift 2 ;;
    --list-awards-with-attachments) LIST_AWARDS_WITH_ATTACHMENTS=true; shift ;;
    --diff-award-attachments) DIFF_AWARD_ATTACHMENTS="$2"; shift 2 ;;
    --bulk-load) BULK_LOAD="$2"; shift 2 ;;
    --bulk-batch-size) BULK_BATCH_SIZE="$2"; shift 2 ;;
    --state-file) STATE_FILE="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ "$SHOW_UPLOAD_STATUS" == true && -z "$FILE_ID" ]]; then
  echo "ERROR: --show-upload-status requires --file-id" >&2
  exit 1
fi

# Batch-verb validation, mirroring load_award_attachments.py's own
# parse_args - fail fast here rather than only inside the ECS task, after
# an image build/push and a task-definition registration have already run.
ACTIVE_BATCH_VERBS=()
[[ -n "$CREATE_BATCH" ]] && ACTIVE_BATCH_VERBS+=(--create-batch)
[[ -n "$LOAD_BATCH" ]] && ACTIVE_BATCH_VERBS+=(--load-batch)
[[ -n "$SHOW_BATCH" ]] && ACTIVE_BATCH_VERBS+=(--show-batch)

if [[ "${#ACTIVE_BATCH_VERBS[@]}" -gt 1 ]]; then
  echo "ERROR: ${ACTIVE_BATCH_VERBS[*]} cannot be combined - choose one batch operation at a time" >&2
  exit 1
fi

if [[ -n "$CREATE_BATCH" ]] && ! [[ "$CREATE_BATCH" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: --create-batch must be a positive integer, got '$CREATE_BATCH'" >&2
  exit 1
fi

if [[ "$INCLUDE_ALREADY_UPLOADED" == true && -z "$CREATE_BATCH" ]]; then
  echo "ERROR: --include-already-uploaded requires --create-batch" >&2
  exit 1
fi

if [[ "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
  if [[ "$UPLOAD" == true ]]; then
    echo "ERROR: ${ACTIVE_BATCH_VERBS[0]} cannot be combined with --upload - use --upload --batch-id BATCH_ID to upload a batch" >&2
    exit 1
  fi
  if [[ -n "$LOAD_FILE_ID" ]]; then
    echo "ERROR: ${ACTIVE_BATCH_VERBS[0]} cannot be combined with --load-file-id" >&2
    exit 1
  fi
  if [[ -n "$FILE_ID" ]]; then
    echo "ERROR: ${ACTIVE_BATCH_VERBS[0]} cannot be combined with --file-id" >&2
    exit 1
  fi
fi

if [[ -n "$BATCH_ID" ]]; then
  if [[ "$UPLOAD" == false ]]; then
    echo "ERROR: --batch-id is only valid together with --upload" >&2
    exit 1
  fi
  if [[ "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
    echo "ERROR: --batch-id cannot be combined with ${ACTIVE_BATCH_VERBS[0]}" >&2
    exit 1
  fi
  if [[ -n "$LOAD_FILE_ID" ]]; then
    echo "ERROR: --batch-id cannot be combined with --load-file-id" >&2
    exit 1
  fi
fi

if [[ -n "$FILE_ID" && -n "$BATCH_ID" ]]; then
  echo "ERROR: --file-id and --batch-id cannot be combined" >&2
  exit 1
fi

if [[ -n "$LOAD_FILE_IDS" ]]; then
  if [[ -n "$LOAD_FILE_ID" ]]; then
    echo "ERROR: --load-file-id and --load-file-ids cannot be combined" >&2
    exit 1
  fi
  if [[ -n "$FILE_ID" ]]; then
    echo "ERROR: --file-id cannot be combined with --load-file-ids" >&2
    exit 1
  fi
  if [[ -n "$BATCH_ID" ]]; then
    echo "ERROR: --batch-id cannot be combined with --load-file-ids" >&2
    exit 1
  fi
  if [[ "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
    echo "ERROR: ${ACTIVE_BATCH_VERBS[0]} cannot be combined with --load-file-ids" >&2
    exit 1
  fi
fi

if [[ -n "$BULK_LOAD" ]]; then
  if ! [[ "$BULK_LOAD" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: --bulk-load must be a positive integer, got '$BULK_LOAD'" >&2
    exit 1
  fi
  if ! [[ "$BULK_BATCH_SIZE" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: --bulk-batch-size must be a positive integer, got '$BULK_BATCH_SIZE'" >&2
    exit 1
  fi
  if [[ "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
    echo "ERROR: --bulk-load cannot be combined with ${ACTIVE_BATCH_VERBS[0]} - --bulk-load owns the whole create-batch/load-batch cycle itself" >&2
    exit 1
  fi
  for flag_name in "--file-id:$FILE_ID" "--load-file-id:$LOAD_FILE_ID" "--load-file-ids:$LOAD_FILE_IDS" "--batch-id:$BATCH_ID" "--diff-award-attachments:$DIFF_AWARD_ATTACHMENTS"; do
    if [[ -n "${flag_name#*:}" ]]; then
      echo "ERROR: --bulk-load cannot be combined with ${flag_name%%:*}" >&2
      exit 1
    fi
  done
  if [[ "$MIGRATE_ONLY" == true || "$SHOW_UPLOAD_STATUS" == true || "$LIST_AWARDS_WITH_ATTACHMENTS" == true ]]; then
    echo "ERROR: --bulk-load cannot be combined with --migrate-only/--show-upload-status/--list-awards-with-attachments" >&2
    exit 1
  fi
  if [[ -z "$STATE_FILE" ]]; then
    STATE_FILE="/tmp/${PROJECT_NAME}-${ENVIRONMENT}-bulk-load-state.json"
  fi
fi

# --show-batch and --list-awards-with-attachments are both PostgreSQL-only
# (like --migrate-only/--show-upload-status), so they're exempt from the
# Oracle secret requirement below; --create-batch, --load-batch, and
# --bulk-load all read Oracle and so are NOT exempt.
if [[ "$MIGRATE_ONLY" == false && "$SHOW_UPLOAD_STATUS" == false && -z "$SHOW_BATCH" && "$LIST_AWARDS_WITH_ATTACHMENTS" == false ]]; then
  : "${ORACLE_SECRET_ID:?ORACLE_SECRET_ID is not set - Secrets Manager ARN/name for the Oracle secret (required for every --ecs invocation except --migrate-only/--show-upload-status/--show-batch/--list-awards-with-attachments)}"
fi

if [[ -z "$IMAGE_URI_OVERRIDE" ]]; then
  : "${ECR_REPOSITORY_URI:?ECR_REPOSITORY_URI is not set - set it to the loader image\'s ECR repository URI, or pass --image-uri to reuse an already-pushed image}"
fi

if [[ "$UPLOAD" == true && "$DRY_RUN" == false ]]; then
  echo "=== WARNING: this will perform a REAL S3 upload run ==="
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
}

# --- Reused by both the single-shot path and --bulk-load: run exactly one
# ECS task with the current $NEW_REVISION_ARN, using whatever the caller
# has put in the OVERRIDE_ARGS array. Never builds an image or registers a
# task definition itself - the caller does that exactly once, up front.
# Sets TASK_EXIT_CODE and TASK_LOG_FILE (the captured CloudWatch log text,
# for callers that need to parse a value like a batch_id out of it) rather
# than exiting directly, so bulk-load can decide how to react to a
# failure (persist state, stop) instead of the process just dying.
run_ecs_task() {
  local overrides_json task_run_output task_arn task_id log_stream
  local task_describe_file exit_code

  overrides_json="$(
    cd "$ROOT_DIR/etl" \
      && uv run python scripts/build_award_attachment_ecs_overrides.py "${OVERRIDE_ARGS[@]}"
  )"
  echo "Overrides: $overrides_json"

  echo "=== Launching ECS task ==="
  task_run_output="$(aws ecs run-task \
    --cluster "$CLUSTER_NAME" \
    --task-definition "$NEW_REVISION_ARN" \
    --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_IDS],securityGroups=[$SECURITY_GROUP_ID],assignPublicIp=DISABLED}" \
    --overrides "$overrides_json" \
    --region "$AWS_REGION")"

  task_arn="$(echo "$task_run_output" | python3 -c 'import json, sys; print(json.load(sys.stdin)["tasks"][0]["taskArn"])')"
  echo "Task started: $task_arn"

  echo "=== Waiting for task completion (this can take a few minutes) ==="
  aws ecs wait tasks-stopped \
    --cluster "$CLUSTER_NAME" \
    --tasks "$task_arn" \
    --region "$AWS_REGION"

  task_id="${task_arn##*/}"
  log_stream="loader/${CONTAINER_NAME}/${task_id}"
  TASK_LOG_FILE="$TMP_DIR/task-${task_id}.log"

  echo "=== CloudWatch logs (${LOG_GROUP} / ${log_stream}) ==="
  aws logs tail "$LOG_GROUP" \
    --log-stream-names "$log_stream" \
    --region "$AWS_REGION" \
    --since 1h 2>&1 | tee "$TASK_LOG_FILE" \
    || echo "WARNING: could not tail logs - check the CloudWatch console directly."

  echo "=== Checking task exit code ==="
  task_describe_file="$TMP_DIR/task-describe-${task_id}.json"
  aws ecs describe-tasks \
    --cluster "$CLUSTER_NAME" \
    --tasks "$task_arn" \
    --region "$AWS_REGION" \
    --output json \
    > "$task_describe_file"

  exit_code="$(jq -r --arg name "$CONTAINER_NAME" \
    '.tasks[0].containers[] | select(.name == $name) | .exitCode // empty' \
    "$task_describe_file")"

  if [[ -z "$exit_code" ]]; then
    # A container that fails before its process ever starts (a bad exec,
    # a missing image, a resource limit) never gets an exitCode at all -
    # ECS instead records why on the task and the container themselves.
    # Report both explicitly rather than just saying it couldn't be
    # determined - this is exactly the shape of failure a broken
    # containerOverrides command produces (see the "executable file not
    # found in $PATH" incident this script was fixed after).
    local stopped_reason container_reason
    stopped_reason="$(jq -r '.tasks[0].stoppedReason // "(none reported)"' "$task_describe_file")"
    container_reason="$(jq -r --arg name "$CONTAINER_NAME" \
      '.tasks[0].containers[] | select(.name == $name) | .reason // "(none reported)"' \
      "$task_describe_file")"
    echo "ERROR: the task container never reported an exit code - it most likely failed during initialization, before load_award_attachments.py ever ran." >&2
    echo "  Task stoppedReason: $stopped_reason" >&2
    echo "  Container reason:   $container_reason" >&2
    TASK_EXIT_CODE=1
    return
  fi

  echo "Task exit code: $exit_code"
  TASK_EXIT_CODE="$exit_code"
}

# --- jq helpers for the --bulk-load state file ------------------------------

state_init() {
  jq -n --argjson total "$1" --argjson batch_size "$2" --argjson upload "$3" \
    '{total_target: $total, batch_size: $batch_size, upload: $upload,
      image_uri: null, task_definition_arn: null,
      processed_files: 0, status: "IN_PROGRESS", batches: []}'
}

state_set_image() {
  jq --arg image_uri "$1" --arg arn "$2" \
    '.image_uri = $image_uri | .task_definition_arn = $arn' \
    "$STATE_FILE" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

state_append_batch() {
  # $1=batch_id $2=requested_size $3=selected_count
  # upload_status starts PENDING (not NOT_REQUESTED) whenever this run
  # has --upload - NOT_REQUESTED is reserved for runs that never upload
  # at all, so it unambiguously means "this batch's upload phase does
  # not apply", never "upload requested but not yet attempted". Getting
  # this wrong is exactly what caused a real incident: a batch whose
  # upload step crashed (SAML expiry) before ever updating upload_status
  # was indistinguishable from a batch that never needed uploading, so
  # resume treated it as nothing-to-do and created a new batch instead of
  # retrying it - see reconcile_incomplete_batches for the defense-in-depth
  # fix on top of this one.
  local initial_upload_status="NOT_REQUESTED"
  [[ "$UPLOAD" == true ]] && initial_upload_status="PENDING"
  jq --argjson batch_id "$1" --argjson requested_size "$2" --argjson selected_count "$3" \
    --arg upload_status "$initial_upload_status" \
    '.batches += [{batch_id: $batch_id, requested_size: $requested_size,
      selected_count: $selected_count, load_status: "PENDING", upload_status: $upload_status}]' \
    "$STATE_FILE" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

state_set_last_batch_field() {
  # $1=field name $2=value (string)
  jq --arg field "$1" --arg value "$2" \
    '.batches[-1][$field] = $value' \
    "$STATE_FILE" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

state_set_batch_field_by_index() {
  # $1=zero-based index into .batches $2=field name $3=value (string)
  jq --argjson index "$1" --arg field "$2" --arg value "$3" \
    '.batches[$index][$field] = $value' \
    "$STATE_FILE" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

state_add_processed() {
  jq --argjson delta "$1" '.processed_files += $delta' \
    "$STATE_FILE" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

state_set_status() {
  jq --arg status "$1" '.status = $status' \
    "$STATE_FILE" > "$STATE_FILE.tmp" && mv "$STATE_FILE.tmp" "$STATE_FILE"
}

# --- Resume reconciliation: before a resumed run decides whether to
# retry the last batch or create a new one, verify every batch the local
# state considers incomplete against live --show-batch, and correct the
# local state to match reality. Needed because a local crash (e.g. a
# SAML/credential expiry mid-call) can leave the state file behind
# reality: an ECS task can genuinely finish in AWS after the local
# script has already died trying to wait for/describe it, so trusting
# the local file alone risks creating a brand new batch instead of
# recognizing the previous one actually succeeded (and double-counting
# or under-counting processed_files as a result). Only ever touches
# batches this run's own local state already has an entry for - it does
# not discover or adopt batches it doesn't know about.
reconcile_incomplete_batches() {
  local total_batches i
  total_batches="$(jq -r '.batches | length' "$STATE_FILE")"

  for (( i=0; i<total_batches; i++ )); do
    local batch_id load_status upload_status selected_count
    batch_id="$(jq -r ".batches[$i].batch_id" "$STATE_FILE")"
    load_status="$(jq -r ".batches[$i].load_status" "$STATE_FILE")"
    upload_status="$(jq -r ".batches[$i].upload_status" "$STATE_FILE")"
    selected_count="$(jq -r ".batches[$i].selected_count" "$STATE_FILE")"

    local needs_load_check=false needs_upload_check=false
    [[ "$load_status" != "COMPLETED" ]] && needs_load_check=true
    # Deliberately no "&& upload_status != NOT_REQUESTED" exclusion here:
    # when $UPLOAD is true, a batch legitimately has NOT_REQUESTED only
    # for the instant between state_append_batch and its first real
    # upload attempt - state_append_batch always initializes it to
    # PENDING in that case (see its own comment), so NOT_REQUESTED
    # surviving to this point while $UPLOAD is true can only be the
    # exact corruption this function exists to catch (a crash before the
    # first real status update) - excluding it here would silently skip
    # reconciling precisely the batch that needs it, which is the bug
    # this function was written to fix in the first place.
    if [[ "$UPLOAD" == true && "$upload_status" != "COMPLETED" ]]; then
      needs_upload_check=true
    fi

    if [[ "$needs_load_check" == false && "$needs_upload_check" == false ]]; then
      continue
    fi

    echo "=== Reconciling batch $batch_id against live state (local: load_status=$load_status upload_status=$upload_status) ==="
    OVERRIDE_ARGS=(--show-batch "$batch_id")
    OVERRIDE_ARGS+=("${COMMON_OVERRIDE_ARGS[@]}")
    run_ecs_task
    if [[ "$TASK_EXIT_CODE" -ne 0 ]]; then
      echo "WARNING: could not verify batch $batch_id's live status (--show-batch failed, exit $TASK_EXIT_CODE) - leaving local state as-is; it will be re-checked on the next run." >&2
      continue
    fi

    local live_line
    live_line="$(grep -oE 'batch_id=[0-9]+ status=[A-Z_]+ total_files=[0-9]+ metadata_loaded=[0-9]+ pending=[0-9]+ uploading=[0-9]+ uploaded=[0-9]+ failed=[0-9]+ missing_source_content=[0-9]+ missing_metadata=[0-9]+' "$TASK_LOG_FILE" | tail -1 || true)"
    if [[ -z "$live_line" ]]; then
      echo "WARNING: could not parse batch $batch_id's --show-batch report from $TASK_LOG_FILE - leaving local state as-is." >&2
      continue
    fi

    local live_status live_pending live_failed
    live_status="$(echo "$live_line" | grep -oE 'status=[A-Z_]+' | cut -d= -f2)"
    live_pending="$(echo "$live_line" | grep -oE ' pending=[0-9]+' | cut -d= -f2)"
    live_failed="$(echo "$live_line" | grep -oE 'failed=[0-9]+' | cut -d= -f2)"

    echo "Batch $batch_id live: status=$live_status pending=$live_pending failed=$live_failed"

    # etl_batch.status starts CREATED and moves to READY only inside the
    # same, single transaction --load-batch commits once every member is
    # resolved (see load_award_attachments._run_load_batch) - so "status
    # is anything other than CREATED" is the correct, atomically-true
    # signal that the load step genuinely finished. The report's own
    # metadata_loaded/missing_metadata fields do NOT work for this:
    # missing_metadata is defined as total_files - metadata_loaded, so
    # their sum always equals total_files whether or not loading ever
    # ran - it is not an independent completion signal.
    if [[ "$needs_load_check" == true && "$live_status" != "CREATED" ]]; then
      echo "Batch $batch_id: live batch status ($live_status) shows loading has completed - correcting local load_status PENDING -> COMPLETED"
      state_set_batch_field_by_index "$i" "load_status" "COMPLETED"
      load_status="COMPLETED"
    fi

    # Per the incident this fixes: status=COMPLETED alone is not enough
    # (the upload step marks a batch COMPLETED even when some files
    # failed) - pending=0 and failed=0 must also hold. Files marked
    # MISSING_SOURCE_CONTENT count as resolved, not blocking completion -
    # there is nothing that could ever be uploaded for them.
    if [[ "$needs_upload_check" == true && "$live_status" == "COMPLETED" && "$live_pending" -eq 0 && "$live_failed" -eq 0 ]]; then
      echo "Batch $batch_id: live upload is complete (status=COMPLETED pending=0 failed=0) - correcting local upload_status -> COMPLETED"
      state_set_batch_field_by_index "$i" "upload_status" "COMPLETED"
      upload_status="COMPLETED"
    fi

    local load_ok=false upload_ok=false
    [[ "$load_status" == "COMPLETED" ]] && load_ok=true
    if [[ "$UPLOAD" == true ]]; then
      [[ "$upload_status" == "COMPLETED" ]] && upload_ok=true
    else
      upload_ok=true
    fi

    # This batch was, until this reconciliation pass, considered
    # incomplete (that's the only way this loop iteration reaches here) -
    # so if it is now fully resolved on every required axis, its files
    # were never credited to processed_files. Credit them exactly once:
    # once corrected to COMPLETED here, this batch will never again be
    # selected by the needs_load_check/needs_upload_check test above on
    # any future reconciliation pass, so this can't double-count.
    if [[ "$load_ok" == true && "$upload_ok" == true ]]; then
      state_add_processed "$selected_count"
      echo "Batch $batch_id: reconciled as fully complete - credited $selected_count file(s) to processed_files"
    fi
  done
}

run_bulk_load() {
  local total="$1" batch_size="$2"
  local start_epoch processed batches_run=0

  if [[ -f "$STATE_FILE" ]]; then
    local existing_total
    existing_total="$(jq -r '.total_target' "$STATE_FILE")"
    if [[ "$existing_total" != "$total" ]]; then
      echo "ERROR: --state-file $STATE_FILE already tracks a bulk load for $existing_total total files, not $total. Pass a different --state-file, or remove the old one, to start an unrelated run." >&2
      exit 1
    fi
    echo "=== Resuming bulk load from $STATE_FILE ==="
    processed="$(jq -r '.processed_files' "$STATE_FILE")"
    echo "Already processed: $processed/$total files across $(jq -r '.batches | length' "$STATE_FILE") batch(es)."
  else
    echo "=== Starting new bulk load: $STATE_FILE ==="
    state_init "$total" "$batch_size" "$UPLOAD" > "$STATE_FILE"
  fi

  # Reuse a previously-recorded task definition (from this same state
  # file) if one exists and the caller didn't ask for a fresh image -
  # this is what makes a resumed run skip the build/push/register cycle
  # entirely, not just avoid it within one already-running loop.
  local resumed_arn
  resumed_arn="$(jq -r '.task_definition_arn // empty' "$STATE_FILE")"
  if [[ -n "$resumed_arn" && -z "$IMAGE_URI_OVERRIDE" ]]; then
    echo "=== Reusing task definition recorded in $STATE_FILE: $resumed_arn ==="
    NEW_REVISION_ARN="$resumed_arn"
    IMAGE_URI="$(jq -r '.image_uri' "$STATE_FILE")"
  else
    build_and_register_task_definition
    state_set_image "$IMAGE_URI" "$NEW_REVISION_ARN"
  fi

  reconcile_incomplete_batches

  start_epoch="$(date +%s)"

  while true; do
    processed="$(jq -r '.processed_files' "$STATE_FILE")"
    if [[ "$processed" -ge "$total" ]]; then
      break
    fi

    local remaining size batch_id selected_count
    remaining=$((total - processed))
    size=$((remaining < batch_size ? remaining : batch_size))

    # Resume-from-failure: if the last recorded batch never finished
    # (load or upload failed, or never ran), retry ITS remaining steps
    # rather than creating a brand new batch and losing that selection.
    local last_batch_id last_load_status last_upload_status
    last_batch_id="$(jq -r '.batches[-1].batch_id // empty' "$STATE_FILE")"
    last_load_status="$(jq -r '.batches[-1].load_status // empty' "$STATE_FILE")"
    last_upload_status="$(jq -r '.batches[-1].upload_status // empty' "$STATE_FILE")"

    if [[ -n "$last_batch_id" && "$last_load_status" != "COMPLETED" ]]; then
      echo "=== Resuming incomplete batch $last_batch_id (load_status=$last_load_status) ==="
      batch_id="$last_batch_id"
      selected_count="$(jq -r '.batches[-1].selected_count' "$STATE_FILE")"
    elif [[ -n "$last_batch_id" && "$UPLOAD" == true && "$last_upload_status" != "COMPLETED" ]]; then
      echo "=== Resuming incomplete batch $last_batch_id (upload_status=$last_upload_status) ==="
      batch_id="$last_batch_id"
      selected_count="$(jq -r '.batches[-1].selected_count' "$STATE_FILE")"
    else
      echo ""
      echo "=== Creating batch #$((batches_run + 1)): requesting $size file(s) ($processed/$total processed so far) ==="
      OVERRIDE_ARGS=(--create-batch "$size")
      OVERRIDE_ARGS+=("${COMMON_OVERRIDE_ARGS[@]}")
      run_ecs_task
      if [[ "$TASK_EXIT_CODE" -ne 0 ]]; then
        echo "ERROR: --create-batch failed (exit $TASK_EXIT_CODE) - see log above. State saved at $STATE_FILE; re-run the same command to retry." >&2
        state_set_status "FAILED"
        print_bulk_summary "$total" "$start_epoch" "FAILED"
        exit 1
      fi

      batch_id="$(grep -oE 'Created batch_id=[0-9]+' "$TASK_LOG_FILE" | tail -1 | grep -oE '[0-9]+' || true)"
      selected_count="$(grep -oE 'Created batch_id=.*selected=[0-9]+' "$TASK_LOG_FILE" | tail -1 | grep -oE 'selected=[0-9]+' | grep -oE '[0-9]+' || true)"

      if [[ -z "$batch_id" || -z "$selected_count" ]]; then
        echo "ERROR: could not parse a batch_id/selected count out of the task log - see $TASK_LOG_FILE" >&2
        state_set_status "FAILED"
        print_bulk_summary "$total" "$start_epoch" "FAILED"
        exit 1
      fi

      if [[ "$selected_count" -eq 0 ]]; then
        echo "=== No more file_ids available to batch (candidate pool exhausted) - stopping early with $processed/$total processed ==="
        state_set_status "COMPLETED"
        print_bulk_summary "$total" "$start_epoch" "COMPLETED (candidate pool exhausted)"
        exit 0
      fi

      echo "Batch $batch_id created: requested=$size selected=$selected_count"
      state_append_batch "$batch_id" "$size" "$selected_count"
      # A batch just appended above always starts PENDING - last_load_status
      # still holds the *previous* batch's status (read before this one
      # existed), so it must be refreshed here, or the load step below is
      # wrongly skipped on this batch's first pass and its file count gets
      # added to processed_files without ever being loaded (then added
      # again when the next loop iteration correctly resumes and loads it).
      last_load_status="PENDING"
    fi

    if [[ "$last_load_status" != "COMPLETED" ]]; then
      echo "=== Loading batch $batch_id ($selected_count file(s)) ==="
      OVERRIDE_ARGS=(--load-batch "$batch_id")
      OVERRIDE_ARGS+=("${COMMON_OVERRIDE_ARGS[@]}")
      run_ecs_task
      if [[ "$TASK_EXIT_CODE" -ne 0 ]]; then
        echo "ERROR: --load-batch $batch_id failed (exit $TASK_EXIT_CODE) - see log above. State saved at $STATE_FILE; re-run the same command to retry this batch." >&2
        state_set_last_batch_field "load_status" "FAILED"
        state_set_status "FAILED"
        print_bulk_summary "$total" "$start_epoch" "FAILED"
        exit 1
      fi

      echo "=== Verifying batch $batch_id ==="
      OVERRIDE_ARGS=(--show-batch "$batch_id")
      OVERRIDE_ARGS+=("${COMMON_OVERRIDE_ARGS[@]}")
      run_ecs_task
      if [[ "$TASK_EXIT_CODE" -ne 0 ]]; then
        echo "ERROR: --show-batch $batch_id failed (exit $TASK_EXIT_CODE) - could not verify batch $batch_id's load status." >&2
        state_set_last_batch_field "load_status" "FAILED"
        state_set_status "FAILED"
        print_bulk_summary "$total" "$start_epoch" "FAILED"
        exit 1
      fi
      local reported_status
      reported_status="$(grep -oE 'batch_id=[0-9]+ status=[A-Z_]+' "$TASK_LOG_FILE" | tail -1 | grep -oE 'status=[A-Z_]+' | cut -d= -f2 || true)"
      echo "Batch $batch_id reported status: ${reported_status:-unknown}"

      state_set_last_batch_field "load_status" "COMPLETED"
    fi

    if [[ "$UPLOAD" == true ]]; then
      local current_upload_status
      current_upload_status="$(jq -r '.batches[-1].upload_status' "$STATE_FILE")"
      if [[ "$current_upload_status" != "COMPLETED" ]]; then
        echo "=== Uploading batch $batch_id to S3 ==="
        OVERRIDE_ARGS=(--upload --batch-id "$batch_id")
        [[ -n "$BUCKET" ]] && OVERRIDE_ARGS+=(--bucket "$BUCKET")
        [[ -n "$PREFIX" ]] && OVERRIDE_ARGS+=(--prefix "$PREFIX")
        [[ "$RETRY_FAILED" == true ]] && OVERRIDE_ARGS+=(--retry-failed)
        [[ "$DRY_RUN" == true ]] && OVERRIDE_ARGS+=(--dry-run)
        OVERRIDE_ARGS+=("${COMMON_OVERRIDE_ARGS[@]}")
        run_ecs_task
        if [[ "$TASK_EXIT_CODE" -ne 0 ]]; then
          echo "ERROR: --upload --batch-id $batch_id failed (exit $TASK_EXIT_CODE) - see log above. State saved at $STATE_FILE; re-run the same command to retry this batch's upload." >&2
          state_set_last_batch_field "upload_status" "FAILED"
          state_set_status "FAILED"
          print_bulk_summary "$total" "$start_epoch" "FAILED"
          exit 1
        fi
        state_set_last_batch_field "upload_status" "COMPLETED"
      fi
    fi

    state_add_processed "$selected_count"
    batches_run=$((batches_run + 1))
    processed="$(jq -r '.processed_files' "$STATE_FILE")"
    local percent=$((total > 0 ? processed * 100 / total : 100))
    echo "=== Progress: batch $batch_id complete - $processed/$total file(s) processed (${percent}%) ==="
  done

  state_set_status "COMPLETED"
  print_bulk_summary "$total" "$start_epoch" "COMPLETED"
}

print_bulk_summary() {
  local total="$1" start_epoch="$2" final_status="$3"
  local elapsed processed batch_count
  elapsed=$(( $(date +%s) - start_epoch ))
  processed="$(jq -r '.processed_files' "$STATE_FILE")"
  batch_count="$(jq -r '.batches | length' "$STATE_FILE")"

  echo ""
  echo "=== Bulk load summary ==="
  echo "Status:            $final_status"
  echo "Files processed:   $processed / $total"
  echo "Batches run:       $batch_count"
  echo "Elapsed:           ${elapsed}s"
  echo "State file:        $STATE_FILE"
}

build_and_register_task_definition() {
  if [[ -n "$IMAGE_URI_OVERRIDE" ]]; then
    echo "=== Reusing already-pushed image (--image-uri): $IMAGE_URI_OVERRIDE ==="
    IMAGE_URI="$IMAGE_URI_OVERRIDE"
  else
    echo "=== Building loader image ==="
    local git_sha image_tag
    git_sha="$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
    image_tag="$(date -u +%Y%m%dT%H%M%SZ)-${git_sha}"
    IMAGE_URI="${ECR_REPOSITORY_URI}:${image_tag}"

    docker build \
      --platform linux/amd64 \
      -t "$IMAGE_URI" \
      -f "$ROOT_DIR/etl/Dockerfile.loader" \
      "$ROOT_DIR"

    echo "=== Pushing image to ECR ==="
    aws ecr get-login-password --region "$AWS_REGION" \
      | docker login --username AWS --password-stdin "${ECR_REPOSITORY_URI%%/*}"
    docker push "$IMAGE_URI"
  fi

  echo "=== Registering new task definition revision ==="
  local current_taskdef_file new_taskdef_file
  current_taskdef_file="$TMP_DIR/current-taskdef.json"
  new_taskdef_file="$TMP_DIR/new-taskdef.json"

  aws ecs describe-task-definition \
    --task-definition "$TASK_FAMILY" \
    --region "$AWS_REGION" \
    --query 'taskDefinition' \
    --output json \
    > "$current_taskdef_file"

  if [[ ! -s "$current_taskdef_file" ]]; then
    echo "ERROR: aws ecs describe-task-definition returned no output for family '$TASK_FAMILY'" >&2
    exit 1
  fi

  if ! jq empty "$current_taskdef_file" 2>/dev/null; then
    echo "ERROR: aws ecs describe-task-definition did not return valid JSON" >&2
    exit 1
  fi

  local actual_family
  actual_family="$(jq -r '.family' "$current_taskdef_file")"
  if [[ "$actual_family" != "$TASK_FAMILY" ]]; then
    echo "ERROR: current task definition family is '$actual_family', expected '$TASK_FAMILY'" >&2
    exit 1
  fi

  if ! jq -e --arg name "$CONTAINER_NAME" \
    '(.containerDefinitions // []) | map(.name) | index($name) != null' \
    "$current_taskdef_file" > /dev/null; then
    echo "ERROR: no '$CONTAINER_NAME' container found in the current task definition" >&2
    exit 1
  fi

  (
    cd "$ROOT_DIR/etl" \
      && uv run python scripts/transform_loader_task_definition.py \
           --input "$current_taskdef_file" \
           --output "$new_taskdef_file" \
           --container-name "$CONTAINER_NAME" \
           --image-uri "$IMAGE_URI" \
           --family "$TASK_FAMILY"
  )

  if [[ ! -s "$new_taskdef_file" ]]; then
    echo "ERROR: task-definition transform produced no output" >&2
    exit 1
  fi

  if ! jq empty "$new_taskdef_file" 2>/dev/null; then
    echo "ERROR: task-definition transform produced invalid JSON" >&2
    exit 1
  fi

  NEW_REVISION_ARN="$(aws ecs register-task-definition \
    --region "$AWS_REGION" \
    --cli-input-json "file://${new_taskdef_file}" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)"

  echo "Registered: $NEW_REVISION_ARN"
}

# The single-shot path and the --bulk-load early-exit below - both need
# TMP_DIR/the parsed globals from parse_and_validate_args, so this only
# ever runs after that (see the sourced-guard at the bottom of this
# file).
dispatch() {
# Non-secret configuration only - identifiers and connection routing
# info, never a password/DSN/secret value. POSTGRES_SECRET_ID is always
# required (checked above); ORACLE_SECRET_ID is required unless
# --migrate-only. POSTGRES_HOST/PORT/DB and
# AWARD_ATTACHMENT_BUCKET_NAME/AWS_REGION are passed through only if set
# - the loader has its own fallbacks/defaults for all of them. Shared by
# every run_ecs_task call - the single-shot path and every batch/step
# --bulk-load launches - so each one resolves the same credentials the
# same way, appended once here instead of duplicated at each call site.
COMMON_OVERRIDE_ARGS=(--postgres-secret-id "$POSTGRES_SECRET_ID")
[[ -n "${ORACLE_SECRET_ID:-}" ]] && COMMON_OVERRIDE_ARGS+=(--oracle-secret-id "$ORACLE_SECRET_ID")
[[ -n "${POSTGRES_HOST:-}" ]] && COMMON_OVERRIDE_ARGS+=(--postgres-host "$POSTGRES_HOST")
[[ -n "${POSTGRES_PORT:-}" ]] && COMMON_OVERRIDE_ARGS+=(--postgres-port "$POSTGRES_PORT")
[[ -n "${POSTGRES_DB:-}" ]] && COMMON_OVERRIDE_ARGS+=(--postgres-db "$POSTGRES_DB")
[[ -n "${AWARD_ATTACHMENT_BUCKET_NAME:-}" ]] && COMMON_OVERRIDE_ARGS+=(--award-attachment-bucket-name "$AWARD_ATTACHMENT_BUCKET_NAME")
[[ -n "$AWS_REGION" ]] && COMMON_OVERRIDE_ARGS+=(--aws-region "$AWS_REGION")

if [[ -n "$BULK_LOAD" ]]; then
  run_bulk_load "$BULK_LOAD" "$BULK_BATCH_SIZE"
  exit 0
fi

build_and_register_task_definition

echo "=== Building command + environment override ==="
OVERRIDE_ARGS=()
[[ -n "$FILE_ID" ]] && OVERRIDE_ARGS+=(--file-id "$FILE_ID")
[[ -n "$LOAD_FILE_ID" ]] && OVERRIDE_ARGS+=(--load-file-id "$LOAD_FILE_ID")
[[ -n "$LOAD_FILE_IDS" ]] && OVERRIDE_ARGS+=(--load-file-ids "$LOAD_FILE_IDS")
[[ -n "$LIMIT" ]] && OVERRIDE_ARGS+=(--limit "$LIMIT")
[[ "$RETRY_FAILED" == true ]] && OVERRIDE_ARGS+=(--retry-failed)
[[ "$DRY_RUN" == true ]] && OVERRIDE_ARGS+=(--dry-run)
[[ "$UPLOAD" == true ]] && OVERRIDE_ARGS+=(--upload)
[[ "$MIGRATE_ONLY" == true ]] && OVERRIDE_ARGS+=(--migrate-only)
[[ "$SHOW_UPLOAD_STATUS" == true ]] && OVERRIDE_ARGS+=(--show-upload-status)
[[ -n "$CREATE_BATCH" ]] && OVERRIDE_ARGS+=(--create-batch "$CREATE_BATCH")
[[ "$INCLUDE_ALREADY_UPLOADED" == true ]] && OVERRIDE_ARGS+=(--include-already-uploaded)
[[ -n "$LOAD_BATCH" ]] && OVERRIDE_ARGS+=(--load-batch "$LOAD_BATCH")
[[ -n "$SHOW_BATCH" ]] && OVERRIDE_ARGS+=(--show-batch "$SHOW_BATCH")
[[ "$LIST_AWARDS_WITH_ATTACHMENTS" == true ]] && OVERRIDE_ARGS+=(--list-awards-with-attachments)
[[ -n "$DIFF_AWARD_ATTACHMENTS" ]] && OVERRIDE_ARGS+=(--diff-award-attachments "$DIFF_AWARD_ATTACHMENTS")
[[ -n "$BATCH_ID" ]] && OVERRIDE_ARGS+=(--batch-id "$BATCH_ID")
[[ -n "$BUCKET" ]] && OVERRIDE_ARGS+=(--bucket "$BUCKET")
[[ -n "$PREFIX" ]] && OVERRIDE_ARGS+=(--prefix "$PREFIX")
OVERRIDE_ARGS+=("${COMMON_OVERRIDE_ARGS[@]}")

run_ecs_task
exit "$TASK_EXIT_CODE"
}

# Only actually parse argv and run when this file is executed directly
# (bash scripts/run-award-attachment-loader.sh ...) - never when it is
# `source`d, e.g. by a test script that wants the function definitions
# above (run_bulk_load, reconcile_incomplete_batches, the state_* helpers)
# without argv parsing, its env-var requirements, or a real AWS/Docker
# call ever firing.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  parse_and_validate_args "$@"
  dispatch
fi
