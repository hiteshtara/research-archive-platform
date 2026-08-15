#!/usr/bin/env bash
#
# Run on the Mac (not CloudShell) - see scripts/mac-show-rds-master-password.sh
# for why every AWS API call in this workflow happens here, using
# AWS_PROFILE=bu-nprd, instead of from inside CloudShell (whose egress
# is scoped to RDS on 5432 only - no AWS API, no GitHub, nothing else).
#
# Step 2 of the one-time archive_analyst role-creation workflow (see
# docs/runbooks/CLOUDSHELL_ANALYSIS.md):
#   1. Generates a strong password restricted to an alphanumeric
#      alphabet only (no quotes, backslashes, or other SQL/shell
#      metacharacters) - safe even if it were ever interpolated
#      directly, though the actual role-creation SQL
#      (database/analysis-role/create_archive_analyst_role.sql) never
#      does that: it uses psql's own `\password` meta-command, which
#      safely parameterizes the value server-side regardless.
#   2. Stores it as an SSM Standard-tier SecureString
#      (/research-archive-platform/dev/postgres-analyst, encrypted with
#      the AWS-managed alias/aws/ssm key - no customer-managed KMS key,
#      no Advanced parameter) via --value file://<temp file>, never a
#      literal command-line argument - the temp file is deleted
#      immediately after.
#   3. Copies it to the Mac clipboard via `pbcopy` - never printed to
#      the terminal, never written to any other file, never logged.
#
# Paste it (Cmd-V) into CloudShell's interactive `\password
# archive_analyst` prompt (asked twice, hidden). This script waits for
# you to confirm the paste, then clears the clipboard.
#
# Fixed 2026-08-15: the JSON-construction step passed RDS_HOST/RDS_PORT/
# RDS_DBNAME/ARCHIVE_ANALYST_PASSWORD to python3 as trailing positional
# arguments after `-c '...'` - which the script's own `-c` code never
# read (it called os.environ[...] instead), so those four values did
# nothing. A top-of-script `export` of the same names happened to make
# os.environ[...] work anyway, which is why the underlying bug was easy
# to miss - but it meant password/host/port/dbname sat in this whole
# script's broadly-exported environment for its entire run, available to
# every child process, rather than only the one command that needs them.
# Fixed by passing them as a proper env-var prefix (`VAR=val python3
# -c ...`), which scopes them to exactly that one subprocess invocation
# and needs no `export`/os.environ at all.
#
# Usage:
#   scripts/mac-generate-analyst-password.sh

set -uo pipefail

REGION="us-east-1"
EXPECTED_ACCOUNT_ID="${EXPECTED_ACCOUNT_ID:-770203350335}"
ANALYST_PARAMETER_NAME="/research-archive-platform/dev/postgres-analyst"
RDS_HOST="research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com"
RDS_PORT=5432
RDS_DBNAME="research_archive"

err()  { echo "ERROR: $*" >&2; }
log()  { echo "-- $*" >&2; }

clear_clipboard() { pbcopy </dev/null; }

TMP_PARAM_FILE=""
TMP_ERR_FILE=""
COPIED_TO_CLIPBOARD=false
cleanup() {
  # Clears the clipboard on ANY exit path (normal completion, an error
  # after the copy, or interruption/Ctrl-C) if - and only if - this
  # script actually put the password there; an early failure that never
  # reached pbcopy must never blank whatever the user already had
  # clipped before running this script.
  if [ "$COPIED_TO_CLIPBOARD" = true ]; then
    clear_clipboard 2>/dev/null || true
  fi
  unset ARCHIVE_ANALYST_PASSWORD PARAM_JSON
  [ -n "$TMP_PARAM_FILE" ] && rm -f "$TMP_PARAM_FILE"
  [ -n "$TMP_ERR_FILE" ] && rm -f "$TMP_ERR_FILE"
}
# A trap registered for INT/TERM suppresses their default (terminating)
# disposition - without an explicit exit here, a SIGTERM/SIGINT while
# blocked at the "press Enter" prompt below would run cleanup() and then
# simply resume waiting at that same prompt forever, never actually
# stopping. `exit N` itself triggers the EXIT trap (cleanup), so INT/TERM
# don't need to call cleanup separately.
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v pbcopy >/dev/null 2>&1 || {
  err "pbcopy not found - this script requires macOS."
  exit 1
}
command -v pbpaste >/dev/null 2>&1 || {
  err "pbpaste not found - this script requires macOS."
  exit 1
}

TMP_ERR_FILE="$(mktemp)"

: "${AWS_PROFILE:=bu-nprd}"
export AWS_PROFILE

log "Resolving AWS identity (profile: $AWS_PROFILE)..."
if ! CALLER_IDENTITY_JSON="$(aws sts get-caller-identity --region "$REGION" --output json 2>"$TMP_ERR_FILE")"; then
  err "Could not resolve AWS identity: $(cat "$TMP_ERR_FILE")"
  exit 1
fi
ACCOUNT_ID="$(echo "$CALLER_IDENTITY_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Account"])' 2>"$TMP_ERR_FILE")" || {
  err "Could not parse AWS identity response: $(cat "$TMP_ERR_FILE")"
  exit 1
}
if [ "$ACCOUNT_ID" != "$EXPECTED_ACCOUNT_ID" ]; then
  err "Resolved AWS account ($ACCOUNT_ID) != expected BU account ($EXPECTED_ACCOUNT_ID)."
  exit 1
fi

log "Generating a strong, alphanumeric-only password..."
# tr restricts to A-Za-z0-9 only - deliberately excludes every
# character with SQL or shell significance (', ", \, ;, $, `, etc.).
ARCHIVE_ANALYST_PASSWORD="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
if [ "${#ARCHIVE_ANALYST_PASSWORD}" -lt 32 ]; then
  err "Password generation produced fewer than 32 characters - aborting rather than using a weaker value."
  exit 1
fi

log "Storing it in SSM Parameter Store ($ANALYST_PARAMETER_NAME, Standard SecureString, alias/aws/ssm)..."
# RDS_HOST/RDS_PORT/RDS_DBNAME/ARCHIVE_ANALYST_PASSWORD are passed as a
# genuine environment-variable prefix (before the command name) - scoped
# to only this one python3 invocation, never exported into this script's
# own broader environment.
if ! PARAM_JSON="$(RDS_HOST="$RDS_HOST" RDS_PORT="$RDS_PORT" RDS_DBNAME="$RDS_DBNAME" \
  ARCHIVE_ANALYST_PASSWORD="$ARCHIVE_ANALYST_PASSWORD" python3 -c '
import json, os
print(json.dumps({
    "engine": "postgres",
    "host": os.environ["RDS_HOST"],
    "port": int(os.environ["RDS_PORT"]),
    "dbname": os.environ["RDS_DBNAME"],
    "username": "archive_analyst",
    "password": os.environ["ARCHIVE_ANALYST_PASSWORD"],
}))
' 2>"$TMP_ERR_FILE")" || [ -z "$PARAM_JSON" ]; then
  err "Could not build the credential JSON: $(cat "$TMP_ERR_FILE")"
  exit 1
fi

TMP_PARAM_FILE="$(mktemp)"
chmod 600 "$TMP_PARAM_FILE"
printf '%s' "$PARAM_JSON" > "$TMP_PARAM_FILE"
unset PARAM_JSON

if ! aws ssm put-parameter --region "$REGION" \
  --name "$ANALYST_PARAMETER_NAME" \
  --type SecureString \
  --tier Standard \
  --key-id alias/aws/ssm \
  --value "file://$TMP_PARAM_FILE" \
  --overwrite >/dev/null 2>"$TMP_ERR_FILE"; then
  err "aws ssm put-parameter failed - nothing was copied to the clipboard: $(cat "$TMP_ERR_FILE")"
  exit 1
fi
rm -f "$TMP_PARAM_FILE"
TMP_PARAM_FILE=""

if ! printf '%s' "$ARCHIVE_ANALYST_PASSWORD" | pbcopy; then
  err "pbcopy failed - the password was stored in SSM but NOT copied to your clipboard. Run scripts/mac-show-analyst-password.sh to retrieve it instead of regenerating."
  exit 1
fi
COPIED_TO_CLIPBOARD=true

# Verify the clipboard genuinely holds the generated password before
# claiming success - by length AND content, never by printing either
# value (a `[ ... = ... ]` string comparison prints nothing).
CLIPBOARD_CONTENT="$(pbpaste)"
if [ "${#CLIPBOARD_CONTENT}" -ne "${#ARCHIVE_ANALYST_PASSWORD}" ] || [ "$CLIPBOARD_CONTENT" != "$ARCHIVE_ANALYST_PASSWORD" ]; then
  err "Clipboard verification failed after pbcopy - the password was stored in SSM but the clipboard does not match it. Run scripts/mac-show-analyst-password.sh to retrieve it instead of regenerating."
  unset CLIPBOARD_CONTENT
  exit 1
fi
unset CLIPBOARD_CONTENT ARCHIVE_ANALYST_PASSWORD

cat <<EOF

========================================================================
Stored in SSM. Password copied to your clipboard (not printed above)
and verified to match exactly.

Paste it (Cmd-V) into CloudShell's two prompts from
\password archive_analyst:
  Enter new password:
  Enter it again:
========================================================================
EOF

read -r -p "Press Enter once you've pasted it into CloudShell (both prompts), to clear the clipboard... "
clear_clipboard
echo "Clipboard cleared."
