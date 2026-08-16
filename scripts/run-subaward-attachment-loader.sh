#!/usr/bin/env bash
set -euo pipefail

# Safe, fail-closed detached-ECS launcher for the Subaward stage of
# etl/attachment_orchestrator.py, scoped to specific Subaward Code(s) via
# the --subaward-code pilot-scope feature. Launches exactly ONE detached
# Fargate task on the existing research-archive-platform-dev-loader ECS
# task family (see terraform/modules/ecs/main.tf - this script never
# modifies Terraform or Terraform-managed state), waits for it to finish,
# streams its CloudWatch logs, and returns the task container's own exit
# code.
#
# This does NOT run automatically - it must be invoked explicitly, and
# every AWS call it makes (once validation passes) is a real one. See
# docs/runbooks/attachments/SUBAWARD_ATTACHMENT_ORCHESTRATOR.md before
# running it against a real environment.
#
# --- Fail-closed by design -------------------------------------------
#
# This launcher refuses to guess anything that could silently touch the
# wrong AWS account, the wrong scope of Subaward data, or the wrong
# container image:
#
#   - AWS identity is verified (AWS_PROFILE resolves to the expected BU
#     account) BEFORE any other AWS call - including read-only ones like
#     resolving Terraform outputs.
#   - Exactly one of --dry-run / --run is required. There is no default
#     verb.
#   - Exactly one of a scoped --subaward-code list / an explicit
#     --all-subawards is required. An invocation naming neither is
#     refused outright - there is no implicit "everything" behavior.
#     --all-subawards can never be combined with --subaward-code.
#   - Exactly one of --image-uri / --build-image is required. This
#     script never silently rebuilds an image, and never silently
#     resolves or reuses a "latest"/previous image on its own.
#
# --- Usage --------------------------------------------------------------
#
#   scripts/run-subaward-attachment-loader.sh (--dry-run|--run) \
#       (--subaward-code CODE [--subaward-code CODE ...] | --all-subawards) \
#       (--image-uri URI | --build-image)
#
# --dry-run: forwards --dry-run to attachment_orchestrator.py - a
#   read-only preview (candidate counts, unresolved codes, destination-
#   key shape, and the cross-scope safety check) with no PostgreSQL
#   write, no S3 write, and no Oracle BLOB read. Still requires
#   POSTGRES_SECRET_ID/ORACLE_SECRET_ID (the preview itself reads both
#   Oracle and PostgreSQL - see attachment_orchestrator.plan_subaward_batch).
#
# --run: the real orchestration (metadata load, then S3 upload) for
#   exactly the requested scope.
#
# --subaward-code CODE (repeatable): restrict the run to this Subaward
#   Code - pass multiple times for multiple codes. At least one is
#   required unless --all-subawards is given instead.
#
# --all-subawards: the unmistakable, explicit opt-in for the full,
#   unscoped Subaward population - the ONLY way to invoke
#   attachment_orchestrator.py without any --subaward-code. Cannot be
#   combined with --subaward-code.
#
# --image-uri URI: reuse an already-built-and-pushed image. When given,
#   this script never invokes `docker build`, `docker login`, `aws ecr
#   get-login-password`, or `docker push` - it registers a new task-
#   definition revision directly from the supplied image URI.
#
# --build-image: build/push a fresh image from the current commit before
#   launching. The explicit alternative to --image-uri - never the
#   default.
#
# --- Required environment ------------------------------------------------
#
#   AWS_PROFILE            - REQUIRED, no default. Verified (via `aws
#                             sts get-caller-identity --profile
#                             "$AWS_PROFILE"`) to resolve to account
#                             770203350335 (override via
#                             EXPECTED_ACCOUNT_ID for a non-dev BU
#                             environment) before any other AWS call.
#   POSTGRES_SECRET_ID      - Secrets Manager ARN/name for the PostgreSQL
#                             secret (an identifier, never a credential).
#   ORACLE_SECRET_ID        - Secrets Manager ARN/name for the Oracle
#                             secret. Always required here (unlike
#                             load_award_attachments.py's --migrate-only
#                             exemption) - both --dry-run and --run read
#                             Oracle for the Subaward stage.
#
# --- Bucket / network configuration --------------------------------------
#
# Each of these may be set explicitly; any left unset is resolved from
# this project's own Terraform outputs
# (terraform/environments/dev - already-established, verified project
# configuration, never guessed or hardcoded). If neither an explicit
# value nor a resolvable Terraform output is available, this script
# fails closed rather than falling back to a literal default:
#
#   BUCKET_NAME          (explicit) - else `terraform output -raw documents_bucket_name`
#   SUBNET_IDS             (explicit, comma-separated) - else `terraform output -json private_subnet_ids | jq -r 'join(",")'`
#   SECURITY_GROUP_ID       (explicit) - else `terraform output -raw loader_security_group_id`
#
# --- Optional environment (sensible defaults, same convention as
# scripts/run-award-attachment-loader.sh) --------------------------------
#
#   AWS_REGION          (default: us-east-1)
#   PROJECT_NAME        (default: research-archive-platform)
#   ENVIRONMENT         (default: dev)
#   CLUSTER_NAME        (default: ${PROJECT_NAME}-${ENVIRONMENT}-etl)
#   TASK_FAMILY         (default: ${PROJECT_NAME}-${ENVIRONMENT}-loader)
#   LOG_GROUP           (default: /ecs/${PROJECT_NAME}-${ENVIRONMENT}-loader)
#   EXPECTED_ACCOUNT_ID (default: 770203350335)
#   ECR_REPOSITORY_URI  (only with --build-image; else `terraform output
#                        -raw loader_ecr_repository_url`)
#
# None of POSTGRES_USER, POSTGRES_PASSWORD, ORACLE_USER, ORACLE_PASSWORD,
# or ORACLE_DSN are ever read or passed through by this script - those
# always come from Secrets Manager, resolved by the loader process
# itself at runtime, never from an environment override.
#
# --- Examples -------------------------------------------------------------
#
#   # Scoped, read-only preview of the approved dev pilot fixture (see
#   # the runbook for why 3595 specifically):
#   AWS_PROFILE=bu-nprd POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#     scripts/run-subaward-attachment-loader.sh --dry-run --subaward-code 3595 --image-uri <uri>
#
#   # Real scoped pilot run, multiple codes, fresh image:
#   AWS_PROFILE=bu-nprd POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#     scripts/run-subaward-attachment-loader.sh --run --subaward-code 3595 --subaward-code 3596 --build-image
#
#   # Full population - requires the explicit, separate flag:
#   AWS_PROFILE=bu-nprd POSTGRES_SECRET_ID=arn:...:postgres ORACLE_SECRET_ID=arn:...:oracle \
#     scripts/run-subaward-attachment-loader.sh --run --all-subawards --image-uri <uri>

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT_DIR/terraform/environments/dev"

# Split out (rather than left as top-level script code) so this file can
# be `source`d by a test script that overrides verify_aws_identity/
# resolve_project_configuration/build_and_register_task_definition/
# run_ecs_task and calls parse_and_validate_args/dispatch directly,
# without a real AWS/Docker/Terraform call ever firing - the same
# pattern scripts/run-award-attachment-loader.sh already established
# (see scripts/tests/test-bulk-load-reconciliation.sh). See the
# executed-directly guard at the bottom of this file.
parse_and_validate_args() {
AWS_REGION="${AWS_REGION:-us-east-1}"
PROJECT_NAME="${PROJECT_NAME:-research-archive-platform}"
ENVIRONMENT="${ENVIRONMENT:-dev}"
CLUSTER_NAME="${CLUSTER_NAME:-${PROJECT_NAME}-${ENVIRONMENT}-etl}"
TASK_FAMILY="${TASK_FAMILY:-${PROJECT_NAME}-${ENVIRONMENT}-loader}"
LOG_GROUP="${LOG_GROUP:-/ecs/${PROJECT_NAME}-${ENVIRONMENT}-loader}"
CONTAINER_NAME="loader"
EXPECTED_ACCOUNT_ID="${EXPECTED_ACCOUNT_ID:-770203350335}"

# Secret *identifiers* only - never a credential. Checked here, in pure
# argument/environment validation, before any AWS call at all (not even
# the read-only identity check) - the whole point of failing closed is
# to never depend on reaching a later step to notice a missing one.
: "${POSTGRES_SECRET_ID:?POSTGRES_SECRET_ID is not set - Secrets Manager ARN/name for the PostgreSQL secret (an identifier, never a credential)}"
: "${ORACLE_SECRET_ID:?ORACLE_SECRET_ID is not set - Secrets Manager ARN/name for the Oracle secret (an identifier, never a credential). Required for both --dry-run and --run - the Subaward stage always reads Oracle.}"
: "${AWS_PROFILE:?AWS_PROFILE is not set - this launcher refuses to guess which AWS credentials/account to use}"

OPERATION=""
SUBAWARD_CODES=()
ALL_SUBAWARDS=false
IMAGE_URI_OVERRIDE=""
BUILD_IMAGE=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      if [[ -n "$OPERATION" ]]; then
        echo "ERROR: --dry-run and --run are mutually exclusive" >&2
        exit 1
      fi
      OPERATION="dry-run"; shift ;;
    --run)
      if [[ -n "$OPERATION" ]]; then
        echo "ERROR: --dry-run and --run are mutually exclusive" >&2
        exit 1
      fi
      OPERATION="run"; shift ;;
    --subaward-code) SUBAWARD_CODES+=("$2"); shift 2 ;;
    --all-subawards) ALL_SUBAWARDS=true; shift ;;
    --image-uri) IMAGE_URI_OVERRIDE="$2"; shift 2 ;;
    --build-image) BUILD_IMAGE=true; shift ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$OPERATION" ]]; then
  echo "ERROR: exactly one of --dry-run or --run is required - there is no default verb" >&2
  exit 1
fi

if [[ "${#SUBAWARD_CODES[@]}" -eq 0 && "$ALL_SUBAWARDS" == false ]]; then
  echo "ERROR: at least one --subaward-code is required for a scoped pilot run. To run the full, unscoped population, pass --all-subawards explicitly - an unscoped invocation is refused otherwise." >&2
  exit 1
fi

if [[ "${#SUBAWARD_CODES[@]}" -gt 0 && "$ALL_SUBAWARDS" == true ]]; then
  echo "ERROR: --all-subawards cannot be combined with --subaward-code - choose a scoped pilot or the full population, not both" >&2
  exit 1
fi

if [[ -n "$IMAGE_URI_OVERRIDE" && "$BUILD_IMAGE" == true ]]; then
  echo "ERROR: --image-uri and --build-image are mutually exclusive" >&2
  exit 1
fi

if [[ -z "$IMAGE_URI_OVERRIDE" && "$BUILD_IMAGE" == false ]]; then
  echo "ERROR: exactly one of --image-uri URI or --build-image is required - this launcher never silently rebuilds or selects a 'latest'/previous image" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
}

# --- AWS identity verification: must run before ANY other AWS call
# (including read-only Terraform-output resolution) - see dispatch().
verify_aws_identity() {
  echo "=== Verifying AWS identity (profile: $AWS_PROFILE) ==="
  local caller_identity_json account_id
  if ! caller_identity_json="$(aws sts get-caller-identity --profile "$AWS_PROFILE" --region "$AWS_REGION" --output json 2>&1)"; then
    echo "ERROR: could not resolve AWS identity for AWS_PROFILE '$AWS_PROFILE'" >&2
    echo "$caller_identity_json" >&2
    exit 1
  fi

  account_id="$(echo "$caller_identity_json" | python3 -c 'import json, sys; print(json.load(sys.stdin)["Account"])')"
  echo "Resolved account: $account_id (expected: $EXPECTED_ACCOUNT_ID)"

  if [[ "$account_id" != "$EXPECTED_ACCOUNT_ID" ]]; then
    echo "ERROR: AWS_PROFILE '$AWS_PROFILE' resolves to account '$account_id', not the expected BU account '$EXPECTED_ACCOUNT_ID'. Refusing to proceed. If this is intentional (a different BU environment), set EXPECTED_ACCOUNT_ID explicitly." >&2
    exit 1
  fi
}

# --- Bucket/subnet/security-group resolution: explicit env var first,
# else this project's own verified Terraform outputs. Fails closed (no
# literal fallback) if neither resolves - unlike
# scripts/dev-deploy.sh's resolve_terraform_output helper, which is
# deliberately used only for a low-stakes value (an Amplify app id) with
# a documented literal fallback; a Fargate task's network placement and
# upload destination are not that.
resolve_required_terraform_output() {
  local name="$1" label="$2" value
  if ! value="$(cd "$TF_DIR" && terraform output -raw "$name" 2>/dev/null)" || [[ -z "$value" ]]; then
    echo "ERROR: could not resolve $label - set it explicitly, or ensure 'terraform output $name' works from $TF_DIR" >&2
    exit 1
  fi
  echo "$value"
}

resolve_required_terraform_output_list_joined() {
  local name="$1" label="$2" value
  if ! value="$(cd "$TF_DIR" && terraform output -json "$name" 2>/dev/null | jq -r 'join(",")' 2>/dev/null)" || [[ -z "$value" ]]; then
    echo "ERROR: could not resolve $label - set it explicitly, or ensure 'terraform output $name' works from $TF_DIR" >&2
    exit 1
  fi
  echo "$value"
}

resolve_project_configuration() {
  if [[ -n "${BUCKET_NAME:-}" ]]; then
    BUCKET="$BUCKET_NAME"
  else
    BUCKET="$(resolve_required_terraform_output documents_bucket_name "the documents bucket name (set BUCKET_NAME, or ensure 'terraform output documents_bucket_name' works)")"
  fi

  if [[ -n "${SUBNET_IDS:-}" ]]; then
    : # explicit override, used as-is
  else
    SUBNET_IDS="$(resolve_required_terraform_output_list_joined private_subnet_ids "the Fargate task subnet IDs (set SUBNET_IDS, or ensure 'terraform output private_subnet_ids' works)")"
  fi

  if [[ -n "${SECURITY_GROUP_ID:-}" ]]; then
    : # explicit override, used as-is
  else
    SECURITY_GROUP_ID="$(resolve_required_terraform_output loader_security_group_id "the loader task security group (set SECURITY_GROUP_ID, or ensure 'terraform output loader_security_group_id' works)")"
  fi

  echo "Resolved bucket:          $BUCKET"
  echo "Resolved subnet IDs:      $SUBNET_IDS"
  echo "Resolved security group:  $SECURITY_GROUP_ID"
}

# --- Command construction: a bash ARRAY the whole way through - never a
# concatenated/interpolated string - then handed to jq's $ARGS.positional
# to become a JSON array. jq owns all quoting/escaping; no element is
# ever pasted into a shell command line or a JSON string by hand. This is
# what guarantees every repeated --subaward-code token survives exactly
# once, in order: each is its own, independent bash array element from
# the moment it's read off argv to the moment jq serializes it.
build_command_array() {
  COMMAND_ARRAY=(python attachment_orchestrator.py --bucket "$BUCKET" --modules subaward --ecs)

  if [[ "$OPERATION" == "dry-run" ]]; then
    COMMAND_ARRAY+=(--dry-run)
  fi

  if [[ "$ALL_SUBAWARDS" == true ]]; then
    : # Deliberately no --subaward-code at all - attachment_orchestrator.py's
      # own absent-flag behavior is the full, unscoped population;
      # --all-subawards is the explicit, unmistakable gate that allows
      # this launcher to omit it. Never invoked without having passed
      # through this gate or the --subaward-code branch below - there is
      # no third path.
  else
    local code
    for code in "${SUBAWARD_CODES[@]}"; do
      COMMAND_ARRAY+=(--subaward-code "$code")
    done
  fi
}

build_overrides_json() {
  local command_json environment_json
  command_json="$(jq -n '$ARGS.positional' --args -- "${COMMAND_ARRAY[@]}")"

  # Non-secret configuration only - identifiers, never a credential
  # value (see module docstring / archive_etl/config/ecs.py).
  environment_json="$(jq -n \
    --arg postgres_secret_id "$POSTGRES_SECRET_ID" \
    --arg oracle_secret_id "$ORACLE_SECRET_ID" \
    '[{name: "POSTGRES_SECRET_ID", value: $postgres_secret_id},
      {name: "ORACLE_SECRET_ID", value: $oracle_secret_id}]')"

  OVERRIDES_JSON="$(jq -n \
    --arg name "$CONTAINER_NAME" \
    --argjson command "$command_json" \
    --argjson environment "$environment_json" \
    '{containerOverrides: [{name: $name, command: $command, environment: $environment}]}')"
}

print_launch_plan() {
  echo ""
  echo "=== Launch plan ==="
  echo "Operation:  $OPERATION"
  echo "Scope:      $([[ "$ALL_SUBAWARDS" == true ]] && echo "ALL SUBAWARDS (unscoped)" || echo "${SUBAWARD_CODES[*]}")"
  echo "Image:      $IMAGE_URI"
  echo "Cluster:    $CLUSTER_NAME"
  echo "Task family: $TASK_FAMILY"
  echo "Command:    ${COMMAND_ARRAY[*]}"
  echo "containerOverrides.command (JSON): $(echo "$OVERRIDES_JSON" | jq -c '.containerOverrides[0].command')"
  # POSTGRES_SECRET_ID/ORACLE_SECRET_ID are Secrets Manager *identifiers*
  # (an ARN or name), never a credential - the container resolves the
  # actual username/password/dsn itself, at runtime, from Secrets
  # Manager. Nothing that resolves to a password, a DSN, or a secret's
  # JSON content is ever printed, read, or forwarded by this script.
  echo "(POSTGRES_SECRET_ID/ORACLE_SECRET_ID above are identifiers only - never credentials.)"
}

build_and_register_task_definition() {
  if [[ -n "$IMAGE_URI_OVERRIDE" ]]; then
    echo "=== Reusing already-pushed image (--image-uri): $IMAGE_URI_OVERRIDE ==="
    IMAGE_URI="$IMAGE_URI_OVERRIDE"
  else
    echo "=== Building loader image (--build-image) ==="
    local git_sha image_tag repository_uri
    git_sha="$(git -C "$ROOT_DIR" rev-parse --short HEAD)"
    image_tag="$(date -u +%Y%m%dT%H%M%SZ)-${git_sha}"
    if [[ -n "${ECR_REPOSITORY_URI:-}" ]]; then
      repository_uri="$ECR_REPOSITORY_URI"
    else
      repository_uri="$(resolve_required_terraform_output loader_ecr_repository_url "the loader ECR repository URI (set ECR_REPOSITORY_URI, or ensure 'terraform output loader_ecr_repository_url' works)")"
    fi
    IMAGE_URI="${repository_uri}:${image_tag}"

    docker build \
      --platform linux/amd64 \
      -t "$IMAGE_URI" \
      -f "$ROOT_DIR/etl/Dockerfile.loader" \
      "$ROOT_DIR"

    echo "=== Pushing image to ECR ==="
    aws ecr get-login-password --region "$AWS_REGION" --profile "$AWS_PROFILE" \
      | docker login --username AWS --password-stdin "${repository_uri%%/*}"
    docker push "$IMAGE_URI"
  fi

  echo "=== Registering new task definition revision ==="
  local current_taskdef_file new_taskdef_file
  current_taskdef_file="$TMP_DIR/current-taskdef.json"
  new_taskdef_file="$TMP_DIR/new-taskdef.json"

  aws ecs describe-task-definition \
    --task-definition "$TASK_FAMILY" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE" \
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
    --profile "$AWS_PROFILE" \
    --cli-input-json "file://${new_taskdef_file}" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)"

  echo "Registered: $NEW_REVISION_ARN"
}

# --- Launch exactly one detached Fargate task, wait, stream logs, and
# return the container's own exit code. Mirrors
# scripts/run-award-attachment-loader.sh's run_ecs_task, without its
# --bulk-load/state-file machinery (this launcher is single-shot only).
run_ecs_task() {
  echo "=== Launching detached ECS Fargate task ==="
  local task_run_output task_arn task_id log_stream task_describe_file exit_code

  task_run_output="$(aws ecs run-task \
    --cluster "$CLUSTER_NAME" \
    --task-definition "$NEW_REVISION_ARN" \
    --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_IDS],securityGroups=[$SECURITY_GROUP_ID],assignPublicIp=DISABLED}" \
    --overrides "$OVERRIDES_JSON" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE")"

  task_arn="$(echo "$task_run_output" | python3 -c 'import json, sys; print(json.load(sys.stdin)["tasks"][0]["taskArn"])')"
  echo "Task started (detached): $task_arn"

  echo "=== Waiting for task completion (this can take a few minutes) ==="
  aws ecs wait tasks-stopped \
    --cluster "$CLUSTER_NAME" \
    --tasks "$task_arn" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE"

  task_id="${task_arn##*/}"
  log_stream="loader/${CONTAINER_NAME}/${task_id}"

  echo "=== CloudWatch logs (${LOG_GROUP} / ${log_stream}) ==="
  aws logs tail "$LOG_GROUP" \
    --log-stream-names "$log_stream" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE" \
    --since 1h \
    || echo "WARNING: could not tail logs - check the CloudWatch console directly."

  echo "=== Checking task exit code ==="
  task_describe_file="$TMP_DIR/task-describe-${task_id}.json"
  aws ecs describe-tasks \
    --cluster "$CLUSTER_NAME" \
    --tasks "$task_arn" \
    --region "$AWS_REGION" \
    --profile "$AWS_PROFILE" \
    --output json \
    > "$task_describe_file"

  exit_code="$(jq -r --arg name "$CONTAINER_NAME" \
    '.tasks[0].containers[] | select(.name == $name) | .exitCode // empty' \
    "$task_describe_file")"

  if [[ -z "$exit_code" ]]; then
    local stopped_reason container_reason
    stopped_reason="$(jq -r '.tasks[0].stoppedReason // "(none reported)"' "$task_describe_file")"
    container_reason="$(jq -r --arg name "$CONTAINER_NAME" \
      '.tasks[0].containers[] | select(.name == $name) | .reason // "(none reported)"' \
      "$task_describe_file")"
    echo "ERROR: the task container never reported an exit code - it most likely failed during initialization, before attachment_orchestrator.py ever ran." >&2
    echo "  Task stoppedReason: $stopped_reason" >&2
    echo "  Container reason:   $container_reason" >&2
    TASK_EXIT_CODE=1
    return
  fi

  echo "Task exit code: $exit_code"
  TASK_EXIT_CODE="$exit_code"
}

# Only actually parse argv and run when this file is executed directly
# (bash scripts/run-subaward-attachment-loader.sh ...) - never when it
# is `source`d, e.g. by a test script that wants the function
# definitions above without argv parsing, its env-var requirements, or
# a real AWS/Docker/Terraform call ever firing.
dispatch() {
  # Order matters: identity verification first, before ANY other AWS
  # call (including read-only Terraform-output resolution below) - and
  # every read-only validation step (identity, config resolution,
  # command construction) completes before build_and_register_task_definition
  # or run_ecs_task - the only two functions that ever mutate anything.
  verify_aws_identity
  resolve_project_configuration
  build_command_array
  build_overrides_json

  build_and_register_task_definition
  print_launch_plan

  run_ecs_task
  exit "$TASK_EXIT_CODE"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  parse_and_validate_args "$@"
  dispatch
fi
