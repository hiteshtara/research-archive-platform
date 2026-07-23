from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd

from archive_etl.pipeline.postgres import PostgreSQLLoadContext
from load_protocol_funding import (
    FUNDING_COLUMNS,
    parse_args,
    prepare_funding,
    read_source,
    reconcile,
    resolve_parents,
    upsert_funding,
)


def source_row() -> dict[str, object]:
    return {
        "protocol_funding_source_id": "10",
        "source_protocol_id": "999",
        "protocol_number": "000100",
        "sequence_number": "2",
        "funding_source_type_code": "1",
        "funding_source_number": "00001234",
        "funding_source_name": "Sponsor",
        "source_update_timestamp": "2026-01-02 03:04:05",
        "source_update_user": "USER",
        "source_version_number": "3",
        "source_object_id": "OBJ",
    }


class ProtocolFundingLoaderTest(unittest.TestCase):
    def test_oracle_and_csv_modes_produce_identical_funding(self) -> None:
        raw = pd.DataFrame([source_row()])
        with tempfile.TemporaryDirectory() as directory:
            csv_path = Path(directory) / "protocol_funding.csv"
            raw.to_csv(csv_path, index=False)
            with patch(
                "load_protocol_funding.OracleDataSource.read",
                return_value=raw,
            ):
                oracle, _ = read_source(None)
            csv, _ = read_source(Path(directory))

        pd.testing.assert_frame_equal(oracle, csv)
        self.assertEqual(oracle.columns.tolist(), FUNDING_COLUMNS)
        self.assertEqual(oracle.loc[0, "protocol_number"], "000100")
        self.assertEqual(
            oracle.loc[0, "funding_source_number"],
            "00001234",
        )

    def test_oracle_is_default_and_csv_dir_is_explicit(self) -> None:
        self.assertFalse(parse_args([]).oracle)
        self.assertIsNone(parse_args([]).csv_dir)
        self.assertTrue(parse_args(["--oracle"]).oracle)
        self.assertEqual(
            parse_args(["--csv-dir", "/tmp/funding"]).csv_dir,
            Path("/tmp/funding"),
        )

    def test_number_sequence_resolution_preserves_source_id(self) -> None:
        funding = prepare_funding(pd.DataFrame([source_row()]))
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
            funding,
        )

        self.assertEqual(funding["protocol_id"].tolist(), [100])
        self.assertEqual(funding["source_protocol_id"].tolist(), [999])
        self.assertEqual(report.resolved_parents, 1)
        self.assertEqual(report.source_protocol_id_mismatches, 1)

    def test_direct_id_is_not_used_as_missing_tuple_fallback(self) -> None:
        row = source_row()
        row["source_protocol_id"] = "100"
        row["sequence_number"] = "3"
        funding = prepare_funding(pd.DataFrame([row]))
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
                funding,
            )

    def test_ambiguous_number_sequence_parent_fails(self) -> None:
        funding = prepare_funding(pd.DataFrame([source_row()]))
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            },
            {
                "protocol_id": 101,
                "protocol_number": "000100",
                "sequence_number": 2,
            },
        ]

        with self.assertRaisesRegex(
            RuntimeError,
            "missing=0, ambiguous=1",
        ):
            resolve_parents(
                PostgreSQLLoadContext(connection, 7),
                funding,
            )

    def test_upsert_is_idempotent_by_source_primary_key(self) -> None:
        funding = prepare_funding(pd.DataFrame([source_row()]))
        funding["protocol_id"] = 100
        connection = MagicMock()

        count = upsert_funding(
            PostgreSQLLoadContext(connection, 7),
            funding,
        )

        self.assertEqual(count, 1)
        sql = str(connection.execute.call_args.args[0])
        self.assertIn(
            "ON CONFLICT (protocol_funding_source_id) DO UPDATE",
            sql,
        )

    def test_reconciliation_exposes_required_metrics(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.one.return_value = {
            "total_rows": 43405,
            "resolved_parents": 43405,
            "missing_parents": 0,
            "ambiguous_parents": 0,
            "source_protocol_id_mismatches": 192,
            "protocol_mismatches": 0,
            "funding_value_mismatches": 0,
        }

        result = reconcile(connection)

        self.assertEqual(result.metrics["total_rows"], 43405)
        self.assertEqual(
            result.metrics["source_protocol_id_mismatches"],
            192,
        )
        self.assertEqual(result.metrics["protocol_mismatches"], 0)


if __name__ == "__main__":
    unittest.main()
