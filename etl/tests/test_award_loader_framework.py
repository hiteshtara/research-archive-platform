from __future__ import annotations

import unittest
from unittest.mock import MagicMock, Mock

import pandas as pd

from archive_etl.pipeline.sources import OracleDataSource
from load_awards_from_csv import (
    AMOUNTS_ORACLE_SQL,
    PEOPLE_ORACLE_SQL,
    PROPOSALS_ORACLE_SQL,
    VERSIONS_ORACLE_SQL,
    prepare_versions,
)


class AwardLoaderFrameworkTest(unittest.TestCase):
    def test_prepare_versions_normalizes_columns_and_computes_primary_current(
        self,
    ) -> None:
        dataframe = pd.DataFrame(
            [
                {
                    "award_id": 101,
                    "award_number": "000001",
                    "sequence_number": 1,
                    "title": "Award",
                    "award_sequence_status": "ACTIVE",
                    "update_timestamp": "2025-01-02 03:04:05",
                }
            ]
        )

        prepared = prepare_versions(dataframe)

        self.assertEqual(prepared["award_id"].tolist(), [101])
        self.assertEqual(
            prepared["award_number"].tolist(),
            ["000001"],
        )
        self.assertEqual(
            prepared["is_primary_current"].tolist(),
            [True],
        )

    def test_oracle_extraction_sql_files_exist_and_are_readable(self) -> None:
        # These are checked in directly rather than downloaded at runtime,
        # so a missing/renamed file should fail loudly and immediately
        # rather than surfacing as a confusing FileNotFoundError deep
        # inside a real Oracle-connected run.
        for sql_path in (
            VERSIONS_ORACLE_SQL,
            AMOUNTS_ORACLE_SQL,
            PEOPLE_ORACLE_SQL,
            PROPOSALS_ORACLE_SQL,
        ):
            self.assertTrue(
                sql_path.is_file(),
                f"expected Oracle extraction SQL at {sql_path}",
            )

    def test_oracle_data_source_tolerates_the_sqlplus_header(self) -> None:
        # sql/extract/award/*.sql begin with SQL*Plus SET directives (used
        # by a separate manual export workflow) that OracleDataSource must
        # skip rather than try to execute.
        cursor = MagicMock()
        cursor.description = [("AWARD_ID",)]
        cursor.fetchmany.side_effect = [[(101,)], []]
        cursor.__enter__.return_value = cursor
        connection = MagicMock()
        connection.cursor.return_value = cursor
        connection.__enter__.return_value = connection
        connect = Mock(return_value=connection)

        dataframe = OracleDataSource(
            VERSIONS_ORACLE_SQL,
            connect=connect,
            environ={
                "ORACLE_USER": "user",
                "ORACLE_PASSWORD": "password",
                "ORACLE_DSN": "dsn",
            },
        ).read()

        self.assertEqual(dataframe["award_id"].tolist(), [101])
        executed_sql = cursor.execute.call_args.args[0]
        self.assertTrue(executed_sql.lstrip().upper().startswith("SELECT"))


if __name__ == "__main__":
    unittest.main()
