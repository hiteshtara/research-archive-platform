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
#   ECR_REPOSITORY_URI   - ECR repository URI for the loader image
#   SUBNET_IDS            - comma-separated private subnet IDs for the
#                            Fargate task (same VPC as the loader task's
#                            security group)
#   SECURITY_GROUP_ID      - the loader task's security group ID
#
# Optional environment (sensible defaults matching the current
# Terraform naming convention):
#   AWS_REGION       (default: us-east-1)
#   PROJECT_NAME      (default: research-archive-platform)
#   ENVIRONMENT       (default: dev)
#   CLUSTER_NAME      (default: ${PROJECT_NAME}-${ENVIRONMENT}-etl)
#   TASK_FAMILY       (default: ${PROJECT_NAME}-${ENVIRONMENT}-loader)
#
# Usage:
#   scripts/run-award-attachment-loader.sh [--dry-run] [--upload] \
#       [--limit N] [--file-id N] [--retry-failed] \
#       [--bucket NAME] [--prefix PREFIX]
#
# Examples:
#   # One-file validation, read-only, no PostgreSQL/S3 writes:
#   scripts/run-award-attachment-loader.sh --file-id 9001 --dry-run
#
#   # Batch upload of up to 100 pending/uploading files:
#   scripts/run-award-attachment-loader.sh --upload --limit 100
#
#   # Recovery after an interrupted run - retry FAILED rows too:
#   scripts/run-award-attachment-loader.sh --upload --retry-failed

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

AWS_REGION="${AWS_REGION:-us-east-1}"
PROJECT_NAME="${PROJECT_NAME:-research-archive-platform}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
CLUSTER_NAME="${CLUSTER_NAME:-${PROJECT_NAME}-${ENVIRONMENT}-etl}"
TASK_FAMILY="${TASK_FAMILY:-${PROJECT_NAME}-${ENVIRONMENT}-loader}"
LOG_GROUP="${LOG_GROUP:-/ecs/${PROJECT_NAME}-${ENVIRONMENT}-loader}"
CONTAINER_NAME="loader"

: "${ECR_REPOSITORY_URI:?ECR_REPOSITORY_URI is not set - set it to the loader image's ECR repository URI}"
: "${SUBNET_IDS:?SUBNET_IDS is not set - comma-separated private subnet IDs for the Fargate task}"
: "${SECURITY_GROUP_ID:?SECURITY_GROUP_ID is not set - the loader task's security group ID}"

FILE_ID=""
LIMIT=""
RETRY_FAILED=false
DRY_RUN=false
UPLOAD=false
BUCKET=""
PREFIX=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file-id) FILE_ID="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --retry-failed) RETRY_FAILED=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --upload) UPLOAD=true; shift ;;
    --bucket) BUCKET="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ "$UPLOAD" == true && "$DRY_RUN" == false ]]; then
  echo "=== WARNING: this will perform a REAL S3 upload run ==="
fi

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

echo "=== Registering new task definition revision ==="
CURRENT_TASKDEF="$(aws ecs describe-task-definition \
  --task-definition "$TASK_FAMILY" \
  --region "$AWS_REGION" \
  --query 'taskDefinition')"

NEW_TASKDEF="$(python3 - "$CONTAINER_NAME" "$IMAGE_URI" <<'PYEOF'
import json
import sys

container_name, image_uri = sys.argv[1], sys.argv[2]
taskdef = json.load(sys.stdin)

for field in (
    "taskDefinitionArn",
    "revision",
    "status",
    "requiresAttributes",
    "compatibilities",
    "registeredAt",
    "registeredBy",
):
    taskdef.pop(field, None)

for container in taskdef["containerDefinitions"]:
    if container["name"] == container_name:
        container["image"] = image_uri

print(json.dumps(taskdef))
PYEOF
<<< "$CURRENT_TASKDEF")"

NEW_REVISION_ARN="$(aws ecs register-task-definition \
  --region "$AWS_REGION" \
  --cli-input-json "$NEW_TASKDEF" \
  --query 'taskDefinition.taskDefinitionArn' \
  --output text)"

echo "Registered: $NEW_REVISION_ARN"

echo "=== Building command override ==="
OVERRIDE_ARGS=()
[[ -n "$FILE_ID" ]] && OVERRIDE_ARGS+=(--file-id "$FILE_ID")
[[ -n "$LIMIT" ]] && OVERRIDE_ARGS+=(--limit "$LIMIT")
[[ "$RETRY_FAILED" == true ]] && OVERRIDE_ARGS+=(--retry-failed)
[[ "$DRY_RUN" == true ]] && OVERRIDE_ARGS+=(--dry-run)
[[ "$UPLOAD" == true ]] && OVERRIDE_ARGS+=(--upload)
[[ -n "$BUCKET" ]] && OVERRIDE_ARGS+=(--bucket "$BUCKET")
[[ -n "$PREFIX" ]] && OVERRIDE_ARGS+=(--prefix "$PREFIX")

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
EXIT_CODE="$(aws ecs describe-tasks \
  --cluster "$CLUSTER_NAME" \
  --tasks "$TASK_ARN" \
  --region "$AWS_REGION" \
  --query "tasks[0].containers[?name=='${CONTAINER_NAME}'].exitCode | [0]" \
  --output text)"

if [[ -z "$EXIT_CODE" || "$EXIT_CODE" == "None" ]]; then
  echo "ERROR: could not determine the task's exit code - check CloudWatch logs and the ECS console."
  exit 1
fi

echo "Task exit code: $EXIT_CODE"
exit "$EXIT_CODE"
