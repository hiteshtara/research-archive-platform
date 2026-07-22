#!/bin/bash

set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REGION="us-east-1"
AMPLIFY_APP_ID="d33qc0afy3ltcj"
AMPLIFY_BRANCH="main"

pause() {
  echo
  read -r -p "Press Enter to continue..."
}

run_script() {
  local script="$1"

  if [[ ! -x "$OPS_DIR/$script" ]]; then
    echo "ERROR: Missing or non-executable script:"
    echo "$OPS_DIR/$script"
    return 1
  fi

  "$OPS_DIR/$script"
}

show_amplify_jobs() {
  aws amplify list-jobs \
    --region "$REGION" \
    --app-id "$AMPLIFY_APP_ID" \
    --branch-name "$AMPLIFY_BRANCH" \
    --max-results 10 \
    --query 'jobSummaries[].{JobId:jobId,Status:status,Start:startTime,End:endTime,Commit:commitId,Message:commitMessage}' \
    --output table
}

trigger_amplify_build() {
  aws amplify start-job \
    --region "$REGION" \
    --app-id "$AMPLIFY_APP_ID" \
    --branch-name "$AMPLIFY_BRANCH" \
    --job-type RELEASE
}

while true; do
  clear

  cat <<'MENU'
==================================================
 Research Archive Platform - AWS Operations
==================================================

1) Deploy API
2) View API logs
3) Open UI
4) Print API URL
5) Check API/ECS status
6) Trigger Amplify build
7) Show Amplify jobs
8) Open AWS operations handbook
9) Exit

MENU

  read -r -p "Choose an option: " choice

  echo

  case "$choice" in
    1)
      run_script "deploy-api.sh"
      pause
      ;;
    2)
      run_script "logs-api.sh"
      ;;
    3)
      run_script "open-ui.sh"
      pause
      ;;
    4)
      run_script "print-api.sh"
      echo
      pause
      ;;
    5)
      run_script "status-api.sh"
      pause
      ;;
    6)
      trigger_amplify_build
      pause
      ;;
    7)
      show_amplify_jobs
      pause
      ;;
    8)
      HANDBOOK="$OPS_DIR/AWS_OPERATIONS.md"

      if [[ ! -f "$HANDBOOK" ]]; then
        echo "ERROR: AWS operations handbook not found:"
        echo "$HANDBOOK"
      elif command -v open >/dev/null 2>&1; then
        open "$HANDBOOK"
      else
        less "$HANDBOOK"
      fi

      pause
      ;;
    9)
      echo "Goodbye."
      exit 0
      ;;
    *)
      echo "Invalid option: $choice"
      pause
      ;;
  esac
done
