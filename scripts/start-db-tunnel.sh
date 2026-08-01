#!/usr/bin/env bash
set -euo pipefail

: "${SSM_INSTANCE_ID:?SSM_INSTANCE_ID is not set}"
: "${REMOTE_POSTGRES_HOST:?REMOTE_POSTGRES_HOST is not set}"
: "${REMOTE_POSTGRES_PORT:?REMOTE_POSTGRES_PORT is not set}"
: "${POSTGRES_PORT:?POSTGRES_PORT is not set}"

echo "Starting PostgreSQL tunnel:"
echo "  localhost:${POSTGRES_PORT} -> ${REMOTE_POSTGRES_HOST}:${REMOTE_POSTGRES_PORT}"

aws ssm start-session \
  --target "$SSM_INSTANCE_ID" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$REMOTE_POSTGRES_HOST\"],\"portNumber\":[\"$REMOTE_POSTGRES_PORT\"],\"localPortNumber\":[\"$POSTGRES_PORT\"]}"
