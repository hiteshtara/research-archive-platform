#!/usr/bin/env bash
#
# Run on the Mac (not CloudShell) - see scripts/mac-show-rds-master-password.sh
# for why every AWS API call in this workflow happens here, using
# AWS_PROFILE=bu-nprd, instead of from inside CloudShell.
#
# Normal connection workflow (after the one-time role creation - see
# docs/runbooks/CLOUDSHELL_ANALYSIS.md): fetches the already-stored
# archive_analyst credential from SSM Parameter Store and copies it to
# the Mac clipboard via `pbcopy` - never printed to the terminal, never
# written to a file, never passed as a command-line argument, never
# logged. Prints only the non-secret psql connection command.
#
# Fails closed (nonzero exit, no clipboard change, no secret ever
# printed) if the SSM parameter is missing, its value isn't valid JSON,
# any required field (including password) is absent/blank, or pbcopy
# itself fails or doesn't verifiably match afterward - see
# verify_clipboard_matches() below. A prior version of this script
# claimed "Password copied to your clipboard" unconditionally, with no
# check that pbcopy actually succeeded or that stderr from a *successful*
# AWS call hadn't been silently merged into the JSON text via `2>&1`
# (which corrupts the parse without making the command itself "fail") -
# confirmed live 2026-08-15: the clipboard still held an unrelated
# 1,237-character SQL string after a "successful" run, causing repeated
# PostgreSQL authentication failures. Both are fixed below by never
# merging stdout/stderr on a call whose stdout gets parsed, and by
# reading the clipboard back and comparing it to the fetched password
# (length and content) before ever claiming success.
#
# Paste it (Cmd-V) into psql's "Password for user archive_analyst:"
# prompt inside a CloudShell VPC environment session. This script waits
# for you to confirm the paste, then clears the clipboard.
#
# Usage:
#   scripts/mac-show-analyst-password.sh

set -uo pipefail

REGION="us-east-1"
EXPECTED_ACCOUNT_ID="${EXPECTED_ACCOUNT_ID:-770203350335}"
ANALYST_PARAMETER_NAME="/research-archive-platform/dev/postgres-analyst"

err()  { echo "ERROR: $*" >&2; }
log()  { echo "-- $*" >&2; }

clear_clipboard() { pbcopy </dev/null; }

TMP_ERR_FILE=""
TMP_JSON_FILE=""
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
  unset ANALYST_JSON_RAW ANALYST_PASSWORD ANALYST_HOST ANALYST_PORT ANALYST_DBNAME ANALYST_USERNAME PARSED_FIELDS
  [ -n "$TMP_ERR_FILE" ] && rm -f "$TMP_ERR_FILE"
  [ -n "$TMP_JSON_FILE" ] && rm -f "$TMP_JSON_FILE"
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

log "Fetching the archive_analyst credential from SSM Parameter Store..."
# stdout and stderr are deliberately captured separately here - not
# `2>&1` - so a warning AWS CLI prints on an otherwise-successful call
# can never be silently appended to the JSON text this gets parsed as.
if ! ANALYST_JSON_RAW="$(aws ssm get-parameter --region "$REGION" \
  --name "$ANALYST_PARAMETER_NAME" --with-decryption \
  --query 'Parameter.Value' --output text 2>"$TMP_ERR_FILE")"; then
  err "Could not fetch $ANALYST_PARAMETER_NAME: $(cat "$TMP_ERR_FILE")"
  err "Has the one-time role-creation workflow been run yet? See docs/runbooks/CLOUDSHELL_ANALYSIS.md."
  exit 1
fi
if [ -z "$ANALYST_JSON_RAW" ]; then
  err "$ANALYST_PARAMETER_NAME returned an empty value."
  exit 1
fi

# Written to a mode-0600 temp file rather than re-piped through `echo`,
# so the JSON (which contains the password) is parsed exactly as
# fetched - no shell echo/quoting edge cases - and reaches python3 by
# file path, never as a command-line argument (visible to other users
# via `ps`) and never lingering in a variable longer than needed.
TMP_JSON_FILE="$(mktemp)"
chmod 600 "$TMP_JSON_FILE"
printf '%s' "$ANALYST_JSON_RAW" > "$TMP_JSON_FILE"
unset ANALYST_JSON_RAW

# One python3 call parses the JSON once and fails closed (nonzero exit,
# nothing printed to stdout) if it isn't valid JSON, isn't an object, or
# is missing/blank on ANY required field - never just "password". Emits
# the five fields one per line, in a fixed order, on success only.
PARSED_FIELDS="$(python3 -c '
import json, sys

path = sys.argv[1]
try:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
except (OSError, json.JSONDecodeError) as error:
    print(f"invalid JSON in {path}: {error}", file=sys.stderr)
    sys.exit(1)

if not isinstance(data, dict):
    print(f"expected a JSON object in {path}, got {type(data).__name__}", file=sys.stderr)
    sys.exit(1)

required = ("password", "host", "port", "dbname", "username")
missing = [key for key in required if not str(data.get(key, "")).strip()]
if missing:
    print("missing/blank required field(s): " + ", ".join(missing), file=sys.stderr)
    sys.exit(1)

for key in required:
    print(str(data[key]))
' "$TMP_JSON_FILE" 2>"$TMP_ERR_FILE")"
PARSE_EXIT=$?
rm -f "$TMP_JSON_FILE"
TMP_JSON_FILE=""
if [ "$PARSE_EXIT" -ne 0 ]; then
  err "Stored credential at $ANALYST_PARAMETER_NAME is unusable: $(cat "$TMP_ERR_FILE")"
  exit 1
fi

# Sequential reads (not `mapfile`/`readarray`, which need bash 4+ and
# macOS's system /bin/bash is 3.2) - each consumes exactly one line, in
# the fixed order the python3 call above prints them.
{
  IFS= read -r ANALYST_PASSWORD
  IFS= read -r ANALYST_HOST
  IFS= read -r ANALYST_PORT
  IFS= read -r ANALYST_DBNAME
  IFS= read -r ANALYST_USERNAME
} <<EOF_PARSED
$PARSED_FIELDS
EOF_PARSED
unset PARSED_FIELDS

if [ -z "$ANALYST_PASSWORD" ]; then
  err "Parsed password is blank - refusing to copy an empty credential."
  exit 1
fi

if ! printf '%s' "$ANALYST_PASSWORD" | pbcopy; then
  err "pbcopy failed - clipboard was not updated."
  exit 1
fi
COPIED_TO_CLIPBOARD=true

# Verify the clipboard genuinely holds the password before ever
# claiming success - by length AND content, never by printing either
# value (a `[ ... = ... ]` string comparison prints nothing).
CLIPBOARD_CONTENT="$(pbpaste)"
if [ "${#CLIPBOARD_CONTENT}" -ne "${#ANALYST_PASSWORD}" ] || [ "$CLIPBOARD_CONTENT" != "$ANALYST_PASSWORD" ]; then
  err "Clipboard verification failed after pbcopy - clipboard does not match the fetched credential. Nothing was displayed; try again."
  unset CLIPBOARD_CONTENT
  exit 1
fi
unset CLIPBOARD_CONTENT ANALYST_PASSWORD

cat <<EOF

========================================================================
Password copied to your clipboard (not printed above) and verified to
match exactly.

Run this in your CloudShell VPC environment session:

  psql "host=$ANALYST_HOST port=$ANALYST_PORT dbname=$ANALYST_DBNAME user=$ANALYST_USERNAME sslmode=require"

When prompted "Password for user $ANALYST_USERNAME:", paste (Cmd-V).
========================================================================
EOF

read -r -p "Press Enter once you've pasted it into CloudShell, to clear the clipboard... "
clear_clipboard
echo "Clipboard cleared."
