from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import pandas as pd

from archive_etl.pipeline.postgres import PostgreSQLLoadContext
from load_protocol_research_areas import (
    COLUMNS,
    prepare_research_areas,
    read_source,
    reconcile,
    resolve_parents,
    upsert,
)


def source_row() -> dict[str, object]:
    return {
        "protocol_research_area_id": "10",
        "source_protocol_id": "999",
        "protocol_number": "000100",
        "sequence_number": "2",
        "research_area_code": "RA-1",
        "source_update_timestamp": "2026-01-02 03:04:05",
        "source_update_user": "USER",
        "source_version_number": "3",
        "source_object_id": "OBJ",
    }


class ProtocolResearchAreaLoaderTest(unittest.TestCase):
    def test_oracle_and_csv_modes_produce_identical_rows(self) -> None:
        raw = pd.DataFrame([source_row()])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "protocol_research_areas.csv"
            raw.to_csv(path, index=False)
            with patch(
                "load_protocol_research_areas.OracleDataSource.read",
                return_value=raw,
            ):
                oracle, _ = read_source(None)
            csv, _ = read_source(Path(directory))
        pd.testing.assert_frame_equal(oracle, csv)
        self.assertEqual(oracle.columns.tolist(), COLUMNS)

    def test_number_sequence_resolution_preserves_source_id(self) -> None:
        areas = prepare_research_areas(pd.DataFrame([source_row()]))
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]
        mismatches = resolve_parents(
            PostgreSQLLoadContext(connection, 7),
            areas,
        )
        self.assertEqual(areas["protocol_id"].tolist(), [100])
        self.assertEqual(areas["source_protocol_id"].tolist(), [999])
        self.assertEqual(mismatches, 1)

    def test_direct_id_is_not_a_missing_tuple_fallback(self) -> None:
        row = source_row()
        row["source_protocol_id"] = "100"
        row["sequence_number"] = "3"
        areas = prepare_research_areas(pd.DataFrame([row]))
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
                areas,
            )

    def test_ambiguous_tuple_fails(self) -> None:
        areas = prepare_research_areas(pd.DataFrame([source_row()]))
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
                areas,
            )

    def test_upsert_uses_oracle_primary_key(self) -> None:
        areas = prepare_research_areas(pd.DataFrame([source_row()]))
        areas["protocol_id"] = 100
        connection = MagicMock()
        count = upsert(
            PostgreSQLLoadContext(connection, 7),
            areas,
        )
        self.assertEqual(count, 1)
        self.assertIn(
            "ON CONFLICT (protocol_research_area_id) DO UPDATE",
            str(connection.execute.call_args.args[0]),
        )

    def test_reconciliation_reports_required_metrics(self) -> None:
        connection = MagicMock()
        expected = {
            "total_source_rows": 35331,
            "resolved_parents": 35331,
            "missing_parents": 0,
            "ambiguous_parents": 0,
            "source_protocol_id_mismatches": 189,
            "protocol_mismatches": 0,
            "research_area_value_mismatches": 0,
            "duplicate_source_identifiers": 0,
            "archive_orphan_count": 0,
        }
        connection.execute.return_value.mappings.return_value.one.return_value = (
            expected
        )
        self.assertEqual(reconcile(connection).metrics, expected)


if __name__ == "__main__":
    unittest.main()
