#!/bin/bash

set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$REPO_ROOT/ops/open-ui.sh"
