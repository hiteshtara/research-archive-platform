"""Verify connectivity to the Research Archive PostgreSQL database.

Reads POSTGRES_HOST / POSTGRES_PORT / POSTGRES_DB / POSTGRES_USER /
POSTGRES_PASSWORD (and optional POSTGRES_SSLMODE) from the environment,
opens a connection, and reports the server version plus the applied
migration state so an operator can confirm the target schema is current
before running a load.

Usage:
    uv run python scripts/test_postgres_connection.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import text  # noqa: E402
from sqlalchemy.exc import SQLAlchemyError  # noqa: E402

from archive_etl.config.settings import ConfigurationError  # noqa: E402
from archive_etl.upload.migrations import (  # noqa: E402
    discover_migrations,
    find_missing_migration_versions,
    get_applied_versions,
)
from archive_etl.upload.postgres import create_postgres_engine  # noqa: E402

PROJECT_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIRECTORY = PROJECT_ROOT / "database" / "migrations"


def main() -> int:
    try:
        engine = create_postgres_engine()
    except ConfigurationError as error:
        print(f"Configuration error: {error}", file=sys.stderr)
        return 1

    print(f"Connecting to PostgreSQL host '{engine.url.host}'...")

    try:
        with engine.connect() as connection:
            server_version = connection.execute(text("SELECT version()")).scalar_one()
    except SQLAlchemyError as error:
        print(f"Connection failed: {error}", file=sys.stderr)
        return 1

    print("Connected successfully.")
    print(f"PostgreSQL server version: {server_version}")

    if not MIGRATIONS_DIRECTORY.exists():
        print(f"Migrations directory not found, skipping migration check: {MIGRATIONS_DIRECTORY}")
        return 0

    on_disk = discover_migrations(MIGRATIONS_DIRECTORY)
    missing = find_missing_migration_versions(MIGRATIONS_DIRECTORY)

    if missing:
        print(
            "WARNING: gap in migration sequence on disk - missing version(s): "
            + ", ".join(f"V{version:03d}" for version in missing)
        )

    try:
        applied = get_applied_versions(engine)
    except SQLAlchemyError:
        print("Migration table not present yet - no migrations have been applied.")
        return 0

    pending = [version for version, _, _ in on_disk if version not in applied]

    print(
        f"Migrations on disk: {len(on_disk)}, applied: {len(applied)}, "
        f"pending: {len(pending)}"
    )
    if pending:
        versions = ", ".join(f"V{version:03d}" for version in pending)
        print(f"Pending migration version(s): {versions}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
