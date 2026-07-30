#!/usr/bin/env bash
# Sets up synthetic Subaward attachment fixtures for local development:
# generates 3 small placeholder files, seeds matching attachment metadata
# into the local PostgreSQL database, and verifies the row count. Never
# touches Oracle, AWS, or any real BU data - see tools/
# generate-local-attachment-fixtures.py and
# scripts/seed-local-subaward-attachments.sql for the individual steps
# this wraps. Safe to re-run - the SQL seed is idempotent.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

POSTGRES_HOST="${POSTGRES_HOST:-127.0.0.1}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-$(whoami)}"
POSTGRES_DB="${POSTGRES_DB:-research_archive}"

echo "Research Archive Platform - local attachment fixture setup"
echo "============================================================"

echo
echo "1. Generating synthetic sample files..."
python3 "$ROOT_DIR/tools/generate-local-attachment-fixtures.py"

echo
echo "2. Seeding synthetic attachment metadata into PostgreSQL..."
echo "   (host=$POSTGRES_HOST port=$POSTGRES_PORT db=$POSTGRES_DB user=$POSTGRES_USER)"
psql \
    -h "$POSTGRES_HOST" \
    -p "$POSTGRES_PORT" \
    -U "$POSTGRES_USER" \
    -d "$POSTGRES_DB" \
    -v ON_ERROR_STOP=1 \
    -f "$ROOT_DIR/scripts/seed-local-subaward-attachments.sql"

echo
echo "3. Verifying the seeded attachment count..."
ACTUAL_COUNT="$(psql \
    -h "$POSTGRES_HOST" \
    -p "$POSTGRES_PORT" \
    -U "$POSTGRES_USER" \
    -d "$POSTGRES_DB" \
    -tAc "SELECT COUNT(*) FROM archive.subaward_attachment WHERE attachment_id BETWEEN 9000000001 AND 9000000004;")"
EXPECTED_COUNT=4

if [[ "$ACTUAL_COUNT" -ne "$EXPECTED_COUNT" ]]; then
    echo "ERROR: expected $EXPECTED_COUNT synthetic attachment rows, found $ACTUAL_COUNT." >&2
    exit 1
fi

echo "OK: $ACTUAL_COUNT synthetic attachment rows present for subaward_id=1."
echo
echo "Done. app.attachments.storage=local is already the default in"
echo "application-local.yml, so running the API with"
echo "SPRING_PROFILES_ACTIVE=local (scripts/run-local.sh or"
echo "api/scripts/dev.sh) will serve these fixtures at:"
echo "  GET /api/subawards/1/attachments"
echo "  GET /api/subawards/1/attachments/{attachmentId}/download"
