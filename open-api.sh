#!/bin/bash

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$REPO_ROOT/terraform/environments/dev"

if [[ ! -d "$TF_DIR" ]]; then
  echo "ERROR: Terraform directory not found: $TF_DIR"
  exit 1
fi

API_URL=$(terraform -chdir="$TF_DIR" output -raw api_url)

if [[ -z "$API_URL" ]]; then
  echo "ERROR: Could not determine API URL."
  exit 1
fi

echo "$API_URL"

if command -v open >/dev/null 2>&1; then
  open "$API_URL"
fi
