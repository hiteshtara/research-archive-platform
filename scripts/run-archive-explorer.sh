#!/usr/bin/env bash
set -euo pipefail

# Read-only Archive Explorer, run through the existing Award loader ECS
# task (research-archive-platform-dev-loader) - same image, same task
# role, same PostgreSQL secret, same VPC networking as
# scripts/run-award-loader.sh. Never touches Oracle - the explorer only
# queries already-archived PostgreSQL data using fixed, predefined SQL
# (see etl/archive_etl/explorer.py). Phase 1 only: a command-line tool,
# not a web UI/API - see docs/ARCHIVE_EXPLORER.md.
#
# Usage:
#   scripts/run-archive-explorer.sh <resource> [resource flags...] [--output table|json]
#
# Examples:
#   scripts/run-archive-explorer.sh award --award-number 100012-00002
#   scripts/run-archive-explorer.sh unit --unit-number 1203250000
#   scripts/run-archive-explorer.sh workflow --document-number 328797
#   scripts/run-archive-explorer.sh award-contacts --award-id 1135067
#   scripts/run-archive-explorer.sh unit --unit-number 1203250000 --output json
#
# Required environment (no safe defaults):
#   ECR_REPOSITORY_URI   - ECR repository URI for the loader image (not
#                            required if --image-uri is given)
#   SUBNET_IDS            - comma-separated private subnet IDs
#   SECURITY_GROUP_ID      - the loader task's security group ID
#   POSTGRES_SECRET_ID      - Secrets Manager ARN/name for the
#                              PostgreSQL secret
#
# Optional environment:
#   AWS_REGION (default: us-east-1), PROJECT_NAME (default:
#   research-archive-platform), ENVIRONMENT (default: dev),
#   POLL_INTERVAL_SECONDS (default: 15)
#
# --image-uri <uri>: reuse an already-pushed image instead of
#   build/push - never invokes docker in that case.
#
# Never accepts arbitrary SQL - every resource maps to a fixed query in
# etl/archive_etl/explorer.py. ORACLE_SECRET_ID is never required or
# read by this script.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

AWS_REGION="${AWS_REGION:-us-east-1}"
PROJECT_NAME="${PROJECT_NAME:-research-archive-platform}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
CLUSTER_NAME="${CLUSTER_NAME:-${PROJECT_NAME}-${ENVIRONMENT}-etl}"
TASK_FAMILY="${TASK_FAMILY:-${PROJECT_NAME}-${ENVIRONMENT}-loader}"
LOG_GROUP="${LOG_GROUP:-/ecs/${PROJECT_NAME}-${ENVIRONMENT}-loader}"
CONTAINER_NAME="loader"
EXPECTED_ACCOUNT_ID="${EXPECTED_ACCOUNT_ID:-770203350335}"

if [[ $# -lt 1 ]]; then
  echo "ERROR: a resource is required, e.g. 'award', 'unit', 'workflow', 'award-contacts'" >&2
  exit 1
fi

IMAGE_URI_OVERRIDE=""
EXPLORE_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --image-uri) IMAGE_URI_OVERRIDE="$2"; shift 2 ;;
    *) EXPLORE_ARGS+=("$1"); shift ;;
  esac
done

: "${SUBNET_IDS:?SUBNET_IDS is not set - comma-separated private subnet IDs for the Fargate task}"
: "${SECURITY_GROUP_ID:?SECURITY_GROUP_ID is not set - the loader task\'s security group ID}"
: "${POSTGRES_SECRET_ID:?POSTGRES_SECRET_ID is not set - Secrets Manager ARN/name for the PostgreSQL secret (an identifier, never a credential)}"
if [[ -z "$IMAGE_URI_OVERRIDE" ]]; then
  : "${ECR_REPOSITORY_URI:?ECR_REPOSITORY_URI is not set - set it to the loader image\'s ECR repository URI, or pass --image-uri to reuse an already-pushed image}"
fi

echo "=== Verifying AWS account ==="
ACTUAL_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text --region "$AWS_REGION")"
if [[ "$ACTUAL_ACCOUNT_ID" != "$EXPECTED_ACCOUNT_ID" ]]; then
  echo "ERROR: current AWS account is '$ACTUAL_ACCOUNT_ID', expected '$EXPECTED_ACCOUNT_ID' - refusing to proceed" >&2
  exit 1
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

(
  cd "$ROOT_DIR/etl" \
    && uv run python scripts/transform_loader_task_definition.py \
         --input "$CURRENT_TASKDEF_FILE" \
         --output "$NEW_TASKDEF_FILE" \
         --container-name "$CONTAINER_NAME" \
         --image-uri "$IMAGE_URI" \
         --family "$TASK_FAMILY"
)

NEW_REVISION_ARN="$(aws ecs register-task-definition \
  --region "$AWS_REGION" \
  --cli-input-json "file://${NEW_TASKDEF_FILE}" \
  --query 'taskDefinition.taskDefinitionArn' \
  --output text)"

echo "Registered: $NEW_REVISION_ARN"

echo "=== Building command + environment override ==="
# Non-secret configuration only - identifiers, never a password/DSN.
OVERRIDES_JSON="$(python3 -c '
import json, sys
command = ["python", "-m", "archive_etl", "explore", *sys.argv[1:]]
overrides = {
    "containerOverrides": [{
        "name": "'"$CONTAINER_NAME"'",
        "command": command,
        "environment": [
            {"name": "POSTGRES_SECRET_ID", "value": "'"$POSTGRES_SECRET_ID"'"},
            {"name": "AWS_REGION", "value": "'"$AWS_REGION"'"},
        ],
    }]
}
print(json.dumps(overrides))
' "${EXPLORE_ARGS[@]}")"
echo "Command: python -m archive_etl explore ${EXPLORE_ARGS[*]}"

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
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-15}"
echo "=== Waiting for task completion (polling every ${POLL_INTERVAL_SECONDS}s) ==="

while true; do
  aws ecs describe-tasks \
    --cluster "$CLUSTER_NAME" \
    --tasks "$TASK_ARN" \
    --region "$AWS_REGION" \
    --output json \
    > "$TASK_DESCRIBE_FILE"

  LAST_STATUS="$(jq -r '.tasks[0].lastStatus // empty' "$TASK_DESCRIBE_FILE")"

  if [[ -z "$LAST_STATUS" ]]; then
    echo "ERROR: aws ecs describe-tasks returned no task for $TASK_ARN" >&2
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

echo "=== Result (${LOG_GROUP} / ${LOG_STREAM}) ==="
aws logs tail "$LOG_GROUP" \
  --log-stream-names "$LOG_STREAM" \
  --region "$AWS_REGION" \
  --since 1h || echo "WARNING: could not tail logs - check the CloudWatch console directly."

EXIT_CODE="$(jq -r --arg name "$CONTAINER_NAME" \
  '.tasks[0].containers[] | select(.name == $name) | .exitCode // empty' \
  "$TASK_DESCRIBE_FILE")"

echo "=== Exit code: ${EXIT_CODE:-unknown} ==="
if [[ -n "$EXIT_CODE" && "$EXIT_CODE" != "0" ]]; then
  exit "$EXIT_CODE"
fi
