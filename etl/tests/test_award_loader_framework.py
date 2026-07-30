from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, Mock

import pandas as pd

from archive_etl.pipeline.sources import OracleDataSource
from load_awards_from_csv import (
    AMOUNTS_ORACLE_SQL,
    DOWNLOAD_DIR,
    PEOPLE_ORACLE_SQL,
    PROPOSALS_ORACLE_SQL,
    VERSIONS_ORACLE_SQL,
    parse_args,
    prepare_versions,
    read_csv,
)


class AwardLoaderFrameworkTest(unittest.TestCase):
    def test_shared_csv_source_preserves_award_preparation(self) -> None:
        with TemporaryDirectory() as directory:
            path = Path(directory) / "award_versions.csv"
            pd.DataFrame(
                [
                    {
                        "AWARD ID": "101",
                        "AWARD-NUMBER": "000001",
                        "SEQUENCE_NUMBER": "1",
                        "TITLE": "Award",
                        "AWARD_SEQUENCE_STATUS": "ACTIVE",
                        "UPDATE_TIMESTAMP": "2025-01-02 03:04:05",
                    }
                ]
            ).to_csv(path, index=False)

            prepared = prepare_versions(read_csv(path))

        self.assertEqual(prepared["award_id"].tolist(), [101])
        self.assertEqual(
            prepared["award_number"].tolist(),
            ["000001"],
        )
        self.assertEqual(
            prepared["is_primary_current"].tolist(),
            [True],
        )

    def test_oracle_is_default_and_csv_is_explicit(self) -> None:
        defaults = parse_args([])
        self.assertFalse(defaults.csv)
        self.assertEqual(defaults.csv_dir, DOWNLOAD_DIR)

        explicit_csv = parse_args(["--csv"])
        self.assertTrue(explicit_csv.csv)

        custom_dir = parse_args(["--csv-dir", "/tmp/award-exports"])
        self.assertEqual(
            custom_dir.csv_dir,
            Path("/tmp/award-exports"),
        )

    def test_oracle_and_csv_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            parse_args(["--oracle", "--csv"])

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
