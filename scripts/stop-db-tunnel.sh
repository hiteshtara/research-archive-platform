#!/usr/bin/env bash
#
# Stops any tunnel task left running in the dev dbtunnel cluster.
#
# start-db-tunnel.sh stops its own task through a trap, and the container
# has a hard `sleep` ceiling that ends it regardless. This is the third
# path: for when the Mac was forcibly powered off, the trap never ran, and
# you would rather not wait out the ceiling.
#
# Safe to run when nothing is running - it reports that and exits 0.
#
set -uo pipefail

REGION="us-east-1"
EXPECTED_ACCOUNT_ID="770203350335"
CLUSTER="research-archive-platform-dev-dbtunnel"

err() { echo "ERROR: $*" >&2; }
log() { echo "-- $*"; }

: "${AWS_PROFILE:=bu-nprd}"
export AWS_PROFILE

ACCOUNT_ID="$(aws sts get-caller-identity --region "$REGION" \
  --query Account --output text 2>/dev/null)"
if [ "$ACCOUNT_ID" != "$EXPECTED_ACCOUNT_ID" ]; then
  err "Wrong AWS account: got '${ACCOUNT_ID:-none}', expected $EXPECTED_ACCOUNT_ID."
  exit 1
fi

TASKS="$(aws ecs list-tasks --region "$REGION" --cluster "$CLUSTER" \
  --desired-status RUNNING --query 'taskArns[]' --output text 2>/dev/null)"

if [ -z "$TASKS" ] || [ "$TASKS" = "None" ]; then
  log "No tunnel tasks are running."
  exit 0
fi

STATUS=0
for task in $TASKS; do
  log "Stopping ${task##*/}..."
  if aws ecs stop-task --region "$REGION" --cluster "$CLUSTER" \
       --task "$task" --reason "stopped by stop-db-tunnel.sh" >/dev/null 2>&1; then
    log "Stopped."
  else
    err "Failed to stop $task"
    STATUS=1
  fi
done
exit "$STATUS"
