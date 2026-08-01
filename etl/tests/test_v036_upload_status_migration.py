"""Regression test proving the full migration chain - and V036
specifically - applies cleanly against a real, clean PostgreSQL
database.

Added after a live ECS --migrate-only run failed applying V036 with:

    psycopg.errors.StringDataRightTruncation: value too long for type
    character varying(20)

V035 created archive.attachment_object.upload_status as VARCHAR(20),
sized only for Sprint 1's short values (PENDING/SKIPPED/UPLOADED/
FAILED, all <= 8 characters). V036 tried to UPDATE existing SKIPPED
rows to 'MISSING_SOURCE_CONTENT' (22 characters) while the column was
still VARCHAR(20), before ever reaching the ALTER TABLE ... DROP/ADD
CONSTRAINT statements - Postgres rejected the UPDATE outright. V036 now
widens the column to VARCHAR(30) (matching this schema's existing
convention for status/code columns) before writing that value.

Skips entirely if no local PostgreSQL is reachable - this suite must
still pass without one (see scripts/run-local.sh for how local Postgres
is normally provisioned). Every test runs the migrations against a
freshly created, uniquely-named, throwaway database - never an existing
one - and drops it afterward.
"""

from __future__ import annotations

import getpass
import os
import shutil
import unittest
import uuid
from pathlib import Path
from tempfile import TemporaryDirectory

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

from archive_etl.upload.migrations import apply_migrations, discover_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"

POSTGRES_HOST = os.environ.get("PYTEST_POSTGRES_HOST", "localhost")
POSTGRES_PORT = os.environ.get("PYTEST_POSTGRES_PORT", "5432")
POSTGRES_USER = os.environ.get("PYTEST_POSTGRES_USER", getpass.getuser())
MAINTENANCE_DB = os.environ.get("PYTEST_POSTGRES_MAINTENANCE_DB", "postgres")


def _maintenance_engine() -> Engine:
    return create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
        f"{POSTGRES_PORT}/{MAINTENANCE_DB}"
    )


def _postgres_available() -> bool:
    try:
        engine = _maintenance_engine()
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        engine.dispose()
    except Exception:
        return False
    return True


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class V036MigrationRegressionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db_name = f"pytest_v036_{uuid.uuid4().hex[:12]}"

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'CREATE DATABASE "{self.db_name}"'))
        maintenance.dispose()

        self.engine = create_engine(
            f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
            f"{POSTGRES_PORT}/{self.db_name}"
        )

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    def test_full_migration_chain_applies_cleanly(self) -> None:
        # This is the exact code path --migrate-only runs in production -
        # must not raise.
        apply_migrations(self.engine, MIGRATIONS_DIR)

    def test_upload_status_column_is_widened_to_varchar_30(self) -> None:
        apply_migrations(self.engine, MIGRATIONS_DIR)

        with self.engine.connect() as connection:
            length = connection.execute(
                text(
                    "SELECT character_maximum_length "
                    "FROM information_schema.columns "
                    "WHERE table_schema = 'archive' "
                    "AND table_name = 'attachment_object' "
                    "AND column_name = 'upload_status'"
                )
            ).scalar_one()

        self.assertEqual(length, 30)

    def test_missing_source_content_value_fits_without_truncation(self) -> None:
        apply_migrations(self.engine, MIGRATIONS_DIR)

        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.attachment_object "
                    "(file_id, upload_status) "
                    "VALUES (:file_id, 'MISSING_SOURCE_CONTENT')"
                ),
                {"file_id": 1},
            )
            value = connection.execute(
                text(
                    "SELECT upload_status FROM archive.attachment_object "
                    "WHERE file_id = 1"
                )
            ).scalar_one()

        self.assertEqual(value, "MISSING_SOURCE_CONTENT")

    def test_check_constraint_accepts_every_expected_status(self) -> None:
        apply_migrations(self.engine, MIGRATIONS_DIR)

        expected_statuses = [
            "PENDING",
            "UPLOADING",
            "UPLOADED",
            "FAILED",
            "MISSING_SOURCE_CONTENT",
        ]

        with self.engine.begin() as connection:
            for file_id, status in enumerate(expected_statuses, start=1):
                connection.execute(
                    text(
                        "INSERT INTO archive.attachment_object "
                        "(file_id, upload_status) VALUES (:file_id, :status)"
                    ),
                    {"file_id": file_id, "status": status},
                )

    def test_check_constraint_still_rejects_invalid_values(self) -> None:
        apply_migrations(self.engine, MIGRATIONS_DIR)

        with self.assertRaises(IntegrityError):
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        "INSERT INTO archive.attachment_object "
                        "(file_id, upload_status) "
                        "VALUES (:file_id, 'NOT_A_REAL_STATUS')"
                    ),
                    {"file_id": 99},
                )

    def test_skipped_rows_are_renamed_to_missing_source_content(self) -> None:
        # V036's own forward-only rename: any SKIPPED row from before V036
        # ran is updated in place, not left behind or dropped. Applies
        # migrations 1..35 first (via a temp directory containing only
        # those files, so apply_migrations' own schema_migration tracking
        # is used correctly), inserts a SKIPPED row while upload_status is
        # still VARCHAR(20), then applies the real, full migrations
        # directory - which sees 1..35 already applied and runs only V036.
        with TemporaryDirectory() as tmp:
            partial_dir = Path(tmp)
            for version, _description, migration_file in discover_migrations(
                MIGRATIONS_DIR
            ):
                if version <= 35:
                    shutil.copy(migration_file, partial_dir / migration_file.name)

            apply_migrations(self.engine, partial_dir)

        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO archive.attachment_object "
                    "(file_id, upload_status) VALUES (1, 'SKIPPED')"
                )
            )

        apply_migrations(self.engine, MIGRATIONS_DIR)

        with self.engine.connect() as connection:
            value = connection.execute(
                text(
                    "SELECT upload_status FROM archive.attachment_object "
                    "WHERE file_id = 1"
                )
            ).scalar_one()

        self.assertEqual(value, "MISSING_SOURCE_CONTENT")


if __name__ == "__main__":
    unittest.main()
