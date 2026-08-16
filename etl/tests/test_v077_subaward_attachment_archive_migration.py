"""Regression test proving V077 (widen archive.subaward_attachment_archive
.archive_status; drop the now-incorrect ux_subaward_attachment_archive_object
UNIQUE(s3_bucket, s3_key) constraint) applies cleanly and actually fixes the
real, never-yet-executed failure it was written for, plus a real-schema
proof of etl/attachment_orchestrator.py's shared-physical-file upload path.

Real failure this corrects: etl/attachment_orchestrator.py's
subaward_binary_stage intentionally sets every archive.subaward_attachment_archive
row sharing one Oracle FILE_DATA_ID to the identical (s3_bucket, s3_key) in
a single bulk UPDATE (one physical file, many historical reference rows -
the same pattern archive.proposal_attachment already handles correctly,
which has no such UNIQUE constraint). V019's original
ux_subaward_attachment_archive_object constraint was written for an older,
now-superseded per-reference-row key scheme (one distinct key per
attachment_id - see etl/archive_etl/attachments/plugins/subaward.py's
s3_key(), which produced every one of the real 1,764 ARCHIVED rows in dev
RDS today) and rejects that bulk UPDATE the moment it touches a second row
sharing a file - live-verified 2026-08-15 not to be a rare case (11 of 13
physical files in the Subaward Code 3595 pilot population are
multiply-referenced).

Deliberately does NOT use the raw database/migrations/ directory - that
would also pick up whatever untracked, uncommitted files happen to be
sitting in this working tree (V071/V073 as of 2026-08-15 - see
docs/project-memory/CURRENT_STATE.md's "Open items") and silently make
this test's "clean chain" depend on local state a fresh checkout would
never have. Instead, only `git ls-files database/migrations` output is
copied into a throwaway directory before applying it - this must be run
with V077 staged (`git add`) if run before it is committed, since
`git ls-files` reflects the index, not just HEAD.

Skips entirely if no local PostgreSQL is reachable - mirrors
test_v075_proposal_award_natural_key_migration.py's pattern exactly
(throwaway, uniquely-named database per test, dropped afterward).
"""

from __future__ import annotations

import getpass
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import MagicMock, patch

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

from archive_etl.upload.migrations import apply_migrations

import attachment_orchestrator as orch

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"
TARGET_VERSION = 77

_MIGRATION_VERSION_PATTERN = re.compile(r"^V(\d+)__")


def _migration_version(path: Path) -> int | None:
    """Parses the numeric version from a migration filename - not a bare
    string prefix match, since filenames are zero-padded (V077, not V77)
    while TARGET_VERSION is a plain int."""
    match = _MIGRATION_VERSION_PATTERN.match(path.name)
    return int(match.group(1)) if match else None

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


def _git_tracked_migration_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--", "database/migrations"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    paths = [
        REPO_ROOT / line
        for line in result.stdout.splitlines()
        if line.strip()
    ]
    return [path for path in paths if path.exists()]


def _clean_migrations_dir(*, max_version: int | None = None) -> Path:
    """Copies only git-tracked migration files (optionally capped below a
    version) into a fresh temp directory - never the raw source tree,
    which may contain untracked files. Caller owns cleanup."""
    tracked = _git_tracked_migration_files()
    destination = Path(tempfile.mkdtemp(prefix="clean_migrations_"))
    for source_path in tracked:
        if max_version is not None:
            version = _migration_version(source_path)
            if version is not None and version >= max_version:
                continue
        shutil.copy2(source_path, destination / source_path.name)
    return destination


def _create_throwaway_database(name: str) -> None:
    maintenance = _maintenance_engine()
    with maintenance.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'CREATE DATABASE "{name}"'))
    maintenance.dispose()


def _drop_throwaway_database(name: str) -> None:
    maintenance = _maintenance_engine()
    with maintenance.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'DROP DATABASE IF EXISTS "{name}"'))
    maintenance.dispose()


def _database_engine(name: str) -> Engine:
    return create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
        f"{POSTGRES_PORT}/{name}"
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class V077SubawardAttachmentArchiveMigrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.assertTrue(
            (self.clean_migrations_dir / (
                "V077__widen_subaward_attachment_archive_status_"
                "and_allow_shared_objects.sql"
            )).exists(),
            "V077 must be git-tracked (staged, if not yet committed) for "
            "this test to exercise it at all",
        )

        self.db_name = f"pytest_v077_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)

    def tearDown(self) -> None:
        self.engine.dispose()
        _drop_throwaway_database(self.db_name)
        shutil.rmtree(self.clean_migrations_dir, ignore_errors=True)

    def test_full_committed_migration_chain_applies_cleanly(self) -> None:
        # apply_migrations already ran once in setUp - re-running here
        # (idempotent, tracked via schema_migration) is the exact code
        # path --migrate-only runs in production and must not raise.
        apply_migrations(self.engine, self.clean_migrations_dir)

    def test_ux_subaward_attachment_archive_object_no_longer_exists(self) -> None:
        with self.engine.connect() as connection:
            exists = connection.execute(
                text(
                    "SELECT 1 FROM pg_constraint "
                    "WHERE conname = 'ux_subaward_attachment_archive_object'"
                )
            ).first()
        self.assertIsNone(exists)

    def _seed_parent_chain(self, connection, attachment_id: int) -> None:
        connection.execute(
            text(
                "INSERT INTO archive.subaward "
                "(subaward_id, sequence_number, subaward_code) "
                "VALUES (:id, 1, 'CONSTRAINT-TEST')"
            ),
            {"id": attachment_id},
        )
        connection.execute(
            text(
                "INSERT INTO archive.subaward_attachment "
                "(attachment_id, subaward_id, subaward_code, sequence_number) "
                "VALUES (:id, :id, 'CONSTRAINT-TEST', 1)"
            ),
            {"id": attachment_id},
        )

    def test_archive_status_accepts_all_five_states(self) -> None:
        with self.engine.begin() as connection:
            for offset, status in enumerate(
                ["PENDING", "UPLOADING", "ARCHIVED", "MISSING", "FAILED"]
            ):
                attachment_id = 800000 + offset
                self._seed_parent_chain(connection, attachment_id)
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment_archive "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number, archive_status) VALUES "
                        "(:id, :id, 'CONSTRAINT-TEST', 1, :status)"
                    ),
                    {"id": attachment_id, "status": status},
                )

    def test_archive_status_rejects_invalid_value(self) -> None:
        with self.engine.begin() as connection:
            self._seed_parent_chain(connection, 777777)

        with self.assertRaises(Exception) as ctx:
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment_archive "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number, archive_status) VALUES "
                        "(777777, 777777, 'CONSTRAINT-TEST', 1, 'BOGUS')"
                    )
                )
        self.assertIn(
            "ck_subaward_attachment_archive_status", str(ctx.exception)
        )

    def test_default_is_pending(self) -> None:
        with self.engine.begin() as connection:
            self._seed_parent_chain(connection, 766000)
            connection.execute(
                text(
                    "INSERT INTO archive.subaward_attachment_archive "
                    "(attachment_id, subaward_id, subaward_code, "
                    "sequence_number) VALUES "
                    "(766000, 766000, 'CONSTRAINT-TEST', 1)"
                )
            )
            status = connection.execute(
                text(
                    "SELECT archive_status FROM "
                    "archive.subaward_attachment_archive "
                    "WHERE attachment_id = 766000"
                )
            ).scalar()
        self.assertEqual(status, "PENDING")

    def test_attachment_id_primary_key_still_enforced(self) -> None:
        with self.engine.begin() as connection:
            self._seed_parent_chain(connection, 733000)
            connection.execute(
                text(
                    "INSERT INTO archive.subaward_attachment_archive "
                    "(attachment_id, subaward_id, subaward_code, "
                    "sequence_number) VALUES "
                    "(733000, 733000, 'CONSTRAINT-TEST', 1)"
                )
            )

        with self.assertRaises(IntegrityError):
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment_archive "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number) VALUES "
                        "(733000, 733000, 'CONSTRAINT-TEST', 1)"
                    )
                )

    def test_foreign_keys_to_subaward_and_subaward_attachment_still_enforced(
        self,
    ) -> None:
        with self.assertRaises(IntegrityError):
            with self.engine.begin() as connection:
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment_archive "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number) VALUES "
                        "(722000, 722000, 'CONSTRAINT-TEST', 1)"
                    )
                )

    def test_two_or_more_rows_may_now_share_one_bucket_and_key(self) -> None:
        shared_file_data_id = "22222222-2222-2222-2222-222222222222"
        with self.engine.begin() as connection:
            self._seed_parent_chain(connection, 744000)
            self._seed_parent_chain(connection, 744001)
            for attachment_id in (744000, 744001):
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment_archive "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number, file_data_id, s3_bucket, s3_key, "
                        "archive_status) VALUES "
                        "(:id, :id, 'CONSTRAINT-TEST', 1, :fid, "
                        "'shared-bucket', 'subawards/shared-file.pdf', "
                        "'PENDING')"
                    ),
                    {"id": attachment_id, "fid": shared_file_data_id},
                )

            # Mirrors mark_subaward_file_uploaded's own bulk UPDATE
            # exactly - one statement, every sharing row updated together.
            connection.execute(
                text(
                    "UPDATE archive.subaward_attachment_archive "
                    "SET archive_status = 'ARCHIVED', "
                    "s3_bucket = 'shared-bucket', "
                    "s3_key = 'subawards/shared-file.pdf' "
                    "WHERE file_data_id = :fid"
                ),
                {"fid": shared_file_data_id},
            )

            matching = connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.subaward_attachment_archive "
                    "WHERE file_data_id = :fid AND archive_status = 'ARCHIVED' "
                    "AND s3_bucket = 'shared-bucket' "
                    "AND s3_key = 'subawards/shared-file.pdf'"
                ),
                {"fid": shared_file_data_id},
            ).scalar()
        self.assertEqual(matching, 2)

    def test_existing_archived_row_from_before_v077_is_preserved(self) -> None:
        """Two-phase, mirroring
        SubawardAttachmentArchiveMigrationTest's Java "preservation"
        container exactly: apply everything BELOW V077 to a fresh DB,
        seed a fixture row under V019's original constraint, snapshot it,
        then apply V077 alone and re-read - must be byte-for-byte
        identical."""
        before_migrations_dir = _clean_migrations_dir(max_version=TARGET_VERSION)
        db_name = f"pytest_v077_before_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(db_name)
        engine = _database_engine(db_name)
        try:
            apply_migrations(engine, before_migrations_dir)

            with engine.begin() as connection:
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward "
                        "(subaward_id, sequence_number, subaward_code) "
                        "VALUES (900001, 1, 'TEST-CODE-1')"
                    )
                )
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number, file_data_id, file_name, mime_type) "
                        "VALUES (900001, 900001, 'TEST-CODE-1', 1, "
                        "'11111111-1111-1111-1111-111111111111', "
                        "'fixture.pdf', 'application/pdf')"
                    )
                )
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment_archive "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number, file_data_id, original_file_name, "
                        "mime_type, s3_bucket, s3_key, byte_size, sha256, "
                        "archive_status, archived_timestamp) VALUES "
                        "(900001, 900001, 'TEST-CODE-1', 1, "
                        "'11111111-1111-1111-1111-111111111111', "
                        "'fixture.pdf', 'application/pdf', 'test-bucket', "
                        "'subawards/900001/900001/fixture.pdf', 1234, "
                        f"'{'ab' * 32}', 'ARCHIVED', "
                        "'2026-08-01T00:00:00Z')"
                    )
                )

            row_query = text(
                "SELECT attachment_id, subaward_id, subaward_code, "
                "sequence_number, file_data_id, original_file_name, "
                "mime_type, s3_bucket, s3_key, byte_size, sha256, "
                "archive_status, archived_timestamp, error_message "
                "FROM archive.subaward_attachment_archive "
                "WHERE attachment_id = 900001"
            )
            with engine.connect() as connection:
                before = dict(
                    connection.execute(row_query).mappings().one()
                )

            # Real V077 (git-tracked, staged) applied on top.
            v077_path = next(
                path for path in _git_tracked_migration_files()
                if _migration_version(path) == TARGET_VERSION
            )
            with engine.begin() as connection:
                connection.execute(text(v077_path.read_text()))

            with engine.connect() as connection:
                after = dict(
                    connection.execute(row_query).mappings().one()
                )

            self.assertEqual(before, after)
            self.assertEqual(after["archive_status"], "ARCHIVED")
        finally:
            engine.dispose()
            _drop_throwaway_database(db_name)
            shutil.rmtree(before_migrations_dir, ignore_errors=True)


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class SubawardBinaryStageSharedFileDataIdRealSchemaTest(unittest.TestCase):
    """Runs the real etl.attachment_orchestrator.subaward_binary_stage
    against a real, fully-migrated (through V077) Postgres schema - only
    Oracle/S3 I/O is simulated (mocked), never the database layer, so this
    genuinely exercises the bulk UPDATE ... WHERE file_data_id = ... and
    the now-dropped UNIQUE(s3_bucket, s3_key) constraint together, which
    every existing attachment_orchestrator test mocks away entirely
    (mark_subaward_file_uploaded/select_subaward_upload_candidates are
    both patched out in etl/tests/test_attachment_orchestrator.py - this
    is deliberately the one place that does not)."""

    SHARED_FILE_DATA_ID = "33333333-3333-3333-3333-333333333333"
    REFERENCE_ATTACHMENT_IDS = (611001, 611002, 611003)

    def setUp(self) -> None:
        self.clean_migrations_dir = _clean_migrations_dir()
        self.db_name = f"pytest_orch_v077_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.db_name)
        self.engine = _database_engine(self.db_name)
        apply_migrations(self.engine, self.clean_migrations_dir)

        with self.engine.begin() as connection:
            for attachment_id in self.REFERENCE_ATTACHMENT_IDS:
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward "
                        "(subaward_id, sequence_number, subaward_code) "
                        "VALUES (:id, 1, 'SHARED-TEST')"
                    ),
                    {"id": attachment_id},
                )
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number, file_data_id, file_name, mime_type) "
                        "VALUES (:id, :id, 'SHARED-TEST', 1, :fid, "
                        "'shared.pdf', 'application/pdf')"
                    ),
                    {"id": attachment_id, "fid": self.SHARED_FILE_DATA_ID},
                )
                # Mirrors _upsert_subaward_attachments' own post-V077
                # behavior: a fresh archive-state row defaults to PENDING.
                connection.execute(
                    text(
                        "INSERT INTO archive.subaward_attachment_archive "
                        "(attachment_id, subaward_id, subaward_code, "
                        "sequence_number, file_data_id, original_file_name, "
                        "mime_type) VALUES "
                        "(:id, :id, 'SHARED-TEST', 1, :fid, 'shared.pdf', "
                        "'application/pdf')"
                    ),
                    {"id": attachment_id, "fid": self.SHARED_FILE_DATA_ID},
                )

    def tearDown(self) -> None:
        self.engine.dispose()
        _drop_throwaway_database(self.db_name)
        shutil.rmtree(self.clean_migrations_dir, ignore_errors=True)

    def _row_count(self, **where: object) -> int:
        clause = " AND ".join(f"{column} = :{column}" for column in where)
        with self.engine.connect() as connection:
            return connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.subaward_attachment_archive "
                    f"WHERE {clause}"
                ),
                where,
            ).scalar()

    def test_three_shared_references_produce_exactly_one_simulated_upload_and_no_duplicate_key_exception(
        self,
    ) -> None:
        with (
            patch.object(
                orch, "_batch_file_data_ids",
                return_value=[self.SHARED_FILE_DATA_ID],
            ),
            patch("attachment_orchestrator.boto3.client") as boto_client,
            patch.object(orch, "_stream_file_data_to_s3") as stream,
            # subaward_binary_stage resolves Oracle credentials and opens
            # a connection inline (not inside _stream_file_data_to_s3),
            # before ever calling the mocked stream function - both must
            # be faked so this test never needs real Oracle access.
            patch.object(
                orch, "require_oracle_environment",
                return_value={
                    "ORACLE_USER": "test", "ORACLE_PASSWORD": "test",
                    "ORACLE_DSN": "test",
                },
            ),
            patch("attachment_orchestrator.oracledb.connect"),
        ):
            s3_client = MagicMock()
            s3_client.head_object.side_effect = orch.ClientError(
                {"Error": {"Code": "404", "Message": "Not Found"}}, "HeadObject"
            )
            boto_client.return_value = s3_client
            stream.return_value = (2048, "deadbeef" * 8)

            # No exception (in particular, no
            # "duplicate key value violates unique constraint
            # ux_subaward_attachment_archive_object") is the core proof -
            # this call previously would have raised on the bulk UPDATE's
            # second matching row before V077.
            report = orch.subaward_binary_stage(
                self.engine, bucket="test-bucket", batch_id=1
            )

        # Exactly one simulated Oracle/S3 stream for three sharing
        # reference rows - the physical-file dedup this whole design is
        # for, proven end to end, not just at the SQL-selection layer.
        stream.assert_called_once()
        self.assertEqual(report["uploaded"], 1)

        # Every reference row sharing the file must now point at the one
        # shared object, not just the row select_subaward_upload_candidates
        # happened to pick as representative.
        self.assertEqual(
            self._row_count(
                file_data_id=self.SHARED_FILE_DATA_ID,
                archive_status="ARCHIVED",
            ),
            len(self.REFERENCE_ATTACHMENT_IDS),
        )

    def test_idempotent_rerun_performs_zero_additional_uploads(self) -> None:
        with (
            patch.object(
                orch, "_batch_file_data_ids",
                return_value=[self.SHARED_FILE_DATA_ID],
            ),
            patch("attachment_orchestrator.boto3.client") as boto_client,
            patch.object(orch, "_stream_file_data_to_s3") as stream,
            # subaward_binary_stage resolves Oracle credentials and opens
            # a connection inline (not inside _stream_file_data_to_s3),
            # before ever calling the mocked stream function - both must
            # be faked so this test never needs real Oracle access.
            patch.object(
                orch, "require_oracle_environment",
                return_value={
                    "ORACLE_USER": "test", "ORACLE_PASSWORD": "test",
                    "ORACLE_DSN": "test",
                },
            ),
            patch("attachment_orchestrator.oracledb.connect"),
        ):
            s3_client = MagicMock()
            s3_client.head_object.side_effect = orch.ClientError(
                {"Error": {"Code": "404", "Message": "Not Found"}}, "HeadObject"
            )
            boto_client.return_value = s3_client
            stream.return_value = (2048, "deadbeef" * 8)

            first_report = orch.subaward_binary_stage(
                self.engine, bucket="test-bucket", batch_id=1
            )
            stream.reset_mock()

            second_report = orch.subaward_binary_stage(
                self.engine, bucket="test-bucket", batch_id=1
            )

        self.assertEqual(first_report["uploaded"], 1)
        stream.assert_not_called()
        # select_subaward_upload_candidates filters to PENDING/UPLOADING
        # at the SQL level - once ARCHIVED, these rows are not "skipped",
        # they are simply never selected as candidates again at all.
        self.assertEqual(second_report["physical_files_selected"], 0)
        self.assertEqual(second_report["uploaded"], 0)
        self.assertEqual(second_report["skipped_already_uploaded"], 0)
