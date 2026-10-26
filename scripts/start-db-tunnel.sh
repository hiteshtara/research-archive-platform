#!/usr/bin/env bash
#
# Opens a read-only tunnel from localhost:15432 to the dev Research Archive
# PostgreSQL database, for psql / DBeaver / DataGrip on the Mac.
#
#   Mac :15432 -> SSM Session Manager -> ephemeral Fargate task -> RDS :5432
#
# Requires terraform -var manage_db_tunnel_access=true to have been applied,
# and the dbtunnel-operator IAM policy attached to you. See
# docs/runbooks/SSM_DB_TUNNEL.md.
#
# PORT 15432, NOT 5432. Local Homebrew PostgreSQL already owns 5432 on this
# Mac - the original August 2026 version of this script chose 15432 for the
# same reason. Pointing anything at localhost:5432 reaches the disposable
# local copy, never dev.
#
# This script NEVER prints or handles a database password. You authenticate
# to PostgreSQL yourself, as archive_analyst, after the tunnel is up.
#
# Usage:
#   scripts/start-db-tunnel.sh            # open the tunnel; Ctrl-C closes it
#   scripts/start-db-tunnel.sh --check    # verify prerequisites and exit
#
set -uo pipefail

REGION="us-east-1"
EXPECTED_ACCOUNT_ID="770203350335"
PROJECT="research-archive-platform"
ENVIRONMENT="dev"
CLUSTER="${PROJECT}-${ENVIRONMENT}-dbtunnel"
TASK_FAMILY="${PROJECT}-${ENVIRONMENT}-dbtunnel"
LOCAL_PORT="${LOCAL_PORT:-15432}"
REMOTE_PORT="5432"

CHECK_ONLY=false
[ "${1:-}" = "--check" ] && CHECK_ONLY=true

err()  { echo "ERROR: $*" >&2; }
log()  { echo "-- $*"; }

TASK_ARN=""

# --- Cleanup: the task must not outlive this script ----------------------
#
# Runs on normal exit, on Ctrl-C, on SIGTERM, and on any error (set -e is
# deliberately NOT used; every step checks its own status and exits, and
# this trap fires for all of them).
#
# Three independent things stop a task, because any one of them can fail:
#   1. this trap, the normal path;
#   2. the container's own `sleep <max>` ceiling, if the Mac dies first;
#   3. scripts/stop-db-tunnel.sh, to sweep strays by hand.
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [ -n "$TASK_ARN" ]; then
    log "Stopping tunnel task..."
    if aws ecs stop-task --region "$REGION" --cluster "$CLUSTER" \
         --task "$TASK_ARN" --reason "tunnel session ended" >/dev/null 2>&1; then
      log "Stopped."
    else
      err "Could not stop $TASK_ARN automatically."
      err "Run: scripts/stop-db-tunnel.sh"
    fi
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

# --- 1. Tooling ----------------------------------------------------------
command -v aws >/dev/null 2>&1 || { err "aws CLI not found"; exit 1; }
if ! command -v session-manager-plugin >/dev/null 2>&1; then
  err "session-manager-plugin not found - required for port forwarding."
  err "Install: brew install --cask session-manager-plugin"
  exit 1
fi

# --- 2. The right AWS account, resolved fresh ----------------------------
: "${AWS_PROFILE:=bu-nprd}"
export AWS_PROFILE
ACCOUNT_ID="$(aws sts get-caller-identity --region "$REGION" \
  --query Account --output text 2>/dev/null)"
if [ "$ACCOUNT_ID" != "$EXPECTED_ACCOUNT_ID" ]; then
  err "Wrong AWS account: got '${ACCOUNT_ID:-none}', expected $EXPECTED_ACCOUNT_ID."
  err "Run 'buaws' and retry. Refusing to continue."
  exit 1
fi
log "AWS account $ACCOUNT_ID (profile $AWS_PROFILE)"

# --- 3. The local port must be free --------------------------------------
if lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  err "Port $LOCAL_PORT is already in use - a tunnel may already be open."
  err "Close it, or set LOCAL_PORT to something else."
  exit 1
fi

# --- 4. Resolve the RDS endpoint rather than trusting a constant ---------
RDS_HOST="$(aws rds describe-db-instances --region "$REGION" \
  --db-instance-identifier "${PROJECT}-${ENVIRONMENT}-postgres" \
  --query 'DBInstances[0].Endpoint.Address' --output text 2>/dev/null)"
if [ -z "$RDS_HOST" ] || [ "$RDS_HOST" = "None" ]; then
  err "Could not resolve the dev RDS endpoint. Is the database up?"
  exit 1
fi
log "Target: $RDS_HOST:$REMOTE_PORT"

# --- 5. Networking the task needs ----------------------------------------
SUBNET_ID="$(aws ec2 describe-subnets --region "$REGION" \
  --filters "Name=tag:Name,Values=${PROJECT}-${ENVIRONMENT}-private-1" \
  --query 'Subnets[0].SubnetId' --output text 2>/dev/null)"
SG_ID="$(aws ec2 describe-security-groups --region "$REGION" \
  --filters "Name=group-name,Values=${PROJECT}-${ENVIRONMENT}-dbtunnel-sg" \
  --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null)"
if [ -z "$SUBNET_ID" ] || [ "$SUBNET_ID" = "None" ] \
   || [ -z "$SG_ID" ] || [ "$SG_ID" = "None" ]; then
  err "Tunnel infrastructure not found (subnet or security group)."
  err "Has terraform been applied with -var manage_db_tunnel_access=true?"
  exit 1
fi

if $CHECK_ONLY; then
  log "Prerequisites OK. Subnet $SUBNET_ID, security group $SG_ID."
  log "(--check does not start a task.)"
  exit 0
fi

# --- 6. Start the ephemeral task ------------------------------------------
log "Starting tunnel task..."
TASK_ARN="$(aws ecs run-task --region "$REGION" \
  --cluster "$CLUSTER" \
  --task-definition "$TASK_FAMILY" \
  --launch-type FARGATE \
  --enable-execute-command \
  --count 1 \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_ID],securityGroups=[$SG_ID],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text 2>/dev/null)"
if [ -z "$TASK_ARN" ] || [ "$TASK_ARN" = "None" ]; then
  TASK_ARN=""
  err "run-task did not return a task. Check the dbtunnel-operator policy is attached."
  exit 1
fi
TASK_ID="${TASK_ARN##*/}"
log "Task $TASK_ID"

log "Waiting for it to run..."
if ! aws ecs wait tasks-running --region "$REGION" \
     --cluster "$CLUSTER" --tasks "$TASK_ARN" 2>/dev/null; then
  err "Task did not reach RUNNING."
  aws ecs describe-tasks --region "$REGION" --cluster "$CLUSTER" --tasks "$TASK_ARN" \
    --query 'tasks[0].{Status:lastStatus,Stopped:stoppedReason}' --output json 2>/dev/null >&2
  exit 1
fi

# The exec agent starts a moment after the task does; without this the first
# StartSession can fail with TargetNotConnectedException.
log "Waiting for the exec agent..."
for _ in $(seq 1 30); do
  # shellcheck disable=SC2016  # backticks here are JMESPath, not shell
  AGENT="$(aws ecs describe-tasks --region "$REGION" --cluster "$CLUSTER" \
    --tasks "$TASK_ARN" --query 'tasks[0].containers[0].managedAgents[?name==`ExecuteCommandAgent`].lastStatus | [0]' \
    --output text 2>/dev/null)"
  [ "$AGENT" = "RUNNING" ] && break
  sleep 2
done
if [ "${AGENT:-}" != "RUNNING" ]; then
  err "The ExecuteCommandAgent never reported RUNNING (last: ${AGENT:-unknown})."
  err "Usually means the ssmmessages VPC endpoint is missing or unreachable."
  exit 1
fi

RUNTIME_ID="$(aws ecs describe-tasks --region "$REGION" --cluster "$CLUSTER" \
  --tasks "$TASK_ARN" --query 'tasks[0].containers[0].runtimeId' --output text 2>/dev/null)"
if [ -z "$RUNTIME_ID" ] || [ "$RUNTIME_ID" = "None" ]; then
  err "Could not read the container runtime id."
  exit 1
fi

# --- 7. Forward the port --------------------------------------------------
cat <<BANNER

  Tunnel open.  localhost:$LOCAL_PORT  ->  dev RDS :$REMOTE_PORT

    psql "host=localhost port=$LOCAL_PORT dbname=research_archive \\
          user=archive_analyst sslmode=require" --password

    DBeaver / DataGrip:  Host 127.0.0.1   Port $LOCAL_PORT
                         Database research_archive
                         User archive_analyst

  Get the password with: scripts/mac-show-analyst-password.sh
  Press Ctrl-C here to close the tunnel and stop the task.

BANNER

aws ssm start-session --region "$REGION" \
  --target "ecs:${CLUSTER}_${TASK_ID}_${RUNTIME_ID}" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$RDS_HOST\"],\"portNumber\":[\"$REMOTE_PORT\"],\"localPortNumber\":[\"$LOCAL_PORT\"]}"

# Falls through to cleanup() on exit.
