from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd

from archive_etl.pipeline.postgres import PostgreSQLLoadContext
from load_protocol_actions import (
    COLUMNS,
    prepare_actions,
    read_source,
    reconcile,
    resolve_parents,
    upsert,
)


def source_row() -> dict[str, object]:
    return {
        "protocol_action_id": "10",
        "action_id": "4",
        "source_protocol_id": "999",
        "protocol_number": "000100",
        "sequence_number": "2",
        "submission_number": "3",
        "submission_id_fk": "50",
        "protocol_action_type_code": "100",
        "action_date": "2026-01-02 03:04:05",
        "source_update_timestamp": "2026-01-03 04:05:06",
        "source_update_user": "USER",
        "source_version_number": "1",
        "source_object_id": "OBJ",
    }


class ProtocolActionLoaderTest(unittest.TestCase):
    def test_oracle_and_csv_modes_produce_identical_rows(self) -> None:
        raw = pd.DataFrame([source_row()])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "protocol_actions.csv"
            raw.to_csv(path, index=False)
            with patch(
                "load_protocol_actions.OracleDataSource.read",
                return_value=raw,
            ):
                oracle, _ = read_source(None)
            csv, _ = read_source(Path(directory))
        pd.testing.assert_frame_equal(oracle, csv)
        self.assertEqual(oracle.columns.tolist(), COLUMNS)

    def test_tuple_parent_resolution_ignores_direct_id(self) -> None:
        actions = prepare_actions(pd.DataFrame([source_row()]))
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]
        report = resolve_parents(
            PostgreSQLLoadContext(connection, 7),
            actions,
        )
        self.assertEqual(actions["protocol_id"].tolist(), [100])
        self.assertEqual(actions["source_protocol_id"].tolist(), [999])
        self.assertEqual(report.direct_id_differences, 1)

    def test_duplicate_source_identifier_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            RuntimeError,
            "duplicate protocol_action_id",
        ):
            prepare_actions(
                pd.DataFrame([source_row(), source_row()])
            )

    def test_missing_tuple_parent_is_rejected_without_fallback(self) -> None:
        actions = prepare_actions(pd.DataFrame([source_row()]))
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = []
        with self.assertRaisesRegex(
            RuntimeError,
            "missing=1, ambiguous=0",
        ):
            resolve_parents(
                PostgreSQLLoadContext(connection, 7),
                actions,
            )

    def test_ambiguous_tuple_parent_is_rejected(self) -> None:
        actions = prepare_actions(pd.DataFrame([source_row()]))
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": protocol_id,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
            for protocol_id in (100, 101)
        ]
        with self.assertRaisesRegex(
            RuntimeError,
            "missing=0, ambiguous=1",
        ):
            resolve_parents(
                PostgreSQLLoadContext(connection, 7),
                actions,
            )

    def test_upsert_is_idempotent_by_protocol_action_id(self) -> None:
        actions = prepare_actions(pd.DataFrame([source_row()]))
        actions["protocol_id"] = 100
        connection = MagicMock()
        self.assertEqual(
            upsert(
                PostgreSQLLoadContext(connection, 7),
                actions,
            ),
            1,
        )
        self.assertIn(
            "ON CONFLICT (protocol_action_id) DO UPDATE",
            str(connection.execute.call_args.args[0]),
        )

    def test_reconciliation_reports_validated_counts(self) -> None:
        expected = {
            "total_archived_rows": 903796,
            "resolved_tuple_parents": 903796,
            "missing_tuple_parents": 0,
            "ambiguous_tuple_parents": 0,
            "direct_id_differences": 773324,
            "duplicate_source_identifiers": 0,
            "archive_orphan_count": 0,
        }
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.one.return_value = (
            expected
        )
        self.assertEqual(reconcile(connection).metrics, expected)


if __name__ == "__main__":
    unittest.main()
