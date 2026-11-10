#!/usr/bin/env bash
#
# Point every committed operator task definition at the image the API
# service is actually running.
#
# WHY THIS EXISTS. The operator definitions pin their image by DIGEST, which
# is right - the ECR repository is MUTABLE, so a tag can be repointed - and
# the digest was then maintained by hand. It was last bumped for #103; by
# revision 87 it still said so, and an operator running the only approved
# grant path would have run months-old CLI code against today's database.
# Three things now prevent that, and this is the one that fixes it:
#
#   OperatorTaskDefinitionTest          CI, against the recorded snapshot
#   verify-grant-admin-taskdef.sh       live, against the running service
#   this script                         the one command that corrects both
#
# It edits FILES ONLY. It registers nothing, deploys nothing and touches no
# AWS resource beyond two read-only describes. Review the diff, commit it,
# and register the definitions as a separate, deliberate act.
#
# Usage, after an API deploy:
#   AWS_PROFILE=bu-nprd ops/sync-operator-taskdefs.sh
#
set -uo pipefail

REGION="us-east-1"
EXPECTED_ACCOUNT="770203350335"
API_SERVICE="research-archive-platform-dev-api"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TASKDEF_DIR="$REPO_ROOT/ops/grant-admin"
READINESS="$REPO_ROOT/ui/src/features/qa/securityReadiness.json"

account="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)"
if [ "$account" != "$EXPECTED_ACCOUNT" ]; then
  echo "FAIL  expected AWS account $EXPECTED_ACCOUNT, got ${account:-<none>}." >&2
  echo "      Set AWS_PROFILE=bu-nprd. The default profile on this Mac has" >&2
  echo "      resolved to a personal account before." >&2
  exit 1
fi
echo "OK    AWS account $account"

service_taskdef="$(aws ecs describe-services \
  --region "$REGION" --cluster "$API_SERVICE" --services "$API_SERVICE" \
  --query 'services[0].taskDefinition' --output text 2>/dev/null || true)"
if [ -z "$service_taskdef" ] || [ "$service_taskdef" = "None" ]; then
  echo "FAIL  could not read which task definition $API_SERVICE is running." >&2
  exit 1
fi

live_image="$(aws ecs describe-task-definition \
  --region "$REGION" --task-definition "$service_taskdef" \
  --query 'taskDefinition.containerDefinitions[0].image' --output text 2>/dev/null || true)"
if [[ "$live_image" != *"@sha256:"* ]]; then
  echo "FAIL  the running image is not pinned by digest: ${live_image:-<none>}" >&2
  echo "      Copying a tag into an operator definition would let it drift," >&2
  echo "      which is the whole problem this script exists to fix." >&2
  exit 1
fi

echo "      service:  $service_taskdef"
echo "      image:    $live_image"

changed=0
for file in "$TASKDEF_DIR"/*.taskdef.json; do
  [ -f "$file" ] || continue
  if TASKDEF="$file" IMAGE="$live_image" python3 - <<'ENDOFPY'
import json, os, sys
from collections import OrderedDict

path = os.environ["TASKDEF"]
image = os.environ["IMAGE"]
with open(path) as handle:
    taskdef = json.load(handle, object_pairs_hook=OrderedDict)

container = taskdef["containerDefinitions"][0]
if container.get("image") == image:
    sys.exit(1)          # nothing to do
container["image"] = image
with open(path, "w") as handle:
    handle.write(json.dumps(taskdef, indent=2) + "\n")
sys.exit(0)
ENDOFPY
  then
    echo "UPDATED $(basename "$file")"
    changed=$((changed + 1))
  else
    echo "OK      $(basename "$file") already matches"
  fi
done

# The CI test derives the expected digest from the readiness snapshot, so a
# deploy that refreshes one and not the other simply moves the drift.
if [ -f "$READINESS" ]; then
  recorded="$(python3 -c '
import json, sys
print(json.load(open(sys.argv[1]))["deployment"].get("imageDigest", ""))
' "$READINESS" 2>/dev/null || true)"
  live_digest="${live_image##*@}"
  if [ "$recorded" != "$live_digest" ]; then
    echo ""
    echo "WARN  securityReadiness.json records a DIFFERENT digest:" >&2
    echo "        recorded: ${recorded:-<none>}" >&2
    echo "        running:  $live_digest" >&2
    echo "      OperatorTaskDefinitionTest compares the definitions against" >&2
    echo "      the snapshot, so it will fail until the snapshot is refreshed" >&2
    echo "      too. Refreshing it is part of the deployment, not a follow-up." >&2
  else
    echo "OK    securityReadiness.json already records this digest"
  fi
fi

echo ""
if [ "$changed" -gt 0 ]; then
  echo "$changed definition(s) updated. Review the diff and commit:"
  echo "  git diff ops/grant-admin/"
  echo "Nothing was registered. Register them deliberately, after review."
else
  echo "Nothing to change."
fi
