#!/usr/bin/env bash
set -euo pipefail

# Obtain a fresh Cognito access token via `aws cognito-idp
# admin-initiate-auth` (ADMIN_USER_PASSWORD_AUTH - email + password, no
# browser, no SRP client implementation needed; see
# terraform/modules/cognito/variables.tf's allow_admin_password_auth for
# why this flow exists at all: admin-created dev/test users only, never
# a substitute for real end-user auth). Prints ONLY the access token to
# stdout - every prompt/diagnostic goes to stderr - so it composes
# directly into:
#
#   export ACCESS_TOKEN="$(scripts/get-access-token.sh)"
#
# The password is read via `read -s` (terminal echo disabled) straight
# into a shell variable and is never passed as a literal CLI argument to
# any process (which would otherwise be visible to other users on the
# same machine via `ps`) - it reaches `jq` only via its own process
# environment (env.PASSWORD, exported but never an --arg), not argv.
# The resulting JSON payload (which does contain the plaintext password)
# is written to a mode-0600 temp file for `aws --cli-input-json
# file://...` to read - not piped via file:///dev/stdin, which this
# AWS CLI build fails to parse from a non-seekable pipe (reproducible:
# `... | aws ... --cli-input-json file:///dev/stdin` reports "Invalid
# JSON received" even for valid JSON) - and is deleted immediately after
# the call via a trap, so it never outlives this script's own process.
#
# Usage:
#   scripts/get-access-token.sh [USERNAME]
#   export ACCESS_TOKEN="$(scripts/get-access-token.sh someone@bu.edu)"
#
# Optional environment overrides (defaults match the dev User Pool):
#   COGNITO_USER_POOL_ID   (default: us-east-1_VJ4ekQ27c)
#   COGNITO_CLIENT_ID      (default: seqgmc8sccr22sq8lcafqjcpb)
#   AWS_REGION             (default: us-east-1)
#   COGNITO_USERNAME       (used if USERNAME isn't given positionally)

AWS_REGION="${AWS_REGION:-us-east-1}"
COGNITO_USER_POOL_ID="${COGNITO_USER_POOL_ID:-us-east-1_VJ4ekQ27c}"
COGNITO_CLIENT_ID="${COGNITO_CLIENT_ID:-seqgmc8sccr22sq8lcafqjcpb}"
USERNAME="${1:-${COGNITO_USERNAME:-}}"

if [[ -z "$USERNAME" ]]; then
  echo "ERROR: no username given - pass it as the first argument or set COGNITO_USERNAME" >&2
  exit 1
fi

if [[ ! -t 0 ]]; then
  echo "ERROR: this script needs an interactive terminal to prompt for your password" >&2
  exit 1
fi

read -r -s -p "Password for $USERNAME: " PASSWORD
echo "" >&2

if [[ -z "$PASSWORD" ]]; then
  echo "ERROR: empty password" >&2
  unset PASSWORD
  exit 1
fi

PAYLOAD_FILE="$(mktemp)"
trap 'rm -f "$PAYLOAD_FILE"' EXIT

export PASSWORD
jq -n \
  --arg pool "$COGNITO_USER_POOL_ID" \
  --arg client "$COGNITO_CLIENT_ID" \
  --arg username "$USERNAME" \
  '{UserPoolId: $pool, ClientId: $client, AuthFlow: "ADMIN_USER_PASSWORD_AUTH",
    AuthParameters: {USERNAME: $username, PASSWORD: env.PASSWORD}}' \
  > "$PAYLOAD_FILE"
unset PASSWORD

RESPONSE="$(
  aws cognito-idp admin-initiate-auth \
    --region "$AWS_REGION" \
    --cli-input-json "file://${PAYLOAD_FILE}"
)"
rm -f "$PAYLOAD_FILE"

CHALLENGE="$(echo "$RESPONSE" | jq -r '.ChallengeName // empty')"
if [[ -n "$CHALLENGE" ]]; then
  echo "ERROR: Cognito returned a challenge ($CHALLENGE) instead of tokens - this user needs to complete that challenge (e.g. set a permanent password) before admin-initiate-auth can issue a token directly." >&2
  exit 1
fi

ACCESS_TOKEN="$(echo "$RESPONSE" | jq -r '.AuthenticationResult.AccessToken // empty')"
if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "ERROR: no AccessToken in the Cognito response - check the username/password and that this user exists in user pool $COGNITO_USER_POOL_ID" >&2
  exit 1
fi

echo "$ACCESS_TOKEN"
