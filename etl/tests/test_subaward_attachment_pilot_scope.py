"""Tests for the Subaward attachment pilot-scope feature
(--subaward-code) added to attachment_orchestrator.py.

All fixtures are synthetic (invented Subaward Codes, subaward_ids, and
file_data_id UUIDs) - none of this reads or references real BU data.
Every Oracle/Postgres interaction is mocked; no network, database, or
AWS access happens anywhere in this file.
"""

from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

import attachment_orchestrator as orch

# Synthetic fixture values only.
CODE_A = "SA-PILOT-001"
CODE_B = "SA-PILOT-002"
OTHER_CODE = "SA-OUTSIDE-999"
FILE_1 = "11111111-1111-1111-1111-111111111111"
FILE_2 = "22222222-2222-2222-2222-222222222222"
SHARED_FILE = "33333333-3333-3333-3333-333333333333"


def _engine_with_connection(connection: MagicMock) -> MagicMock:
    engine = MagicMock()
    engine.connect.return_value.__enter__.return_value = connection
    engine.begin.return_value.__enter__.return_value = connection
    return engine


# ---------------------------------------------------------------------
# _normalize_subaward_codes
# ---------------------------------------------------------------------


class NormalizeSubawardCodesTest(unittest.TestCase):
    def test_none_and_empty_normalize_to_none(self) -> None:
        self.assertIsNone(orch._normalize_subaward_codes(None))
        self.assertIsNone(orch._normalize_subaward_codes([]))
        self.assertIsNone(orch._normalize_subaward_codes(["", "  "]))

    def test_sorts_dedups_and_trims(self) -> None:
        result = orch._normalize_subaward_codes([" SA-2 ", "SA-1", "SA-1", "SA-2"])
        self.assertEqual(result, ["SA-1", "SA-2"])


# ---------------------------------------------------------------------
# Resolving codes -> Subaward version IDs (parameterized query)
# ---------------------------------------------------------------------


class ResolveSubawardIdsForCodesTest(unittest.TestCase):
    def test_query_is_parameterized_and_returns_id_to_code_mapping(self) -> None:
        connection = MagicMock()
        row_a = MagicMock(subaward_id=9001, subaward_code=CODE_A)
        row_b = MagicMock(subaward_id=9002, subaward_code=CODE_A)
        connection.execute.return_value.fetchall.return_value = [row_a, row_b]
        engine = _engine_with_connection(connection)

        result = orch._resolve_subaward_ids_for_codes(engine, [CODE_A])

        statement, params = connection.execute.call_args.args
        self.assertIn("subaward_code = ANY(:codes)", str(statement))
        self.assertEqual(params["codes"], [CODE_A])
        self.assertEqual(result, {9001: CODE_A, 9002: CODE_A})

    def test_never_string_interpolates_requested_codes_into_sql_text(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.fetchall.return_value = []
        engine = _engine_with_connection(connection)

        malicious = "x'; DROP TABLE archive.subaward; --"
        orch._resolve_subaward_ids_for_codes(engine, [malicious])

        statement, params = connection.execute.call_args.args
        self.assertNotIn(malicious, str(statement))
        self.assertEqual(params["codes"], [malicious])


# ---------------------------------------------------------------------
# Batch selection restricted to resolved Subaward version IDs
# ---------------------------------------------------------------------


class ScopedBatchSelectionTest(unittest.TestCase):
    def test_scoped_selection_only_scans_resolved_and_loaded_subaward_ids(self) -> None:
        engine = MagicMock()
        raw = pd.DataFrame({"file_data_id": [FILE_1, FILE_2]})

        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001, 9002, 9999]),
            patch.object(
                orch, "_resolve_subaward_ids_for_codes",
                return_value={9001: CODE_A, 9002: CODE_A},
            ) as resolve,
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "_assert_no_cross_scope_file_sharing") as cross_check,
            patch.object(orch.batch_framework, "create_batch") as create_batch,
        ):
            oracle_source.return_value.read_filtered.return_value = raw
            create_batch.return_value = {"batch_id": 1}

            result = orch._run_create_subaward_attachment_batch(
                engine, 100, run_id="r1", subaward_codes=[CODE_A]
            )

        resolve.assert_called_once_with(engine, [CODE_A])
        # 9999 is loaded but was never resolved from CODE_A - must never
        # be included in the Oracle scan scope.
        read_filtered_kwargs = oracle_source.return_value.read_filtered.call_args.kwargs
        self.assertEqual(set(read_filtered_kwargs["values"]), {9001, 9002})
        self.assertEqual(read_filtered_kwargs["column"], "subaward_id")

        cross_check.assert_called_once_with(engine, [FILE_1, FILE_2], {9001, 9002})

        selection_parameters = create_batch.call_args.kwargs["selection_parameters"]
        self.assertEqual(selection_parameters["subaward_codes"], [CODE_A])
        self.assertEqual(selection_parameters["file_data_ids"], [FILE_1, FILE_2])
        self.assertEqual(result["selected_file_data_ids"], [FILE_1, FILE_2])

    def test_unscoped_call_preserves_exact_prior_behavior(self) -> None:
        # No --subaward-code given: every loaded subaward_id is eligible
        # (unchanged from before this feature existed), and the
        # cross-scope check never runs at all.
        engine = MagicMock()
        raw = pd.DataFrame({"file_data_id": [FILE_1]})

        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001, 9002, 9999]),
            patch.object(orch, "_resolve_subaward_ids_for_codes") as resolve,
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "_assert_no_cross_scope_file_sharing") as cross_check,
            patch.object(orch.batch_framework, "create_batch") as create_batch,
        ):
            oracle_source.return_value.read_filtered.return_value = raw
            create_batch.return_value = {"batch_id": 1}

            orch._run_create_subaward_attachment_batch(engine, 100, run_id="r1")

        resolve.assert_not_called()
        cross_check.assert_not_called()
        read_filtered_kwargs = oracle_source.return_value.read_filtered.call_args.kwargs
        self.assertEqual(set(read_filtered_kwargs["values"]), {9001, 9002, 9999})

        selection_parameters = create_batch.call_args.kwargs["selection_parameters"]
        self.assertIsNone(selection_parameters["subaward_codes"])
        self.assertEqual(
            create_batch.call_args.kwargs["selection_strategy"],
            "ORACLE_SCAN_SUBAWARD_ID_SCOPED_FILE_DATA_ID_EXCL_LOADED",
        )

    def test_scoped_selection_uses_distinct_selection_strategy(self) -> None:
        engine = MagicMock()
        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001]),
            patch.object(orch, "_resolve_subaward_ids_for_codes", return_value={9001: CODE_A}),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "_assert_no_cross_scope_file_sharing"),
            patch.object(orch.batch_framework, "create_batch") as create_batch,
        ):
            oracle_source.return_value.read_filtered.return_value = pd.DataFrame(
                {"file_data_id": [FILE_1]}
            )
            create_batch.return_value = {"batch_id": 1}

            orch._run_create_subaward_attachment_batch(
                engine, 10, run_id="r1", subaward_codes=[CODE_A]
            )

        self.assertEqual(
            create_batch.call_args.kwargs["selection_strategy"],
            "ORACLE_SCAN_SUBAWARD_CODE_SCOPED_FILE_DATA_ID_EXCL_LOADED",
        )


# ---------------------------------------------------------------------
# Fail before any write on cross-scope physical-file sharing
# ---------------------------------------------------------------------


class CrossScopeFileSharingTest(unittest.TestCase):
    def test_assert_raises_when_a_candidate_file_is_referenced_outside_scope(self) -> None:
        engine = MagicMock()
        # SHARED_FILE is referenced by subaward_id 9001 (in scope) AND
        # 8888 (a completely different, out-of-scope Subaward family).
        raw = pd.DataFrame(
            {
                "subaward_id": [9001, 8888],
                "file_data_id": [SHARED_FILE, SHARED_FILE],
            }
        )
        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.return_value = raw
            with self.assertRaises(orch.CrossScopeFileSharingError) as ctx:
                orch._assert_no_cross_scope_file_sharing(engine, [SHARED_FILE], {9001})

        self.assertEqual(ctx.exception.file_data_id, SHARED_FILE)
        self.assertEqual(ctx.exception.offending_subaward_ids, {8888})

    def test_assert_passes_when_every_reference_is_in_scope(self) -> None:
        engine = MagicMock()
        raw = pd.DataFrame(
            {"subaward_id": [9001, 9002], "file_data_id": [SHARED_FILE, SHARED_FILE]}
        )
        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            oracle_source.return_value.read_filtered.return_value = raw
            orch._assert_no_cross_scope_file_sharing(engine, [SHARED_FILE], {9001, 9002})
        # No exception raised is the assertion.

    def test_assert_short_circuits_on_empty_file_list_without_querying_oracle(self) -> None:
        engine = MagicMock()
        with patch("attachment_orchestrator.OracleDataSource") as oracle_source:
            orch._assert_no_cross_scope_file_sharing(engine, [], {9001})
        oracle_source.assert_not_called()

    def test_create_batch_never_called_when_cross_scope_violation_detected(self) -> None:
        """Blocker: the whole point of the fail-before-write check is that
        no write happens at all - not even the batch-bookkeeping write -
        once a violation is found."""
        engine = MagicMock()
        raw = pd.DataFrame({"file_data_id": [SHARED_FILE]})

        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001]),
            patch.object(orch, "_resolve_subaward_ids_for_codes", return_value={9001: CODE_A}),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(
                orch, "_assert_no_cross_scope_file_sharing",
                side_effect=orch.CrossScopeFileSharingError(
                    file_data_id=SHARED_FILE, offending_subaward_ids={8888}
                ),
            ),
            patch.object(orch.batch_framework, "create_batch") as create_batch,
        ):
            oracle_source.return_value.read_filtered.return_value = raw
            with self.assertRaises(orch.CrossScopeFileSharingError):
                orch._run_create_subaward_attachment_batch(
                    engine, 10, run_id="r1", subaward_codes=[CODE_A]
                )

        create_batch.assert_not_called()


# ---------------------------------------------------------------------
# Resume-scope guard: never silently resume a differently-scoped batch
# ---------------------------------------------------------------------


class ResumeScopeGuardTest(unittest.TestCase):
    def test_resumes_when_incomplete_batch_has_the_same_scope(self) -> None:
        engine = MagicMock()
        with (
            patch.object(orch, "_find_incomplete_batch", return_value=42),
            patch.object(orch, "_batch_subaward_codes", return_value=[CODE_A]),
            patch.object(orch, "_batch_file_data_ids", return_value=[FILE_1]),
            patch.object(orch, "_run_create_subaward_attachment_batch") as create_batch,
            patch.object(
                orch, "_run_load_subaward_attachment_batch", return_value={"batch_id": 42}
            ) as load_batch,
        ):
            orch.subaward_metadata_stage(
                engine, batch_size=100, run_id="r1", subaward_codes=[CODE_A]
            )

        create_batch.assert_not_called()
        load_batch.assert_called_once_with(engine, 42, [FILE_1], run_id="r1")

    def test_raises_when_incomplete_batch_has_a_different_scope(self) -> None:
        engine = MagicMock()
        with (
            patch.object(orch, "_find_incomplete_batch", return_value=42),
            patch.object(orch, "_batch_subaward_codes", return_value=[OTHER_CODE]),
            patch.object(orch, "_run_create_subaward_attachment_batch") as create_batch,
            patch.object(orch, "_run_load_subaward_attachment_batch") as load_batch,
        ):
            with self.assertRaises(orch.SubawardCodeScopeMismatch) as ctx:
                orch.subaward_metadata_stage(
                    engine, batch_size=100, run_id="r1", subaward_codes=[CODE_A]
                )

        self.assertEqual(ctx.exception.batch_id, 42)
        create_batch.assert_not_called()
        load_batch.assert_not_called()

    def test_backward_compatible_unscoped_resume_still_works(self) -> None:
        # A batch created before this feature existed has no
        # "subaward_codes" key in selection_parameters at all -
        # _batch_subaward_codes returns None, which must match an
        # unscoped (None) request exactly as before.
        engine = MagicMock()
        with (
            patch.object(orch, "_find_incomplete_batch", return_value=7),
            patch.object(orch, "_batch_subaward_codes", return_value=None),
            patch.object(orch, "_batch_file_data_ids", return_value=[FILE_1]),
            patch.object(orch, "_run_create_subaward_attachment_batch") as create_batch,
            patch.object(
                orch, "_run_load_subaward_attachment_batch", return_value={"batch_id": 7}
            ) as load_batch,
        ):
            orch.subaward_metadata_stage(engine, batch_size=100, run_id="r1")

        create_batch.assert_not_called()
        load_batch.assert_called_once_with(engine, 7, [FILE_1], run_id="r1")

    def test_new_scoped_batch_is_created_when_nothing_is_resuming(self) -> None:
        engine = MagicMock()
        with (
            patch.object(orch, "_find_incomplete_batch", return_value=None),
            patch.object(
                orch, "_run_create_subaward_attachment_batch",
                return_value={"batch_id": 5, "selected_file_data_ids": [FILE_1]},
            ) as create_batch,
            patch.object(
                orch, "_run_load_subaward_attachment_batch", return_value={"batch_id": 5}
            ),
        ):
            orch.subaward_metadata_stage(
                engine, batch_size=100, run_id="r1", subaward_codes=[CODE_A, CODE_B]
            )

        create_batch.assert_called_once_with(
            engine, 100, run_id="r1", subaward_codes=[CODE_A, CODE_B]
        )


# ---------------------------------------------------------------------
# run_orchestration threads subaward_codes only to the subaward module
# ---------------------------------------------------------------------


class RunOrchestrationSubawardCodesTest(unittest.TestCase):
    def test_subaward_codes_passed_to_subaward_metadata_stage_only(self) -> None:
        with (
            patch.object(orch, "acquire_lock", return_value=MagicMock()),
            patch.object(orch, "release_lock"),
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(
                orch, "subaward_metadata_stage", return_value={"selected_count": 0}
            ) as stage,
            patch.object(orch, "_next_ready_batch", return_value=None),
        ):
            orch.run_orchestration(
                modules=(orch.SUBAWARD,), bucket="bucket-x", subaward_codes=[CODE_A]
            )

        stage.assert_called_once()
        self.assertEqual(stage.call_args.kwargs["subaward_codes"], [CODE_A])

    def test_subaward_codes_ignored_by_award_and_proposal(self) -> None:
        with (
            patch.object(orch, "acquire_lock", return_value=MagicMock()),
            patch.object(orch, "release_lock"),
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(
                orch, "award_metadata_stage", return_value={"selected_count": 0}
            ) as award_stage,
            patch.object(
                orch, "proposal_metadata_stage", return_value={"selected_count": 0}
            ) as proposal_stage,
            patch.object(orch, "_next_ready_batch", return_value=None),
        ):
            orch.run_orchestration(
                modules=(orch.AWARD, orch.PROPOSAL), bucket="bucket-x", subaward_codes=[CODE_A]
            )

        self.assertNotIn("subaward_codes", award_stage.call_args.kwargs)
        self.assertNotIn("subaward_codes", proposal_stage.call_args.kwargs)


# ---------------------------------------------------------------------
# CLI: repeatable --subaward-code, ECS-array-style forwarding
# ---------------------------------------------------------------------


class CliSubawardCodeForwardingTest(unittest.TestCase):
    """An ECS containerOverrides `command` is a plain list of exact
    string tokens (no shell involved) - the same shape main()'s own
    argv parsing receives. Every repeated --subaward-code token must
    survive into the resolved list, in the order given, regardless of
    how many are passed."""

    def test_three_repeated_flags_all_survive_in_order(self) -> None:
        with patch.object(
            orch, "run_orchestration", return_value={"modules": {}}
        ) as run_orchestration:
            exit_code = orch.main(
                [
                    "--bucket", "bucket-x",
                    "--modules", "subaward",
                    "--subaward-code", CODE_A,
                    "--subaward-code", CODE_B,
                    "--subaward-code", OTHER_CODE,
                ]
            )

        self.assertEqual(exit_code, 0)
        self.assertEqual(
            run_orchestration.call_args.kwargs["subaward_codes"],
            [CODE_A, CODE_B, OTHER_CODE],
        )

    def test_option_absent_forwards_none_preserving_unscoped_behavior(self) -> None:
        with patch.object(
            orch, "run_orchestration", return_value={"modules": {}}
        ) as run_orchestration:
            orch.main(["--bucket", "bucket-x", "--modules", "subaward"])

        self.assertIsNone(run_orchestration.call_args.kwargs["subaward_codes"])

    def test_single_flag_still_produces_a_list(self) -> None:
        with patch.object(
            orch, "run_orchestration", return_value={"modules": {}}
        ) as run_orchestration:
            orch.main(
                ["--bucket", "bucket-x", "--modules", "subaward", "--subaward-code", CODE_A]
            )

        self.assertEqual(run_orchestration.call_args.kwargs["subaward_codes"], [CODE_A])


# ---------------------------------------------------------------------
# --dry-run planning: counts and destination-key shape, zero writes
# ---------------------------------------------------------------------


class DryRunPlanningTest(unittest.TestCase):
    def test_plan_reports_counts_and_key_shape_without_writing_anything(self) -> None:
        engine = MagicMock()
        # Both calls hit the same real SQL file (SUBAWARD_ATTACHMENT_FILE_IDS_SQL) -
        # the candidate scan (filtered by subaward_id) and the cross-scope
        # check (filtered by file_data_id) - so both always carry the same
        # two columns, matching the real query's shape.
        candidate_scan = pd.DataFrame(
            {"subaward_id": [9001, 9002], "file_data_id": [FILE_1, FILE_2]}
        )
        cross_scope_scan = pd.DataFrame(
            {"subaward_id": [9001, 9002], "file_data_id": [FILE_1, FILE_2]}
        )

        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001, 9002]),
            patch.object(
                orch, "_resolve_subaward_ids_for_codes",
                return_value={9001: CODE_A, 9002: CODE_A},
            ),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch.batch_framework, "create_batch") as create_batch,
            patch("attachment_orchestrator.boto3.client") as boto_client,
        ):
            oracle_source.return_value.read_filtered.side_effect = [
                candidate_scan, cross_scope_scan,
            ]
            plan = orch.plan_subaward_batch(engine, 100, subaward_codes=[CODE_A])

        create_batch.assert_not_called()
        boto_client.assert_not_called()
        self.assertEqual(plan["candidate_file_data_id_count"], 2)
        self.assertEqual(plan["resolved_subaward_version_count"], 2)
        self.assertEqual(plan["in_scope_loaded_subaward_id_count"], 2)
        self.assertEqual(plan["requested_subaward_codes"], [CODE_A])
        self.assertEqual(plan["unresolved_subaward_codes"], [])
        self.assertEqual(
            plan["destination_key_shape"],
            "subawards/by-file-data-id/{file_data_id}/{safe_file_name}",
        )
        self.assertIsNone(plan["cross_scope_violation"])

    def test_plan_reports_unresolved_codes(self) -> None:
        engine = MagicMock()
        scan = pd.DataFrame({"subaward_id": [9001], "file_data_id": [FILE_1]})
        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001]),
            patch.object(
                orch, "_resolve_subaward_ids_for_codes", return_value={9001: CODE_A}
            ),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
        ):
            oracle_source.return_value.read_filtered.side_effect = [scan, scan]
            plan = orch.plan_subaward_batch(engine, 100, subaward_codes=[CODE_A, OTHER_CODE])

        self.assertEqual(plan["unresolved_subaward_codes"], [OTHER_CODE])

    def test_plan_surfaces_a_cross_scope_violation_without_raising(self) -> None:
        # A --dry-run must be able to *report* the same violation a real
        # run would fail on, not crash the whole preview.
        engine = MagicMock()
        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001]),
            patch.object(
                orch, "_resolve_subaward_ids_for_codes", return_value={9001: CODE_A}
            ),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(
                orch, "_assert_no_cross_scope_file_sharing",
                side_effect=orch.CrossScopeFileSharingError(
                    file_data_id=SHARED_FILE, offending_subaward_ids={8888}
                ),
            ),
        ):
            oracle_source.return_value.read_filtered.return_value = pd.DataFrame(
                {"file_data_id": [SHARED_FILE]}
            )
            plan = orch.plan_subaward_batch(engine, 100, subaward_codes=[CODE_A])

        self.assertEqual(
            plan["cross_scope_violation"],
            {"file_data_id": SHARED_FILE, "offending_subaward_ids": [8888]},
        )

    def test_plan_with_no_codes_previews_the_unscoped_batch(self) -> None:
        engine = MagicMock()
        with (
            patch.object(orch, "_subaward_excluded_file_data_ids", return_value=set()),
            patch.object(orch, "_loaded_subaward_ids", return_value=[9001, 9002]),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
        ):
            oracle_source.return_value.read_filtered.return_value = pd.DataFrame(
                {"file_data_id": [FILE_1]}
            )
            plan = orch.plan_subaward_batch(engine, 100)

        self.assertIsNone(plan["requested_subaward_codes"])
        self.assertIsNone(plan["resolved_subaward_version_count"])
        self.assertEqual(plan["in_scope_loaded_subaward_id_count"], 2)

    def test_main_dry_run_never_calls_run_orchestration(self) -> None:
        with (
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(
                orch, "plan_subaward_batch",
                return_value={
                    "dry_run": True, "candidate_file_data_id_count": 0,
                    "cross_scope_violation": None,
                },
            ) as plan_fn,
            patch.object(orch, "run_orchestration") as run_orchestration,
        ):
            exit_code = orch.main(
                ["--bucket", "bucket-x", "--dry-run", "--subaward-code", CODE_A]
            )

        run_orchestration.assert_not_called()
        plan_fn.assert_called_once()
        self.assertEqual(plan_fn.call_args.kwargs["subaward_codes"], [CODE_A])
        self.assertEqual(exit_code, 0)

    def test_main_dry_run_exits_nonzero_on_cross_scope_violation(self) -> None:
        with (
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(
                orch, "plan_subaward_batch",
                return_value={
                    "dry_run": True,
                    "cross_scope_violation": {
                        "file_data_id": SHARED_FILE, "offending_subaward_ids": [8888],
                    },
                },
            ),
            patch.object(orch, "run_orchestration") as run_orchestration,
        ):
            exit_code = orch.main(["--bucket", "bucket-x", "--dry-run"])

        run_orchestration.assert_not_called()
        self.assertNotEqual(exit_code, 0)


if __name__ == "__main__":
    unittest.main()
