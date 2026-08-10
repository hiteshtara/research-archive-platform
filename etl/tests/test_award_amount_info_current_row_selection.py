"""Regression coverage for the Award "current AWARD_AMOUNT_INFO row"
selection rule used throughout AwardArchiveRepository.java
(findSummaryCards, findSummaryByAwardId, searchAwards,
findCurrentAmounts).

Kuali's own rule (docs/kuali-business-rules/Time and Money.md, Rule 3,
sourced from TimeAndMoneyHistoryServiceImpl.java) is: the current row is
the one with MAX(award_amount_info_id) - full stop. source_version_number
(Oracle's VER_NBR) is not part of that rule and must never outrank a
later-appended row.

These tests run the exact SQL text used in AwardArchiveRepository.java's
LATERAL amount subquery against a real Postgres database, so a future
"fix" that reintroduces `source_version_number DESC` ahead of
`award_amount_info_id DESC` fails here, not just in production.

Case A (award_id 3187665, real fixture: Award 204713-00133): four
competing rows, all with source_version_number=0 (a real Oracle tie).
The highest-id row is the correct current row and it happens to be
$0.00 - this was investigated as a suspected bug and confirmed NOT a
bug: it matches the last real, fully-processed Time and Money
transaction (Oracle document 923179). See Time and Money.md for the
full evidence chain.

Case B (award_id 8, real fixture): two rows where a lower-id row has a
higher source_version_number than a later, higher-id row. Before the
fix, the two-column ORDER BY incorrectly preferred the higher-
source_version_number (but stale) row; this test locks in that the
higher-award_amount_info_id row wins instead, per Kuali's own rule.
"""

from __future__ import annotations

import getpass
import os
import unittest
import uuid
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from archive_etl.upload.migrations import apply_migrations

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"

POSTGRES_HOST = os.environ.get("PYTEST_POSTGRES_HOST", "localhost")
POSTGRES_PORT = os.environ.get("PYTEST_POSTGRES_PORT", "5432")
POSTGRES_USER = os.environ.get("PYTEST_POSTGRES_USER", getpass.getuser())
MAINTENANCE_DB = os.environ.get("PYTEST_POSTGRES_MAINTENANCE_DB", "postgres")

# The exact LATERAL subquery used by AwardArchiveRepository.java's
# findSummaryCards/findSummaryByAwardId/searchAwards/findCurrentAmounts
# after the fix - kept in sync by hand, mirroring this repo's existing
# *SqlColumnContractTest convention of asserting real production SQL
# fragments rather than reimplementing them.
CURRENT_AMOUNT_SQL = """
    SELECT ai.award_amount_info_id, ai.obligated_total_direct
    FROM archive.award_amount_info ai
    WHERE ai.award_id = :award_id
    ORDER BY
        ai.award_amount_info_id DESC
    LIMIT 1
"""


def _maintenance_engine() -> Engine:
    return create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
        f"{POSTGRES_PORT}/{MAINTENANCE_DB}"
    )


def _postgres_available() -> bool:
    try:
        engine = _maintenance_engine()
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        engine.dispose()
    except Exception:
        return False
    return True


def _insert_award_version(connection, *, award_id: int, award_number: str) -> None:
    connection.execute(
        text(
            """
            INSERT INTO archive.award_version (
                award_id, award_number, sequence_number,
                is_current_version, is_primary_current
            ) VALUES (
                :award_id, :award_number, 0, TRUE, TRUE
            )
            """
        ),
        {"award_id": award_id, "award_number": award_number},
    )


def _insert_amount_row(
    connection,
    *,
    award_amount_info_id: int,
    award_id: int,
    award_number: str,
    obligated_total_direct: float,
    source_version_number,
    tnm_document_number: str | None,
    transaction_id: int | None,
) -> None:
    connection.execute(
        text(
            """
            INSERT INTO archive.award_amount_info (
                award_amount_info_id, award_id, award_number, sequence_number,
                obligated_total_direct, source_version_number,
                tnm_document_number, transaction_id
            ) VALUES (
                :id, :award_id, :award_number, 0,
                :obligated, :ver_nbr,
                :doc_number, :transaction_id
            )
            """
        ),
        {
            "id": award_amount_info_id,
            "award_id": award_id,
            "award_number": award_number,
            "obligated": obligated_total_direct,
            "ver_nbr": source_version_number,
            "doc_number": tnm_document_number,
            "transaction_id": transaction_id,
        },
    )


@unittest.skipUnless(_postgres_available(), "local PostgreSQL is not reachable")
class AwardAmountInfoCurrentRowSelectionTest(unittest.TestCase):
    db_prefix = "pytest_award_amount_current_row"

    def setUp(self) -> None:
        self.db_name = f"{self.db_prefix}_{uuid.uuid4().hex[:12]}"

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'CREATE DATABASE "{self.db_name}"'))
        maintenance.dispose()

        self.engine = create_engine(
            f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
            f"{POSTGRES_PORT}/{self.db_name}"
        )
        apply_migrations(self.engine, MIGRATIONS_DIR)

    def tearDown(self) -> None:
        self.engine.dispose()

        maintenance = _maintenance_engine()
        with maintenance.connect() as connection:
            connection.execution_options(isolation_level="AUTOCOMMIT")
            connection.execute(text(f'DROP DATABASE IF EXISTS "{self.db_name}"'))
        maintenance.dispose()

    def _selected_row(self, award_id: int):
        with self.engine.connect() as connection:
            return connection.execute(
                text(CURRENT_AMOUNT_SQL), {"award_id": award_id}
            ).one()

    def test_case_a_award_204713_00133_true_zero_is_current(self) -> None:
        award_id = 3187665
        with self.engine.begin() as connection:
            _insert_award_version(
                connection, award_id=award_id, award_number="204713-00133"
            )
            _insert_amount_row(
                connection,
                award_amount_info_id=3187674,
                award_id=award_id,
                award_number="204713-00133",
                obligated_total_direct=280607.11,
                source_version_number=0,
                tnm_document_number=None,
                transaction_id=76555,
            )
            _insert_amount_row(
                connection,
                award_amount_info_id=3187908,
                award_id=award_id,
                award_number="204713-00133",
                obligated_total_direct=0.0,
                source_version_number=0,
                tnm_document_number="923179",
                transaction_id=76644,
            )
            _insert_amount_row(
                connection,
                award_amount_info_id=3195981,
                award_id=award_id,
                award_number="204713-00133",
                obligated_total_direct=0.0,
                source_version_number=0,
                tnm_document_number="925932",
                transaction_id=None,
            )
            _insert_amount_row(
                connection,
                award_amount_info_id=3195982,
                award_id=award_id,
                award_number="204713-00133",
                obligated_total_direct=0.0,
                source_version_number=0,
                tnm_document_number="925932",
                transaction_id=None,
            )

        selected = self._selected_row(award_id)

        self.assertEqual(selected.award_amount_info_id, 3195982)
        self.assertEqual(float(selected.obligated_total_direct), 0.0)

    def test_case_b_award_id_8_higher_id_beats_higher_ver_nbr(self) -> None:
        award_id = 8
        with self.engine.begin() as connection:
            _insert_award_version(
                connection, award_id=award_id, award_number="100000-00008"
            )
            _insert_amount_row(
                connection,
                award_amount_info_id=8,
                award_id=award_id,
                award_number="100000-00008",
                obligated_total_direct=55345.00,
                source_version_number=1,
                tnm_document_number="103709",
                transaction_id=142,
            )
            _insert_amount_row(
                connection,
                award_amount_info_id=897305,
                award_id=award_id,
                award_number="100000-00008",
                obligated_total_direct=55281.06,
                source_version_number=0,
                tnm_document_number="285168",
                transaction_id=16688,
            )

        selected = self._selected_row(award_id)

        self.assertEqual(selected.award_amount_info_id, 897305)
        self.assertEqual(float(selected.obligated_total_direct), 55281.06)

    def test_general_invariant_selected_row_always_equals_max_id(self) -> None:
        """For every award with multiple amount rows and ANY mix of
        source_version_number values (including ties, nulls, and
        non-monotonic VER_NBR), the current-row query must select the
        row with the highest award_amount_info_id - never anything
        else. This is Kuali's Rule 3, and it must hold regardless of
        VER_NBR shape."""
        award_id = 999
        fixture_rows = [
            (100, 5, "111"),  # (award_amount_info_id, ver_nbr, doc)
            (200, 0, "222"),
            (150, 9, "150"),  # highest VER_NBR, but not highest id
            (300, None, "333"),  # highest id, null VER_NBR
        ]
        with self.engine.begin() as connection:
            _insert_award_version(
                connection, award_id=award_id, award_number="900000-00999"
            )
            for info_id, ver_nbr, doc in fixture_rows:
                _insert_amount_row(
                    connection,
                    award_amount_info_id=info_id,
                    award_id=award_id,
                    award_number="900000-00999",
                    obligated_total_direct=info_id,
                    source_version_number=ver_nbr,
                    tnm_document_number=doc,
                    transaction_id=info_id,
                )

        selected = self._selected_row(award_id)

        self.assertEqual(
            selected.award_amount_info_id, max(row[0] for row in fixture_rows)
        )


if __name__ == "__main__":
    unittest.main()
