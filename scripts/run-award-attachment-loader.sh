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
#       [--batch-id BATCH_ID]
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
# --batch-id BATCH_ID (only valid with --upload): restrict the upload run
#   to exactly this batch's membership, instead of every PENDING/
#   UPLOADING (+FAILED with --retry-failed) row. Mutually exclusive with
#   --file-id and with --create-batch/--load-batch/--show-batch.
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

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file-id) FILE_ID="$2"; shift 2 ;;
    --load-file-id) LOAD_FILE_ID="$2"; shift 2 ;;
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

# --show-batch is PostgreSQL-only (like --migrate-only/--show-upload-status),
# so it's exempt from the Oracle secret requirement below; --create-batch
# and --load-batch both read Oracle and so are NOT exempt.
if [[ "$MIGRATE_ONLY" == false && "$SHOW_UPLOAD_STATUS" == false && -z "$SHOW_BATCH" ]]; then
  : "${ORACLE_SECRET_ID:?ORACLE_SECRET_ID is not set - Secrets Manager ARN/name for the Oracle secret (required for every --ecs invocation except --migrate-only/--show-upload-status/--show-batch)}"
fi

if [[ -z "$IMAGE_URI_OVERRIDE" ]]; then
  : "${ECR_REPOSITORY_URI:?ECR_REPOSITORY_URI is not set - set it to the loader image\'s ECR repository URI, or pass --image-uri to reuse an already-pushed image}"
fi

if [[ "$UPLOAD" == true && "$DRY_RUN" == false ]]; then
  echo "=== WARNING: this will perform a REAL S3 upload run ==="
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [[ -n "$IMAGE_URI_OVERRIDE" ]]; then
  echo "=== Reusing already-pushed image (--image-uri): $IMAGE_URI_OVERRIDE ==="
  IMAGE_URI="$IMAGE_URI_OVERRIDE"
else
  echo "=== Building loader image ==="
  GIT_SHA="$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
  IMAGE_TAG="$(date -u +%Y%m%dT%H%M%SZ)-${GIT_SHA}"
  IMAGE_URI="${ECR_REPOSITORY_URI}:${IMAGE_TAG}"

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
CURRENT_TASKDEF_FILE="$TMP_DIR/current-taskdef.json"
NEW_TASKDEF_FILE="$TMP_DIR/new-taskdef.json"

aws ecs describe-task-definition \
  --task-definition "$TASK_FAMILY" \
  --region "$AWS_REGION" \
  --query 'taskDefinition' \
  --output json \
  > "$CURRENT_TASKDEF_FILE"

if [[ ! -s "$CURRENT_TASKDEF_FILE" ]]; then
  echo "ERROR: aws ecs describe-task-definition returned no output for family '$TASK_FAMILY'" >&2
  exit 1
fi

if ! jq empty "$CURRENT_TASKDEF_FILE" 2>/dev/null; then
  echo "ERROR: aws ecs describe-task-definition did not return valid JSON" >&2
  exit 1
fi

ACTUAL_FAMILY="$(jq -r '.family' "$CURRENT_TASKDEF_FILE")"
if [[ "$ACTUAL_FAMILY" != "$TASK_FAMILY" ]]; then
  echo "ERROR: current task definition family is '$ACTUAL_FAMILY', expected '$TASK_FAMILY'" >&2
  exit 1
fi

if ! jq -e --arg name "$CONTAINER_NAME" \
  '(.containerDefinitions // []) | map(.name) | index($name) != null' \
  "$CURRENT_TASKDEF_FILE" > /dev/null; then
  echo "ERROR: no '$CONTAINER_NAME' container found in the current task definition" >&2
  exit 1
fi

(
  cd "$ROOT_DIR/etl" \
    && uv run python scripts/transform_loader_task_definition.py \
         --input "$CURRENT_TASKDEF_FILE" \
         --output "$NEW_TASKDEF_FILE" \
         --container-name "$CONTAINER_NAME" \
         --image-uri "$IMAGE_URI" \
         --family "$TASK_FAMILY"
)

if [[ ! -s "$NEW_TASKDEF_FILE" ]]; then
  echo "ERROR: task-definition transform produced no output" >&2
  exit 1
fi

if ! jq empty "$NEW_TASKDEF_FILE" 2>/dev/null; then
  echo "ERROR: task-definition transform produced invalid JSON" >&2
  exit 1
fi

NEW_REVISION_ARN="$(aws ecs register-task-definition \
  --region "$AWS_REGION" \
  --cli-input-json "file://${NEW_TASKDEF_FILE}" \
  --query 'taskDefinition.taskDefinitionArn' \
  --output text)"

echo "Registered: $NEW_REVISION_ARN"

echo "=== Building command + environment override ==="
OVERRIDE_ARGS=()
[[ -n "$FILE_ID" ]] && OVERRIDE_ARGS+=(--file-id "$FILE_ID")
[[ -n "$LOAD_FILE_ID" ]] && OVERRIDE_ARGS+=(--load-file-id "$LOAD_FILE_ID")
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
[[ -n "$BATCH_ID" ]] && OVERRIDE_ARGS+=(--batch-id "$BATCH_ID")
[[ -n "$BUCKET" ]] && OVERRIDE_ARGS+=(--bucket "$BUCKET")
[[ -n "$PREFIX" ]] && OVERRIDE_ARGS+=(--prefix "$PREFIX")

# Non-secret configuration only - identifiers and connection routing
# info, never a password/DSN/secret value. POSTGRES_SECRET_ID is always
# required (checked above); ORACLE_SECRET_ID is required unless
# --migrate-only. POSTGRES_HOST/PORT/DB and
# AWARD_ATTACHMENT_BUCKET_NAME/AWS_REGION are passed through only if set
# - the loader has its own fallbacks/defaults for all of them.
OVERRIDE_ARGS+=(--postgres-secret-id "$POSTGRES_SECRET_ID")
[[ -n "${ORACLE_SECRET_ID:-}" ]] && OVERRIDE_ARGS+=(--oracle-secret-id "$ORACLE_SECRET_ID")
[[ -n "${POSTGRES_HOST:-}" ]] && OVERRIDE_ARGS+=(--postgres-host "$POSTGRES_HOST")
[[ -n "${POSTGRES_PORT:-}" ]] && OVERRIDE_ARGS+=(--postgres-port "$POSTGRES_PORT")
[[ -n "${POSTGRES_DB:-}" ]] && OVERRIDE_ARGS+=(--postgres-db "$POSTGRES_DB")
[[ -n "${AWARD_ATTACHMENT_BUCKET_NAME:-}" ]] && OVERRIDE_ARGS+=(--award-attachment-bucket-name "$AWARD_ATTACHMENT_BUCKET_NAME")
[[ -n "$AWS_REGION" ]] && OVERRIDE_ARGS+=(--aws-region "$AWS_REGION")

OVERRIDES_JSON="$(
  cd "$ROOT_DIR/etl" \
    && uv run python scripts/build_award_attachment_ecs_overrides.py "${OVERRIDE_ARGS[@]}"
)"
echo "Overrides: $OVERRIDES_JSON"

echo "=== Launching one-off ECS task ==="
RUN_TASK_OUTPUT="$(aws ecs run-task \
  --cluster "$CLUSTER_NAME" \
  --task-definition "$NEW_REVISION_ARN" \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_IDS],securityGroups=[$SECURITY_GROUP_ID],assignPublicIp=DISABLED}" \
  --overrides "$OVERRIDES_JSON" \
  --region "$AWS_REGION")"

TASK_ARN="$(echo "$RUN_TASK_OUTPUT" | python3 -c 'import json, sys; print(json.load(sys.stdin)["tasks"][0]["taskArn"])')"
echo "Task started: $TASK_ARN"

echo "=== Waiting for task completion (this can take a few minutes) ==="
aws ecs wait tasks-stopped \
  --cluster "$CLUSTER_NAME" \
  --tasks "$TASK_ARN" \
  --region "$AWS_REGION"

TASK_ID="${TASK_ARN##*/}"
LOG_STREAM="loader/${CONTAINER_NAME}/${TASK_ID}"

echo "=== CloudWatch logs (${LOG_GROUP} / ${LOG_STREAM}) ==="
aws logs tail "$LOG_GROUP" \
  --log-stream-names "$LOG_STREAM" \
  --region "$AWS_REGION" \
  --since 1h || echo "WARNING: could not tail logs - check the CloudWatch console directly."

echo "=== Checking task exit code ==="
TASK_DESCRIBE_FILE="$TMP_DIR/task-describe.json"
aws ecs describe-tasks \
  --cluster "$CLUSTER_NAME" \
  --tasks "$TASK_ARN" \
  --region "$AWS_REGION" \
  --output json \
  > "$TASK_DESCRIBE_FILE"

EXIT_CODE="$(jq -r --arg name "$CONTAINER_NAME" \
  '.tasks[0].containers[] | select(.name == $name) | .exitCode // empty' \
  "$TASK_DESCRIBE_FILE")"

if [[ -z "$EXIT_CODE" ]]; then
  # A container that fails before its process ever starts (a bad exec,
  # a missing image, a resource limit) never gets an exitCode at all -
  # ECS instead records why on the task and the container themselves.
  # Report both explicitly rather than just saying it couldn't be
  # determined - this is exactly the shape of failure a broken
  # containerOverrides command produces (see the "executable file not
  # found in $PATH" incident this script was fixed after).
  STOPPED_REASON="$(jq -r '.tasks[0].stoppedReason // "(none reported)"' "$TASK_DESCRIBE_FILE")"
  CONTAINER_REASON="$(jq -r --arg name "$CONTAINER_NAME" \
    '.tasks[0].containers[] | select(.name == $name) | .reason // "(none reported)"' \
    "$TASK_DESCRIBE_FILE")"
  echo "ERROR: the task container never reported an exit code - it most likely failed during initialization, before load_award_attachments.py ever ran." >&2
  echo "  Task stoppedReason: $STOPPED_REASON" >&2
  echo "  Container reason:   $CONTAINER_REASON" >&2
  exit 1
fi

echo "Task exit code: $EXIT_CODE"
exit "$EXIT_CODE"
