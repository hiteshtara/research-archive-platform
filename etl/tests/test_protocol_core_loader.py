from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, Mock

import pandas as pd

from load_protocols_from_csv import (
    CsvReader,
    OracleReader,
    parse_args,
    prepare_protocols,
    select_reader,
    upsert_protocols,
)


class ProtocolCoreLoaderTest(unittest.TestCase):
    def test_preserves_multiple_versions_and_lookup_descriptions(self) -> None:
        prepared = prepare_protocols(
            pd.DataFrame(
                [
                    self._row(101, 1),
                    self._row(102, 2),
                ]
            )
        )

        self.assertEqual(prepared["protocol_id"].tolist(), [101, 102])
        self.assertEqual(prepared["sequence_number"].tolist(), [1, 2])
        self.assertEqual(
            prepared["protocol_status_description"].tolist(),
            ["Active", "Active"],
        )
        self.assertEqual(
            prepared["protocol_type_description"].tolist(),
            ["Human Subjects", "Human Subjects"],
        )

    def test_missing_optional_document_join_is_allowed(self) -> None:
        row = self._row(101, 1)
        row["protocol_workflow_type"] = None
        row["rerouted_flag"] = None

        prepared = prepare_protocols(pd.DataFrame([row]))

        self.assertTrue(
            pd.isna(prepared.iloc[0]["protocol_workflow_type"])
        )

    def test_duplicate_protocol_id_fails(self) -> None:
        with self.assertRaisesRegex(
            RuntimeError,
            "duplicate protocol_id",
        ):
            prepare_protocols(
                pd.DataFrame(
                    [
                        self._row(101, 1),
                        self._row(101, 2),
                    ]
                )
            )

    def test_invalid_date_fails_instead_of_becoming_null(self) -> None:
        row = self._row(101, 1)
        row["expiration_date"] = "not-a-date"

        with self.assertRaisesRegex(
            RuntimeError,
            "invalid expiration_date",
        ):
            prepare_protocols(pd.DataFrame([row]))

    def test_upsert_is_idempotent_by_protocol_id(self) -> None:
        dataframe = prepare_protocols(
            pd.DataFrame([self._row(101, 1)])
        )
        connection = Mock()

        loaded = upsert_protocols(connection, dataframe, 5)

        self.assertEqual(loaded, 1)
        statement = str(connection.execute.call_args.args[0])
        self.assertIn("ON CONFLICT (protocol_id) DO UPDATE", statement)
        values = connection.execute.call_args.args[1]
        self.assertEqual(values[0]["protocol_id"], 101)
        self.assertEqual(values[0]["load_run_id"], 5)

    def test_oracle_and_csv_readers_produce_identical_protocols(self) -> None:
        source_row = {
            **self._row(101, 1),
            "approval_date": "2025-01-02",
            "source_update_timestamp": "2025-01-03 04:05:06",
            "source_version_number": "7",
            "source_object_id": "OBJ-101",
        }

        with TemporaryDirectory() as directory:
            csv_path = Path(directory) / "protocol_versions.csv"
            sql_path = Path(directory) / "export_protocol_versions.sql"
            pd.DataFrame([source_row]).to_csv(csv_path, index=False)
            sql_path.write_text(
                "SELECT protocol_id FROM KCOEUS.PROTOCOL;",
                encoding="utf-8",
            )

            columns = list(source_row)
            oracle_row = tuple(source_row[column] for column in columns)
            cursor = MagicMock()
            cursor.description = [
                (column.upper(),)
                for column in columns
            ]
            cursor.fetchmany.side_effect = [[oracle_row], []]
            cursor.__enter__.return_value = cursor
            connection = MagicMock()
            connection.cursor.return_value = cursor
            connection.__enter__.return_value = connection
            connect = Mock(return_value=connection)

            csv_protocols = CsvReader(csv_path).read()
            oracle_protocols = OracleReader(
                sql_path,
                connect=connect,
                environ={
                    "ORACLE_USER": "user",
                    "ORACLE_PASSWORD": "password",
                    "ORACLE_DSN": "dsn",
                },
            ).read()

        self.assertEqual(oracle_protocols, csv_protocols)
        self.assertEqual(oracle_protocols[0].protocol_id, 101)
        self.assertEqual(
            oracle_protocols[0].protocol_number,
            "00000100",
        )
        connect.assert_called_once_with(
            user="user",
            password="password",
            dsn="dsn",
        )
        cursor.execute.assert_called_once_with(
            "SELECT protocol_id FROM KCOEUS.PROTOCOL"
        )

    def test_oracle_is_default_and_csv_is_explicit(self) -> None:
        self.assertIsInstance(
            select_reader(parse_args([])),
            OracleReader,
        )
        reader = select_reader(
            parse_args(["--csv", "/tmp/protocols.csv"])
        )
        self.assertIsInstance(reader, CsvReader)
        self.assertEqual(
            reader.path,
            Path("/tmp/protocols.csv"),
        )

    def _row(self, protocol_id: int, sequence: int) -> dict[str, object]:
        return {
            "protocol_id": str(protocol_id),
            "protocol_number": "00000100",
            "sequence_number": str(sequence),
            "document_number": None,
            "active": "Y",
            "protocol_type_code": "1",
            "protocol_type_description": "Human Subjects",
            "protocol_status_code": "200",
            "protocol_status_description": "Active",
            "title": "Protocol title",
        }


if __name__ == "__main__":
    unittest.main()
