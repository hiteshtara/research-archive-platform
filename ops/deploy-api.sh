#!/bin/bash
#
# Research Archive API deployment.
#
# SAFETY: the account is never hardcoded. It is resolved fresh from the
# active AWS credentials (`aws sts get-caller-identity`) every run and
# checked against EXPECTED_ACCOUNT_ID before anything mutating happens.
# This exists because an earlier version of this script hardcoded a
# personal AWS account ID - with matching resource names by coincidence,
# it silently built/deployed to the wrong account for an unknown period
# with no error at any step. See docs/architecture/AWARD_IMPLEMENTATION_ROADMAP.md's
# "Eleventh same-day follow-up" and the follow-up entry documenting this
# fix for the full incident writeup.
#
# To deploy to a different BU environment (not dev), set
# EXPECTED_ACCOUNT_ID explicitly in the environment - this is the one
# documented override:
#
#   EXPECTED_ACCOUNT_ID=123456789012 ops/deploy-api.sh
#
# Modes:
#   ops/deploy-api.sh              build, push, deploy, wait for stability
#   ops/deploy-api.sh --check-only validate identity/region/resources only;
#                                   no build, no push, no deploy

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
API_DIR="$REPO_ROOT/api"

REGION="us-east-1"
EXPECTED_ACCOUNT_ID="${EXPECTED_ACCOUNT_ID:-770203350335}"

ECR_REPOSITORY_NAME="research-archive-platform-dev-api"
ECS_CLUSTER="research-archive-platform-dev-api"
ECS_SERVICE="research-archive-platform-dev-api"

CHECK_ONLY=false
if [[ "${1:-}" == "--check-only" ]]; then
  CHECK_ONLY=true
fi

echo "========================================"
echo "Research Archive API Deployment"
echo "========================================"

# --- Resolve and print context before anything mutating -------------------

echo ""
echo "Resolving AWS identity..."

if ! CALLER_IDENTITY_JSON="$(aws sts get-caller-identity --output json 2>&1)"; then
  echo "ERROR: Could not resolve AWS identity (aws sts get-caller-identity failed)." >&2
  echo "$CALLER_IDENTITY_JSON" >&2
  echo "Check AWS_PROFILE / credentials before retrying." >&2
  exit 1
fi

ACCOUNT_ID="$(echo "$CALLER_IDENTITY_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Account"])')"
CALLER_ARN="$(echo "$CALLER_IDENTITY_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Arn"])')"

CONFIGURED_REGION="$(aws configure get region 2>/dev/null || true)"
CONFIGURED_REGION="${CONFIGURED_REGION:-${AWS_REGION:-${AWS_DEFAULT_REGION:-}}}"

GIT_SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
IMAGE_TAG="$(date -u +%Y%m%dT%H%M%SZ)-${GIT_SHA}"

ECR_REPOSITORY_URI="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPOSITORY_NAME}"

echo ""
echo "Resolved deployment context:"
echo "  Account (resolved):   $ACCOUNT_ID"
echo "  Account (expected):   $EXPECTED_ACCOUNT_ID"
echo "  Caller ARN:           $CALLER_ARN"
echo "  Region (target):      $REGION"
echo "  Region (configured):  ${CONFIGURED_REGION:-<none>}"
echo "  ECR repository:       $ECR_REPOSITORY_URI"
echo "  ECS cluster:          $ECS_CLUSTER"
echo "  ECS service:          $ECS_SERVICE"
echo "  Image tag:            $IMAGE_TAG"
echo "  Mode:                 $([[ "$CHECK_ONLY" == true ]] && echo "check-only (no deploy)" || echo "deploy")"

# --- Abort before anything mutating if the context is wrong --------------

ABORT=false

if [[ "$ACCOUNT_ID" != "$EXPECTED_ACCOUNT_ID" ]]; then
  echo ""
  echo "ERROR: Resolved account ($ACCOUNT_ID) does not match the expected" >&2
  echo "BU account ($EXPECTED_ACCOUNT_ID)." >&2
  echo "If this is intentional (a different environment), set" >&2
  echo "EXPECTED_ACCOUNT_ID explicitly - see this script's header comment." >&2
  ABORT=true
fi

if [[ -n "$CONFIGURED_REGION" && "$CONFIGURED_REGION" != "$REGION" ]]; then
  echo ""
  echo "ERROR: Configured AWS region ($CONFIGURED_REGION) does not match" >&2
  echo "the expected region ($REGION)." >&2
  ABORT=true
fi

echo ""
echo "Checking required ECR/ECS resources exist..."

if ! aws ecr describe-repositories \
    --region "$REGION" \
    --repository-names "$ECR_REPOSITORY_NAME" \
    >/dev/null 2>&1; then
  echo "ERROR: ECR repository not found: $ECR_REPOSITORY_NAME (account $ACCOUNT_ID, region $REGION)." >&2
  ABORT=true
fi

CLUSTER_STATUS="$(aws ecs describe-clusters \
  --region "$REGION" \
  --clusters "$ECS_CLUSTER" \
  --query 'clusters[0].status' \
  --output text 2>/dev/null || echo "MISSING")"

if [[ "$CLUSTER_STATUS" != "ACTIVE" ]]; then
  echo "ERROR: ECS cluster not ACTIVE (status: $CLUSTER_STATUS): $ECS_CLUSTER" >&2
  ABORT=true
fi

SERVICE_STATUS="$(aws ecs describe-services \
  --region "$REGION" \
  --cluster "$ECS_CLUSTER" \
  --services "$ECS_SERVICE" \
  --query 'services[0].status' \
  --output text 2>/dev/null || echo "MISSING")"

if [[ "$SERVICE_STATUS" != "ACTIVE" ]]; then
  echo "ERROR: ECS service not ACTIVE (status: $SERVICE_STATUS): $ECS_SERVICE" >&2
  ABORT=true
fi

if [[ "$ABORT" == true ]]; then
  echo ""
  echo "Aborting before any Docker login, push, task registration, or" >&2
  echo "service update. Nothing was changed." >&2
  exit 1
fi

echo "All required resources present and account/region confirmed."

if [[ "$CHECK_ONLY" == true ]]; then
  echo ""
  echo "--check-only: validation complete, no build/push/deploy performed."
  exit 0
fi

# --- Build ------------------------------------------------------------

echo ""
echo "Building Spring Boot application..."

(cd "$API_DIR" && mvn clean package -DskipTests)

echo ""
echo "Building Docker image..."

docker build \
  --platform linux/amd64 \
  -t "${ECR_REPOSITORY_NAME}:${IMAGE_TAG}" \
  "$API_DIR"

# --- Push (immutable tag, plus :latest for convenience) ----------------

echo ""
echo "Logging into Amazon ECR..."

aws ecr get-login-password \
  --region "$REGION" \
| docker login \
    --username AWS \
    --password-stdin \
    "${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo ""
echo "Tagging and pushing $IMAGE_TAG..."

docker tag \
  "${ECR_REPOSITORY_NAME}:${IMAGE_TAG}" \
  "${ECR_REPOSITORY_URI}:${IMAGE_TAG}"

docker push "${ECR_REPOSITORY_URI}:${IMAGE_TAG}"

docker tag \
  "${ECR_REPOSITORY_NAME}:${IMAGE_TAG}" \
  "${ECR_REPOSITORY_URI}:latest"

docker push "${ECR_REPOSITORY_URI}:latest"

echo ""
echo "Verifying $IMAGE_TAG exists in ECR..."

if ! aws ecr describe-images \
    --region "$REGION" \
    --repository-name "$ECR_REPOSITORY_NAME" \
    --image-ids imageTag="$IMAGE_TAG" \
    >/dev/null 2>&1; then
  echo "ERROR: Pushed tag $IMAGE_TAG not found in ECR after push." >&2
  exit 1
fi

echo "Confirmed: $IMAGE_TAG is present in $ECR_REPOSITORY_NAME."

# --- Register a new, immutable task definition revision ----------------

echo ""
echo "Registering a new task definition revision using $IMAGE_TAG..."

CURRENT_TASK_DEFINITION_ARN="$(aws ecs describe-services \
  --region "$REGION" \
  --cluster "$ECS_CLUSTER" \
  --services "$ECS_SERVICE" \
  --query 'services[0].taskDefinition' \
  --output text)"

NEW_TASK_DEFINITION_JSON="$(aws ecs describe-task-definition \
  --region "$REGION" \
  --task-definition "$CURRENT_TASK_DEFINITION_ARN" \
  --query 'taskDefinition' \
  --output json \
| python3 -c "
import json, sys

task_def = json.load(sys.stdin)
task_def['containerDefinitions'][0]['image'] = '${ECR_REPOSITORY_URI}:${IMAGE_TAG}'

for field in (
    'taskDefinitionArn', 'revision', 'status', 'requiresAttributes',
    'compatibilities', 'registeredAt', 'registeredBy', 'tags',
    'deregisteredAt',
):
    task_def.pop(field, None)

print(json.dumps(task_def))
")"

NEW_TASK_DEFINITION_ARN="$(aws ecs register-task-definition \
  --region "$REGION" \
  --cli-input-json "$NEW_TASK_DEFINITION_JSON" \
  --query 'taskDefinition.taskDefinitionArn' \
  --output text)"

echo "Registered: $NEW_TASK_DEFINITION_ARN"

# --- Deploy --------------------------------------------------------------

echo ""
echo "Updating ECS service to the new task definition..."

aws ecs update-service \
  --region "$REGION" \
  --cluster "$ECS_CLUSTER" \
  --service "$ECS_SERVICE" \
  --task-definition "$NEW_TASK_DEFINITION_ARN" \
  >/dev/null

echo ""
echo "Waiting for the service to stabilize..."

if ! aws ecs wait services-stable \
    --region "$REGION" \
    --cluster "$ECS_CLUSTER" \
    --services "$ECS_SERVICE"; then
  echo "" >&2
  echo "ERROR: Service did not stabilize. Recent events and stopped-task" >&2
  echo "reasons follow." >&2

  echo ""
  echo "Recent service events:"
  aws ecs describe-services \
    --region "$REGION" \
    --cluster "$ECS_CLUSTER" \
    --services "$ECS_SERVICE" \
    --query 'services[0].events[0:10].[createdAt,message]' \
    --output table

  echo ""
  echo "Recently stopped tasks:"
  STOPPED_TASKS="$(aws ecs list-tasks \
    --region "$REGION" \
    --cluster "$ECS_CLUSTER" \
    --service-name "$ECS_SERVICE" \
    --desired-status STOPPED \
    --query 'taskArns' \
    --output text)"

  if [[ -n "$STOPPED_TASKS" ]]; then
    read -r -a STOPPED_TASK_ARRAY <<< "$STOPPED_TASKS"
    aws ecs describe-tasks \
      --region "$REGION" \
      --cluster "$ECS_CLUSTER" \
      --tasks "${STOPPED_TASK_ARRAY[@]}" \
      --query 'tasks[*].[taskArn,stoppedReason,containers[0].reason]' \
      --output table
  fi

  exit 1
fi

echo ""
echo "========================================"
echo "Deployment Complete!"
echo "========================================"
echo ""
echo "Image tag:      $IMAGE_TAG"
echo "Task definition: $NEW_TASK_DEFINITION_ARN"
echo ""
echo "Current Service Status"

aws ecs describe-services \
  --region "$REGION" \
  --cluster "$ECS_CLUSTER" \
  --services "$ECS_SERVICE" \
  --query 'services[0].deployments[*].[status,runningCount,desiredCount]' \
  --output table
