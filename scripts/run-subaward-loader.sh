#!/usr/bin/env bash
set -euo pipefail

# Build/push the loader image, register a new task-definition revision
# from it, run the Subaward loader (and optionally the Subaward
# attachment plugin) as a one-off Fargate task on the existing
# research-archive-platform-dev-loader ECS task family, wait for it to
# finish, stream its CloudWatch logs, and exit with the container's
# own exit code.
#
# Mirrors scripts/run-negotiation-loader.sh's orchestration exactly,
# scoped to load_subawards_from_csv.py's family-targeted flags. Subaward
# is versioned (SUBAWARD_ID = physical version, SUBAWARD_CODE = family,
# SEQUENCE_NUMBER = version order) - targeting is by family, not by a
# single version, so every load loads every version and all child rows
# for the selected SUBAWARD_CODE(s) together.
#
# This does NOT run automatically - it must be invoked explicitly, and
# every AWS call it makes is a real one (image push, task-definition
# registration, ECS task launch).
#
# Required environment (no safe defaults):
#   ECR_REPOSITORY_URI   - ECR repository URI for the loader image. Not
#                            required if --image-uri is given.
#   SUBNET_IDS            - comma-separated private subnet IDs
#   SECURITY_GROUP_ID      - the loader task's security group ID
#
# Optional environment:
#   AWS_REGION             (default: us-east-1)
#   PROJECT_NAME            (default: research-archive-platform)
#   ENVIRONMENT             (default: dev)
#   CLUSTER_NAME            (default: ${PROJECT_NAME}-${ENVIRONMENT}-etl)
#   TASK_FAMILY             (default: ${PROJECT_NAME}-${ENVIRONMENT}-loader)
#   ATTACHMENT_BUCKET_NAME  (default: ${PROJECT_NAME}-${ENVIRONMENT}-documents-770203350335)
#   POLL_INTERVAL_SECONDS   (default: 15)
#
# Usage:
#   scripts/run-subaward-loader.sh --load-subaward-code CODE [--load-subaward-code CODE ...] [--attachments]
#   scripts/run-subaward-loader.sh --load-subaward-id ID [--attachments]
#   scripts/run-subaward-loader.sh --max-families N [--attachments]
#   scripts/run-subaward-loader.sh --attachments   (alone: every archived Subaward's attachments)
#   scripts/run-subaward-loader.sh ... --image-uri URI
#
# --load-subaward-code CODE: load only the given Subaward family/families
#   (repeatable) - every version and all child rows - via an idempotent
#   per-family UPSERT, one transaction per family. Safe to re-run.
#
# --load-subaward-id ID: load the whole family that physical version ID
#   belongs to - resolved to its SUBAWARD_CODE first.
#
# --max-families N: load the first N families, ascending by
#   SUBAWARD_CODE. Mutually exclusive with the two above.
#
# --attachments: after the main load succeeds, also fetch Subaward
#   attachment metadata from Oracle for the same targeted families,
#   upload any not-yet-archived binaries to S3, and sync the manifest
#   into archive.subaward_attachment_archive - reuses the existing
#   generic attachment plugin/pipeline unchanged
#   (etl/archive_etl/attachments/plugins/subaward.py), never a new
#   storage mechanism.
#
# --image-uri <full-ecr-image-uri>: reuse an already-built-and-pushed
#   image instead of building/pushing a new one.
#
# Examples:
#   scripts/run-subaward-loader.sh --load-subaward-code 94202 --attachments
#   scripts/run-subaward-loader.sh --max-families 25

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

AWS_REGION="${AWS_REGION:-us-east-1}"
PROJECT_NAME="${PROJECT_NAME:-research-archive-platform}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
CLUSTER_NAME="${CLUSTER_NAME:-${PROJECT_NAME}-${ENVIRONMENT}-etl}"
TASK_FAMILY="${TASK_FAMILY:-${PROJECT_NAME}-${ENVIRONMENT}-loader}"
LOG_GROUP="${LOG_GROUP:-/ecs/${PROJECT_NAME}-${ENVIRONMENT}-loader}"
ATTACHMENT_BUCKET_NAME="${ATTACHMENT_BUCKET_NAME:-${PROJECT_NAME}-${ENVIRONMENT}-documents-770203350335}"
CONTAINER_NAME="loader"

: "${SUBNET_IDS:?SUBNET_IDS is not set - comma-separated private subnet IDs for the Fargate task}"
: "${SECURITY_GROUP_ID:?SECURITY_GROUP_ID is not set - the loader task\'s security group ID}"

SUBAWARD_CODES=()
SUBAWARD_IDS=()
MAX_FAMILIES=""
ATTACHMENTS=false
IMAGE_URI_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --load-subaward-code) SUBAWARD_CODES+=("$2"); shift 2 ;;
    --load-subaward-id) SUBAWARD_IDS+=("$2"); shift 2 ;;
    --max-families) MAX_FAMILIES="$2"; shift 2 ;;
    --attachments) ATTACHMENTS=true; shift ;;
    --image-uri) IMAGE_URI_OVERRIDE="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

SELECTORS=0
[[ "${#SUBAWARD_CODES[@]}" -gt 0 ]] && SELECTORS=$((SELECTORS + 1))
[[ "${#SUBAWARD_IDS[@]}" -gt 0 ]] && SELECTORS=$((SELECTORS + 1))
[[ -n "$MAX_FAMILIES" ]] && SELECTORS=$((SELECTORS + 1))

if [[ "$SELECTORS" -eq 0 && "$ATTACHMENTS" == false ]]; then
  echo "ERROR: one of --load-subaward-code, --load-subaward-id, --max-families, or --attachments (alone, for every archived Subaward) is required" >&2
  exit 1
fi

if [[ "$SELECTORS" -gt 1 ]]; then
  echo "ERROR: --load-subaward-code, --load-subaward-id, and --max-families cannot be combined" >&2
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
LOAD_ARGS=(python3 load_subawards_from_csv.py --ecs)
for code in "${SUBAWARD_CODES[@]:-}"; do
  [[ -n "$code" ]] && LOAD_ARGS+=(--load-subaward-code "$code")
done
for id in "${SUBAWARD_IDS[@]:-}"; do
  [[ -n "$id" ]] && LOAD_ARGS+=(--load-subaward-id "$id")
done
[[ -n "$MAX_FAMILIES" ]] && LOAD_ARGS+=(--max-families "$MAX_FAMILIES")

join_shell_quoted() {
  local out=""
  for arg in "$@"; do
    out+=" $(printf '%q' "$arg")"
  done
  echo "$out"
}

if [[ "$ATTACHMENTS" == false ]]; then
  COMMAND_ARGS=("${LOAD_ARGS[@]}")
elif [[ "$SELECTORS" -eq 0 ]]; then
  # --attachments alone: every archived Subaward's attachments, no main
  # metadata load step.
  FETCH_ARGS=(python3 fetch_subaward_attachment_metadata.py --ecs --output /tmp/subaward_attachments.csv)
  UPLOAD_ARGS=(python3 archive_attachments.py --module subaward --ecs --metadata-csv /tmp/subaward_attachments.csv --s3-bucket "$ATTACHMENT_BUCKET_NAME" --s3-prefix subawards)
  SYNC_ARGS=(python3 archive_attachments.py --module subaward --ecs --metadata-csv /tmp/subaward_attachments.csv --sync-postgres)

  # ; (not &&) between upload and sync: the upload step exits non-zero
  # whenever any attachment has a missing/failed blob, which must never
  # prevent syncing the attachments that DID upload successfully (see
  # docs/ATTACHMENT_MODULE_INVENTORY.md's Negotiation section for the
  # incident that established this pattern).
  SHELL_COMMAND="$(join_shell_quoted "${FETCH_ARGS[@]}") &&$(join_shell_quoted "${UPLOAD_ARGS[@]}");$(join_shell_quoted "${SYNC_ARGS[@]}")"
  COMMAND_ARGS=(sh -c "$SHELL_COMMAND")
else
  # One container filesystem across all four steps - the attachment
  # plugin's manifest sqlite3 (written by the upload step, read by
  # --sync-postgres) is ephemeral per-task, so load, fetch, upload, and
  # sync must run as one chained command, not four separate run-task
  # calls.
  FETCH_ARGS=(python3 fetch_subaward_attachment_metadata.py --ecs --output /tmp/subaward_attachments.csv)
  UPLOAD_ARGS=(python3 archive_attachments.py --module subaward --ecs --metadata-csv /tmp/subaward_attachments.csv --s3-bucket "$ATTACHMENT_BUCKET_NAME" --s3-prefix subawards)
  SYNC_ARGS=(python3 archive_attachments.py --module subaward --ecs --metadata-csv /tmp/subaward_attachments.csv --sync-postgres)
  for code in "${SUBAWARD_CODES[@]:-}"; do
    [[ -n "$code" ]] && FETCH_ARGS+=(--subaward-code "$code")
  done
  # --load-subaward-id/--max-families resolve to families inside the
  # Python loader itself; fetch_subaward_attachment_metadata.py only
  # understands --subaward-code, so for those two selectors the
  # attachment fetch intentionally covers every archived Subaward's
  # metadata rather than re-deriving the resolved code list in bash -
  # acceptable for a targeted/proving run, revisit if this becomes the
  # full-population path.

  SHELL_COMMAND="$(join_shell_quoted "${LOAD_ARGS[@]}") &&$(join_shell_quoted "${FETCH_ARGS[@]}") &&$(join_shell_quoted "${UPLOAD_ARGS[@]}");$(join_shell_quoted "${SYNC_ARGS[@]}")"
  COMMAND_ARGS=(sh -c "$SHELL_COMMAND")
fi

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
  echo "ERROR: the task container never reported an exit code - it most likely failed during initialization." >&2
  echo "  Task stoppedReason: $STOPPED_REASON" >&2
  echo "  Container reason:   $CONTAINER_REASON" >&2
  exit 1
fi

echo "Task exit code: $EXIT_CODE"
exit "$EXIT_CODE"
