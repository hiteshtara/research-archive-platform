#!/usr/bin/env bash
#
# Refuse to register a grant-admin task definition that is missing something
# the deployed API needs in order to start.
#
# WHY THIS EXISTS. On 2026-11-02 the first real DEV grant-provisioning dry run
# exited 1 before the CLI ran at all: the hand-written task definition omitted
# ARCHIVE_DOCUMENTS_BUCKET, and S3AwardAttachmentStorage refuses to construct
# without it. The same attempt also set SPRING_PROFILES_ACTIVE=grant-admin,
# replacing "aws" instead of adding to it.
#
# GrantAdminTaskDefinitionTest asserts the shape of the committed file and runs
# in CI. It cannot see a variable added to the DEPLOYED API later. This script
# closes that gap by diffing the committed file against the live API task
# definition, so a new API requirement shows up here instead of as a failed
# one-off task.
#
# Usage:
#   scripts/verify-grant-admin-taskdef.sh [path-to-taskdef.json]
#
# Exit 0 only if every check passes. Requires AWS_PROFILE=bu-nprd.
set -uo pipefail

FILE="${1:-ops/grant-admin/dev-grant-admin.taskdef.json}"
API_FAMILY="research-archive-platform-dev-api"
REGION="us-east-1"
EXPECTED_ACCOUNT="770203350335"
FAILED=0

[ -f "$FILE" ] || { echo "FAIL  $FILE not found" >&2; exit 1; }

account="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)"
if [ "$account" != "$EXPECTED_ACCOUNT" ]; then
  echo "FAIL  wrong AWS account: got '${account:-none}', expected $EXPECTED_ACCOUNT" >&2
  echo "      (the default profile on this Mac resolves to a personal account)" >&2
  exit 1
fi
echo "OK    AWS account $account"

LIVE_FILE="$(mktemp)"
trap 'rm -f "$LIVE_FILE"' EXIT

if ! aws ecs describe-task-definition --region "$REGION" \
       --task-definition "$API_FAMILY" \
       --query 'taskDefinition.containerDefinitions[0].environment' \
       --output json > "$LIVE_FILE" 2>/dev/null; then
  echo "FAIL  could not read the live $API_FAMILY task definition" >&2
  exit 1
fi

# The live JSON goes to a FILE, not to stdin. This block originally used both a
# heredoc and a here-string; the here-string won, python received the JSON as
# its program, failed, and the empty output read as "nothing missing". A
# validation script that silently passes is worse than no script at all.
missing="$(LIVE_FILE="$LIVE_FILE" TASKDEF="$FILE" python3 - <<'ENDOFPY'
import json, os
live = {e["name"] for e in json.load(open(os.environ["LIVE_FILE"]))}
d = json.load(open(os.environ["TASKDEF"]))
ours = {e["name"] for e in d["containerDefinitions"][0]["environment"]}
print(",".join(sorted(live - ours)))
ENDOFPY
)"

if [ -n "$missing" ]; then
  echo "FAIL  the committed definition is missing variables the live API declares: $missing" >&2
  FAILED=1
else
  echo "OK    every environment variable the live API declares is present"
fi

if TASKDEF="$FILE" python3 - <<'ENDOFPY'
import json, os
d = json.load(open(os.environ["TASKDEF"]))
c = d["containerDefinitions"][0]
env = {e["name"]: e["value"] for e in c["environment"]}
bad = []
profiles = env.get("SPRING_PROFILES_ACTIVE", "").split(",")
if "aws" not in profiles:
    bad.append("SPRING_PROFILES_ACTIVE must still contain 'aws'")
if "grant-admin" not in profiles:
    bad.append("SPRING_PROFILES_ACTIVE must contain 'grant-admin'")
if env.get("SPRING_MAIN_WEB_APPLICATION_TYPE") != "none":
    bad.append("SPRING_MAIN_WEB_APPLICATION_TYPE must be 'none' - no HTTP listener")
if "portMappings" in c:
    bad.append("portMappings must be absent - this task is never behind the ALB")
if "@sha256:" not in c.get("image", ""):
    bad.append("image must be pinned by digest, not by tag")
if d.get("family") == "research-archive-platform-dev-api":
    bad.append("family must NOT be the API service's own family")
for line in bad:
    print("FAIL  " + line)
raise SystemExit(1 if bad else 0)
ENDOFPY
then
  echo "OK    profiles, non-web mode, no ports, digest-pinned image, separate family"
else
  FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
  echo >&2
  echo "Refusing. A missing setting here does not fail at registration - it fails" >&2
  echo "when the task starts, before the CLI runs, which is how 2026-11-02 went." >&2
  exit 1
fi
echo "OK    $FILE is safe to register"
