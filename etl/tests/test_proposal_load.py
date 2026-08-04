from __future__ import annotations

import inspect
import unittest

import pandas as pd

import load_proposals_from_csv
from load_proposals_from_csv import (
    AWARD_COLUMNS,
    VERSION_COLUMNS,
    parse_args,
    prepare_awards,
    prepare_versions,
)


class ProjectRootResolutionTest(unittest.TestCase):
    # Regression test for a real bug: main()/run_targeted_load() each
    # called apply_migrations() with their own hardcoded
    # Path(__file__).resolve().parents[1] instead of the module-level
    # PROJECT_ROOT (itself resolved via _resolve_project_root() to
    # support both a local checkout and the flat ECS container layout -
    # see that function's own docstring). The hardcoded form only
    # breaks inside the container, which is exactly why it went
    # unnoticed - this loader had never actually run in ECS before.
    # Every apply_migrations() call site must use PROJECT_ROOT, not a
    # fresh Path(__file__) resolution of its own.
    def test_no_call_site_recomputes_project_root_independently(self) -> None:
        source = inspect.getsource(load_proposals_from_csv)
        occurrences = source.count("Path(__file__).resolve()")
        # Exactly two: both inside _resolve_project_root() itself.
        self.assertEqual(
            occurrences,
            2,
            "found a call site recomputing the project root independently "
            "instead of using the module-level PROJECT_ROOT - see "
            "_resolve_project_root()'s own docstring for why this matters "
            "inside the ECS container",
        )

    def test_project_root_resolves_to_a_directory_containing_sql_extract_proposal(
        self,
    ) -> None:
        self.assertTrue(
            (load_proposals_from_csv.PROJECT_ROOT / "sql" / "extract" / "proposal").is_dir()
        )


class PrepareVersionsTest(unittest.TestCase):
    def _fixture_row(self, **overrides):
        row = {
            "proposal_id": 2986,
            "proposal_number": "205",
            "version_number": 2,
            "document_number": "125761",
            "title": "Quality of Care in the Treatment of Burn Injuries",
            "proposal_sequence_status": "ACTIVE",
            "status_code": 2,
            "status_description": "Funded",
            "proposal_type_code": 3,
            "proposal_type": "Renewal",
            "activity_type_code": 1,
            "activity_type": "Research",
            "sponsor_code": "301957",
            "sponsor_name": "Some Sponsor",
            "lead_unit_number": "1262160000",
            "lead_unit_name": "Some Unit",
            "principal_investigator_id": "U56572816",
            "principal_investigator_name": "LOIS K HORWITZ",
            "initial_start_date": "2020-01-01",
            "initial_end_date": "2021-01-01",
            "initial_direct_cost": 10519.0,
            "initial_indirect_cost": 2735.0,
            "initial_total_cost": 13254.0,
            "total_start_date": "2020-01-01",
            "total_end_date": "2021-01-01",
            "total_direct_cost": 10519.0,
            "total_indirect_cost": 2735.0,
            "total_cost": 13254.0,
            "source_update_timestamp": "2020-01-01",
            "source_update_user": "jsmith",
        }
        row.update(overrides)
        return row

    # --- Real fixture-driven proof: document_number/status_code/
    # source_update_user survive prepare_versions unchanged - these are
    # exactly the three scalar fields
    # docs/kuali-business-rules/InstitutionalProposal.md and
    # PROPOSAL_ARCHIVE_COVERAGE.md both proved were missing.

    def test_preserves_document_number_status_code_and_update_user(self) -> None:
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_versions(dataframe)

        row = prepared.iloc[0]
        self.assertEqual(row["document_number"], "125761")
        self.assertEqual(row["status_code"], 2)
        self.assertEqual(row["status_description"], "Funded")
        self.assertEqual(row["source_update_user"], "jsmith")

    def test_preserves_every_version_regardless_of_sequence_status(self) -> None:
        # ARCHIVED, ACTIVE, PENDING, and CANCELED versions must all
        # survive prepare_versions unfiltered - "current" selection is
        # an application-layer concern (proposal_sequence_status ==
        # 'ACTIVE'), never an ETL-time filter. Real fixture: family 205
        # has one ARCHIVED (212) and one ACTIVE (2986) version.
        dataframe = pd.DataFrame([
            self._fixture_row(
                proposal_id=212, version_number=1,
                proposal_sequence_status="ARCHIVED", document_number="115569",
            ),
            self._fixture_row(
                proposal_id=2986, version_number=2,
                proposal_sequence_status="ACTIVE", document_number="125761",
            ),
            self._fixture_row(
                proposal_id=9001, version_number=1,
                proposal_number="999", proposal_sequence_status="PENDING",
            ),
            self._fixture_row(
                proposal_id=9002, version_number=1,
                proposal_number="998", proposal_sequence_status="CANCELED",
            ),
        ])

        prepared = prepare_versions(dataframe)

        self.assertEqual(len(prepared), 4)
        statuses = set(prepared["proposal_sequence_status"])
        self.assertEqual(statuses, {"ARCHIVED", "ACTIVE", "PENDING", "CANCELED"})

    def test_active_version_is_identified_by_status_not_highest_sequence(self) -> None:
        # Family 205: version 1 (212) is ARCHIVED, version 2 (2986,
        # the HIGHER sequence) is ACTIVE - proving "highest
        # version_number" alone would happen to give the right answer
        # here, but the real rule (checked below) is the status field,
        # never sequence position - see
        # docs/kuali-business-rules/InstitutionalProposal.md.
        dataframe = pd.DataFrame([
            self._fixture_row(
                proposal_id=212, version_number=1,
                proposal_sequence_status="ARCHIVED",
            ),
            self._fixture_row(
                proposal_id=2986, version_number=2,
                proposal_sequence_status="ACTIVE",
            ),
        ])

        prepared = prepare_versions(dataframe)

        active_rows = prepared[prepared["proposal_sequence_status"] == "ACTIVE"]
        self.assertEqual(len(active_rows), 1)
        self.assertEqual(active_rows.iloc[0]["proposal_id"], 2986)

    def test_a_cancelled_version_is_never_the_active_one(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(
                proposal_id=1, version_number=1,
                proposal_sequence_status="CANCELED",
            ),
            self._fixture_row(
                proposal_id=2, version_number=2,
                proposal_sequence_status="ACTIVE",
            ),
        ])

        prepared = prepare_versions(dataframe)

        cancelled = prepared[prepared["proposal_sequence_status"] == "CANCELED"]
        self.assertFalse((cancelled["proposal_id"] == 2).any())
        active = prepared[prepared["proposal_sequence_status"] == "ACTIVE"]
        self.assertEqual(list(active["proposal_id"]), [2])

    def test_rejects_duplicate_proposal_id_and_version_number_rows(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(proposal_id=2986, version_number=2),
            self._fixture_row(proposal_id=2986, version_number=2),
        ])

        with self.assertRaises(RuntimeError):
            prepare_versions(dataframe)

    def test_requires_proposal_id_number_and_version_number(self) -> None:
        dataframe = pd.DataFrame([{"title": "no identity columns"}])

        with self.assertRaises(RuntimeError):
            prepare_versions(dataframe)

    def test_all_expected_columns_are_declared(self) -> None:
        for column in (
            "document_number",
            "status_code",
            "status_description",
            "source_update_user",
        ):
            self.assertIn(column, VERSION_COLUMNS)


class PrepareAwardsTest(unittest.TestCase):
    def _fixture_row(self, **overrides):
        row = {
            "award_funding_proposal_id": 148183,
            "proposal_id": 2986,
            "award_id": 148155,
            "active": "Y",
            "source_update_timestamp": "2020-01-01",
            "source_update_user": "jsmith",
        }
        row.update(overrides)
        return row

    # --- Real fixture-driven proof: AWARD_FUNDING_PROPOSAL_ID 148183,
    # AWARD_ID 148155, PROPOSAL_ID 2986, ACTIVE='Y'.

    def test_preserves_exact_award_id_and_proposal_id_as_a_real_row(self) -> None:
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_awards(dataframe)

        row = prepared.iloc[0]
        self.assertEqual(row["award_id"], 148155)
        self.assertEqual(row["proposal_id"], 2986)
        self.assertEqual(row["award_funding_proposal_id"], 148183)

    def test_preserves_the_row_level_active_flag_as_a_real_boolean(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(award_funding_proposal_id=1, active="Y"),
            self._fixture_row(award_funding_proposal_id=2, active="N"),
        ])

        prepared = prepare_awards(dataframe)

        active_by_id = dict(
            zip(prepared["award_funding_proposal_id"], prepared["active"])
        )
        self.assertIs(active_by_id[1], True)
        self.assertIs(active_by_id[2], False)

    def test_does_not_reduce_the_relationship_to_award_number_or_proposal_number(
        self,
    ) -> None:
        # prepare_awards must never resolve/require award_number or
        # proposal_number - the exact awardId<->proposalId row is the
        # unit of storage; family-wide resolution is an
        # application-layer concern (see
        # docs/kuali-business-rules/InstitutionalProposal.md's Award
        # relationship section).
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_awards(dataframe)

        self.assertNotIn("award_number", prepared.columns)
        self.assertNotIn("proposal_number", prepared.columns)

    def test_deduplicates_by_the_real_award_funding_proposal_id_not_by_the_tuple(
        self,
    ) -> None:
        # Two rows sharing (proposal_id, award_id) but with DIFFERENT
        # award_funding_proposal_id values are two distinct real Oracle
        # rows (e.g. deactivated then re-recorded) and must both
        # survive - only a true duplicate award_funding_proposal_id
        # should be collapsed.
        dataframe = pd.DataFrame([
            self._fixture_row(award_funding_proposal_id=1, active="N"),
            self._fixture_row(award_funding_proposal_id=2, active="Y"),
        ])

        prepared = prepare_awards(dataframe)

        self.assertEqual(len(prepared), 2)

    def test_collapses_a_true_duplicate_award_funding_proposal_id(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(award_funding_proposal_id=1),
            self._fixture_row(award_funding_proposal_id=1),
        ])

        prepared = prepare_awards(dataframe)

        self.assertEqual(len(prepared), 1)

    def test_requires_proposal_id_and_award_id(self) -> None:
        dataframe = pd.DataFrame([{"active": "Y"}])

        with self.assertRaises(RuntimeError):
            prepare_awards(dataframe)

    def test_all_expected_columns_are_declared(self) -> None:
        for column in (
            "award_funding_proposal_id",
            "proposal_id",
            "award_id",
            "active",
            "source_update_timestamp",
            "source_update_user",
        ):
            self.assertIn(column, AWARD_COLUMNS)


class ParseArgsTest(unittest.TestCase):
    def test_proposal_number_is_repeatable(self) -> None:
        args = parse_args(["--proposal-number", "205", "--proposal-number", "999"])
        self.assertEqual(args.proposal_number, ["205", "999"])

    def test_max_families_accepts_an_integer(self) -> None:
        args = parse_args(["--max-families", "10"])
        self.assertEqual(args.max_families, 10)

    def test_proposal_number_and_max_families_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            parse_args(["--proposal-number", "205", "--max-families", "10"])

    def test_defaults_to_no_targeting(self) -> None:
        args = parse_args([])
        self.assertIsNone(args.proposal_number)
        self.assertIsNone(args.max_families)
        self.assertIsNone(args.limit)


if __name__ == "__main__":
    unittest.main()
