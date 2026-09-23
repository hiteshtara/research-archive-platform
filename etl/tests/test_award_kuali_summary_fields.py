"""Source-mapping contract for the Kuali-labelled Award Summary fields
added by V078 (Obligation Start Date, FAIN ID, NSF Science Code,
Account/Activity/Award Type).

These assert the real production artefacts - the Oracle extraction SQL,
the loader's column mapping, the migration, and the Java repository's
own SQL text - rather than reimplementing any of them, following this
repo's existing *SqlColumnContract convention.

The point is to pin the two mappings that were empirically WRONG-looking
and are easy to "fix" backwards later:

  1. Project Start Date is AWARD.AWARD_EFFECTIVE_DATE, never
     AWARD.BEGIN_DATE. BEGIN_DATE is populated in 2 of 267,386 Oracle
     AWARD rows; a future relabel onto begin_date would render blank.
  2. Obligation Start Date is read from the AWARD_AMOUNT_INFO row chosen
     by Kuali's MAX(award_amount_info_id) rule - never
     source_version_number - per docs/kuali-business-rules/Time and
     Money.md Rule 3, the same rule pinned by
     test_award_amount_info_current_row_selection.py.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
VERSIONS_SQL = REPO_ROOT / "sql" / "extract" / "award" / "01_award_versions.sql"
AMOUNTS_SQL = REPO_ROOT / "sql" / "extract" / "award" / "02_award_amounts.sql"
MIGRATION = (
    REPO_ROOT
    / "database"
    / "migrations"
    / "V078__add_award_kuali_summary_fields.sql"
)
LOADER = REPO_ROOT / "etl" / "load_awards_from_csv.py"
REPOSITORY = (
    REPO_ROOT
    / "api"
    / "src"
    / "main"
    / "java"
    / "edu"
    / "bu"
    / "archive"
    / "adapter"
    / "out"
    / "persistence"
    / "AwardArchiveRepository.java"
)


class AwardVersionExtractionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sql = VERSIONS_SQL.read_text(encoding="utf-8")

    def test_selects_every_new_award_sourced_column(self) -> None:
        for column in (
            "a.FAIN_ID",
            "a.NSF_SEQUENCE_NUMBER",
            "a.ACCOUNT_TYPE_CODE",
            "a.ACTIVITY_TYPE_CODE",
            "a.AWARD_TYPE_CODE",
        ):
            self.assertIn(column, self.sql)

    def test_resolves_nsf_science_code_through_nsf_codes(self) -> None:
        # NSF Science Code is NSF_CODES.NSF_CODE reached via the
        # surrogate AWARD.NSF_SEQUENCE_NUMBER - not a raw Award column.
        self.assertIn("n.NSF_CODE AS NSF_SCIENCE_CODE", self.sql)
        self.assertRegex(
            self.sql,
            r"LEFT JOIN NSF_CODES n\s*\n\s*ON n\.NSF_SEQUENCE_NUMBER = a\.NSF_SEQUENCE_NUMBER",
        )

    def test_activity_type_join_bridges_the_oracle_type_mismatch(self) -> None:
        # ACTIVITY_TYPE.ACTIVITY_TYPE_CODE is VARCHAR2 while
        # AWARD.ACTIVITY_TYPE_CODE is NUMBER. Without TO_CHAR this join
        # relies on implicit conversion.
        self.assertIn("TO_CHAR(a.ACTIVITY_TYPE_CODE)", self.sql)

    def test_resolves_code_descriptions_from_their_lookup_tables(self) -> None:
        for fragment in (
            "act.DESCRIPTION AS ACCOUNT_TYPE",
            "aty.DESCRIPTION AS ACTIVITY_TYPE",
            "awt.DESCRIPTION AS AWARD_TYPE",
        ):
            self.assertIn(fragment, self.sql)

    def test_still_extracts_award_effective_date(self) -> None:
        # The column "Project Start Date" actually renders.
        self.assertIn("a.AWARD_EFFECTIVE_DATE", self.sql)

    def test_does_not_extract_fed_award_year(self) -> None:
        # Populated in 0 of 267,386 Oracle rows - deliberately unarchived.
        self.assertNotIn("FED_AWARD_YEAR", self.sql)


class AwardAmountsExtractionTest(unittest.TestCase):
    def test_extracts_the_obligation_start_date_column(self) -> None:
        sql = AMOUNTS_SQL.read_text(encoding="utf-8")
        self.assertIn("aai.CURRENT_FUND_EFFECTIVE_DATE", sql)


def _strip_sql_comments(sql: str) -> str:
    """Only the executable statements, never the header prose - the
    header legitimately discusses begin_date, renaming and
    FED_AWARD_YEAR while explaining why none of them are touched."""
    return "\n".join(
        line for line in sql.splitlines() if not line.lstrip().startswith("--")
    )


class MigrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sql = _strip_sql_comments(MIGRATION.read_text(encoding="utf-8"))

    def test_adds_columns_idempotently(self) -> None:
        for column in (
            "fain_id",
            "nsf_sequence_number",
            "nsf_science_code",
            "account_type_code",
            "account_type",
            "activity_type_code",
            "activity_type",
            "award_type_code",
            "award_type",
            "current_fund_effective_date",
        ):
            self.assertRegex(
                self.sql,
                rf"ADD COLUMN IF NOT EXISTS\s+{column}\b",
                f"{column} must be added idempotently",
            )

    def test_does_not_drop_or_rename_begin_or_closeout_date(self) -> None:
        # Both are real archived columns even though the Summary UI no
        # longer shows them, and the PDF still renders them.
        self.assertNotIn("DROP COLUMN", self.sql.upper())
        self.assertNotIn("RENAME", self.sql.upper())

    def test_does_not_add_a_federal_award_year_column(self) -> None:
        self.assertNotIn("fed_award_year", self.sql.lower())


class LoaderMappingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = LOADER.read_text(encoding="utf-8")

    def test_every_new_column_is_inserted_and_upserted(self) -> None:
        for column in (
            "fain_id",
            "nsf_sequence_number",
            "nsf_science_code",
            "account_type_code",
            "account_type",
            "activity_type_code",
            "activity_type",
            "award_type_code",
            "award_type",
            "current_fund_effective_date",
        ):
            self.assertIn(f":{column}", self.source, f"{column} bind param")
            self.assertRegex(
                self.source,
                rf"{column}\s*=\s*\n?\s*EXCLUDED\.{column}",
                f"{column} upsert assignment",
            )

    def test_obligation_start_date_is_parsed_as_a_date(self) -> None:
        self.assertIn('"current_fund_effective_date",', self.source)

    def test_new_integer_codes_are_converted_numerically(self) -> None:
        for column in (
            "nsf_sequence_number",
            "account_type_code",
            "activity_type_code",
            "award_type_code",
        ):
            self.assertIn(f'"{column}"', self.source)


class RepositoryCurrentRowRuleTest(unittest.TestCase):
    """The Java side must read Obligation Start Date from the row Kuali
    considers current, using the already-validated rule."""

    def setUp(self) -> None:
        self.source = REPOSITORY.read_text(encoding="utf-8")

    def test_obligation_start_date_comes_from_the_amount_lateral(self) -> None:
        self.assertIn(
            "amt.current_fund_effective_date AS obligation_start_date",
            self.source,
        )

    def test_the_lateral_selecting_it_orders_by_max_award_amount_info_id(
        self,
    ) -> None:
        lateral = re.search(
            r"SELECT\s+ai\.obligated_total_amount,\s*"
            r"ai\.anticipated_total_amount,\s*"
            r"ai\.current_fund_effective_date\s+"
            r"FROM archive\.award_amount_info ai\s+"
            r"WHERE ai\.award_id = av\.award_id\s+"
            r"ORDER BY\s+ai\.award_amount_info_id DESC\s+"
            r"LIMIT 1",
            self.source,
        )
        self.assertIsNotNone(
            lateral,
            "Obligation Start Date must be read from the MAX("
            "award_amount_info_id) row, in the same LATERAL as the "
            "amounts, so the two can never disagree about which row "
            "was current",
        )

    def test_current_row_rule_never_orders_by_source_version_number(
        self,
    ) -> None:
        self.assertNotIn("ai.source_version_number DESC", self.source)

    def test_project_start_date_is_award_effective_date_not_begin_date(
        self,
    ) -> None:
        self.assertIn("av.award_effective_date", self.source)
        self.assertNotIn("av.begin_date AS project_start_date", self.source)
        self.assertNotIn(
            "av.begin_date AS award_effective_date", self.source
        )


class FullLoadAndIncrementalPathParityTest(unittest.TestCase):
    """The Award loader has TWO write paths that do not share a column
    list:

      - the full load COPYs via load_dataframe(), using an inline column
        list at its own call site;
      - --load-award-id/--load-batch UPSERT via _AWARD_VERSION_COLUMNS
        and the INSERT/ON CONFLICT SQL.

    A column added to one and not the other loads as NULL for whichever
    path was missed, with no error anywhere. That happened on
    2026-09-21: the V078 columns were added to the incremental path
    only, a targeted --load-award-id run populated them correctly, and a
    subsequent full reload then wrote NULL into all 267,386 rows.
    """

    def setUp(self) -> None:
        self.source = LOADER.read_text(encoding="utf-8")

    V078_VERSION_COLUMNS = (
        "fain_id", "nsf_sequence_number", "nsf_science_code",
        "account_type_code", "account_type", "activity_type_code",
        "activity_type", "award_type_code", "award_type",
    )

    def test_every_v078_version_column_appears_at_least_twice(self) -> None:
        # Once in _AWARD_VERSION_COLUMNS (incremental) and once in the
        # full load's inline load_dataframe column list.
        for column in self.V078_VERSION_COLUMNS:
            self.assertGreaterEqual(
                self.source.count(f'"{column}",'),
                2,
                f"{column} must be listed in BOTH the incremental "
                f"_AWARD_VERSION_COLUMNS and the full-load COPY column "
                f"list - one of the two is missing it",
            )

    def test_obligation_start_date_is_in_both_paths(self) -> None:
        self.assertGreaterEqual(
            self.source.count('"current_fund_effective_date",'),
            2,
            "current_fund_effective_date must be listed in BOTH the "
            "incremental award_amount_info column list and the "
            "full-load COPY column list",
        )

    def test_full_load_column_list_still_carries_the_pre_v078_columns(
        self,
    ) -> None:
        # Guards against a careless edit dropping existing columns while
        # adding the new ones.
        for column in ("award_effective_date", "begin_date",
                       "closeout_date", "modification_number",
                       "sponsor_award_number"):
            self.assertIn(f'"{column}",', self.source, column)


if __name__ == "__main__":
    unittest.main()