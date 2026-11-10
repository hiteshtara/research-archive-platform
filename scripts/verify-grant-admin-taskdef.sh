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
# OperatorTaskDefinitionTest asserts the shape of the committed file and runs
# in CI. It cannot see a variable added to the DEPLOYED API later. This script
# closes that gap by diffing the committed file against the live API task
# definition, so a new API requirement shows up here instead of as a failed
# one-off task.
#
# Usage:
#   scripts/verify-grant-admin-taskdef.sh [path-to-taskdef.json]
#
# Defaults to the grant-admin definition. Pass a path to check another
# operator definition, such as ops/grant-admin/dev-link-identity.taskdef.json;
# the expected Spring profile is derived from the file's own family, so no
# argument but the path is needed.
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

# --- The image the SERVICE is actually running ---------------------------
#
# WHY THE SERVICE AND NOT THE FAMILY. The latest revision of the API family
# is not necessarily the one running; a deploy registers a revision and then
# updates the service, and those can diverge. "What is running" is the
# service's own taskDefinition, so that is what an operator utility must
# match.
#
# WHY THIS CHECK EXISTS. The committed grant-admin definition pinned its
# image by digest - correctly - and the digest was then maintained by hand.
# It was last bumped for #103; five releases later it still said so, while
# the API ran revision 87. An operator running the only approved grant path
# would have run months-old CLI code against today's database, and nothing
# said a word. OperatorTaskDefinitionTest catches this in CI against the
# recorded snapshot; this catches it against the live service, which the
# snapshot can itself fall behind.

SERVICE_TASKDEF="$(aws ecs describe-services   --region "$REGION"   --cluster "$API_FAMILY"   --services "$API_FAMILY"   --query 'services[0].taskDefinition'   --output text 2>/dev/null || true)"

if [ -z "$SERVICE_TASKDEF" ] || [ "$SERVICE_TASKDEF" = "None" ]; then
  echo "FAIL  could not read which task definition the $API_FAMILY service is running" >&2
  FAILED=1
else
  LIVE_IMAGE="$(aws ecs describe-task-definition     --region "$REGION"     --task-definition "$SERVICE_TASKDEF"     --query 'taskDefinition.containerDefinitions[0].image'     --output text 2>/dev/null || true)"
  OURS_IMAGE="$(TASKDEF="$FILE" python3 -c '
import json, os
d = json.load(open(os.environ["TASKDEF"]))
print(d["containerDefinitions"][0].get("image", ""))
')"

  if [ -z "$LIVE_IMAGE" ] || [ "$LIVE_IMAGE" = "None" ]; then
    echo "FAIL  could not read the running image for $SERVICE_TASKDEF" >&2
    FAILED=1
  elif [ "$LIVE_IMAGE" != "$OURS_IMAGE" ]; then
    echo "FAIL  this operator definition does not run the image the API is running." >&2
    echo "      API service is running: $LIVE_IMAGE" >&2
    echo "      this definition pins:   $OURS_IMAGE" >&2
    echo "      Registering or running it would execute DIFFERENT code from the" >&2
    echo "      deployed API. Run ops/sync-operator-taskdefs.sh, commit the" >&2
    echo "      change, and re-run this script." >&2
    FAILED=1
  else
    echo "OK    image matches the running API service exactly"
  fi
fi

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
# The utility profile is DERIVED from the family rather than hardcoded, so
# this script works for every operator task definition. It was written for
# grant-admin only, and a second utility (link-identity) would have failed
# here spuriously - which is the kind of false alarm that gets a check
# switched off.
family = d.get("family", "")
prefix = "research-archive-platform-dev-"
utility = family[len(prefix):] if family.startswith(prefix) else None
if not utility:
    bad.append("family must be " + prefix + "<utility>, got " + repr(family))
elif utility not in profiles:
    bad.append("SPRING_PROFILES_ACTIVE must contain '" + utility + "'"
               " to match the family " + family)
if "bootstrap-admin" in profiles:
    bad.append("bootstrap-admin is a separate one-time procedure; it must not "
               "be active here")
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
