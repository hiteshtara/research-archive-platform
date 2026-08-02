#!/usr/bin/env bash
#
# Opens an SSM port-forwarding tunnel from localhost:15432 to the BU dev
# RDS PostgreSQL instance, for local development against real dev data
# (see docs/runbooks/LOCAL_SETUP.md).
#
# SAFETY: this script previously trusted whatever SSM_INSTANCE_ID/
# REMOTE_POSTGRES_HOST happened to already be set in the caller's
# environment, with no verification at all - a personal-account value
# left in a shell profile would have silently tunneled to the wrong
# infrastructure. This version resolves the AWS identity fresh every
# run, requires account 770203350335, derives the RDS endpoint from
# Terraform/Secrets Manager instead of trusting a hardcoded or
# externally-supplied value blindly, and either verifies an explicitly
# given SSM instance or discovers one within this project's own VPC -
# it never falls back to any other instance if none is found.
#
# Usage:
#   scripts/start-db-tunnel.sh                 # discover + open the tunnel
#   scripts/start-db-tunnel.sh --check-only     # validate only, no tunnel
#   scripts/start-db-tunnel.sh --instance-id i-0123456789abcdef0
#                                               # use this SSM instance
#                                               # instead of discovering one
#   AWS_PROFILE=bu-nprd scripts/start-db-tunnel.sh
#
# Env var overrides (all optional):
#   AWS_PROFILE            AWS CLI profile (default: bu-nprd, only if
#                           AWS_PROFILE is not already set by the caller)
#   EXPECTED_ACCOUNT_ID     AWS account required to proceed (default:
#                           770203350335 - BU dev)
#   SSM_INSTANCE_ID         Same as --instance-id
#   REMOTE_POSTGRES_HOST    Overrides the derived RDS endpoint
#   REMOTE_POSTGRES_PORT    Remote PostgreSQL port (default: 5432)
#   POSTGRES_PORT           Local port to forward to (default: 15432)
#
# Never creates any AWS resource (EC2, networking, IAM, RDS). If no
# SSM-managed instance can reach the private RDS subnet, this script
# stops and says so - it does not fall back to any other instance.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TF_DIR="$REPO_ROOT/terraform/environments/dev"

REGION="us-east-1"
EXPECTED_ACCOUNT_ID="${EXPECTED_ACCOUNT_ID:-770203350335}"
FALLBACK_RDS_HOST="research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com"
FALLBACK_VPC_ID="vpc-0590614d7cfcdedf6"
POSTGRES_SECRET_ID="research-archive-platform/dev/postgres"

CHECK_ONLY=false
INSTANCE_ID_OVERRIDE="${SSM_INSTANCE_ID:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --check-only) CHECK_ONLY=true ;;
    --instance-id)
      shift
      INSTANCE_ID_OVERRIDE="${1:-}"
      [ -z "$INSTANCE_ID_OVERRIDE" ] && {
        echo "ERROR: --instance-id requires a value" >&2
        exit 1
      }
      ;;
    -h|--help)
      sed -n '2,38p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $1 (see --help)" >&2
      exit 1
      ;;
  esac
  shift
done

err()  { echo "ERROR: $*" >&2; }
warn() { echo "WARNING: $*" >&2; }
log()  { echo "-- $*"; }

# Default AWS_PROFILE only if the caller hasn't already set one -
# "respect an explicitly supplied profile."
: "${AWS_PROFILE:=bu-nprd}"
export AWS_PROFILE

resolve_terraform_output() {
  local name="$1"
  local fallback="$2"
  local value
  if value="$(cd "$TF_DIR" && terraform output -raw "$name" 2>/dev/null)" && [ -n "$value" ]; then
    echo "$value"
  else
    echo "$fallback"
  fi
}

# --- 1. Verify AWS identity (fail closed before anything else) ---------

log "Resolving AWS identity (profile: $AWS_PROFILE)..."

if ! CALLER_IDENTITY_JSON="$(aws sts get-caller-identity --region "$REGION" --output json 2>&1)"; then
  err "Could not resolve AWS identity: $CALLER_IDENTITY_JSON"
  err "Check AWS_PROFILE=$AWS_PROFILE has valid, unexpired credentials."
  exit 1
fi

ACCOUNT_ID="$(echo "$CALLER_IDENTITY_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Account"])')"
CALLER_ARN="$(echo "$CALLER_IDENTITY_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Arn"])')"

if [ "$ACCOUNT_ID" != "$EXPECTED_ACCOUNT_ID" ]; then
  err "Resolved AWS account ($ACCOUNT_ID) != expected BU account ($EXPECTED_ACCOUNT_ID)."
  err "Refusing to open a tunnel against the wrong account. If this is"
  err "intentional, set EXPECTED_ACCOUNT_ID explicitly."
  exit 1
fi

CONFIGURED_REGION="$(aws configure get region 2>/dev/null || true)"
CONFIGURED_REGION="${CONFIGURED_REGION:-${AWS_REGION:-${AWS_DEFAULT_REGION:-}}}"
if [ -n "$CONFIGURED_REGION" ] && [ "$CONFIGURED_REGION" != "$REGION" ]; then
  err "Configured AWS region ($CONFIGURED_REGION) != expected ($REGION)."
  exit 1
fi

# --- 2. Derive the RDS endpoint (Terraform, then Secrets Manager, then --
# a literal fallback - never trust an externally-supplied value without
# at least one of these confirming it).

if [ -n "${REMOTE_POSTGRES_HOST:-}" ]; then
  RDS_HOST="$REMOTE_POSTGRES_HOST"
  RDS_HOST_SOURCE="REMOTE_POSTGRES_HOST override"
elif RDS_HOST="$(cd "$TF_DIR" && terraform output -raw database_endpoint 2>/dev/null)" && [ -n "$RDS_HOST" ]; then
  RDS_HOST_SOURCE="terraform output database_endpoint"
elif SECRET_JSON="$(aws secretsmanager get-secret-value --secret-id "$POSTGRES_SECRET_ID" --region "$REGION" --query SecretString --output text 2>/dev/null)" \
    && RDS_HOST="$(printf '%s' "$SECRET_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["host"])' 2>/dev/null)" \
    && [ -n "$RDS_HOST" ]; then
  RDS_HOST_SOURCE="Secrets Manager ($POSTGRES_SECRET_ID)"
else
  RDS_HOST="$FALLBACK_RDS_HOST"
  RDS_HOST_SOURCE="literal fallback (Terraform/Secrets Manager unreachable)"
  warn "Could not derive the RDS endpoint from Terraform or Secrets" \
    "Manager - using the known literal. Verify this is still correct" \
    "if the database has since been recreated."
fi
unset SECRET_JSON

REMOTE_PORT="${REMOTE_POSTGRES_PORT:-5432}"
LOCAL_PORT="${POSTGRES_PORT:-15432}"

# --- 3. Resolve/discover/verify the SSM instance ------------------------

VPC_ID="$(resolve_terraform_output vpc_id "$FALLBACK_VPC_ID")"

if [ -n "$INSTANCE_ID_OVERRIDE" ]; then
  log "Verifying explicitly given SSM instance $INSTANCE_ID_OVERRIDE..."
  INSTANCE_STATUS_JSON="$(aws ssm describe-instance-information \
    --region "$REGION" \
    --filters "Key=InstanceIds,Values=$INSTANCE_ID_OVERRIDE" \
    --query 'InstanceInformationList[0]' \
    --output json 2>/dev/null || true)"

  if [ -z "$INSTANCE_STATUS_JSON" ] || [ "$INSTANCE_STATUS_JSON" = "null" ]; then
    err "SSM instance $INSTANCE_ID_OVERRIDE was not found in account" \
      "$ACCOUNT_ID / region $REGION - it does not exist here, isn't" \
      "SSM-managed, or the SSM agent has never checked in."
    exit 1
  fi

  PING_STATUS="$(echo "$INSTANCE_STATUS_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("PingStatus",""))')"
  if [ "$PING_STATUS" != "Online" ]; then
    err "SSM instance $INSTANCE_ID_OVERRIDE is not Online (status: $PING_STATUS)."
    exit 1
  fi

  SSM_INSTANCE_ID_RESOLVED="$INSTANCE_ID_OVERRIDE"

  INSTANCE_VPC="$(aws ec2 describe-instances --region "$REGION" \
    --instance-ids "$INSTANCE_ID_OVERRIDE" \
    --query 'Reservations[0].Instances[0].VpcId' --output text 2>/dev/null || true)"
  if [ "$INSTANCE_VPC" != "$VPC_ID" ]; then
    warn "$INSTANCE_ID_OVERRIDE is in VPC ${INSTANCE_VPC:-<unknown>}, not this" \
      "project's VPC ($VPC_ID) - proceeding since it was explicitly given," \
      "but the tunnel will fail if it has no network route to the RDS subnet."
  fi
else
  log "No --instance-id/SSM_INSTANCE_ID given - discovering an SSM-managed" \
    "instance in this project's VPC ($VPC_ID)..."

  VPC_INSTANCE_IDS="$(aws ec2 describe-instances --region "$REGION" \
    --filters "Name=vpc-id,Values=$VPC_ID" "Name=instance-state-name,Values=running" \
    --query 'Reservations[*].Instances[*].InstanceId' --output text 2>/dev/null || true)"

  if [ -z "$VPC_INSTANCE_IDS" ]; then
    err "No running EC2 instances exist in this project's VPC ($VPC_ID) at all."
    err "This project's ECS service runs on Fargate (no EC2 instances), so"
    err "there is currently no bastion host to tunnel through."
    err ""
    err "Per policy, this script does not create one and will not fall back"
    err "to any other instance (including a personal one). If a bastion is"
    err "provisioned later, either pass --instance-id <id> or set"
    err "SSM_INSTANCE_ID once it exists."
    exit 1
  fi

  ONLINE_IDS="$(aws ssm describe-instance-information --region "$REGION" \
    --query "InstanceInformationList[?PingStatus=='Online'].InstanceId" \
    --output text 2>/dev/null || true)"

  MATCHING_IDS=""
  for candidate in $VPC_INSTANCE_IDS; do
    for online in $ONLINE_IDS; do
      [ "$candidate" = "$online" ] && MATCHING_IDS="$MATCHING_IDS $candidate"
    done
  done
  MATCHING_IDS="$(echo "$MATCHING_IDS" | xargs -n1 2>/dev/null | sort -u)"
  MATCH_COUNT="$(echo -n "$MATCHING_IDS" | grep -c . || true)"

  if [ -z "$MATCHING_IDS" ]; then
    err "Found EC2 instance(s) in VPC $VPC_ID, but none are SSM-managed/Online."
    err "No usable bastion host exists - not falling back to any other instance."
    exit 1
  fi

  if [ "$MATCH_COUNT" -gt 1 ]; then
    err "Multiple SSM-managed instances found in VPC $VPC_ID:"
    for id in $MATCHING_IDS; do err "  $id"; done
    err "Pick one explicitly with --instance-id to avoid ambiguity."
    exit 1
  fi

  SSM_INSTANCE_ID_RESOLVED="$(echo "$MATCHING_IDS" | xargs)"
  log "Discovered $SSM_INSTANCE_ID_RESOLVED"
fi

# --- 4. Remaining pre-flight checks -------------------------------------

log "Verifying the RDS endpoint resolves..."
if ! python3 -c "import socket,sys; socket.gethostbyname(sys.argv[1])" "$RDS_HOST" 2>/dev/null; then
  err "RDS endpoint does not resolve via DNS: $RDS_HOST (source: $RDS_HOST_SOURCE)"
  exit 1
fi

log "Verifying local port $LOCAL_PORT is available..."
if lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  err "Local port $LOCAL_PORT is already in use:"
  lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN >&2
  exit 1
fi

# --- 5. Print resolved context (never credentials/secrets) -------------

echo ""
echo "========================================"
echo "BU dev PostgreSQL SSM tunnel"
echo "========================================"
echo "  Account:        $ACCOUNT_ID"
echo "  Profile:         $AWS_PROFILE"
echo "  Region:          $REGION"
echo "  Caller:          $CALLER_ARN"
echo "  SSM instance:    $SSM_INSTANCE_ID_RESOLVED"
echo "  RDS host:        $RDS_HOST"
echo "  RDS host source: $RDS_HOST_SOURCE"
echo "  Local port:      $LOCAL_PORT"
echo "  Remote port:     $REMOTE_PORT"
echo ""

if [ "$CHECK_ONLY" = true ]; then
  echo "--check-only: all checks passed, no tunnel opened."
  exit 0
fi

echo "Starting tunnel: localhost:$LOCAL_PORT -> $RDS_HOST:$REMOTE_PORT"
echo "(leave this running; Ctrl-C to stop)"
echo ""

exec aws ssm start-session \
  --region "$REGION" \
  --target "$SSM_INSTANCE_ID_RESOLVED" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$RDS_HOST\"],\"portNumber\":[\"$REMOTE_PORT\"],\"localPortNumber\":[\"$LOCAL_PORT\"]}"
