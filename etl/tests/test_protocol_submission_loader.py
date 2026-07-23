from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd

from archive_etl.pipeline.postgres import PostgreSQLLoadContext
from load_protocol_submissions import (
    COLUMNS,
    prepare_submissions,
    read_source,
    reconcile,
    resolve_parents,
    upsert,
)


def source_row() -> dict[str, object]:
    return {
        "submission_id": "10",
        "source_protocol_id": "100",
        "protocol_number": "000100",
        "sequence_number": "2",
        "submission_number": "3",
        "submission_type_code": "100",
        "submission_status_code": "200",
        "submission_date": "2026-01-02",
        "yes_vote_count": "5",
        "source_update_timestamp": "2026-01-03 04:05:06",
        "source_update_user": "USER",
        "source_version_number": "1",
        "source_object_id": "OBJ",
    }


def parent() -> dict[str, object]:
    return {
        "protocol_id": 100,
        "protocol_number": "000100",
        "sequence_number": 2,
    }


class ProtocolSubmissionLoaderTest(unittest.TestCase):
    def test_oracle_and_csv_modes_produce_identical_rows(self) -> None:
        raw = pd.DataFrame([source_row()])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "protocol_submissions.csv"
            raw.to_csv(path, index=False)
            with patch(
                "load_protocol_submissions.OracleDataSource.read",
                return_value=raw,
            ):
                oracle, _ = read_source(None)
            csv, _ = read_source(Path(directory))
        pd.testing.assert_frame_equal(oracle, csv)
        self.assertEqual(oracle.columns.tolist(), COLUMNS)

    def test_direct_parent_resolution(self) -> None:
        submissions = prepare_submissions(
            pd.DataFrame([source_row()])
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [parent()]
        report = resolve_parents(
            PostgreSQLLoadContext(connection, 7),
            submissions,
        )
        self.assertEqual(submissions["protocol_id"].tolist(), [100])
        self.assertEqual(report.resolved_parents, 1)
        self.assertEqual(report.direct_id_mismatches, 0)

    def test_duplicate_source_identifier_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            RuntimeError,
            "duplicate submission_id",
        ):
            prepare_submissions(
                pd.DataFrame([source_row(), source_row()])
            )

    def test_missing_direct_parent_is_rejected(self) -> None:
        submissions = prepare_submissions(
            pd.DataFrame([source_row()])
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = []
        with self.assertRaisesRegex(
            RuntimeError,
            "missing=1",
        ):
            resolve_parents(
                PostgreSQLLoadContext(connection, 7),
                submissions,
            )

    def test_direct_parent_tuple_mismatch_is_rejected(self) -> None:
        submissions = prepare_submissions(
            pd.DataFrame([source_row()])
        )
        mismatched = parent()
        mismatched["sequence_number"] = 3
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            mismatched
        ]
        with self.assertRaisesRegex(
            RuntimeError,
            "mismatches=1",
        ):
            resolve_parents(
                PostgreSQLLoadContext(connection, 7),
                submissions,
            )

    def test_upsert_is_idempotent_by_submission_id(self) -> None:
        submissions = prepare_submissions(
            pd.DataFrame([source_row()])
        )
        submissions["protocol_id"] = 100
        connection = MagicMock()
        self.assertEqual(
            upsert(
                PostgreSQLLoadContext(connection, 7),
                submissions,
            ),
            1,
        )
        self.assertIn(
            "ON CONFLICT (submission_id) DO UPDATE",
            str(connection.execute.call_args.args[0]),
        )

    def test_reconciliation_reports_required_metrics(self) -> None:
        expected = {
            "total_archived_rows": 221519,
            "resolved_parents": 221519,
            "missing_parents": 0,
            "duplicate_source_identifiers": 0,
            "direct_id_mismatches": 0,
            "archive_orphan_count": 0,
            "submission_number_mismatches": 0,
            "submission_type_mismatches": 0,
            "submission_status_mismatches": 0,
            "submission_date_mismatches": 0,
            "null_submission_numbers": 0,
            "null_submission_types": 0,
            "null_submission_statuses": 0,
            "null_submission_dates": 0,
        }
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.one.return_value = (
            expected
        )
        self.assertEqual(reconcile(connection).metrics, expected)


if __name__ == "__main__":
    unittest.main()
