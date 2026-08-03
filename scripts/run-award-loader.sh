#!/usr/bin/env bash
set -euo pipefail

# Build/push the (core) Award loader image, register a new task-
# definition revision from it, run the loader as a one-off Fargate task
# on the existing research-archive-platform-dev-loader ECS task family
# (see terraform/modules/ecs/main.tf - this script never modifies
# Terraform or Terraform-managed state), wait for it to finish, stream
# its CloudWatch logs, and exit with the task container's own exit code.
#
# Duplicated from scripts/run-award-attachment-loader.sh - the ECS
# orchestration below (image build/push, task-definition describe/
# transform/register, run-task, wait, log tail, exit-code handling) is
# unchanged from that script. Only the command this script builds, the
# CLI flags it accepts, this header, its validation, and its logging
# names differ - scoped to the plain Award (core) loader
# (load_awards_from_csv.py, the "award" domain under
# python -m archive_etl) instead of the Award Attachment loader.
#
# This does NOT run automatically - it must be invoked explicitly, and
# every AWS call it makes is a real one (image push, task-definition
# registration, ECS task launch).
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
#                              secret (an identifier, not a credential).
#                              Not required for --migrate-only's own
#                              validation, but forwarded for every
#                              invocation, following the Award Attachment
#                              wrapper's own convention.
#
# Required for every invocation EXCEPT --migrate-only and --show-batch
# (neither touches Oracle):
#   ORACLE_SECRET_ID   - Secrets Manager ARN/name for the Oracle secret
#
# Optional environment (sensible defaults matching the current
# Terraform naming convention, or simply omitted if not set):
#   AWS_REGION         (default: us-east-1)
#   PROJECT_NAME        (default: research-archive-platform)
#   ENVIRONMENT         (default: dev)
#   CLUSTER_NAME        (default: ${PROJECT_NAME}-${ENVIRONMENT}-etl)
#   TASK_FAMILY         (default: ${PROJECT_NAME}-${ENVIRONMENT}-loader)
#   POSTGRES_HOST/POSTGRES_PORT/POSTGRES_DB  - only needed as a fallback
#     for whichever of host/port/dbname the PostgreSQL secret doesn't
#     include itself
#   POLL_INTERVAL_SECONDS (default: 15) - how often this script polls
#     `aws ecs describe-tasks` while waiting for the task to stop. No
#     overall timeout is applied - a long Award load is never treated
#     as a failure just for taking a while; only the container's own
#     real exit code (once it does stop) determines this script's exit
#     status.
#
# None of POSTGRES_USER, POSTGRES_PASSWORD, ORACLE_USER, ORACLE_PASSWORD,
# or ORACLE_DSN are ever read or passed through by this script.
#
# Usage:
#   scripts/run-award-loader.sh [--migrate-only] [--load-award-id AWARD_ID] \
#       [--create-batch N] [--load-batch BATCH_ID] [--show-batch BATCH_ID] \
#       [--dry-run] [--image-uri URI]
#
# --image-uri <full-ecr-image-uri>: reuse an already-built-and-pushed
#   image instead of building/pushing a new one. When given, this script
#   never invokes `docker build`, `docker login`, `aws ecr
#   get-login-password`, or `docker push` - it registers a new task-
#   definition revision directly from the supplied image URI.
#
# --migrate-only: apply migrations and exit - a read-only-safe way to
#   bootstrap/validate schema without loading anything. Does not require
#   ORACLE_SECRET_ID.
#
# --load-award-id AWARD_ID: idempotent incremental UPSERT for exactly
#   one award_id's entire award_number version family plus every child
#   table (see load_awards_from_csv.py's own --load-award-id help for
#   the full list). Requires ORACLE_SECRET_ID.
#
# --create-batch N: resolve N Award ids from Oracle, in stable ascending
#   order, and persist that exact membership as a new batch
#   (archive.etl_batch/etl_batch_item - the generic ETL batch framework,
#   see docs/ETL_BATCH_FRAMEWORK.md). Requires ORACLE_SECRET_ID.
#
# --load-batch BATCH_ID: idempotent incremental load for exactly this
#   batch's recorded Award membership. Requires ORACLE_SECRET_ID.
#
# --show-batch BATCH_ID: read-only status report for one batch (requested
#   size, status, and item counts by load state). Does NOT require
#   ORACLE_SECRET_ID - PostgreSQL-only, like --migrate-only.
#
# --dry-run: combine with --load-award-id/--create-batch/--load-batch -
#   every step still runs (so reported counts are accurate) but the
#   database write is rolled back instead of committed.
#
# Examples:
#   # Bootstrap a fresh database (apply migrations, exit):
#   POSTGRES_SECRET_ID=arn:...:postgres \
#     scripts/run-award-loader.sh --migrate-only
#
#   # Resolve and persist a 10-Award batch:
#   scripts/run-award-loader.sh --create-batch 10
#
#   # Inspect a batch's status, read-only:
#   scripts/run-award-loader.sh --show-batch 1
#
#   # Load exactly that batch's membership:
#   scripts/run-award-loader.sh --load-batch 1
#
#   # Idempotent single-Award load, dry run first:
#   scripts/run-award-loader.sh --load-award-id 209899 --dry-run
#   scripts/run-award-loader.sh --load-award-id 209899
#
#   # Reuse an already-pushed image instead of building a new one:
#   scripts/run-award-loader.sh --migrate-only \
#       --image-uri 770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader:20260731T005343Z-b0d475d

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

MIGRATE_ONLY=false
LOAD_AWARD_ID=""
CREATE_BATCH=""
LOAD_BATCH=""
SHOW_BATCH=""
DIFF_AWARD_VERSIONS=""
INVESTIGATE_WORKFLOW_DOCUMENT_NUMBER=""
DRY_RUN=false
IMAGE_URI_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --migrate-only) MIGRATE_ONLY=true; shift ;;
    --load-award-id) LOAD_AWARD_ID="$2"; shift 2 ;;
    --create-batch) CREATE_BATCH="$2"; shift 2 ;;
    --load-batch) LOAD_BATCH="$2"; shift 2 ;;
    --show-batch) SHOW_BATCH="$2"; shift 2 ;;
    --diff-award-versions) DIFF_AWARD_VERSIONS="$2"; shift 2 ;;
    --investigate-workflow-document-number) INVESTIGATE_WORKFLOW_DOCUMENT_NUMBER="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --image-uri) IMAGE_URI_OVERRIDE="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# Batch-verb validation, mirroring load_awards_from_csv.py's own
# parse_args - fail fast here rather than only inside the ECS task,
# after an image build/push and a task-definition registration have
# already run.
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

if [[ -n "$LOAD_AWARD_ID" ]] && ! [[ "$LOAD_AWARD_ID" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: --load-award-id must be a positive integer, got '$LOAD_AWARD_ID'" >&2
  exit 1
fi

if [[ -n "$LOAD_AWARD_ID" && "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
  echo "ERROR: --load-award-id cannot be combined with ${ACTIVE_BATCH_VERBS[0]}" >&2
  exit 1
fi

if [[ "$MIGRATE_ONLY" == true && "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
  echo "ERROR: --migrate-only cannot be combined with ${ACTIVE_BATCH_VERBS[0]}" >&2
  exit 1
fi

if [[ "$MIGRATE_ONLY" == true && -n "$LOAD_AWARD_ID" ]]; then
  echo "ERROR: --migrate-only cannot be combined with --load-award-id" >&2
  exit 1
fi

if [[ "$DRY_RUN" == true && "$MIGRATE_ONLY" == true ]]; then
  echo "ERROR: --dry-run cannot be combined with --migrate-only" >&2
  exit 1
fi

if [[ -n "$DIFF_AWARD_VERSIONS" ]]; then
  if [[ "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
    echo "ERROR: --diff-award-versions cannot be combined with ${ACTIVE_BATCH_VERBS[0]}" >&2
    exit 1
  fi
  if [[ -n "$LOAD_AWARD_ID" ]]; then
    echo "ERROR: --diff-award-versions cannot be combined with --load-award-id" >&2
    exit 1
  fi
  if [[ "$MIGRATE_ONLY" == true ]]; then
    echo "ERROR: --diff-award-versions cannot be combined with --migrate-only" >&2
    exit 1
  fi
fi

if [[ -n "$INVESTIGATE_WORKFLOW_DOCUMENT_NUMBER" ]]; then
  if [[ "${#ACTIVE_BATCH_VERBS[@]}" -gt 0 ]]; then
    echo "ERROR: --investigate-workflow-document-number cannot be combined with ${ACTIVE_BATCH_VERBS[0]}" >&2
    exit 1
  fi
  if [[ -n "$LOAD_AWARD_ID" ]]; then
    echo "ERROR: --investigate-workflow-document-number cannot be combined with --load-award-id" >&2
    exit 1
  fi
  if [[ -n "$DIFF_AWARD_VERSIONS" ]]; then
    echo "ERROR: --investigate-workflow-document-number cannot be combined with --diff-award-versions" >&2
    exit 1
  fi
  if [[ "$MIGRATE_ONLY" == true ]]; then
    echo "ERROR: --investigate-workflow-document-number cannot be combined with --migrate-only" >&2
    exit 1
  fi
fi

# --show-batch is PostgreSQL-only (like --migrate-only), so it's exempt
# from the Oracle secret requirement below; --load-award-id/--create-batch/
# --load-batch all read Oracle and so are NOT exempt.
if [[ "$MIGRATE_ONLY" == false && -z "$SHOW_BATCH" ]]; then
  : "${ORACLE_SECRET_ID:?ORACLE_SECRET_ID is not set - Secrets Manager ARN/name for the Oracle secret (required for every invocation except --migrate-only/--show-batch)}"
fi

if [[ -z "$IMAGE_URI_OVERRIDE" ]]; then
  : "${ECR_REPOSITORY_URI:?ECR_REPOSITORY_URI is not set - set it to the loader image\'s ECR repository URI, or pass --image-uri to reuse an already-pushed image}"
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if [[ -n "$IMAGE_URI_OVERRIDE" ]]; then
  echo "=== Reusing already-pushed image (--image-uri): $IMAGE_URI_OVERRIDE ==="
  IMAGE_URI="$IMAGE_URI_OVERRIDE"
else
  echo "=== Building Award loader image ==="
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
[[ "$MIGRATE_ONLY" == true ]] && OVERRIDE_ARGS+=(--migrate-only)
[[ -n "$LOAD_AWARD_ID" ]] && OVERRIDE_ARGS+=(--load-award-id "$LOAD_AWARD_ID")
[[ -n "$CREATE_BATCH" ]] && OVERRIDE_ARGS+=(--create-batch "$CREATE_BATCH")
[[ -n "$LOAD_BATCH" ]] && OVERRIDE_ARGS+=(--load-batch "$LOAD_BATCH")
[[ -n "$SHOW_BATCH" ]] && OVERRIDE_ARGS+=(--show-batch "$SHOW_BATCH")
[[ -n "$DIFF_AWARD_VERSIONS" ]] && OVERRIDE_ARGS+=(--diff-award-versions "$DIFF_AWARD_VERSIONS")
[[ -n "$INVESTIGATE_WORKFLOW_DOCUMENT_NUMBER" ]] && OVERRIDE_ARGS+=(--investigate-workflow-document-number "$INVESTIGATE_WORKFLOW_DOCUMENT_NUMBER")
[[ "$DRY_RUN" == true ]] && OVERRIDE_ARGS+=(--dry-run)

# Non-secret configuration only - identifiers and connection routing
# info, never a password/DSN/secret value. POSTGRES_SECRET_ID is always
# required (checked above); ORACLE_SECRET_ID is required unless
# --migrate-only/--show-batch. POSTGRES_HOST/PORT/DB and AWS_REGION are
# passed through only if set.
OVERRIDE_ARGS+=(--postgres-secret-id "$POSTGRES_SECRET_ID")
[[ -n "${ORACLE_SECRET_ID:-}" ]] && OVERRIDE_ARGS+=(--oracle-secret-id "$ORACLE_SECRET_ID")
[[ -n "${POSTGRES_HOST:-}" ]] && OVERRIDE_ARGS+=(--postgres-host "$POSTGRES_HOST")
[[ -n "${POSTGRES_PORT:-}" ]] && OVERRIDE_ARGS+=(--postgres-port "$POSTGRES_PORT")
[[ -n "${POSTGRES_DB:-}" ]] && OVERRIDE_ARGS+=(--postgres-db "$POSTGRES_DB")
[[ -n "$AWS_REGION" ]] && OVERRIDE_ARGS+=(--aws-region "$AWS_REGION")

OVERRIDES_JSON="$(
  cd "$ROOT_DIR/etl" \
    && uv run python scripts/build_award_ecs_overrides.py "${OVERRIDE_ARGS[@]}"
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

TASK_DESCRIBE_FILE="$TMP_DIR/task-describe.json"

# `aws ecs wait tasks-stopped` has a fixed, non-configurable polling
# budget (6s delay x 100 attempts = 10 minutes) - it raises a hard
# "Waiter encountered a terminal failure state" error the moment that
# budget is exhausted, even when the task is still running perfectly
# normally (a large --create-batch/--load-batch Award load can easily
# run past 10 minutes). That failure previously aborted this script via
# set -e before it ever reached the exit-code check below, reporting an
# alarming, misleading error for what may just be a still-running (or
# already-succeeded) task. Poll manually instead, with no fixed
# timeout, so a long Award load is never treated as a failure just for
# taking a while - only the container's own real exit code (checked
# below) ever determines this script's own exit status.
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-15}"
echo "=== Waiting for task completion (polling every ${POLL_INTERVAL_SECONDS}s, no fixed timeout - safe for long Award loads) ==="

while true; do
  aws ecs describe-tasks \
    --cluster "$CLUSTER_NAME" \
    --tasks "$TASK_ARN" \
    --region "$AWS_REGION" \
    --output json \
    > "$TASK_DESCRIBE_FILE"

  LAST_STATUS="$(jq -r '.tasks[0].lastStatus // empty' "$TASK_DESCRIBE_FILE")"

  if [[ -z "$LAST_STATUS" ]]; then
    echo "ERROR: aws ecs describe-tasks returned no task for $TASK_ARN - it may already have been stopped and garbage-collected by ECS." >&2
    exit 1
  fi

  if [[ "$LAST_STATUS" == "STOPPED" ]]; then
    echo "Task stopped."
    break
  fi

  echo "Task status: $LAST_STATUS - checking again in ${POLL_INTERVAL_SECONDS}s..."
  sleep "$POLL_INTERVAL_SECONDS"
done

TASK_ID="${TASK_ARN##*/}"
LOG_STREAM="loader/${CONTAINER_NAME}/${TASK_ID}"

echo "=== CloudWatch logs (${LOG_GROUP} / ${LOG_STREAM}) ==="
aws logs tail "$LOG_GROUP" \
  --log-stream-names "$LOG_STREAM" \
  --region "$AWS_REGION" \
  --since 1h || echo "WARNING: could not tail logs - check the CloudWatch console directly."

echo "=== Checking task exit code ==="
# Reuses the describe-tasks payload captured the moment STOPPED was
# first observed above - no need to call describe-tasks a second time.
EXIT_CODE="$(jq -r --arg name "$CONTAINER_NAME" \
  '.tasks[0].containers[] | select(.name == $name) | .exitCode // empty' \
  "$TASK_DESCRIBE_FILE")"

if [[ -z "$EXIT_CODE" ]]; then
  # A container that fails before its process ever starts (a bad exec,
  # a missing image, a resource limit) never gets an exitCode at all -
  # ECS instead records why on the task and the container themselves.
  # Report both explicitly rather than just saying it couldn't be
  # determined.
  STOPPED_REASON="$(jq -r '.tasks[0].stoppedReason // "(none reported)"' "$TASK_DESCRIBE_FILE")"
  CONTAINER_REASON="$(jq -r --arg name "$CONTAINER_NAME" \
    '.tasks[0].containers[] | select(.name == $name) | .reason // "(none reported)"' \
    "$TASK_DESCRIBE_FILE")"
  echo "ERROR: the task container never reported an exit code - it most likely failed during initialization, before load_awards_from_csv.py ever ran." >&2
  echo "  Task stoppedReason: $STOPPED_REASON" >&2
  echo "  Container reason:   $CONTAINER_REASON" >&2
  exit 1
fi

echo "Task exit code: $EXIT_CODE"
exit "$EXIT_CODE"
