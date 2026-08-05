#!/usr/bin/env bash
set -euo pipefail

# Build/push the Proposal loader image, register a new task-definition
# revision from it, run the loader as a one-off Fargate task on the
# existing research-archive-platform-dev-loader ECS task family (see
# terraform/modules/ecs/main.tf - this script never modifies Terraform
# or Terraform-managed state), wait for it to finish, stream its
# CloudWatch logs, and exit with the task container's own exit code.
#
# Mirrors scripts/run-award-loader.sh's orchestration (image build/push,
# task-definition describe/transform/register, run-task, poll-with-no-
# fixed-timeout, log tail, exit-code handling) exactly, scoped to
# load_proposals_from_csv.py's flags instead of Award's. Deliberately
# does NOT reuse Award's scripts/build_award_ecs_overrides.py - that
# script's --postgres-secret-id/--oracle-secret-id passthrough exists
# to inject secret identifiers the task definition doesn't already
# carry; research-archive-platform-dev-loader's task definition already
# declares ORACLE_SECRET_ID/POSTGRES_SECRET_ID as container-level
# environment variables (see terraform/modules/ecs/main.tf), so
# configure_ecs_environment() picks them up from os.environ with no
# CLI flag needed. scripts/transform_loader_task_definition.py IS
# reused as-is - it was already generic (container-name/image-uri/
# family are parameters, no Award-specific logic).
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
#
# Optional environment:
#   AWS_REGION             (default: us-east-1)
#   PROJECT_NAME            (default: research-archive-platform)
#   ENVIRONMENT             (default: dev)
#   CLUSTER_NAME            (default: ${PROJECT_NAME}-${ENVIRONMENT}-etl)
#   TASK_FAMILY             (default: ${PROJECT_NAME}-${ENVIRONMENT}-loader)
#   POLL_INTERVAL_SECONDS   (default: 15) - how often this script polls
#     `aws ecs describe-tasks` while waiting for the task to stop. No
#     overall timeout is applied - a long Proposal batch load is never
#     treated as a failure just for taking a while; only the
#     container's own real exit code determines this script's exit
#     status.
#
# Usage:
#   scripts/run-proposal-loader.sh --create-batch N
#   scripts/run-proposal-loader.sh --load-batch BATCH_ID [--dry-run]
#   scripts/run-proposal-loader.sh --show-batch BATCH_ID
#   scripts/run-proposal-loader.sh --load-proposal-number PROPOSAL_NUMBER \
#       [--load-proposal-number PROPOSAL_NUMBER ...]
#   scripts/run-proposal-loader.sh ... --image-uri URI
#
# --create-batch N: select N genuinely new, archive-aware Proposal
#   families and persist that membership as a new batch
#   (archive.etl_batch/etl_batch_proposal_item). Does not load anything.
#
# --load-batch BATCH_ID: idempotently load a batch's PENDING/FAILED
#   proposal_number membership - one Postgres transaction per family.
#   Combine with --dry-run to report without writing.
#
# --show-batch BATCH_ID: read-only status report for one batch.
#
# --load-proposal-number PROPOSAL_NUMBER: load only the given family/
#   families (repeatable) - a deliberate, manual, idempotent reload,
#   outside the batch framework entirely.
#
# --image-uri <full-ecr-image-uri>: reuse an already-built-and-pushed
#   image instead of building/pushing a new one.
#
# Examples:
#   # Dry-run selection first, then persist a 25-family batch:
#   scripts/run-proposal-loader.sh --create-batch 25
#
#   # Inspect a batch's status, read-only:
#   scripts/run-proposal-loader.sh --show-batch 1
#
#   # Load exactly that batch's membership:
#   scripts/run-proposal-loader.sh --load-batch 1
#
#   # Deliberate targeted reload of one family, outside the batch framework:
#   scripts/run-proposal-loader.sh --load-proposal-number 205

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

CREATE_BATCH=""
LOAD_BATCH=""
SHOW_BATCH=""
PROPOSAL_NUMBERS=()
DRY_RUN=false
IMAGE_URI_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --create-batch) CREATE_BATCH="$2"; shift 2 ;;
    --load-batch) LOAD_BATCH="$2"; shift 2 ;;
    --show-batch) SHOW_BATCH="$2"; shift 2 ;;
    --load-proposal-number) PROPOSAL_NUMBERS+=("$2"); shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --image-uri) IMAGE_URI_OVERRIDE="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# Verb validation, mirroring load_proposals_from_csv.py's own
# parse_args - fail fast here rather than only inside the ECS task,
# after an image build/push and a task-definition registration have
# already run.
ACTIVE_VERBS=()
[[ -n "$CREATE_BATCH" ]] && ACTIVE_VERBS+=(--create-batch)
[[ -n "$LOAD_BATCH" ]] && ACTIVE_VERBS+=(--load-batch)
[[ -n "$SHOW_BATCH" ]] && ACTIVE_VERBS+=(--show-batch)
[[ "${#PROPOSAL_NUMBERS[@]}" -gt 0 ]] && ACTIVE_VERBS+=(--load-proposal-number)

if [[ "${#ACTIVE_VERBS[@]}" -eq 0 ]]; then
  echo "ERROR: one of --create-batch, --load-batch, --show-batch, --load-proposal-number is required" >&2
  exit 1
fi

if [[ "${#ACTIVE_VERBS[@]}" -gt 1 ]]; then
  echo "ERROR: ${ACTIVE_VERBS[*]} cannot be combined - choose one at a time" >&2
  exit 1
fi

if [[ -n "$CREATE_BATCH" ]] && ! [[ "$CREATE_BATCH" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: --create-batch must be a positive integer, got '$CREATE_BATCH'" >&2
  exit 1
fi

if [[ -n "$LOAD_BATCH" ]] && ! [[ "$LOAD_BATCH" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: --load-batch must be a positive integer, got '$LOAD_BATCH'" >&2
  exit 1
fi

if [[ -n "$SHOW_BATCH" ]] && ! [[ "$SHOW_BATCH" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: --show-batch must be a positive integer, got '$SHOW_BATCH'" >&2
  exit 1
fi

if [[ "$DRY_RUN" == true && -z "$LOAD_BATCH" ]]; then
  echo "ERROR: --dry-run only applies to --load-batch" >&2
  exit 1
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
  echo "=== Building Proposal loader image ==="
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

(
  cd "$ROOT_DIR/etl" \
    && uv run python scripts/transform_loader_task_definition.py \
         --input "$CURRENT_TASKDEF_FILE" \
         --output "$NEW_TASKDEF_FILE" \
         --container-name "$CONTAINER_NAME" \
         --image-uri "$IMAGE_URI" \
         --family "$TASK_FAMILY"
)

if [[ ! -s "$NEW_TASKDEF_FILE" ]] || ! jq empty "$NEW_TASKDEF_FILE" 2>/dev/null; then
  echo "ERROR: task-definition transform produced no output or invalid JSON" >&2
  exit 1
fi

NEW_REVISION_ARN="$(aws ecs register-task-definition \
  --region "$AWS_REGION" \
  --cli-input-json "file://${NEW_TASKDEF_FILE}" \
  --query 'taskDefinition.taskDefinitionArn' \
  --output text)"

echo "Registered: $NEW_REVISION_ARN"

echo "=== Building command override ==="
COMMAND_ARGS=(python3 load_proposals_from_csv.py --ecs)
[[ -n "$CREATE_BATCH" ]] && COMMAND_ARGS+=(--create-batch "$CREATE_BATCH")
[[ -n "$LOAD_BATCH" ]] && COMMAND_ARGS+=(--load-batch "$LOAD_BATCH")
[[ -n "$SHOW_BATCH" ]] && COMMAND_ARGS+=(--show-batch "$SHOW_BATCH")
for number in "${PROPOSAL_NUMBERS[@]:-}"; do
  [[ -n "$number" ]] && COMMAND_ARGS+=(--load-proposal-number "$number")
done
[[ "$DRY_RUN" == true ]] && COMMAND_ARGS+=(--dry-run)

COMMAND_JSON="$(printf '%s\n' "${COMMAND_ARGS[@]}" | jq -R . | jq -s .)"
OVERRIDES_JSON="$(jq -n \
  --argjson command "$COMMAND_JSON" \
  --arg name "$CONTAINER_NAME" \
  '{containerOverrides: [{name: $name, command: $command}]}')"
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

# See scripts/run-award-loader.sh's own comment: `aws ecs wait
# tasks-stopped` has a fixed 10-minute polling budget and raises a hard
# failure the moment it's exhausted, even for a task that's still
# running normally - a large --load-batch Proposal load can easily run
# past that. Poll manually instead, with no fixed timeout.
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-15}"
echo "=== Waiting for task completion (polling every ${POLL_INTERVAL_SECONDS}s, no fixed timeout) ==="

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
EXIT_CODE="$(jq -r --arg name "$CONTAINER_NAME" \
  '.tasks[0].containers[] | select(.name == $name) | .exitCode // empty' \
  "$TASK_DESCRIBE_FILE")"

if [[ -z "$EXIT_CODE" ]]; then
  STOPPED_REASON="$(jq -r '.tasks[0].stoppedReason // "(none reported)"' "$TASK_DESCRIBE_FILE")"
  CONTAINER_REASON="$(jq -r --arg name "$CONTAINER_NAME" \
    '.tasks[0].containers[] | select(.name == $name) | .reason // "(none reported)"' \
    "$TASK_DESCRIBE_FILE")"
  echo "ERROR: the task container never reported an exit code - it most likely failed during initialization, before load_proposals_from_csv.py ever ran." >&2
  echo "  Task stoppedReason: $STOPPED_REASON" >&2
  echo "  Container reason:   $CONTAINER_REASON" >&2
  exit 1
fi

echo "Task exit code: $EXIT_CODE"
exit "$EXIT_CODE"
