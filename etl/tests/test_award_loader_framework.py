from __future__ import annotations

import unittest
from unittest.mock import MagicMock, Mock, patch

import pandas as pd

import load_awards_from_csv as award_loader
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


def _oracle_source_stub(dataframe: pd.DataFrame) -> MagicMock:
    stub = MagicMock()
    stub.read_filtered.return_value = dataframe
    return stub


class DiffAwardVersionsTest(unittest.TestCase):
    # Anchored to the real award this diagnostic was built to
    # investigate: "why does Award Versions show '-' for Document
    # number?" A live --diff-award-versions run against real Oracle/
    # PostgreSQL confirmed all 6 sequences of this award_number have
    # MODIFICATION_NUMBER = NULL in *both* Oracle and the archive - no
    # ETL/mapping bug, just a genuinely blank Oracle value. This fixture
    # is that exact real result, not a synthetic one.
    AWARD_NUMBER = "100567-00001"
    REAL_SEQUENCES = [
        (511, 1, "Converted Record"),
        (115975, 2, "No-Cost Extension"),
        (117767, 3, "No-Cost Extension"),
        (508634, 4, "Deobligation"),
        (686179, 5, "Other -- See Comments"),
        (1135067, 6, "Other -- See Comments"),
    ]

    def _real_oracle_rows(self) -> pd.DataFrame:
        return pd.DataFrame(
            [
                {
                    "award_id": award_id,
                    "award_number": self.AWARD_NUMBER,
                    "sequence_number": sequence_number,
                    "title": "Award",
                    "award_sequence_status": (
                        "ACTIVE" if sequence_number == 6 else "ARCHIVED"
                    ),
                    "transaction_type": transaction_type,
                    "modification_number": None,
                    "update_timestamp": "2025-01-02 03:04:05",
                }
                for award_id, sequence_number, transaction_type in self.REAL_SEQUENCES
            ]
        )

    def _archive_rows_matching_oracle(self) -> list[dict]:
        return [
            {
                "award_id": award_id,
                "sequence_number": sequence_number,
                "modification_number": None,
                "transaction_type": transaction_type,
                "source_update_timestamp": "2025-01-02 03:04:05",
            }
            for award_id, sequence_number, transaction_type in self.REAL_SEQUENCES
        ]

    def test_real_award_100567_00001_all_null_document_numbers_is_not_a_bug(
        self,
    ) -> None:
        engine = MagicMock()
        connection = engine.connect.return_value.__enter__.return_value
        connection.execute.return_value.mappings.return_value.all.return_value = (
            self._archive_rows_matching_oracle()
        )

        with patch.object(
            award_loader,
            "OracleDataSource",
            return_value=_oracle_source_stub(self._real_oracle_rows()),
        ):
            report = award_loader._run_diff_award_versions(
                engine, self.AWARD_NUMBER
            )

        self.assertEqual(report["oracle_count"], 6)
        self.assertEqual(report["archive_count"], 6)
        for row in report["rows"]:
            self.assertIsNone(row["oracle_document_number"])
            self.assertIsNone(row["archive_document_number"])
            self.assertIn("not a bug", row["reason"])
            self.assertNotIn("MISMATCH", row["reason"])
            self.assertNotIn("not archived", row["reason"])

    def test_detects_a_genuine_mismatch(self) -> None:
        engine = MagicMock()
        connection = engine.connect.return_value.__enter__.return_value
        connection.execute.return_value.mappings.return_value.all.return_value = [
            {
                "award_id": 511,
                "sequence_number": 1,
                "modification_number": "OLD-VALUE",
                "transaction_type": "Converted Record",
                "source_update_timestamp": "2025-01-02 03:04:05",
            }
        ]
        oracle_rows = pd.DataFrame(
            [
                {
                    "award_id": 511,
                    "award_number": self.AWARD_NUMBER,
                    "sequence_number": 1,
                    "title": "Award",
                    "award_sequence_status": "ACTIVE",
                    "transaction_type": "Converted Record",
                    "modification_number": "NEW-VALUE-FROM-ORACLE",
                    "update_timestamp": "2025-01-02 03:04:05",
                }
            ]
        )

        with patch.object(
            award_loader,
            "OracleDataSource",
            return_value=_oracle_source_stub(oracle_rows),
        ):
            report = award_loader._run_diff_award_versions(
                engine, self.AWARD_NUMBER
            )

        self.assertEqual(len(report["rows"]), 1)
        self.assertIn("MISMATCH", report["rows"][0]["reason"])
        self.assertEqual(
            report["rows"][0]["oracle_document_number"],
            "NEW-VALUE-FROM-ORACLE",
        )
        self.assertEqual(
            report["rows"][0]["archive_document_number"], "OLD-VALUE"
        )

    def test_detects_a_sequence_never_archived_at_all(self) -> None:
        engine = MagicMock()
        connection = engine.connect.return_value.__enter__.return_value
        connection.execute.return_value.mappings.return_value.all.return_value = []
        oracle_rows = pd.DataFrame(
            [
                {
                    "award_id": 1135067,
                    "award_number": self.AWARD_NUMBER,
                    "sequence_number": 6,
                    "title": "Award",
                    "award_sequence_status": "ACTIVE",
                    "transaction_type": "Other -- See Comments",
                    "modification_number": None,
                    "update_timestamp": "2025-01-02 03:04:05",
                }
            ]
        )

        with patch.object(
            award_loader,
            "OracleDataSource",
            return_value=_oracle_source_stub(oracle_rows),
        ):
            report = award_loader._run_diff_award_versions(
                engine, self.AWARD_NUMBER
            )

        self.assertEqual(report["archive_count"], 0)
        self.assertIn("not archived", report["rows"][0]["reason"])

    def test_returns_zero_counts_without_querying_postgres_when_oracle_is_empty(
        self,
    ) -> None:
        engine = MagicMock()

        with patch.object(
            award_loader,
            "OracleDataSource",
            return_value=_oracle_source_stub(pd.DataFrame()),
        ):
            report = award_loader._run_diff_award_versions(
                engine, self.AWARD_NUMBER
            )

        self.assertEqual(report["oracle_count"], 0)
        self.assertEqual(report["archive_count"], 0)
        engine.connect.assert_not_called()

    def test_uses_read_filtered_not_a_full_table_scan(self) -> None:
        engine = MagicMock()
        connection = engine.connect.return_value.__enter__.return_value
        connection.execute.return_value.mappings.return_value.all.return_value = (
            self._archive_rows_matching_oracle()
        )
        oracle_source = _oracle_source_stub(self._real_oracle_rows())

        with patch.object(
            award_loader, "OracleDataSource", return_value=oracle_source
        ):
            award_loader._run_diff_award_versions(engine, self.AWARD_NUMBER)

        oracle_source.read_filtered.assert_called_once()
        oracle_source.read.assert_not_called()
        oracle_source.read_batches.assert_not_called()


if __name__ == "__main__":
    unittest.main()
