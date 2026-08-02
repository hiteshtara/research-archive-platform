"""Regression tests for --load-file-ids: a bounded, idempotent metadata
load for exactly a given set of physical file_ids and their reference
rows - the plural form of --load-file-id.

Anchored to the real gap this was built to close: Award 1833767 has 34
attachment rows in Oracle's KCOEUS.AWARD_ATTACHMENT but only 24 in
archive.award_attachment. A --diff-award-attachments 1833767 run against
the real archive proved all 10 missing rows share the same root cause -
"not yet loaded: this file_id has never been selected into any
--create-batch batch or loaded via --load-file-id" (the global,
ascending-file_id batch progression for --create-batch simply hasn't
reached these file_ids yet for this award) - not an extraction, filtering,
UPSERT, or transaction defect. --load-file-ids exists so those exact 10
file_ids can be backfilled in a single pass instead of one
--load-file-id invocation (and one ECS task) per file_id.

Runs the real UPSERT SQL (INSERT ... ON CONFLICT ... RETURNING
(xmax = 0)) against a real, throwaway PostgreSQL database, exactly like
tests/test_load_file_id.py - the insert/update/unchanged distinction
depends on genuine Postgres semantics a mock cannot exercise correctly.
Skips entirely if no local PostgreSQL is reachable.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

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

# The real award_id this investigation was scoped to, and the real 10
# (attachment_id, file_id) pairs a live --diff-award-attachments run
# proved were missing from archive.award_attachment - see
# tests/test_award_attachment_loader.py::DiffAwardAttachmentsTest for the
# diagnostic side of this same investigation.
AWARD_ID = 1833767
MISSING_ATTACHMENT_FILE_IDS = {
    306557: 5994,
    306558: 5997,
    306559: 5995,
    306560: 5996,
    306585: 5993,
    306586: 6075,
    306587: 13261,
    306588: 13282,
    306589: 20751,
    306590: 32590,
}


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


def _file_row(**overrides) -> dict:
    row = {
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
    return row


def _reference_row(**overrides) -> dict:
    row = {
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
    return row


def _files_for_award_1833767() -> pd.DataFrame:
    return pd.DataFrame(
        [
            _file_row(
                file_id=file_id,
                file_name=f"attachment-{attachment_id}.pdf",
            )
            for attachment_id, file_id in MISSING_ATTACHMENT_FILE_IDS.items()
        ]
    )


def _references_for_award_1833767() -> pd.DataFrame:
    return pd.DataFrame(
        [
            _reference_row(
                award_attachment_id=attachment_id,
                award_id=AWARD_ID,
                award_number="100068-00001",
                file_id=file_id,
            )
            for attachment_id, file_id in MISSING_ATTACHMENT_FILE_IDS.items()
        ]
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class LoadFileIdsRegressionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db_name = f"pytest_load_file_ids_{uuid.uuid4().hex[:12]}"

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

    def _award_attachment_count(self, award_id: int) -> int:
        with self.engine.connect() as connection:
            return connection.execute(
                text(
                    "SELECT COUNT(*) FROM archive.award_attachment "
                    "WHERE award_id = :award_id"
                ),
                {"award_id": award_id},
            ).scalar_one()

    def test_backfills_the_exact_10_missing_award_1833767_file_ids(self) -> None:
        # Simulate the archive already having 24 of Award 1833767's 34
        # attachment rows loaded (the real, live starting state this
        # investigation found), then backfill exactly the 10 missing
        # file_ids in one pass and assert the archive now has all 34.
        already_loaded_file_ids = set(range(90001, 90025))
        for index, file_id in enumerate(already_loaded_file_ids):
            with (
                patch.object(
                    attachment_loader,
                    "read_files_matching_ids",
                    return_value=pd.DataFrame(
                        [_file_row(file_id=file_id, file_name=f"existing-{file_id}.pdf")]
                    ),
                ),
                patch.object(
                    attachment_loader,
                    "read_references_matching_file_ids",
                    return_value=pd.DataFrame(
                        [
                            _reference_row(
                                award_attachment_id=200000 + index,
                                award_id=AWARD_ID,
                                award_number="100068-00001",
                                file_id=file_id,
                            )
                        ]
                    ),
                ),
            ):
                attachment_loader._run_load_file_id(self.engine, file_id)

        self.assertEqual(self._award_attachment_count(AWARD_ID), 24)

        target_file_ids = set(MISSING_ATTACHMENT_FILE_IDS.values())
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_files_for_award_1833767(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_references_for_award_1833767(),
            ),
        ):
            report = attachment_loader._run_load_file_ids(
                self.engine, target_file_ids
            )

        self.assertEqual(report["inserted"], 20)
        self.assertEqual(report["missing"], 0)
        self.assertEqual(self._award_attachment_count(AWARD_ID), 34)

        for attachment_id, file_id in MISSING_ATTACHMENT_FILE_IDS.items():
            with self.engine.connect() as connection:
                row = dict(
                    connection.execute(
                        text(
                            "SELECT * FROM archive.award_attachment "
                            "WHERE award_attachment_id = :attachment_id"
                        ),
                        {"attachment_id": attachment_id},
                    )
                    .mappings()
                    .one()
                )
            self.assertEqual(row["file_id"], file_id)
            self.assertEqual(row["award_id"], AWARD_ID)

    def test_reload_of_the_same_10_file_ids_is_unchanged(self) -> None:
        target_file_ids = set(MISSING_ATTACHMENT_FILE_IDS.values())
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_files_for_award_1833767(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_references_for_award_1833767(),
            ),
        ):
            attachment_loader._run_load_file_ids(self.engine, target_file_ids)
            report = attachment_loader._run_load_file_ids(
                self.engine, target_file_ids
            )

        self.assertEqual(report["inserted"], 0)
        self.assertEqual(report["updated"], 0)
        self.assertEqual(report["unchanged"], 20)
        self.assertEqual(report["missing"], 0)
        self.assertEqual(self._award_attachment_count(AWARD_ID), 10)

    def test_file_ids_not_found_in_oracle_are_reported_missing_and_write_nothing(
        self,
    ) -> None:
        with patch.object(
            attachment_loader,
            "read_files_matching_ids",
            return_value=pd.DataFrame(),
        ):
            report = attachment_loader._run_load_file_ids(
                self.engine, {5994, 5997}
            )

        self.assertEqual(report["inserted"], 0)
        self.assertEqual(report["updated"], 0)
        self.assertEqual(report["unchanged"], 0)
        self.assertEqual(report["missing"], 2)
        self.assertEqual(sorted(report["file_ids"]), [5994, 5997])

        with self.engine.connect() as connection:
            load_run_count = connection.execute(
                text("SELECT COUNT(*) FROM archive.load_run")
            ).scalar_one()
        self.assertEqual(load_run_count, 0)

    def test_partial_match_loads_found_ids_and_reports_the_rest_missing(
        self,
    ) -> None:
        # 3 of the 10 real file_ids are found in Oracle, 7 are not (e.g.
        # transient Oracle-side unavailability for some rows) - the found
        # ones must still load, and the missing ones must be reported,
        # not silently dropped or treated as a hard failure.
        found_attachment_ids = [306557, 306558, 306559]
        found_file_ids = {
            MISSING_ATTACHMENT_FILE_IDS[attachment_id]
            for attachment_id in found_attachment_ids
        }
        target_file_ids = set(MISSING_ATTACHMENT_FILE_IDS.values())

        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=pd.DataFrame(
                    [
                        _file_row(file_id=file_id, file_name=f"attachment-{aid}.pdf")
                        for aid, file_id in MISSING_ATTACHMENT_FILE_IDS.items()
                        if aid in found_attachment_ids
                    ]
                ),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=pd.DataFrame(
                    [
                        _reference_row(
                            award_attachment_id=aid,
                            award_id=AWARD_ID,
                            award_number="100068-00001",
                            file_id=file_id,
                        )
                        for aid, file_id in MISSING_ATTACHMENT_FILE_IDS.items()
                        if aid in found_attachment_ids
                    ]
                ),
            ),
        ):
            report = attachment_loader._run_load_file_ids(
                self.engine, target_file_ids
            )

        self.assertEqual(report["inserted"], 6)
        self.assertEqual(report["missing"], 7)
        self.assertEqual(self._award_attachment_count(AWARD_ID), 3)
        self.assertEqual(len(found_file_ids), 3)

    def test_dry_run_reports_accurate_counts_but_persists_nothing(self) -> None:
        target_file_ids = set(MISSING_ATTACHMENT_FILE_IDS.values())
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_files_for_award_1833767(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_references_for_award_1833767(),
            ),
        ):
            report = attachment_loader._run_load_file_ids(
                self.engine, target_file_ids, dry_run=True
            )

        self.assertEqual(report["inserted"], 20)
        self.assertEqual(self._award_attachment_count(AWARD_ID), 0)

        with self.engine.connect() as connection:
            load_run_count = connection.execute(
                text("SELECT COUNT(*) FROM archive.load_run")
            ).scalar_one()
        self.assertEqual(load_run_count, 0)

    def test_does_not_touch_files_outside_the_target_set(self) -> None:
        # A file/reference already loaded for a completely different
        # file_id must survive a --load-file-ids run untouched.
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=pd.DataFrame(
                    [_file_row(file_id=42, file_name="Unrelated.pdf")]
                ),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=pd.DataFrame(
                    [_reference_row(award_attachment_id=999, file_id=42)]
                ),
            ),
        ):
            attachment_loader._run_load_file_id(self.engine, 42)

        target_file_ids = set(MISSING_ATTACHMENT_FILE_IDS.values())
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_files_for_award_1833767(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_references_for_award_1833767(),
            ),
        ):
            attachment_loader._run_load_file_ids(self.engine, target_file_ids)

        with self.engine.connect() as connection:
            total_files = connection.execute(
                text("SELECT COUNT(*) FROM archive.attachment_object")
            ).scalar_one()
        self.assertEqual(total_files, 11)

    def test_never_creates_an_s3_client(self) -> None:
        target_file_ids = set(MISSING_ATTACHMENT_FILE_IDS.values())
        with (
            patch.object(
                attachment_loader,
                "read_files_matching_ids",
                return_value=_files_for_award_1833767(),
            ),
            patch.object(
                attachment_loader,
                "read_references_matching_file_ids",
                return_value=_references_for_award_1833767(),
            ),
            patch.object(attachment_loader, "create_s3_client") as create_s3,
        ):
            attachment_loader._run_load_file_ids(self.engine, target_file_ids)

        create_s3.assert_not_called()


if __name__ == "__main__":
    unittest.main()
