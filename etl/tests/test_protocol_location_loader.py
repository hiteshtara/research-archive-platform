from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd

from archive_etl.pipeline.postgres import PostgreSQLLoadContext
from load_protocol_locations import (
    COLUMNS,
    prepare_locations,
    read_source,
    reconcile,
    resolve_parents,
    upsert,
)


def source_row() -> dict[str, object]:
    return {
        "protocol_location_id": "10",
        "source_protocol_id": "999",
        "protocol_number": "000100",
        "sequence_number": "2",
        "protocol_org_type_code": "1",
        "organization_id": "00001234",
        "rolodex_id": "50",
        "source_update_timestamp": "2026-01-02 03:04:05",
        "source_update_user": "USER",
        "source_version_number": "3",
        "source_object_id": "OBJ",
    }


class ProtocolLocationLoaderTest(unittest.TestCase):
    def test_oracle_and_csv_modes_produce_identical_rows(self) -> None:
        raw = pd.DataFrame([source_row()])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "protocol_locations.csv"
            raw.to_csv(path, index=False)
            with patch(
                "load_protocol_locations.OracleDataSource.read",
                return_value=raw,
            ):
                oracle, _ = read_source(None)
            csv, _ = read_source(Path(directory))
        pd.testing.assert_frame_equal(oracle, csv)
        self.assertEqual(oracle.columns.tolist(), COLUMNS)
        self.assertEqual(oracle.loc[0, "organization_id"], "00001234")

    def test_number_sequence_resolution_preserves_source_id(self) -> None:
        locations = prepare_locations(pd.DataFrame([source_row()]))
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
            locations,
        )
        self.assertEqual(report.source_protocol_id_mismatches, 1)
        self.assertEqual(locations["protocol_id"].tolist(), [100])
        self.assertEqual(locations["source_protocol_id"].tolist(), [999])
        self.assertEqual(
            locations["parent_resolution_method"].tolist(),
            ["NUMBER_SEQUENCE"],
        )

    def test_placeholder_tuple_uses_controlled_direct_id(self) -> None:
        row = source_row()
        row["protocol_number"] = "0"
        row["sequence_number"] = "0"
        row["source_protocol_id"] = "100"
        locations = prepare_locations(pd.DataFrame([row]))
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
            locations,
        )

        self.assertEqual(locations["protocol_id"].tolist(), [100])
        self.assertEqual(
            locations["parent_resolution_method"].tolist(),
            ["DIRECT_ID_PLACEHOLDER"],
        )
        self.assertEqual(report.direct_id_placeholder_rows, 1)
        self.assertEqual(report.distinct_placeholder_direct_ids, 1)
        self.assertEqual(report.rejected_rows, 0)

    def test_missing_tuple_fails_without_direct_id_fallback(self) -> None:
        row = source_row()
        row["source_protocol_id"] = "100"
        row["sequence_number"] = "3"
        locations = prepare_locations(pd.DataFrame([row]))
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]
        with self.assertRaisesRegex(
            RuntimeError,
            "missing=1, ambiguous=0",
        ):
            resolve_parents(
                PostgreSQLLoadContext(connection, 7),
                locations,
            )

    def test_ambiguous_tuple_fails(self) -> None:
        locations = prepare_locations(pd.DataFrame([source_row()]))
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
                locations,
            )

    def test_placeholder_with_missing_direct_parent_fails(self) -> None:
        row = source_row()
        row["protocol_number"] = "0"
        row["sequence_number"] = "0"
        row["source_protocol_id"] = "999"
        locations = prepare_locations(pd.DataFrame([row]))
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]

        with self.assertRaisesRegex(
            RuntimeError,
            "placeholder direct parent resolution failed",
        ):
            resolve_parents(
                PostgreSQLLoadContext(connection, 7),
                locations,
            )

    def test_upsert_uses_oracle_primary_key(self) -> None:
        locations = prepare_locations(pd.DataFrame([source_row()]))
        locations["protocol_id"] = 100
        connection = MagicMock()
        self.assertEqual(
            upsert(
                PostgreSQLLoadContext(connection, 7),
                locations,
            ),
            1,
        )
        self.assertIn(
            "ON CONFLICT (protocol_location_id) DO UPDATE",
            str(connection.execute.call_args.args[0]),
        )

    def test_reconciliation_reports_required_metrics(self) -> None:
        expected = {
            "total_rows": 40336,
            "number_sequence_rows": 38812,
            "direct_id_placeholder_rows": 1524,
            "distinct_placeholder_direct_ids": 1518,
            "missing_archive_direct_parents": 0,
            "rejected_rows": 0,
            "resolved_parents": 40336,
            "missing_parents": 0,
            "ambiguous_parents": 0,
            "source_protocol_id_mismatches": 610,
            "protocol_mismatches": 0,
            "location_value_mismatches": 0,
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
