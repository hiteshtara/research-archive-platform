#!/usr/bin/env bash
set -euo pipefail

# Build/push the loader image, register a new task-definition revision
# from it, run the Negotiation loader (and optionally the Negotiation
# attachment plugin) as a one-off Fargate task on the existing
# research-archive-platform-dev-loader ECS task family, wait for it to
# finish, stream its CloudWatch logs, and exit with the task container's
# own exit code.
#
# Mirrors scripts/run-proposal-loader.sh's orchestration exactly, scoped
# to load_negotiations_from_csv.py's targeted-load flags. Negotiation has
# no batch framework (--create-batch/--load-batch) by design - see
# docs/kuali-business-rules/Negotiation.md - only a targeted/bounded
# UPSERT load, since row counts (10,775 Negotiations) don't yet justify
# one.
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
#   scripts/run-negotiation-loader.sh --load-negotiation-id ID [--load-negotiation-id ID ...] [--attachments]
#   scripts/run-negotiation-loader.sh --max-negotiations N [--attachments]
#   scripts/run-negotiation-loader.sh ... --image-uri URI
#
# --load-negotiation-id ID: load only the given Negotiation ID(s) (repeatable)
#   via an idempotent per-row UPSERT - safe to re-run.
#
# --max-negotiations N: load the first N Negotiations, ascending by
#   negotiation_id. Mutually exclusive with --load-negotiation-id.
#
# --attachments: after the main load succeeds, also fetch Negotiation
#   Activity attachment metadata from Oracle for the same targeted IDs,
#   upload any not-yet-archived binaries to S3, and sync the manifest
#   into archive.archived_attachment (module_code='NEGOTIATION') - reuses
#   the existing generic attachment plugin/pipeline unchanged
#   (etl/archive_etl/attachments/plugins/negotiation.py), never a new
#   storage mechanism. Requires --load-negotiation-id (attachments are not
#   fetched for an unbounded --max-negotiations run).
#
# --image-uri <full-ecr-image-uri>: reuse an already-built-and-pushed
#   image instead of building/pushing a new one.
#
# Examples:
#   scripts/run-negotiation-loader.sh --load-negotiation-id 355 --attachments
#   scripts/run-negotiation-loader.sh --max-negotiations 25

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

NEGOTIATION_IDS=()
MAX_NEGOTIATIONS=""
LOAD_ALL=false
ATTACHMENTS=false
IMAGE_URI_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --load-negotiation-id) NEGOTIATION_IDS+=("$2"); shift 2 ;;
    --max-negotiations) MAX_NEGOTIATIONS="$2"; shift 2 ;;
    --load-all) LOAD_ALL=true; shift ;;
    --attachments) ATTACHMENTS=true; shift ;;
    --image-uri) IMAGE_URI_OVERRIDE="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

SELECTORS=0
[[ "${#NEGOTIATION_IDS[@]}" -gt 0 ]] && SELECTORS=$((SELECTORS + 1))
[[ -n "$MAX_NEGOTIATIONS" ]] && SELECTORS=$((SELECTORS + 1))
[[ "$LOAD_ALL" == true ]] && SELECTORS=$((SELECTORS + 1))

if [[ "$SELECTORS" -eq 0 && "$ATTACHMENTS" == false ]]; then
  echo "ERROR: one of --load-negotiation-id, --max-negotiations, --load-all, or --attachments (alone, for every archived Negotiation) is required" >&2
  exit 1
fi

if [[ "$SELECTORS" -gt 1 ]]; then
  echo "ERROR: --load-negotiation-id, --max-negotiations, and --load-all cannot be combined" >&2
  exit 1
fi

# --attachments alone (no ID selector) means "every archived Negotiation's
# attachments" - fetch/upload/sync below already default to unfiltered
# when no --negotiation-id is passed.

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
LOAD_ARGS=(python3 load_negotiations_from_csv.py --ecs)
for id in "${NEGOTIATION_IDS[@]:-}"; do
  [[ -n "$id" ]] && LOAD_ARGS+=(--load-negotiation-id "$id")
done
[[ -n "$MAX_NEGOTIATIONS" ]] && LOAD_ARGS+=(--max-negotiations "$MAX_NEGOTIATIONS")
[[ "$LOAD_ALL" == true ]] && LOAD_ARGS+=(--load-all)

if [[ "$ATTACHMENTS" == false ]]; then
  COMMAND_ARGS=("${LOAD_ARGS[@]}")
elif [[ "$SELECTORS" -eq 0 ]]; then
  # --attachments alone: every archived Negotiation's attachments, no
  # main metadata load step.
  FETCH_ARGS=(python3 fetch_negotiation_attachment_metadata.py --ecs --output /tmp/negotiation_attachments.csv)
  UPLOAD_ARGS=(python3 archive_attachments.py --module negotiation --ecs --metadata-csv /tmp/negotiation_attachments.csv --s3-bucket "$ATTACHMENT_BUCKET_NAME" --s3-prefix negotiations)
  SYNC_ARGS=(python3 archive_attachments.py --module negotiation --ecs --metadata-csv /tmp/negotiation_attachments.csv --sync-postgres)

  join_shell_quoted() {
    local out=""
    for arg in "$@"; do
      out+=" $(printf '%q' "$arg")"
    done
    echo "$out"
  }

  # ; (not &&) between upload and sync: the upload step exits non-zero
  # whenever ANY attachment has a missing/failed blob (a real, expected
  # outcome at full-population scale - most NEGOTIATION_ATTACHMENT rows
  # have no matching ATTACHMENT_FILE blob in Oracle), which must never
  # prevent syncing the attachments that DID upload successfully.
  SHELL_COMMAND="$(join_shell_quoted "${FETCH_ARGS[@]}") &&$(join_shell_quoted "${UPLOAD_ARGS[@]}");$(join_shell_quoted "${SYNC_ARGS[@]}")"
  COMMAND_ARGS=(sh -c "$SHELL_COMMAND")
else
  # One container filesystem across all three steps - the attachment
  # plugin's manifest sqlite3 (written by the upload step, read by
  # --sync-postgres) is ephemeral per-task, so fetch, upload, and sync
  # must run as one chained command, not three separate run-task calls.
  FETCH_ARGS=(python3 fetch_negotiation_attachment_metadata.py --ecs --output /tmp/negotiation_attachments.csv)
  UPLOAD_ARGS=(python3 archive_attachments.py --module negotiation --ecs --metadata-csv /tmp/negotiation_attachments.csv --s3-bucket "$ATTACHMENT_BUCKET_NAME" --s3-prefix negotiations)
  SYNC_ARGS=(python3 archive_attachments.py --module negotiation --ecs --metadata-csv /tmp/negotiation_attachments.csv --sync-postgres)
  for id in "${NEGOTIATION_IDS[@]:-}"; do
    [[ -n "$id" ]] && FETCH_ARGS+=(--negotiation-id "$id") && UPLOAD_ARGS+=(--negotiation-id "$id") && SYNC_ARGS+=(--negotiation-id "$id")
  done

  join_shell_quoted() {
    local out=""
    for arg in "$@"; do
      out+=" $(printf '%q' "$arg")"
    done
    echo "$out"
  }

  # ; (not &&) between upload and sync - see the attachments-only branch
  # above for why: a non-zero upload exit (expected whenever any
  # attachment has a missing/failed blob) must never skip syncing the
  # attachments that DID upload successfully.
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
