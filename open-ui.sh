#!/bin/bash

set -e

URL="https://main.d33qc0afy3ltcj.amplifyapp.com"

echo "$URL"

if command -v open >/dev/null 2>&1; then
    open "$URL"
fi
