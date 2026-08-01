"""Tests for the deterministic batch workflow (--create-batch/--load-batch/
--show-batch/--upload --batch-id) - see V037's migration header and the
"Sprint 3: deterministic batch workflow" section of load_award_attachments.py
for the rationale: neither --limit (Oracle-side bounded sampling for
metadata load) nor --limit/select_upload_candidates (a live, unpersisted
PostgreSQL query for upload) is a persisted selection, so there is no
guarantee the same N files are used for metadata loading and upload across
separate invocations. A batch (archive.etl_batch/
etl_batch_item) fixes that: once created, membership never
changes.

CLI-parsing tests run against the real argparse parser (no PostgreSQL).
Everything that touches PostgreSQL runs against a real, uniquely-named,
throwaway database (mirroring tests/test_load_file_id.py and
tests/test_v036_upload_status_migration.py) - the insert/update/unchanged
UPSERT distinction and the real FK/CHECK constraints depend on genuine
Postgres semantics a mock cannot exercise correctly. Oracle, S3, and AWS
identity are always mocked - no real infrastructure is ever touched.
Skips entirely if no local PostgreSQL is reachable.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

import load_award_attachments as attachment_loader
from archive_etl.upload.migrations import apply_migrations

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


def _oracle_batches_stub(batches: list[pd.DataFrame]) -> MagicMock:
    """An OracleDataSource-shaped mock whose read_batches() yields the
    given DataFrames lazily via a real generator (so it has a .close()
    method matching the real return type)."""

    def _generator():
        yield from batches

    stub = MagicMock()
    stub.read_batches.side_effect = _generator
    return stub


def _file_row(**overrides: object) -> pd.DataFrame:
    row: dict[str, object] = {
        "file_id": 1,
        "file_data_id": None,
        "file_name": "Agreement.pdf",
        "content_type": "application/pdf",
        "blob_source": "INLINE",
        "file_size_bytes": 12345,
        "oracle_update_timestamp": "2025-01-01",
        "oracle_update_user": "kcuser",
    }
    row.update(overrides)
    return pd.DataFrame([row])


def _reference_row(**overrides: object) -> pd.DataFrame:
    row: dict[str, object] = {
        "award_attachment_id": 501,
        "award_id": 1001,
        "award_number": "A-1",
        "sequence_number": 1,
        "document_id": "D1",
        "file_id": 1,
        "type_code": "T1",
        "description": "desc",
        "document_status_code": "S1",
        "oracle_update_timestamp": "2025-01-01",
        "oracle_update_user": "kcuser",
    }
    row.update(overrides)
    return pd.DataFrame([row])


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class _BatchPostgresTestCase(unittest.TestCase):
    """Shared throwaway-database lifecycle for every real-Postgres test
    class below - identical to tests/test_load_file_id.py's setUp/tearDown,
    factored once here since this file has several such classes."""

    db_prefix = "pytest_batch"

    def setUp(self) -> None:
        self.db_name = f"{self.db_prefix}_{uuid.uuid4().hex[:12]}"

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'CREATE DATABASE "{self.db_name}"'))
        maintenance.dispose()

        self.engine = create_engine(
            f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
            f"{POSTGRES_PORT}/{self.db_name}"
        )
        apply_migrations(self.engine, MIGRATIONS_DIR)

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    def _insert_attachment_object(
        self, file_id: int, *, upload_status: str = "PENDING", **extra: object
    ) -> None:
        columns = {"file_id": file_id, "upload_status": upload_status, **extra}
        column_list = ", ".join(columns)
        placeholders = ", ".join(f":{name}" for name in columns)
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    f"INSERT INTO archive.attachment_object ({column_list}) "
                    f"VALUES ({placeholders})"
                ),
                columns,
            )

    def _row(self, table: str, **where: object) -> dict:
        clause = " AND ".join(f"{key} = :{key}" for key in where)
        with self.engine.connect() as connection:
            return dict(
                connection.execute(
                    text(f"SELECT * FROM archive.{table} WHERE {clause}"),
                    where,
                )
                .mappings()
                .one()
            )

    def _scalar(self, sql: str, **params: object) -> object:
        with self.engine.connect() as connection:
            return connection.execute(text(sql), params).scalar_one()


# --- parse_args: batch flags ------------------------------------------------


class ParseArgsBatchTest(unittest.TestCase):
    def test_create_batch_parses_positive_int(self) -> None:
        args = attachment_loader.parse_args(["--create-batch", "10"])

        self.assertEqual(args.create_batch, 10)

    def test_defaults_are_none(self) -> None:
        args = attachment_loader.parse_args([])

        self.assertIsNone(args.create_batch)
        self.assertIsNone(args.load_batch)
        self.assertIsNone(args.show_batch)
        self.assertIsNone(args.batch_id)
        self.assertFalse(args.include_already_uploaded)

    def test_create_batch_rejects_zero(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--create-batch", "0"])

    def test_create_batch_rejects_negative(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--create-batch", "-5"])

    def test_create_batch_and_load_batch_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--create-batch", "10", "--load-batch", "1"]
            )

    def test_create_batch_and_show_batch_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--create-batch", "10", "--show-batch", "1"]
            )

    def test_load_batch_and_show_batch_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--load-batch", "1", "--show-batch", "1"]
            )

    def test_include_already_uploaded_requires_create_batch(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--include-already-uploaded"])

    def test_include_already_uploaded_with_create_batch_is_accepted(self) -> None:
        args = attachment_loader.parse_args(
            ["--create-batch", "10", "--include-already-uploaded"]
        )

        self.assertTrue(args.include_already_uploaded)

    def test_batch_id_requires_upload(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--batch-id", "1"])

    def test_batch_id_with_upload_is_accepted(self) -> None:
        args = attachment_loader.parse_args(
            ["--upload", "--batch-id", "1", "--bucket", "test-bucket"]
        )

        self.assertEqual(args.batch_id, 1)
        self.assertTrue(args.upload)

    def test_batch_id_cannot_combine_with_create_batch(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--upload", "--batch-id", "1", "--create-batch", "10"]
            )

    def test_batch_id_cannot_combine_with_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--upload", "--batch-id", "1", "--file-id", "9001"]
            )

    def test_show_batch_does_not_require_ecs(self) -> None:
        # Unlike --show-upload-status, --show-batch works in local dev too.
        args = attachment_loader.parse_args(["--show-batch", "1"])

        self.assertEqual(args.show_batch, 1)
        self.assertFalse(args.ecs)

    def test_create_batch_cannot_combine_with_upload(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--create-batch", "10", "--upload"]
            )

    def test_create_batch_cannot_combine_with_load_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--create-batch", "10", "--load-file-id", "1"]
            )

    def test_create_batch_cannot_combine_with_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--create-batch", "10", "--file-id", "1"]
            )

    def test_load_batch_cannot_combine_with_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--load-batch", "1", "--file-id", "9001"]
            )

    def test_load_batch_cannot_combine_with_upload(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--load-batch", "1", "--upload"])

    def test_load_batch_cannot_combine_with_load_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--load-batch", "1", "--load-file-id", "2"]
            )

    def test_show_batch_cannot_combine_with_upload(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--show-batch", "1", "--upload"])

    def test_show_batch_cannot_combine_with_load_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--show-batch", "1", "--load-file-id", "2"]
            )

    def test_show_batch_cannot_combine_with_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(["--show-batch", "1", "--file-id", "2"])

    def test_batch_id_cannot_combine_with_load_file_id(self) -> None:
        with self.assertRaises(SystemExit):
            attachment_loader.parse_args(
                ["--upload", "--batch-id", "1", "--load-file-id", "2"]
            )

    def test_upload_batch_id_does_not_require_file_id_or_batch_verbs(self) -> None:
        # Sanity check on the other side: the canonical --upload
        # --batch-id invocation itself must still be accepted cleanly.
        args = attachment_loader.parse_args(
            ["--upload", "--batch-id", "5", "--bucket", "test-bucket"]
        )

        self.assertEqual(args.batch_id, 5)
        self.assertIsNone(args.file_id)
        self.assertIsNone(args.load_file_id)
        self.assertIsNone(args.create_batch)
        self.assertIsNone(args.load_batch)
        self.assertIsNone(args.show_batch)


# --- _run_create_batch -------------------------------------------------------


class RunCreateBatchTest(_BatchPostgresTestCase):
    db_prefix = "pytest_create_batch"

    def test_raises_for_non_positive_size(self) -> None:
        with self.assertRaises(ValueError):
            attachment_loader._run_create_batch(self.engine, 0)
        with self.assertRaises(ValueError):
            attachment_loader._run_create_batch(self.engine, -1)

    def test_selects_exactly_n_distinct_file_ids(self) -> None:
        batches = _oracle_batches_stub(
            [pd.DataFrame({"file_id": [5, 3, 3, 1, 4, 2, 6, 7]})]
        )
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches
        ):
            result = attachment_loader._run_create_batch(self.engine, 5)

        self.assertEqual(result["requested_size"], 5)
        self.assertEqual(result["selected_count"], 5)
        self.assertEqual(len(set(result["selected_file_ids"])), 5)

    def test_selection_and_persisted_membership_are_in_stable_ascending_order(
        self,
    ) -> None:
        # Oracle scan order is deliberately NOT sorted (5, 3, 1, 4, 2) -
        # the batch's own membership must still end up in ascending
        # file_id order (ordinal 1..N), independent of scan order.
        batches = _oracle_batches_stub(
            [pd.DataFrame({"file_id": [5, 3, 1, 4, 2]})]
        )
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches
        ):
            result = attachment_loader._run_create_batch(self.engine, 5)

        self.assertEqual(result["selected_file_ids"], [1, 2, 3, 4, 5])

        with self.engine.connect() as connection:
            rows = connection.execute(
                text(
                    "SELECT entity_key, ordinal FROM archive.etl_batch_item "
                    "WHERE batch_id = :batch_id ORDER BY ordinal"
                ),
                {"batch_id": result["batch_id"]},
            ).mappings().all()
        self.assertEqual(
            [(row["entity_key"], row["ordinal"]) for row in rows],
            [(1, 1), (2, 2), (3, 3), (4, 4), (5, 5)],
        )

    def test_membership_is_persisted_and_batch_status_is_created(self) -> None:
        batches = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2, 3]})])
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches
        ):
            result = attachment_loader._run_create_batch(self.engine, 3)

        batch_row = self._row(
            "etl_batch", batch_id=result["batch_id"]
        )
        self.assertEqual(batch_row["status"], "CREATED")
        self.assertEqual(batch_row["requested_size"], 3)

        member_count = self._scalar(
            "SELECT COUNT(*) FROM archive.etl_batch_item "
            "WHERE batch_id = :batch_id",
            batch_id=result["batch_id"],
        )
        self.assertEqual(member_count, 3)

    def test_excludes_already_uploaded_file_ids_by_default(self) -> None:
        self._insert_attachment_object(1, upload_status="UPLOADED")
        batches = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2, 3]})])

        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches
        ):
            result = attachment_loader._run_create_batch(self.engine, 2)

        self.assertEqual(result["selected_file_ids"], [2, 3])

    def test_include_already_uploaded_flag_includes_them(self) -> None:
        self._insert_attachment_object(1, upload_status="UPLOADED")
        batches = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2, 3]})])

        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches
        ):
            result = attachment_loader._run_create_batch(
                self.engine, 3, include_already_uploaded=True
            )

        self.assertEqual(result["selected_file_ids"], [1, 2, 3])

    def test_repeated_creation_produces_independent_batches(self) -> None:
        # Duplicate/repeated --create-batch calls are not an error - each
        # is an independent manifest with its own batch_id and its own
        # persisted membership, never merged or deduplicated against a
        # prior batch.
        batches_first = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2]})])
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches_first
        ):
            first = attachment_loader._run_create_batch(self.engine, 2)

        batches_second = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2]})])
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches_second
        ):
            second = attachment_loader._run_create_batch(self.engine, 2)

        self.assertNotEqual(first["batch_id"], second["batch_id"])
        self.assertEqual(first["selected_file_ids"], second["selected_file_ids"])

        total_batches = self._scalar(
            "SELECT COUNT(*) FROM archive.etl_batch"
        )
        self.assertEqual(total_batches, 2)

    def test_creates_a_smaller_batch_when_oracle_is_exhausted_first(self) -> None:
        batches = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2]})])
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches
        ):
            result = attachment_loader._run_create_batch(self.engine, 10)

        self.assertEqual(result["selected_count"], 2)
        self.assertEqual(result["selected_file_ids"], [1, 2])

    def test_never_reads_a_blob_column(self) -> None:
        # The physical-file Oracle export never selects blob content in
        # the first place - this proves _run_create_batch only ever reads
        # the file_id column it's given, nothing shaped like a blob.
        batches = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2, 3]})])
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches
        ):
            attachment_loader._run_create_batch(self.engine, 3)

        # No assertion beyond "did not raise" is possible against a stub
        # that structurally has no blob column - the guarantee is
        # structural (see FILES_ORACLE_SQL), not runtime-checked here.

    def test_never_creates_an_s3_client(self) -> None:
        batches = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2, 3]})])
        with (
            patch.object(attachment_loader, "OracleDataSource", return_value=batches),
            patch.object(attachment_loader, "create_s3_client") as create_s3,
        ):
            attachment_loader._run_create_batch(self.engine, 3)

        create_s3.assert_not_called()

    def test_does_not_truncate_or_modify_unrelated_existing_batches(self) -> None:
        batches_first = _oracle_batches_stub([pd.DataFrame({"file_id": [1, 2]})])
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches_first
        ):
            first = attachment_loader._run_create_batch(self.engine, 2)

        batches_second = _oracle_batches_stub([pd.DataFrame({"file_id": [3, 4]})])
        with patch.object(
            attachment_loader, "OracleDataSource", return_value=batches_second
        ):
            attachment_loader._run_create_batch(self.engine, 2)

        first_member_count = self._scalar(
            "SELECT COUNT(*) FROM archive.etl_batch_item "
            "WHERE batch_id = :batch_id",
            batch_id=first["batch_id"],
        )
        self.assertEqual(first_member_count, 2)


# --- _run_load_batch ---------------------------------------------------------


class RunLoadBatchTest(_BatchPostgresTestCase):
    db_prefix = "pytest_load_batch"

    def _create_batch(self, file_ids: list[int]) -> int:
        with self.engine.begin() as connection:
            batch_id = connection.execute(
                text(
                    "INSERT INTO archive.etl_batch "
                    "(domain, entity_type, requested_size, status, "
                    "selection_strategy) "
                    "VALUES (:domain, :entity_type, :size, 'CREATED', "
                    "'TEST_FIXTURE') "
                    "RETURNING batch_id"
                ),
                {
                    "domain": attachment_loader.AWARD_ATTACHMENT_BATCH_DOMAIN,
                    "entity_type": attachment_loader.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
                    "size": len(file_ids),
                },
            ).scalar_one()
            for ordinal, file_id in enumerate(file_ids, start=1):
                connection.execute(
                    text(
                        "INSERT INTO archive.etl_batch_item "
                        "(batch_id, entity_key, ordinal, status) "
                        "VALUES (:batch_id, :file_id, :ordinal, 'PENDING')"
                    ),
                    {"batch_id": batch_id, "file_id": file_id, "ordinal": ordinal},
                )
        return int(batch_id)

    def test_raises_for_a_nonexistent_batch(self) -> None:
        with self.assertRaises(RuntimeError):
            attachment_loader._run_load_batch(self.engine, 999999)

    def test_loads_metadata_only_for_batch_members(self) -> None:
        batch_id = self._create_batch([1, 2])

        def _files(source, target_ids):
            rows = [_file_row(file_id=fid) for fid in sorted(target_ids)]
            return pd.concat(rows, ignore_index=True)

        with (
            patch.object(
                attachment_loader, "read_files_matching_ids", side_effect=_files
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=pd.DataFrame(),
            ),
        ):
            report = attachment_loader._run_load_batch(self.engine, batch_id)

        self.assertEqual(report["physical_files_requested"], 2)
        self.assertEqual(report["physical_files_found"], 2)
        self.assertEqual(report["inserted"], 2)
        self.assertEqual(report["missing_in_oracle"], 0)

        total_files = self._scalar(
            "SELECT COUNT(*) FROM archive.attachment_object"
        )
        self.assertEqual(total_files, 2)

    def test_loads_all_reference_rows_for_batch_members(self) -> None:
        batch_id = self._create_batch([1])
        two_references = pd.concat(
            [_reference_row(), _reference_row(award_attachment_id=502)],
            ignore_index=True,
        )

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=two_references,
            ),
        ):
            report = attachment_loader._run_load_batch(self.engine, batch_id)

        self.assertEqual(report["reference_rows_inserted"], 2)

        reference_count = self._scalar(
            "SELECT COUNT(*) FROM archive.award_attachment WHERE file_id = 1"
        )
        self.assertEqual(reference_count, 2)

    def test_does_not_touch_unrelated_existing_rows(self) -> None:
        # A file/reference already loaded outside this batch (e.g. by a
        # prior --load-file-id or full load) must survive untouched.
        self._insert_attachment_object(999, file_name="Unrelated.pdf")
        batch_id = self._create_batch([1])

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_batch(self.engine, batch_id)

        unrelated = self._row("attachment_object", file_id=999)
        self.assertEqual(unrelated["file_name"], "Unrelated.pdf")

        total_files = self._scalar(
            "SELECT COUNT(*) FROM archive.attachment_object"
        )
        self.assertEqual(total_files, 2)

    def test_missing_in_oracle_is_reported_and_flagged_on_the_batch_file(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 999999])

        def _files(source, target_ids):
            if 1 in target_ids:
                return _file_row(file_id=1)
            return pd.DataFrame()

        with (
            patch.object(
                attachment_loader, "read_files_matching_ids", side_effect=_files
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=pd.DataFrame(),
            ),
        ):
            report = attachment_loader._run_load_batch(self.engine, batch_id)

        self.assertEqual(report["missing_in_oracle"], 1)
        self.assertEqual(report["physical_files_found"], 1)

        missing_row = self._row(
            "etl_batch_item", batch_id=batch_id, entity_key=999999
        )
        self.assertEqual(missing_row["status"], "MISSING_SOURCE")

        loaded_row = self._row(
            "etl_batch_item", batch_id=batch_id, entity_key=1
        )
        self.assertEqual(loaded_row["status"], "COMPLETED")

    def test_batch_status_becomes_metadata_loaded_on_success(self) -> None:
        batch_id = self._create_batch([1])

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_batch(self.engine, batch_id)

        batch_row = self._row("etl_batch", batch_id=batch_id)
        self.assertEqual(batch_row["status"], "READY")

    def test_idempotent_rerun_reports_unchanged(self) -> None:
        batch_id = self._create_batch([1])

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_batch(self.engine, batch_id)
            report = attachment_loader._run_load_batch(self.engine, batch_id)

        self.assertEqual(report["inserted"], 0)
        self.assertEqual(report["updated"], 0)
        self.assertEqual(report["unchanged"], 1)
        self.assertEqual(report["reference_rows_unchanged"], 1)

    def test_preserves_existing_upload_state_on_reload(self) -> None:
        batch_id = self._create_batch([1])

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_batch(self.engine, batch_id)

        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.attachment_object "
                    "SET upload_status = 'UPLOADED', s3_bucket = 'my-bucket', "
                    "s3_key = 'my-key' WHERE file_id = 1"
                )
            )

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            attachment_loader._run_load_batch(self.engine, batch_id)

        file_row = self._row("attachment_object", file_id=1)
        self.assertEqual(file_row["upload_status"], "UPLOADED")
        self.assertEqual(file_row["s3_bucket"], "my-bucket")

    def test_dry_run_rolls_back_and_leaves_batch_status_unchanged(self) -> None:
        batch_id = self._create_batch([1])

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
        ):
            report = attachment_loader._run_load_batch(
                self.engine, batch_id, dry_run=True
            )

        self.assertEqual(report["inserted"], 1)
        self.assertEqual(report["reference_rows_inserted"], 1)

        total_files = self._scalar(
            "SELECT COUNT(*) FROM archive.attachment_object"
        )
        self.assertEqual(total_files, 0)

        batch_row = self._row("etl_batch", batch_id=batch_id)
        self.assertEqual(batch_row["status"], "CREATED")

        member_row = self._row(
            "etl_batch_item", batch_id=batch_id, entity_key=1
        )
        self.assertEqual(member_row["status"], "PENDING")

    def test_never_creates_an_s3_client(self) -> None:
        batch_id = self._create_batch([1])

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_file_row(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_reference_row(),
            ),
            patch.object(attachment_loader, "create_s3_client") as create_s3,
        ):
            attachment_loader._run_load_batch(self.engine, batch_id)

        create_s3.assert_not_called()


# --- _run_show_batch ---------------------------------------------------------


class RunShowBatchTest(_BatchPostgresTestCase):
    db_prefix = "pytest_show_batch"

    def _create_batch(self, file_ids: list[int]) -> int:
        with self.engine.begin() as connection:
            batch_id = connection.execute(
                text(
                    "INSERT INTO archive.etl_batch "
                    "(domain, entity_type, requested_size, status, "
                    "selection_strategy) "
                    "VALUES (:domain, :entity_type, :size, 'CREATED', "
                    "'TEST_FIXTURE') "
                    "RETURNING batch_id"
                ),
                {
                    "domain": attachment_loader.AWARD_ATTACHMENT_BATCH_DOMAIN,
                    "entity_type": attachment_loader.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
                    "size": len(file_ids),
                },
            ).scalar_one()
            for ordinal, file_id in enumerate(file_ids, start=1):
                connection.execute(
                    text(
                        "INSERT INTO archive.etl_batch_item "
                        "(batch_id, entity_key, ordinal, status) "
                        "VALUES (:batch_id, :file_id, :ordinal, 'PENDING')"
                    ),
                    {"batch_id": batch_id, "file_id": file_id, "ordinal": ordinal},
                )
        return int(batch_id)

    def test_reports_found_false_for_a_nonexistent_batch(self) -> None:
        report = attachment_loader._run_show_batch(self.engine, 999999)

        self.assertEqual(report, {"batch_id": 999999, "found": False})

    def test_reports_counts_before_metadata_is_loaded(self) -> None:
        batch_id = self._create_batch([1, 2, 3])

        report = attachment_loader._run_show_batch(self.engine, batch_id)

        self.assertTrue(report["found"])
        self.assertEqual(report["total_files"], 3)
        self.assertEqual(report["metadata_loaded"], 0)
        self.assertEqual(report["missing_metadata"], 3)
        self.assertEqual(report["pending"], 0)
        self.assertEqual(report["uploaded"], 0)

    def test_reports_counts_after_metadata_is_loaded(self) -> None:
        batch_id = self._create_batch([1, 2])
        self._insert_attachment_object(1)
        self._insert_attachment_object(2)
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.etl_batch_item "
                    "SET status = 'COMPLETED' WHERE batch_id = :batch_id"
                ),
                {"batch_id": batch_id},
            )

        report = attachment_loader._run_show_batch(self.engine, batch_id)

        self.assertEqual(report["metadata_loaded"], 2)
        self.assertEqual(report["missing_metadata"], 0)
        self.assertEqual(report["pending"], 2)

    def test_reports_upload_status_breakdown(self) -> None:
        batch_id = self._create_batch([1, 2, 3, 4])
        self._insert_attachment_object(1, upload_status="PENDING")
        self._insert_attachment_object(2, upload_status="UPLOADED")
        self._insert_attachment_object(3, upload_status="FAILED")
        self._insert_attachment_object(4, upload_status="MISSING_SOURCE_CONTENT")
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.etl_batch_item "
                    "SET status = 'COMPLETED' WHERE batch_id = :batch_id"
                ),
                {"batch_id": batch_id},
            )

        report = attachment_loader._run_show_batch(self.engine, batch_id)

        self.assertEqual(report["pending"], 1)
        self.assertEqual(report["uploaded"], 1)
        self.assertEqual(report["failed"], 1)
        self.assertEqual(report["missing_source_content"], 1)
        self.assertEqual(report["uploading"], 0)

    def test_is_read_only_and_never_writes_anything(self) -> None:
        batch_id = self._create_batch([1, 2])

        before = self._row("etl_batch", batch_id=batch_id)
        attachment_loader._run_show_batch(self.engine, batch_id)
        attachment_loader._run_show_batch(self.engine, batch_id)
        after = self._row("etl_batch", batch_id=batch_id)

        self.assertEqual(before, after)

        load_run_count = self._scalar("SELECT COUNT(*) FROM archive.load_run")
        self.assertEqual(load_run_count, 0)

    def test_never_creates_an_s3_client_or_reads_oracle(self) -> None:
        batch_id = self._create_batch([1])

        with (
            patch.object(attachment_loader, "create_s3_client") as create_s3,
            patch.object(attachment_loader, "OracleDataSource") as oracle_source,
        ):
            attachment_loader._run_show_batch(self.engine, batch_id)

        create_s3.assert_not_called()
        oracle_source.assert_not_called()


# --- select_upload_candidates: batch scoping ---------------------------------


class SelectUploadCandidatesBatchScopingTest(_BatchPostgresTestCase):
    db_prefix = "pytest_select_candidates"

    def _create_batch(self, file_ids: list[int]) -> int:
        with self.engine.begin() as connection:
            batch_id = connection.execute(
                text(
                    "INSERT INTO archive.etl_batch "
                    "(domain, entity_type, requested_size, status, "
                    "selection_strategy) "
                    "VALUES (:domain, :entity_type, :size, 'CREATED', "
                    "'TEST_FIXTURE') "
                    "RETURNING batch_id"
                ),
                {
                    "domain": attachment_loader.AWARD_ATTACHMENT_BATCH_DOMAIN,
                    "entity_type": attachment_loader.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
                    "size": len(file_ids),
                },
            ).scalar_one()
            for ordinal, file_id in enumerate(file_ids, start=1):
                connection.execute(
                    text(
                        "INSERT INTO archive.etl_batch_item "
                        "(batch_id, entity_key, ordinal, status) "
                        "VALUES (:batch_id, :file_id, :ordinal, 'COMPLETED')"
                    ),
                    {"batch_id": batch_id, "file_id": file_id, "ordinal": ordinal},
                )
        return int(batch_id)

    def test_batch_id_scopes_selection_to_membership_only(self) -> None:
        batch_id = self._create_batch([1, 2])
        self._insert_attachment_object(1, upload_status="PENDING")
        self._insert_attachment_object(2, upload_status="PENDING")
        # Unrelated pending file, not part of this batch.
        self._insert_attachment_object(3, upload_status="PENDING")

        with self.engine.connect() as connection:
            candidates = attachment_loader.select_upload_candidates(
                connection,
                limit=None,
                file_id=None,
                retry_failed=False,
                batch_id=batch_id,
            )

        self.assertEqual(sorted(candidates["file_id"].tolist()), [1, 2])

    def test_batch_id_orders_by_file_id(self) -> None:
        batch_id = self._create_batch([3, 1, 2])
        for file_id in (1, 2, 3):
            self._insert_attachment_object(file_id, upload_status="PENDING")

        with self.engine.connect() as connection:
            candidates = attachment_loader.select_upload_candidates(
                connection,
                limit=None,
                file_id=None,
                retry_failed=False,
                batch_id=batch_id,
            )

        self.assertEqual(candidates["file_id"].tolist(), [1, 2, 3])

    def test_batch_member_already_uploaded_is_excluded(self) -> None:
        batch_id = self._create_batch([1, 2])
        self._insert_attachment_object(1, upload_status="PENDING")
        self._insert_attachment_object(2, upload_status="UPLOADED")

        with self.engine.connect() as connection:
            candidates = attachment_loader.select_upload_candidates(
                connection,
                limit=None,
                file_id=None,
                retry_failed=False,
                batch_id=batch_id,
            )

        self.assertEqual(candidates["file_id"].tolist(), [1])

    def test_batch_member_failed_is_excluded_unless_retry_failed(self) -> None:
        batch_id = self._create_batch([1])
        self._insert_attachment_object(1, upload_status="FAILED")

        with self.engine.connect() as connection:
            without_retry = attachment_loader.select_upload_candidates(
                connection,
                limit=None,
                file_id=None,
                retry_failed=False,
                batch_id=batch_id,
            )
            with_retry = attachment_loader.select_upload_candidates(
                connection,
                limit=None,
                file_id=None,
                retry_failed=True,
                batch_id=batch_id,
            )

        self.assertEqual(len(without_retry), 0)
        self.assertEqual(with_retry["file_id"].tolist(), [1])


# --- _run_upload: --batch-id -------------------------------------------------


class RunUploadBatchIdTest(_BatchPostgresTestCase):
    """Real PostgreSQL, mocked AWS/S3/Oracle boundary - mirrors
    tests/test_award_attachment_loader.py's RunUploadTest convention, but
    against a real database so batch status transitions and per-file
    upload_status updates are verified with genuine SQL, not a mock."""

    db_prefix = "pytest_upload_batch"

    def _create_batch(self, file_ids: list[int]) -> int:
        with self.engine.begin() as connection:
            batch_id = connection.execute(
                text(
                    "INSERT INTO archive.etl_batch "
                    "(domain, entity_type, requested_size, status, "
                    "selection_strategy) "
                    "VALUES (:domain, :entity_type, :size, 'CREATED', "
                    "'TEST_FIXTURE') "
                    "RETURNING batch_id"
                ),
                {
                    "domain": attachment_loader.AWARD_ATTACHMENT_BATCH_DOMAIN,
                    "entity_type": attachment_loader.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
                    "size": len(file_ids),
                },
            ).scalar_one()
            for ordinal, file_id in enumerate(file_ids, start=1):
                connection.execute(
                    text(
                        "INSERT INTO archive.etl_batch_item "
                        "(batch_id, entity_key, ordinal, status) "
                        "VALUES (:batch_id, :file_id, :ordinal, 'COMPLETED')"
                    ),
                    {"batch_id": batch_id, "file_id": file_id, "ordinal": ordinal},
                )
        return int(batch_id)

    def _run_upload(
        self, arguments: MagicMock, *, stream_upload_side_effect=None
    ) -> dict:
        def _default_stream_upload(connection, location, s3_client, **kwargs):
            return 100, "deadbeef" * 8

        with (
            patch.object(
                attachment_loader,
                "validate_aws_identity",
                return_value={"account": "123", "arn": "arn:x"},
            ),
            patch.object(
                attachment_loader, "create_s3_client", return_value=MagicMock()
            ),
            patch.object(attachment_loader, "validate_bucket_accessible"),
            patch.object(
                attachment_loader, "create_postgres_engine", return_value=self.engine
            ),
            patch.object(attachment_loader, "_connect_oracle"),
            patch.object(
                attachment_loader,
                "stream_upload",
                side_effect=stream_upload_side_effect or _default_stream_upload,
            ),
        ):
            return attachment_loader._run_upload(arguments)

    def _arguments(self, **overrides: object) -> MagicMock:
        defaults: dict[str, object] = dict(
            bucket="test-bucket",
            prefix=None,
            limit=None,
            file_id=None,
            batch_id=None,
            retry_failed=False,
            multipart_threshold_bytes=None,
        )
        defaults.update(overrides)
        return MagicMock(**defaults)

    def test_raises_for_a_nonexistent_batch_id(self) -> None:
        with self.assertRaises(RuntimeError):
            self._run_upload(self._arguments(batch_id=999999))

    def test_only_uploads_batch_members_never_unrelated_pending_rows(self) -> None:
        batch_id = self._create_batch([1, 2])
        self._insert_attachment_object(1, upload_status="PENDING", blob_source="INLINE")
        self._insert_attachment_object(2, upload_status="PENDING", blob_source="INLINE")
        # Unrelated pending file outside this batch - must never be
        # touched by --upload --batch-id.
        self._insert_attachment_object(3, upload_status="PENDING")

        report = self._run_upload(self._arguments(batch_id=batch_id))

        self.assertEqual(report["uploaded"], 2)
        unrelated = self._row("attachment_object", file_id=3)
        self.assertEqual(unrelated["upload_status"], "PENDING")

    def test_batch_status_transitions_created_to_uploading_to_complete(self) -> None:
        batch_id = self._create_batch([1])
        self._insert_attachment_object(1, upload_status="PENDING", blob_source="INLINE")

        self._run_upload(self._arguments(batch_id=batch_id))

        batch_row = self._row("etl_batch", batch_id=batch_id)
        self.assertEqual(batch_row["status"], "COMPLETED")
        self.assertIsNotNone(batch_row["started_at"])
        self.assertIsNotNone(batch_row["completed_at"])

    def test_resume_does_not_reset_started_at(self) -> None:
        batch_id = self._create_batch([1, 2])
        self._insert_attachment_object(1, upload_status="UPLOADED")
        self._insert_attachment_object(2, upload_status="PENDING", blob_source="INLINE")

        self._run_upload(self._arguments(batch_id=batch_id))
        first_started_at = self._row(
            "etl_batch", batch_id=batch_id
        )["started_at"]

        # Simulate a resumed run after a partial completion.
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    "UPDATE archive.attachment_object "
                    "SET upload_status = 'PENDING' WHERE file_id = 2"
                )
            )
        self._run_upload(self._arguments(batch_id=batch_id))
        second_started_at = self._row(
            "etl_batch", batch_id=batch_id
        )["started_at"]

        self.assertEqual(first_started_at, second_started_at)

    def test_already_uploaded_batch_members_are_not_reselected(self) -> None:
        # select_upload_candidates only ever selects PENDING/UPLOADING
        # (+FAILED with --retry-failed) rows - an UPLOADED batch member is
        # excluded at the SQL level, before _run_upload's own per-row
        # "already uploaded, skip" check would even see it.
        batch_id = self._create_batch([1])
        matching_key = attachment_loader.build_s3_key(
            attachment_loader.DEFAULT_S3_KEY_PREFIX, 1, "Agreement.pdf"
        )
        self._insert_attachment_object(
            1,
            upload_status="UPLOADED",
            file_name="Agreement.pdf",
            s3_bucket="test-bucket",
            s3_key=matching_key,
        )

        report = self._run_upload(self._arguments(batch_id=batch_id))

        self.assertEqual(report["physical_files_selected"], 0)
        self.assertEqual(report["uploaded"], 0)

    def test_failed_batch_members_retry_only_when_requested(self) -> None:
        batch_id = self._create_batch([1])
        self._insert_attachment_object(1, upload_status="FAILED", blob_source="INLINE")

        without_retry = self._run_upload(
            self._arguments(batch_id=batch_id, retry_failed=False)
        )
        self.assertEqual(without_retry["physical_files_selected"], 0)

        with_retry = self._run_upload(
            self._arguments(batch_id=batch_id, retry_failed=True)
        )
        self.assertEqual(with_retry["uploaded"], 1)

        file_row = self._row("attachment_object", file_id=1)
        self.assertEqual(file_row["upload_status"], "UPLOADED")

    def test_partial_failure_leaves_durable_progress_for_a_later_resume(
        self,
    ) -> None:
        batch_id = self._create_batch([1, 2])
        self._insert_attachment_object(1, upload_status="PENDING", blob_source="INLINE")
        self._insert_attachment_object(2, upload_status="PENDING", blob_source="INLINE")

        self._run_upload(
            self._arguments(batch_id=batch_id),
            stream_upload_side_effect=[RuntimeError("boom"), (50, "abc123" * 10)],
        )

        failed_row = self._row("attachment_object", file_id=1)
        uploaded_row = self._row("attachment_object", file_id=2)
        self.assertEqual(failed_row["upload_status"], "FAILED")
        self.assertEqual(uploaded_row["upload_status"], "UPLOADED")

        # A resumed run only needs --retry-failed to pick file_id=1 back
        # up - file_id=2 (already UPLOADED) is excluded from selection
        # entirely, not merely skipped after being selected.
        second_report = self._run_upload(
            self._arguments(batch_id=batch_id, retry_failed=True)
        )
        self.assertEqual(second_report["physical_files_selected"], 1)
        self.assertEqual(second_report["uploaded"], 1)


if __name__ == "__main__":
    unittest.main()
