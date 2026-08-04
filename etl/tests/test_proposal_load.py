from __future__ import annotations

import inspect
import unittest

import pandas as pd

import load_proposals_from_csv
from load_proposals_from_csv import (
    ATTACHMENT_COLUMNS,
    AWARD_COLUMNS,
    COMMENT_COLUMNS,
    PERSON_COLUMNS,
    PERSON_UNIT_COLUMNS,
    UNIT_CONTACT_COLUMNS,
    VERSION_COLUMNS,
    parse_args,
    prepare_attachments,
    prepare_awards,
    prepare_comments,
    prepare_person_units,
    prepare_persons,
    prepare_unit_contacts,
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


class UnresolvedAwardIdHandlingTest(unittest.TestCase):
    # Regression test for a real, live-caught issue: this archive holds
    # only a fraction of all Oracle Awards (loaded incrementally), so a
    # Proposal batch will routinely reference an Award ID not yet
    # loaded. upsert_proposal_awards() used to raise and abort the
    # WHOLE transaction (rolling back every Proposal version in the
    # same batch too) - "preserve every Proposal version" must not be
    # blocked by an unrelated Award-population gap. Fixed to skip and
    # log the specific unresolved rows instead (the INSERT's own JOIN
    # to archive.award_version already filters them out naturally) -
    # verified statically here since exercising the real SQL needs a
    # live Postgres connection, covered instead by the live fixture/
    # batch load itself.
    def test_upsert_proposal_awards_does_not_raise_for_unresolved_award_ids(
        self,
    ) -> None:
        source = inspect.getsource(load_proposals_from_csv.upsert_proposal_awards)
        self.assertNotIn("raise RuntimeError", source)
        self.assertIn("logger.warning", source)


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
        # Raw column names as 04_award_proposals.sql/normalize_columns
        # actually produce them (update_timestamp/update_user, not yet
        # renamed to source_update_*) - matches real extraction output,
        # not the archive's own target column names.
        row = {
            "award_funding_proposal_id": 148183,
            "proposal_id": 2986,
            "award_id": 148155,
            "active": "Y",
            "update_timestamp": "2020-01-01",
            "update_user": "jsmith",
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

    def test_renames_raw_oracle_columns_to_the_archive_target_names(self) -> None:
        # 04_award_proposals.sql is shared with Award's own loader and
        # must not be renamed itself - prepare_awards() does the
        # rename, Proposal-side only.
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_awards(dataframe)

        self.assertIn("source_update_timestamp", prepared.columns)
        self.assertIn("source_update_user", prepared.columns)
        self.assertNotIn("update_timestamp", prepared.columns)
        self.assertNotIn("update_user", prepared.columns)
        self.assertEqual(prepared.iloc[0]["source_update_user"], "jsmith")

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


class PrepareAttachmentsTest(unittest.TestCase):
    def _fixture_row(self, **overrides):
        # Real fixture, live-verified: Institutional Proposal 01157400,
        # the "Guidelines" attachment on its ACTIVE version (proposal_id
        # 1238613, sequence 7). The SAME file_data_id also appears on
        # proposal_attachment_id 2395 (the ARCHIVED version 2's copy of
        # the same reference) - proving one FILE_DATA_ID legitimately
        # backs more than one real historical attachment row.
        row = {
            "proposal_attachment_id": 86484,
            "proposal_id": 1238613,
            "proposal_number": "01157400",
            "sequence_number": 7,
            "attachment_number": 4,
            "attachment_title": "Ryan_NSF_1.11.17_Guidelines",
            "attachment_type_code": 7,
            "attachment_type_description": "Other",
            "file_name": "Ryan_NSF_1.11.17_Guidelines.pdf",
            "content_type": "application/pdf",
            "comments": "Ryan_NSF_1.11.17_Guidelines NSF-12-8086 DEMS",
            "document_status_code": "A",
            "file_data_id": "d208062d-77ca-4a12-aa1f-0e69318a91ae",
            "source_update_timestamp": "2019-04-11",
            "source_update_user": "egibbs",
        }
        row.update(overrides)
        return row

    def test_preserves_the_real_fixture_row_verbatim(self) -> None:
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_attachments(dataframe)

        row = prepared.iloc[0]
        self.assertEqual(row["proposal_attachment_id"], 86484)
        self.assertEqual(row["proposal_id"], 1238613)
        self.assertEqual(row["file_name"], "Ryan_NSF_1.11.17_Guidelines.pdf")
        self.assertEqual(
            row["file_data_id"], "d208062d-77ca-4a12-aa1f-0e69318a91ae"
        )
        self.assertEqual(row["document_status_code"], "A")

    def test_attachment_type_description_is_oracles_real_taxonomy_not_the_title(
        self,
    ) -> None:
        # Live-verified: PROPOSAL_ATTACHMENT_TYPE has no "Guidelines"
        # category - a file whose TITLE contains "Guidelines" is
        # actually filed under Oracle's real type_code 7 ("Other").
        # Never derive the group label from attachment_title.
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_attachments(dataframe)

        row = prepared.iloc[0]
        self.assertIn("Guidelines", row["attachment_title"])
        self.assertEqual(row["attachment_type_description"], "Other")

    def test_two_rows_may_legitimately_share_one_file_data_id(self) -> None:
        # proposal_attachment_id 2395 (ARCHIVED version 2) and 86484
        # (ACTIVE version 7) are two REAL, distinct historical rows
        # sharing one file_data_id - both must survive, never collapsed
        # as if they were duplicates.
        dataframe = pd.DataFrame([
            self._fixture_row(
                proposal_attachment_id=2395,
                proposal_id=1179677,
                sequence_number=2,
            ),
            self._fixture_row(
                proposal_attachment_id=86484,
                proposal_id=1238613,
                sequence_number=7,
            ),
        ])

        prepared = prepare_attachments(dataframe)

        self.assertEqual(len(prepared), 2)
        self.assertEqual(
            prepared["file_data_id"].nunique(), 1,
            "both rows should share the same real file_data_id",
        )

    def test_collapses_a_true_duplicate_proposal_attachment_id(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(),
            self._fixture_row(),
        ])

        prepared = prepare_attachments(dataframe)

        self.assertEqual(len(prepared), 1)

    def test_requires_identity_columns(self) -> None:
        dataframe = pd.DataFrame([{"attachment_title": "no identity columns"}])

        with self.assertRaises(RuntimeError):
            prepare_attachments(dataframe)

    def test_never_includes_binary_lifecycle_columns(self) -> None:
        # upload_status/s3_bucket/object_key/file_size/checksum/
        # uploaded_at/error_message are owned exclusively by the binary
        # pipeline (ProposalAttachmentPlugin) - the metadata loader must
        # never declare or write them.
        for column in (
            "upload_status",
            "s3_bucket",
            "object_key",
            "file_size",
            "checksum",
            "uploaded_at",
            "error_message",
        ):
            self.assertNotIn(column, ATTACHMENT_COLUMNS)

    def test_all_expected_columns_are_declared(self) -> None:
        for column in (
            "proposal_attachment_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
            "attachment_number",
            "attachment_title",
            "attachment_type_code",
            "attachment_type_description",
            "file_name",
            "content_type",
            "comments",
            "document_status_code",
            "file_data_id",
            "source_update_timestamp",
            "source_update_user",
        ):
            self.assertIn(column, ATTACHMENT_COLUMNS)


class PreparePersonsTest(unittest.TestCase):
    def _fixture_row(self, **overrides):
        # Real fixture, live-verified: Institutional Proposal family
        # 205, PI Lois K Horwitz (U56572816) on proposal_id 212
        # (sequence 1). The same person/role reappears on proposal_id
        # 2986 (sequence 2) with a different proposal_person_id -
        # PROPOSAL_PERSON_ID is Oracle's own real PK, not a
        # cross-version identity.
        row = {
            "proposal_person_id": 126591,
            "proposal_id": 212,
            "proposal_number": "205",
            "sequence_number": 1,
            "person_id": "U56572816",
            "rolodex_id": None,
            "full_name": "LOIS K HORWITZ",
            "contact_role_code": "PI",
            "key_person_project_role": None,
            "faculty_flag": "N",
            "academic_year_effort": None,
            "calendar_year_effort": None,
            "summer_effort": None,
            "total_effort": None,
            "source_update_timestamp": "2011-08-10",
            "source_update_user": "baccari",
        }
        row.update(overrides)
        return row

    def test_preserves_the_real_fixture_row_verbatim(self) -> None:
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_persons(dataframe)

        row = prepared.iloc[0]
        self.assertEqual(row["proposal_person_id"], 126591)
        self.assertEqual(row["proposal_id"], 212)
        self.assertEqual(row["person_id"], "U56572816")
        self.assertEqual(row["full_name"], "LOIS K HORWITZ")
        self.assertEqual(row["contact_role_code"], "PI")

    def test_the_same_person_may_appear_on_more_than_one_version(self) -> None:
        # Real fixture: the same PI appears on both proposal_id 212 and
        # 2986 with two DIFFERENT proposal_person_id values - never
        # collapsed to one row per person.
        dataframe = pd.DataFrame([
            self._fixture_row(),
            self._fixture_row(
                proposal_person_id=148162,
                proposal_id=2986,
                sequence_number=2,
                source_update_timestamp="2011-09-07",
                source_update_user="dmarkey",
            ),
        ])

        prepared = prepare_persons(dataframe)

        self.assertEqual(len(prepared), 2)
        self.assertEqual(prepared["person_id"].nunique(), 1)

    def test_collapses_a_true_duplicate_proposal_person_id(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(),
            self._fixture_row(),
        ])

        prepared = prepare_persons(dataframe)

        self.assertEqual(len(prepared), 1)

    def test_requires_identity_columns(self) -> None:
        dataframe = pd.DataFrame([{"full_name": "no identity columns"}])

        with self.assertRaises(RuntimeError):
            prepare_persons(dataframe)

    def test_all_expected_columns_are_declared(self) -> None:
        for column in (
            "proposal_person_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
            "person_id",
            "rolodex_id",
            "full_name",
            "contact_role_code",
            "key_person_project_role",
            "faculty_flag",
            "academic_year_effort",
            "calendar_year_effort",
            "summer_effort",
            "total_effort",
            "source_update_timestamp",
            "source_update_user",
        ):
            self.assertIn(column, PERSON_COLUMNS)


class PreparePersonUnitsTest(unittest.TestCase):
    def _fixture_row(self, **overrides):
        # Real fixture: PI Lois K Horwitz's PROPOSAL_PERSON_UNITS row on
        # proposal_id 212 - unit 1262160000, lead_unit_flag='Y',
        # matching PROPOSAL.LEAD_UNIT_NUMBER for this same proposal_id
        # (live-confirmed - see V061's migration comment). A different
        # concept from the proposal's own lead_unit_number column,
        # despite agreeing here.
        row = {
            "proposal_person_unit_id": 126592,
            "proposal_person_id": 126591,
            "proposal_id": 212,
            "proposal_number": "205",
            "sequence_number": 1,
            "unit_number": "1262160000",
            "lead_unit_flag": "Y",
            "source_update_timestamp": "2011-08-10",
            "source_update_user": "baccari",
        }
        row.update(overrides)
        return row

    def test_preserves_the_real_fixture_row_verbatim(self) -> None:
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_person_units(dataframe)

        row = prepared.iloc[0]
        self.assertEqual(row["proposal_person_unit_id"], 126592)
        self.assertEqual(row["proposal_person_id"], 126591)
        self.assertEqual(row["unit_number"], "1262160000")
        self.assertEqual(row["lead_unit_flag"], "Y")

    def test_collapses_a_true_duplicate_proposal_person_unit_id(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(),
            self._fixture_row(),
        ])

        prepared = prepare_person_units(dataframe)

        self.assertEqual(len(prepared), 1)

    def test_requires_identity_columns(self) -> None:
        dataframe = pd.DataFrame([{"unit_number": "no identity columns"}])

        with self.assertRaises(RuntimeError):
            prepare_person_units(dataframe)

    def test_all_expected_columns_are_declared(self) -> None:
        for column in (
            "proposal_person_unit_id",
            "proposal_person_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
            "unit_number",
            "lead_unit_flag",
            "source_update_timestamp",
            "source_update_user",
        ):
            self.assertIn(column, PERSON_UNIT_COLUMNS)


class PrepareUnitContactsTest(unittest.TestCase):
    def _fixture_row(self, **overrides):
        # Real fixture: PROPOSAL_UNIT_CONTACTS row on proposal_id 212 -
        # Andrea Cozzi (U19663726), unit_administrator_type_code '1'
        # ("Pre-Award - Department Administrator"). A genuinely
        # different person than the PI (Lois K Horwitz, U56572816) on
        # the same proposal - live-confirmed distinct, never merged
        # with proposal_person.
        row = {
            "proposal_unit_contact_id": 204,
            "proposal_id": 212,
            "proposal_number": "205",
            "sequence_number": 1,
            "person_id": "U19663726",
            "full_name": "ANDREA COZZI",
            "unit_administrator_type_code": "1",
            "unit_contact_type": "CONTACT",
            "source_update_timestamp": "2011-08-10",
            "source_update_user": "baccari",
        }
        row.update(overrides)
        return row

    def test_preserves_the_real_fixture_row_verbatim(self) -> None:
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_unit_contacts(dataframe)

        row = prepared.iloc[0]
        self.assertEqual(row["proposal_unit_contact_id"], 204)
        self.assertEqual(row["person_id"], "U19663726")
        self.assertEqual(row["full_name"], "ANDREA COZZI")
        self.assertEqual(row["unit_administrator_type_code"], "1")

    def test_is_a_different_person_than_the_pi_on_the_same_proposal(
        self,
    ) -> None:
        unit_contact = prepare_unit_contacts(
            pd.DataFrame([self._fixture_row()])
        ).iloc[0]
        person = prepare_persons(
            pd.DataFrame([PreparePersonsTest()._fixture_row()])
        ).iloc[0]

        self.assertEqual(unit_contact["proposal_id"], person["proposal_id"])
        self.assertNotEqual(unit_contact["person_id"], person["person_id"])

    def test_collapses_a_true_duplicate_proposal_unit_contact_id(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(),
            self._fixture_row(),
        ])

        prepared = prepare_unit_contacts(dataframe)

        self.assertEqual(len(prepared), 1)

    def test_requires_identity_columns(self) -> None:
        dataframe = pd.DataFrame([{"full_name": "no identity columns"}])

        with self.assertRaises(RuntimeError):
            prepare_unit_contacts(dataframe)

    def test_all_expected_columns_are_declared(self) -> None:
        for column in (
            "proposal_unit_contact_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
            "person_id",
            "full_name",
            "unit_administrator_type_code",
            "unit_contact_type",
            "source_update_timestamp",
            "source_update_user",
        ):
            self.assertIn(column, UNIT_CONTACT_COLUMNS)


class PrepareCommentsTest(unittest.TestCase):
    def _fixture_row(self, **overrides):
        # Real fixture: family 205, proposal_id 2986 (sequence 2) -
        # PROPOSAL_COMMENTS_ID 433 ("Proposal Comments", type_code 12).
        # A sibling row (id 434, type_code 13, "Proposal IP Review
        # Comments") exists on the same proposal_id with a NULL
        # comment body - both are real, distinct rows.
        row = {
            "proposal_comment_id": 433,
            "proposal_id": 2986,
            "proposal_number": "205",
            "sequence_number": 1,
            "comment_type_code": "12",
            "comments": "Continuation of BU source #5039-5.",
            "source_update_timestamp": "2011-09-07",
            "source_update_user": "dmarkey",
        }
        row.update(overrides)
        return row

    def test_preserves_the_real_fixture_row_verbatim(self) -> None:
        dataframe = pd.DataFrame([self._fixture_row()])

        prepared = prepare_comments(dataframe)

        row = prepared.iloc[0]
        self.assertEqual(row["proposal_comment_id"], 433)
        self.assertEqual(row["proposal_id"], 2986)
        self.assertEqual(row["comment_type_code"], "12")
        self.assertEqual(
            row["comments"], "Continuation of BU source #5039-5."
        )

    def test_a_null_comment_body_is_a_real_distinct_row(self) -> None:
        # Real fixture: proposal_comment_id 434 has comment_type_code
        # 13 and a NULL comment body - still a real row, never dropped
        # for having no text.
        dataframe = pd.DataFrame([
            self._fixture_row(),
            self._fixture_row(
                proposal_comment_id=434,
                comment_type_code="13",
                comments=None,
            ),
        ])

        prepared = prepare_comments(dataframe)

        self.assertEqual(len(prepared), 2)

    def test_collapses_a_true_duplicate_proposal_comment_id(self) -> None:
        dataframe = pd.DataFrame([
            self._fixture_row(),
            self._fixture_row(),
        ])

        prepared = prepare_comments(dataframe)

        self.assertEqual(len(prepared), 1)

    def test_requires_identity_columns(self) -> None:
        dataframe = pd.DataFrame([{"comments": "no identity columns"}])

        with self.assertRaises(RuntimeError):
            prepare_comments(dataframe)

    def test_all_expected_columns_are_declared(self) -> None:
        for column in (
            "proposal_comment_id",
            "proposal_id",
            "proposal_number",
            "sequence_number",
            "comment_type_code",
            "comments",
            "source_update_timestamp",
            "source_update_user",
        ):
            self.assertIn(column, COMMENT_COLUMNS)


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
