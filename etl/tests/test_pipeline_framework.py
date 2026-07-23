from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, Mock

import pandas as pd

from archive_etl.pipeline.postgres import PostgreSQLLoader
from archive_etl.pipeline.reconciliation import (
    ReconciliationResult,
    table_count_reconciler,
)
from archive_etl.pipeline.sources import (
    CsvDataSource,
    OracleDataSource,
    _materialize_oracle_value,
)


class PipelineSourceTest(unittest.TestCase):
    def test_oracle_lob_is_materialized_while_connected(self) -> None:
        lob = MagicMock()
        lob.read.return_value = "comments"

        with unittest.mock.patch(
            "archive_etl.pipeline.sources.oracledb.LOB",
            new=(type(lob),),
        ):
            value = _materialize_oracle_value(lob)

        self.assertEqual(value, "comments")
        lob.read.assert_called_once_with()

    def test_oracle_and_csv_sources_normalize_the_same_rows(self) -> None:
        with TemporaryDirectory() as directory:
            csv_path = Path(directory) / "rows.csv"
            sql_path = Path(directory) / "rows.sql"
            pd.DataFrame(
                [{"Record ID": "10", "Business-Key": "0001"}]
            ).to_csv(csv_path, index=False)
            sql_path.write_text("SELECT 10 FROM dual;", encoding="utf-8")

            cursor = MagicMock()
            cursor.__enter__.return_value = cursor
            cursor.description = [
                ("RECORD ID",),
                ("BUSINESS-KEY",),
            ]
            cursor.fetchmany.side_effect = [
                [("10", "0001")],
                [],
            ]
            connection = MagicMock()
            connection.__enter__.return_value = connection
            connection.cursor.return_value = cursor

            csv_rows = CsvDataSource(csv_path).read()
            oracle_rows = OracleDataSource(
                sql_path,
                connect=Mock(return_value=connection),
                environ={
                    "ORACLE_USER": "user",
                    "ORACLE_PASSWORD": "password",
                    "ORACLE_DSN": "dsn",
                },
            ).read()

        pd.testing.assert_frame_equal(oracle_rows, csv_rows)
        cursor.execute.assert_called_once_with(
            "SELECT 10 FROM dual"
        )


class PostgreSQLPipelineTest(unittest.TestCase):
    def test_loader_uses_one_load_run_and_shared_reconciliation(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalar_one.return_value = 42
        transaction = MagicMock()
        transaction.__enter__.return_value = connection
        engine = MagicMock()
        engine.begin.return_value = transaction
        reconciler = Mock(
            return_value=ReconciliationResult({"difference": 0})
        )
        operation = Mock(return_value=7)

        report = PostgreSQLLoader(engine, "/migrations").load(
            domain="TEST",
            source_system="KUALI",
            source_name="source",
            rows_read=7,
            operation=operation,
            reconciler=reconciler,
        )

        self.assertEqual(report.load_id, 42)
        self.assertEqual(report.rows_loaded, 7)
        self.assertTrue(report.reconciliation.passed)
        operation.assert_called_once()
        self.assertEqual(operation.call_args.args[0].load_id, 42)
        reconciler.assert_called_once_with(connection)

    def test_table_count_reconciliation_reports_differences(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalar_one.side_effect = [5, 2]

        result = table_count_reconciler(
            schema="archive",
            expected_counts={
                "parent": 5,
                "child": 3,
            },
        )(connection)

        self.assertEqual(
            result.metrics,
            {
                "parent_row_count_difference": 0,
                "child_row_count_difference": -1,
            },
        )
        self.assertFalse(result.passed)


if __name__ == "__main__":
    unittest.main()
