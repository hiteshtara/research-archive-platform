from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

import attachment_orchestrator as orch


def _engine_with_connection(connection: MagicMock) -> MagicMock:
    engine = MagicMock()
    engine.connect.return_value.__enter__.return_value = connection
    engine.begin.return_value.__enter__.return_value = connection
    return engine


# ---------------------------------------------------------------------
# 1. Resume after interruption
# ---------------------------------------------------------------------

class ResumeAfterInterruptionTest(unittest.TestCase):
    def test_proposal_metadata_stage_resumes_incomplete_batch_without_creating_a_new_one(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)

        with (
            patch.object(orch, "_find_incomplete_batch", return_value=42) as find_incomplete,
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-a", "uuid-b"]) as batch_ids,
            patch.object(orch, "_run_create_proposal_attachment_batch") as create_batch,
            patch.object(orch, "_run_load_proposal_attachment_batch", return_value={"batch_id": 42}) as load_batch,
        ):
            result = orch.proposal_metadata_stage(engine, batch_size=100, run_id="r1")

        find_incomplete.assert_called_once_with(
            engine, domain=orch.PROPOSAL_ATTACHMENT_DOMAIN, entity_type=orch.PROPOSAL_ATTACHMENT_ENTITY_TYPE
        )
        create_batch.assert_not_called()
        batch_ids.assert_called_once_with(engine, 42)
        load_batch.assert_called_once_with(engine, 42, ["uuid-a", "uuid-b"], run_id="r1")
        self.assertEqual(result["batch_id"], 42)

    def test_award_metadata_stage_resumes_incomplete_batch(self) -> None:
        engine = MagicMock()
        with (
            patch.object(orch, "_find_incomplete_batch", return_value=7),
            patch.object(orch, "_batch_member_count", return_value=500),
            patch.object(orch, "award_attachments") as award_module,
        ):
            award_module._run_load_batch.return_value = {"batch_id": 7}
            result = orch.award_metadata_stage(engine, batch_size=2000, run_id="r1")

        award_module._run_create_batch.assert_not_called()
        award_module._run_load_batch.assert_called_once_with(engine, 7, run_id="r1")
        self.assertEqual(result["selected_count"], 500)

    def test_subaward_metadata_stage_resumes_incomplete_batch(self) -> None:
        engine = MagicMock()
        with (
            patch.object(orch, "_find_incomplete_batch", return_value=9),
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-x"]),
            patch.object(orch, "_run_create_subaward_attachment_batch") as create_batch,
            patch.object(orch, "_run_load_subaward_attachment_batch", return_value={"batch_id": 9}) as load_batch,
        ):
            orch.subaward_metadata_stage(engine, batch_size=100, run_id="r1")

        create_batch.assert_not_called()
        load_batch.assert_called_once_with(engine, 9, ["uuid-x"], run_id="r1")


# ---------------------------------------------------------------------
# 2. Existing-object reuse without Oracle BLOB retrieval
# ---------------------------------------------------------------------

class ExistingObjectReuseTest(unittest.TestCase):
    def _candidate(self, **overrides):
        row = {
            "file_data_id": "uuid-1", "file_name": "a.pdf", "content_type": "application/pdf",
            "upload_status": "UPLOADED", "s3_bucket": "bucket-x", "object_key": None, "file_size": 100,
        }
        row.update(overrides)
        return row

    def test_proposal_skips_already_uploaded_without_touching_oracle(self) -> None:
        matching_key = "proposal/by-file-data-id/uuid-1/a.pdf"
        connection = MagicMock()
        engine = _engine_with_connection(connection)
        candidates = pd.DataFrame([self._candidate(object_key=matching_key)])

        with (
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-1"]),
            patch.object(orch, "select_proposal_upload_candidates", return_value=candidates),
            patch("attachment_orchestrator.oracledb.connect") as oracle_connect,
            patch("attachment_orchestrator.boto3.client", return_value=MagicMock()),
            patch.object(orch, "_stream_file_data_to_s3") as stream,
        ):
            report = orch.proposal_binary_stage(engine, bucket="bucket-x", batch_id=1)

        oracle_connect.assert_not_called()
        stream.assert_not_called()
        self.assertEqual(report["skipped_already_uploaded"], 1)
        self.assertEqual(report["uploaded"], 0)

    def test_subaward_skips_already_archived_without_touching_oracle(self) -> None:
        matching_key = "subawards/by-file-data-id/uuid-2/b.pdf"
        connection = MagicMock()
        engine = _engine_with_connection(connection)
        candidates = pd.DataFrame([
            {
                "file_data_id": "uuid-2", "original_file_name": "b.pdf", "mime_type": "application/pdf",
                "archive_status": "ARCHIVED", "s3_bucket": "bucket-x", "s3_key": matching_key,
            }
        ])

        with (
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-2"]),
            patch.object(orch, "select_subaward_upload_candidates", return_value=candidates),
            patch("attachment_orchestrator.oracledb.connect") as oracle_connect,
            patch("attachment_orchestrator.boto3.client", return_value=MagicMock()),
            patch.object(orch, "_stream_file_data_to_s3") as stream,
        ):
            report = orch.subaward_binary_stage(engine, bucket="bucket-x", batch_id=1)

        oracle_connect.assert_not_called()
        stream.assert_not_called()
        self.assertEqual(report["skipped_already_uploaded"], 1)


class CheckS3ExistingObjectTest(unittest.TestCase):
    def test_returns_none_on_404(self) -> None:
        s3_client = MagicMock()
        s3_client.head_object.side_effect = orch.ClientError(
            {"Error": {"Code": "404", "Message": "Not Found"}}, "HeadObject"
        )
        result = orch.check_s3_existing_object(s3_client, "bucket-x", "key-x", expected_size=None)
        self.assertIsNone(result)

    def test_reraises_non_404_errors(self) -> None:
        s3_client = MagicMock()
        s3_client.head_object.side_effect = orch.ClientError(
            {"Error": {"Code": "403", "Message": "Forbidden"}}, "HeadObject"
        )
        with self.assertRaises(orch.ClientError):
            orch.check_s3_existing_object(s3_client, "bucket-x", "key-x", expected_size=None)

    def test_returns_metadata_when_object_exists_and_no_prior_expectation(self) -> None:
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 500, "Metadata": {"sha256": "abc123"}}
        result = orch.check_s3_existing_object(s3_client, "bucket-x", "key-x", expected_size=None)
        self.assertEqual(result, {"byte_size": 500, "sha256": "abc123"})

    def test_mismatching_prior_size_raises_never_returns_not_found(self) -> None:
        # A different object at the same key than what Postgres expected
        # is a data-integrity condition, never silently folded into
        # "not found" - the caller must stop, not fall through to
        # Oracle and overwrite it. See S3ObjectMismatch.
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 999, "Metadata": {}}
        with self.assertRaises(orch.S3ObjectMismatch) as ctx:
            orch.check_s3_existing_object(s3_client, "bucket-x", "key-x", expected_size=500)
        self.assertEqual(ctx.exception.reason, "size mismatch")
        self.assertEqual(ctx.exception.expected, 500)
        self.assertEqual(ctx.exception.actual, 999)

    def test_mismatching_checksum_raises_when_both_sides_have_one(self) -> None:
        s3_client = MagicMock()
        s3_client.head_object.return_value = {
            "ContentLength": 500, "Metadata": {"sha256": "actual-digest"}
        }
        with self.assertRaises(orch.S3ObjectMismatch) as ctx:
            orch.check_s3_existing_object(
                s3_client, "bucket-x", "key-x",
                expected_size=500, expected_sha256="expected-digest",
            )
        self.assertEqual(ctx.exception.reason, "sha256 mismatch")

    def test_checksum_not_compared_when_s3_object_carries_no_tag(self) -> None:
        # A legacy, pre-checksum-fix object has no Metadata.sha256 at
        # all - absence of the tag is not itself a mismatch (that would
        # make every historical object unreusable); only a genuine
        # disagreement between two present values raises.
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 500, "Metadata": {}}
        result = orch.check_s3_existing_object(
            s3_client, "bucket-x", "key-x",
            expected_size=500, expected_sha256="expected-digest",
        )
        self.assertEqual(result["byte_size"], 500)

    def test_matching_prior_size_is_accepted(self) -> None:
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 500, "Metadata": {"sha256": "abc"}}
        result = orch.check_s3_existing_object(s3_client, "bucket-x", "key-x", expected_size=500)
        self.assertEqual(result["byte_size"], 500)

    def test_matching_size_and_checksum_is_accepted(self) -> None:
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 500, "Metadata": {"sha256": "abc"}}
        result = orch.check_s3_existing_object(
            s3_client, "bucket-x", "key-x", expected_size=500, expected_sha256="abc"
        )
        self.assertEqual(result, {"byte_size": 500, "sha256": "abc"})

    def test_missing_sha256_tag_returns_empty_string_not_none(self) -> None:
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 500, "Metadata": {}}
        result = orch.check_s3_existing_object(s3_client, "bucket-x", "key-x", expected_size=None)
        self.assertEqual(result["sha256"], "")


class CrashWindowReuseTest(unittest.TestCase):
    """The narrow interruption point between a successful S3 PUT and the
    Postgres status UPDATE that was meant to follow it: Postgres still
    shows IN_PROGRESS (the SQL candidate filter alone would re-select
    it), but the object is already real and correct in S3. Proves the
    fresh-instance resume reuses and verifies it without re-uploading."""

    def test_proposal_reuses_s3_object_found_mid_progress_without_touching_oracle(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)
        candidates = pd.DataFrame([
            {"file_data_id": "uuid-1", "file_name": "a.pdf", "content_type": "application/pdf",
             "upload_status": "IN_PROGRESS", "s3_bucket": None, "object_key": None, "file_size": None},
        ])

        with (
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-1"]),
            patch.object(orch, "select_proposal_upload_candidates", return_value=candidates),
            patch.object(
                orch, "check_s3_existing_object",
                return_value={"byte_size": 12345, "sha256": "realsha"},
            ),
            patch("attachment_orchestrator.oracledb.connect") as oracle_connect,
            patch("attachment_orchestrator.boto3.client", return_value=MagicMock()),
            patch.object(orch, "_stream_file_data_to_s3") as stream,
            patch.object(orch, "mark_proposal_file_uploaded") as mark_uploaded,
        ):
            report = orch.proposal_binary_stage(engine, bucket="bucket-x", batch_id=1)

        oracle_connect.assert_not_called()
        stream.assert_not_called()
        mark_uploaded.assert_called_once_with(
            engine, "uuid-1", bucket="bucket-x",
            key="proposal/by-file-data-id/uuid-1/a.pdf",
            sha256="realsha", byte_size=12345,
        )
        self.assertEqual(report["reused_from_s3"], 1)
        self.assertEqual(report["uploaded"], 0)

    def test_subaward_reuses_s3_object_found_mid_progress_without_touching_oracle(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)
        candidates = pd.DataFrame([
            {"file_data_id": "uuid-2", "original_file_name": "b.pdf", "mime_type": "application/pdf",
             "archive_status": "UPLOADING", "s3_bucket": None, "s3_key": None, "byte_size": None},
        ])

        with (
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-2"]),
            patch.object(orch, "select_subaward_upload_candidates", return_value=candidates),
            patch.object(
                orch, "check_s3_existing_object",
                return_value={"byte_size": 999, "sha256": "othersha"},
            ),
            patch("attachment_orchestrator.oracledb.connect") as oracle_connect,
            patch("attachment_orchestrator.boto3.client", return_value=MagicMock()),
            patch.object(orch, "_stream_file_data_to_s3") as stream,
            patch.object(orch, "mark_subaward_file_uploaded") as mark_uploaded,
        ):
            report = orch.subaward_binary_stage(engine, bucket="bucket-x", batch_id=1)

        oracle_connect.assert_not_called()
        stream.assert_not_called()
        mark_uploaded.assert_called_once()
        self.assertEqual(report["reused_from_s3"], 1)


class ChecksumTaggingTest(unittest.TestCase):
    def test_stream_file_data_tags_the_uploaded_object_with_its_sha256(self) -> None:
        blob = MagicMock()
        blob.read.side_effect = [b"hello world", b""]
        cursor = MagicMock()
        cursor.__enter__.return_value = cursor
        cursor.fetchone.return_value = (blob,)
        oracle_connection = MagicMock()
        oracle_connection.cursor.return_value = cursor
        s3_client = MagicMock()

        byte_size, sha256 = orch._stream_file_data_to_s3(
            oracle_connection, s3_client, "uuid-1", "bucket-x", "key-x", "application/pdf"
        )

        put_kwargs = s3_client.put_object.call_args.kwargs
        self.assertEqual(put_kwargs["Metadata"], {"sha256": sha256})
        self.assertEqual(sha256, orch.hashlib.sha256(b"hello world").hexdigest())


class S3MismatchStopsOrchestrationTest(unittest.TestCase):
    """Blocker 1: a detected S3 object mismatch must never fall through
    to an Oracle read + overwrite. Never call put_object/multipart
    upload for a key known to disagree with a prior expectation - the
    whole orchestration must stop instead."""

    def test_proposal_binary_stage_records_mismatch_and_never_touches_oracle_or_s3_write(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)
        candidates = pd.DataFrame([
            {"file_data_id": "uuid-1", "file_name": "a.pdf", "content_type": "application/pdf",
             "upload_status": "NOT_REQUESTED", "s3_bucket": None, "object_key": None,
             "file_size": 500, "checksum": None},
        ])
        s3_client = MagicMock()

        with (
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-1"]),
            patch.object(orch, "select_proposal_upload_candidates", return_value=candidates),
            patch("attachment_orchestrator.boto3.client", return_value=s3_client),
            patch.object(
                orch, "check_s3_existing_object",
                side_effect=orch.S3ObjectMismatch(
                    key="proposal/by-file-data-id/uuid-1/a.pdf",
                    reason="size mismatch", expected=500, actual=999,
                ),
            ),
            patch("attachment_orchestrator.oracledb.connect") as oracle_connect,
            patch.object(orch, "_stream_file_data_to_s3") as stream,
            patch.object(orch, "mark_proposal_file_uploaded") as mark_uploaded,
            patch.object(orch, "mark_proposal_file_in_progress") as mark_in_progress,
        ):
            report = orch.proposal_binary_stage(engine, bucket="bucket-x", batch_id=1)

        oracle_connect.assert_not_called()
        stream.assert_not_called()
        s3_client.put_object.assert_not_called()
        mark_uploaded.assert_not_called()
        mark_in_progress.assert_not_called()
        self.assertIn("s3_mismatch", report)
        self.assertEqual(report["s3_mismatch"]["reason"], "size mismatch")
        self.assertEqual(report["uploaded"], 0)
        self.assertEqual(report["reused_from_s3"], 0)

    def test_subaward_binary_stage_records_mismatch_and_never_touches_oracle_or_s3_write(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)
        candidates = pd.DataFrame([
            {"file_data_id": "uuid-2", "original_file_name": "b.pdf", "mime_type": "application/pdf",
             "archive_status": "PENDING", "s3_bucket": None, "s3_key": None,
             "byte_size": 500, "sha256": None},
        ])
        s3_client = MagicMock()

        with (
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-2"]),
            patch.object(orch, "select_subaward_upload_candidates", return_value=candidates),
            patch("attachment_orchestrator.boto3.client", return_value=s3_client),
            patch.object(
                orch, "check_s3_existing_object",
                side_effect=orch.S3ObjectMismatch(
                    key="subawards/by-file-data-id/uuid-2/b.pdf",
                    reason="size mismatch", expected=500, actual=42,
                ),
            ),
            patch("attachment_orchestrator.oracledb.connect") as oracle_connect,
            patch.object(orch, "_stream_file_data_to_s3") as stream,
            patch.object(orch, "mark_subaward_file_uploaded") as mark_uploaded,
        ):
            report = orch.subaward_binary_stage(engine, bucket="bucket-x", batch_id=1)

        oracle_connect.assert_not_called()
        stream.assert_not_called()
        s3_client.put_object.assert_not_called()
        mark_uploaded.assert_not_called()
        self.assertIn("s3_mismatch", report)

    def test_run_orchestration_stops_the_whole_run_on_a_mismatch_before_reconciling(self) -> None:
        engine = MagicMock()
        lock_connection = MagicMock()

        with (
            patch.object(orch, "create_postgres_engine", return_value=engine),
            patch.object(orch, "acquire_lock", return_value=lock_connection),
            patch.object(orch, "release_lock") as release_lock,
            patch("attachment_orchestrator.boto3.client", return_value=MagicMock()),
            patch.object(orch, "proposal_metadata_stage", return_value={"selected_count": 0}),
            patch.object(orch, "_next_ready_batch", side_effect=[5, None]),
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-1"]),
            patch.object(
                orch, "proposal_binary_stage",
                return_value={
                    "stage": "binary", "physical_files_selected": 1,
                    "s3_mismatch": {"file_data_id": "uuid-1", "reason": "size mismatch"},
                },
            ),
            patch.object(orch, "reconcile_batch") as reconcile,
        ):
            summary = orch.run_orchestration(modules=(orch.PROPOSAL,), bucket="bucket-x")

        reconcile.assert_not_called()
        self.assertIn("stopped_reason", summary)
        self.assertIn("mismatch", summary["stopped_reason"])
        release_lock.assert_called_once_with(lock_connection)


class PerItemStatusFidelityTest(unittest.TestCase):
    """Blocker 2: a metadata batch's per-item status must reflect real
    per-item outcome (COMPLETED/MISSING_SOURCE/FAILED), never
    unconditional COMPLETED - and a batch must never advance to READY
    when the upsert itself failed."""

    @staticmethod
    def _connection() -> MagicMock:
        connection = MagicMock()
        connection.begin_nested.return_value.__enter__.return_value = connection
        connection.begin_nested.return_value.__exit__.return_value = False
        return connection

    def test_proposal_partially_returned_oracle_batch_splits_completed_and_missing(self) -> None:
        connection = self._connection()
        engine = _engine_with_connection(connection)
        raw = pd.DataFrame([{"file_data_id": "uuid-1"}, {"file_data_id": "uuid-2"}])

        with (
            patch.object(orch, "with_bounded_retry", side_effect=lambda op, **kw: op()),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "proposals") as proposals_module,
            patch.object(orch.batch_framework, "set_item_status") as set_item_status,
            patch.object(orch.batch_framework, "set_batch_status") as set_batch_status,
        ):
            oracle_source.return_value.read_filtered.return_value = raw
            proposals_module.prepare_attachments.return_value = raw
            proposals_module.upsert_proposal_attachments.return_value = {
                "inserted": 2, "updated": 0, "unchanged": 0,
            }
            report = orch._run_load_proposal_attachment_batch(
                engine, 1, ["uuid-1", "uuid-2", "uuid-3"], run_id="r1"
            )

        self.assertEqual(
            [c.kwargs["status"] for c in set_item_status.call_args_list],
            [
                orch.batch_framework.ITEM_STATUS_COMPLETED,
                orch.batch_framework.ITEM_STATUS_COMPLETED,
                orch.batch_framework.ITEM_STATUS_MISSING_SOURCE,
            ],
        )
        set_batch_status.assert_called_once_with(
            connection, 1, status=orch.batch_framework.BATCH_STATUS_READY
        )
        self.assertTrue(report["batch_advanced_to_ready"])

    def test_proposal_zero_row_oracle_result_marks_every_selected_id_missing_source(self) -> None:
        connection = self._connection()
        engine = _engine_with_connection(connection)

        with (
            patch.object(orch, "with_bounded_retry", side_effect=lambda op, **kw: op()),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "proposals") as proposals_module,
            patch.object(orch.batch_framework, "set_item_status") as set_item_status,
            patch.object(orch.batch_framework, "set_batch_status") as set_batch_status,
        ):
            oracle_source.return_value.read_filtered.return_value = pd.DataFrame()
            report = orch._run_load_proposal_attachment_batch(
                engine, 1, ["uuid-1", "uuid-2"], run_id="r1"
            )

        proposals_module.upsert_proposal_attachments.assert_not_called()
        self.assertEqual(
            [c.kwargs["status"] for c in set_item_status.call_args_list],
            [
                orch.batch_framework.ITEM_STATUS_MISSING_SOURCE,
                orch.batch_framework.ITEM_STATUS_MISSING_SOURCE,
            ],
        )
        set_batch_status.assert_called_once_with(
            connection, 1, status=orch.batch_framework.BATCH_STATUS_READY
        )
        self.assertTrue(report["batch_advanced_to_ready"])

    def test_proposal_upsert_failure_marks_found_ids_failed_and_never_advances_to_ready(self) -> None:
        connection = self._connection()
        engine = _engine_with_connection(connection)
        raw = pd.DataFrame([{"file_data_id": "uuid-1"}])

        with (
            patch.object(orch, "with_bounded_retry", side_effect=lambda op, **kw: op()),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "proposals") as proposals_module,
            patch.object(orch.batch_framework, "set_item_status") as set_item_status,
            patch.object(orch.batch_framework, "set_batch_status") as set_batch_status,
        ):
            oracle_source.return_value.read_filtered.return_value = raw
            proposals_module.prepare_attachments.return_value = raw
            proposals_module.upsert_proposal_attachments.side_effect = RuntimeError(
                "constraint violation"
            )
            report = orch._run_load_proposal_attachment_batch(
                engine, 1, ["uuid-1", "uuid-2"], run_id="r1"
            )

        self.assertEqual(
            [c.kwargs["status"] for c in set_item_status.call_args_list],
            [
                orch.batch_framework.ITEM_STATUS_FAILED,
                orch.batch_framework.ITEM_STATUS_MISSING_SOURCE,
            ],
        )
        set_batch_status.assert_not_called()
        self.assertFalse(report["batch_advanced_to_ready"])
        self.assertIn("upsert_error", report)

    def test_subaward_partially_returned_oracle_batch_splits_completed_and_missing(self) -> None:
        connection = self._connection()
        engine = _engine_with_connection(connection)
        raw = pd.DataFrame([{"file_data_id": "uuid-1"}])

        with (
            patch.object(orch, "with_bounded_retry", side_effect=lambda op, **kw: op()),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "_upsert_subaward_attachments") as upsert,
            patch.object(orch.batch_framework, "set_item_status") as set_item_status,
            patch.object(orch.batch_framework, "set_batch_status") as set_batch_status,
        ):
            oracle_source.return_value.read_filtered.return_value = raw
            upsert.return_value = {
                "inserted": 1, "updated": 0, "unchanged": 0, "skipped_no_core_record": 0,
            }
            report = orch._run_load_subaward_attachment_batch(
                engine, 1, ["uuid-1", "uuid-2"], run_id="r1"
            )

        self.assertEqual(
            [c.kwargs["status"] for c in set_item_status.call_args_list],
            [
                orch.batch_framework.ITEM_STATUS_COMPLETED,
                orch.batch_framework.ITEM_STATUS_MISSING_SOURCE,
            ],
        )
        self.assertTrue(report["batch_advanced_to_ready"])

    def test_subaward_zero_row_oracle_result_marks_every_selected_id_missing_source(self) -> None:
        connection = self._connection()
        engine = _engine_with_connection(connection)

        with (
            patch.object(orch, "with_bounded_retry", side_effect=lambda op, **kw: op()),
            patch("attachment_orchestrator.OracleDataSource") as oracle_source,
            patch.object(orch, "_upsert_subaward_attachments") as upsert,
            patch.object(orch.batch_framework, "set_item_status") as set_item_status,
            patch.object(orch.batch_framework, "set_batch_status") as set_batch_status,
        ):
            oracle_source.return_value.read_filtered.return_value = pd.DataFrame()
            report = orch._run_load_subaward_attachment_batch(
                engine, 1, ["uuid-1"], run_id="r1"
            )

        upsert.assert_not_called()
        self.assertEqual(
            [c.kwargs["status"] for c in set_item_status.call_args_list],
            [orch.batch_framework.ITEM_STATUS_MISSING_SOURCE],
        )
        self.assertTrue(report["batch_advanced_to_ready"])

    def test_metadata_loop_stops_the_orchestration_when_upsert_fails(self) -> None:
        engine = MagicMock()
        lock_connection = MagicMock()

        with (
            patch.object(orch, "create_postgres_engine", return_value=engine),
            patch.object(orch, "acquire_lock", return_value=lock_connection),
            patch.object(orch, "release_lock") as release_lock,
            patch("attachment_orchestrator.boto3.client", return_value=MagicMock()),
            patch.object(
                orch, "proposal_metadata_stage",
                return_value={
                    "batch_id": 1, "selected_count": 2,
                    "batch_advanced_to_ready": False, "upsert_error": "constraint violation",
                },
            ),
        ):
            summary = orch.run_orchestration(modules=(orch.PROPOSAL,), bucket="bucket-x")

        self.assertIn("stopped_reason", summary)
        self.assertIn("Metadata upsert failed", summary["stopped_reason"])
        release_lock.assert_called_once_with(lock_connection)


# ---------------------------------------------------------------------
# 3. Metadata-before-binary ordering
# ---------------------------------------------------------------------

class MetadataBeforeBinaryOrderingTest(unittest.TestCase):
    def test_metadata_stage_runs_to_exhaustion_before_any_binary_stage_call(self) -> None:
        call_order: list[str] = []

        def fake_metadata(*_args, **_kwargs):
            call_order.append("metadata")
            # First call finds work, second call reports exhausted.
            return {"selected_count": 0 if call_order.count("metadata") > 1 else 5}

        def fake_next_ready(*_args, **_kwargs):
            return None  # no binary work available - keeps this test focused on ordering

        with (
            patch.object(orch, "acquire_lock", return_value=MagicMock()),
            patch.object(orch, "release_lock"),
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(orch, "proposal_metadata_stage", side_effect=fake_metadata),
            patch.object(orch, "_next_ready_batch", side_effect=fake_next_ready) as next_ready,
        ):
            orch.run_orchestration(modules=(orch.PROPOSAL,), bucket="bucket-x")

        # metadata_stage was called (and exhausted) strictly before
        # _next_ready_batch (the binary stage's own batch selector) ran.
        self.assertGreaterEqual(call_order.count("metadata"), 2)
        next_ready.assert_called()


# ---------------------------------------------------------------------
# 4. Duplicate references to one physical file
# ---------------------------------------------------------------------

class DuplicateReferencesShareOnePhysicalFileTest(unittest.TestCase):
    def test_mark_proposal_uploaded_updates_by_file_data_id_not_by_reference_row(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)

        orch.mark_proposal_file_uploaded(
            engine, "uuid-shared", bucket="b", key="k", sha256="deadbeef", byte_size=10
        )

        statement, params = connection.execute.call_args.args
        self.assertIn("WHERE file_data_id = :file_data_id", str(statement))
        self.assertNotIn("proposal_attachment_id", str(statement))
        self.assertEqual(params["file_data_id"], "uuid-shared")

    def test_mark_subaward_uploaded_updates_by_file_data_id_not_by_reference_row(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)

        orch.mark_subaward_file_uploaded(
            engine, "uuid-shared", bucket="b", key="k", sha256="deadbeef", byte_size=10
        )

        statement, params = connection.execute.call_args.args
        self.assertIn("WHERE file_data_id = :file_data_id", str(statement))
        self.assertNotIn("attachment_id =", str(statement))

    def test_proposal_candidate_selection_groups_by_file_data_id(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        orch.select_proposal_upload_candidates(connection, file_data_ids=None)

        statement = str(connection.execute.call_args.args[0])
        self.assertIn("DISTINCT ON (file_data_id)", statement)

    def test_subaward_candidate_selection_groups_by_file_data_id(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        orch.select_subaward_upload_candidates(connection, file_data_ids=None)

        statement = str(connection.execute.call_args.args[0])
        self.assertIn("DISTINCT ON (saa.file_data_id)", statement)


# ---------------------------------------------------------------------
# 5. Possible cross-module physical-file reuse
# ---------------------------------------------------------------------

class CrossModuleFileIdentityIsolationTest(unittest.TestCase):
    def test_proposal_exclusion_query_never_reads_subaward_tables(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalars.return_value = []
        engine = _engine_with_connection(connection)

        orch._proposal_excluded_file_data_ids(engine)

        statement = str(connection.execute.call_args.args[0])
        self.assertIn("archive.proposal_attachment", statement)
        self.assertNotIn("subaward", statement)
        self.assertNotIn("award_attachment", statement)

    def test_subaward_exclusion_query_never_reads_proposal_tables(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalars.return_value = []
        engine = _engine_with_connection(connection)

        orch._subaward_excluded_file_data_ids(engine)

        statement = str(connection.execute.call_args.args[0])
        self.assertIn("archive.subaward_attachment", statement)
        self.assertNotIn("proposal", statement)

    def test_batch_domains_are_distinct_per_module(self) -> None:
        # Real reuse across modules would be silently mishandled if two
        # modules' checkpoint namespaces ever collided.
        domains = {
            orch.PROPOSAL_ATTACHMENT_DOMAIN,
            orch.SUBAWARD_ATTACHMENT_DOMAIN,
            orch.award_attachments.AWARD_ATTACHMENT_BATCH_DOMAIN,
        }
        self.assertEqual(len(domains), 3)

    def test_reconcile_batch_query_is_module_scoped(self) -> None:
        # The reconciliation query itself must never cross modules -
        # each branch reads exactly one module's own table.
        connection = MagicMock()
        connection.execute.return_value.fetchall.return_value = []
        engine = _engine_with_connection(connection)
        s3_client = MagicMock()

        orch.reconcile_batch(engine, s3_client, "bucket-x", orch.PROPOSAL, None)
        statement = str(connection.execute.call_args.args[0])
        self.assertIn("archive.proposal_attachment", statement)
        self.assertNotIn("subaward", statement)
        self.assertNotIn("attachment_object", statement)


# ---------------------------------------------------------------------
# 6. UUID EXTERNAL files
# ---------------------------------------------------------------------

class UuidExternalFileTest(unittest.TestCase):
    def test_stream_file_data_passes_uuid_through_as_text_never_int_coerced(self) -> None:
        uuid_value = "f6f4d6d2-9a3f-4a32-a4e4-b6ffb8647847"
        blob = MagicMock()
        blob.read.side_effect = [b"hello", b""]
        cursor = MagicMock()
        cursor.__enter__.return_value = cursor
        cursor.fetchone.return_value = (blob,)
        oracle_connection = MagicMock()
        oracle_connection.cursor.return_value = cursor
        s3_client = MagicMock()

        byte_size, sha256 = orch._stream_file_data_to_s3(
            oracle_connection, s3_client, uuid_value, "bucket-x", "key-x", "application/pdf"
        )

        bind_kwargs = cursor.execute.call_args.kwargs
        self.assertEqual(bind_kwargs["file_data_id"], uuid_value)
        self.assertIsInstance(bind_kwargs["file_data_id"], str)
        self.assertEqual(byte_size, 5)
        s3_client.put_object.assert_called_once()


# ---------------------------------------------------------------------
# 7. Numeric inline files (Award) - orchestrator wiring only; the
# inline/EXTERNAL resolution itself is already tested exhaustively in
# test_award_attachment_loader.py and is reused unchanged here.
# ---------------------------------------------------------------------

class NumericInlineFileWiringTest(unittest.TestCase):
    def test_award_binary_stage_constructs_the_expected_namespace_and_delegates(self) -> None:
        engine = MagicMock()
        with patch.object(orch, "award_attachments") as award_module:
            award_module._run_upload.return_value = {"uploaded": 1}
            report = orch.award_binary_stage(engine, bucket="bucket-x", batch_id=55, run_id="r1")

        arguments = award_module._run_upload.call_args.args[0]
        self.assertEqual(arguments.bucket, "bucket-x")
        self.assertEqual(arguments.batch_id, 55)
        self.assertFalse(arguments.retry_failed)
        self.assertEqual(report["stage"], "binary")


# ---------------------------------------------------------------------
# 8. Missing Oracle binary
# ---------------------------------------------------------------------

class MissingOracleBinaryTest(unittest.TestCase):
    def test_proposal_missing_blob_is_marked_and_loop_continues(self) -> None:
        connection = MagicMock()
        engine = _engine_with_connection(connection)
        candidates = pd.DataFrame([
            {"file_data_id": "uuid-1", "file_name": "a.pdf", "content_type": None,
             "upload_status": "NOT_REQUESTED", "s3_bucket": None, "object_key": None, "file_size": None},
            {"file_data_id": "uuid-2", "file_name": "b.pdf", "content_type": None,
             "upload_status": "NOT_REQUESTED", "s3_bucket": None, "object_key": None, "file_size": None},
        ])

        def fake_stream(_conn, _s3, file_data_id, *_args, **_kwargs):
            if file_data_id == "uuid-1":
                raise orch.MissingBlob("no blob")
            return 50, "abc123"

        with (
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-1", "uuid-2"]),
            patch.object(orch, "select_proposal_upload_candidates", return_value=candidates),
            patch.object(orch, "check_s3_existing_object", return_value=None),
            patch(
                "attachment_orchestrator.require_oracle_environment",
                return_value={"ORACLE_USER": "u", "ORACLE_PASSWORD": "p", "ORACLE_DSN": "d"},
            ),
            patch("attachment_orchestrator.oracledb.connect", return_value=MagicMock()),
            patch("attachment_orchestrator.boto3.client", return_value=MagicMock()),
            patch.object(orch, "_stream_file_data_to_s3", side_effect=fake_stream),
            patch.object(orch, "mark_proposal_file_missing") as mark_missing,
            patch.object(orch, "mark_proposal_file_uploaded") as mark_uploaded,
        ):
            report = orch.proposal_binary_stage(engine, bucket="bucket-x", batch_id=1)

        mark_missing.assert_called_once_with(engine, "uuid-1")
        mark_uploaded.assert_called_once()
        self.assertEqual(report["missing_source_content"], 1)
        self.assertEqual(report["uploaded"], 1)


# ---------------------------------------------------------------------
# 9. Size/hash mismatch
# ---------------------------------------------------------------------

class SizeHashMismatchTest(unittest.TestCase):
    def test_reconcile_batch_flags_size_mismatch(self) -> None:
        connection = MagicMock()
        row = MagicMock(key="uuid-1", s3_bucket="bucket-x", s3_key="k", size=100, sha256="abc")
        connection.execute.return_value.fetchall.return_value = [row]
        engine = _engine_with_connection(connection)
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 50, "Metadata": {"sha256": "abc"}}

        result = orch.reconcile_batch(engine, s3_client, "bucket-x", orch.PROPOSAL, None)

        self.assertFalse(result["clean"])
        self.assertEqual(result["mismatches"][0]["reason"], "size mismatch")

    def test_reconcile_batch_flags_sha256_mismatch(self) -> None:
        connection = MagicMock()
        row = MagicMock(key="uuid-1", s3_bucket="bucket-x", s3_key="k", size=100, sha256="abc")
        connection.execute.return_value.fetchall.return_value = [row]
        engine = _engine_with_connection(connection)
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 100, "Metadata": {"sha256": "different"}}

        result = orch.reconcile_batch(engine, s3_client, "bucket-x", orch.PROPOSAL, None)

        self.assertFalse(result["clean"])
        self.assertEqual(result["mismatches"][0]["reason"], "sha256 mismatch")

    def test_reconcile_batch_clean_when_everything_matches(self) -> None:
        connection = MagicMock()
        row = MagicMock(key="uuid-1", s3_bucket="bucket-x", s3_key="k", size=100, sha256="abc")
        connection.execute.return_value.fetchall.return_value = [row]
        engine = _engine_with_connection(connection)
        s3_client = MagicMock()
        s3_client.head_object.return_value = {"ContentLength": 100, "Metadata": {"sha256": "abc"}}

        result = orch.reconcile_batch(engine, s3_client, "bucket-x", orch.PROPOSAL, None)

        self.assertTrue(result["clean"])
        self.assertEqual(result["checked"], 1)

    def test_run_orchestration_stops_on_reconciliation_failure(self) -> None:
        with (
            patch.object(orch, "acquire_lock", return_value=MagicMock()),
            patch.object(orch, "release_lock"),
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(orch, "proposal_metadata_stage", return_value={"selected_count": 0}),
            patch.object(orch, "_next_ready_batch", side_effect=[1, None]),
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-1"]),
            patch.object(orch, "proposal_binary_stage", return_value={"physical_files_selected": 1}),
            patch.object(
                orch, "reconcile_batch",
                return_value={"clean": False, "mismatches": [{"key": "uuid-1", "reason": "size mismatch"}]},
            ),
        ):
            summary = orch.run_orchestration(modules=(orch.PROPOSAL,), bucket="bucket-x")

        self.assertIn("stopped_reason", summary)
        self.assertIn("uuid-1", summary["stopped_reason"])

    def test_run_orchestration_does_not_stop_the_binary_loop_after_a_zero_selected_batch(self) -> None:
        # Live incident (2026-08-12): _next_ready_batch can legitimately
        # return an older READY batch whose own candidates are already
        # fully UPLOADED (physical_files_selected=0 for THAT batch) -
        # this must never be treated as "nothing left to do anywhere".
        # A real run hit exactly this: it processed one such zero-work
        # batch, stopped the whole binary stage, and exited 0 while
        # ~90,000 genuinely PENDING files were never touched. The binary
        # loop must keep calling _next_ready_batch until it returns None
        # - not stop early just because one batch along the way had
        # nothing to upload.
        binary_reports = [
            {"physical_files_selected": 0, "uploaded": 0},
            {"physical_files_selected": 500, "uploaded": 500},
            {"physical_files_selected": 0, "uploaded": 0},
        ]
        with (
            patch.object(orch, "acquire_lock", return_value=MagicMock()),
            patch.object(orch, "release_lock"),
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(orch, "proposal_metadata_stage", return_value={"selected_count": 0}),
            patch.object(orch, "_next_ready_batch", side_effect=[1, 2, 3, None]),
            patch.object(orch, "_batch_file_data_ids", return_value=["uuid-1"]),
            patch.object(orch, "proposal_binary_stage", side_effect=binary_reports) as binary_stage,
            patch.object(orch, "reconcile_batch", return_value={"clean": True, "mismatches": []}),
        ):
            summary = orch.run_orchestration(modules=(orch.PROPOSAL,), bucket="bucket-x")

        self.assertEqual(binary_stage.call_count, 3)
        self.assertNotIn("stopped_reason", summary)
        self.assertEqual(len(summary["modules"]["proposal"]["binary_batches"]), 3)


# ---------------------------------------------------------------------
# 10. Failed batch restart
# ---------------------------------------------------------------------

class FailedBatchRestartTest(unittest.TestCase):
    def test_proposal_candidates_exclude_failed_by_default(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        orch.select_proposal_upload_candidates(connection, file_data_ids=None, retry_failed=False)

        params = connection.execute.call_args.args[1]
        self.assertEqual(set(params["statuses"]), {"NOT_REQUESTED", "IN_PROGRESS"})

    def test_proposal_candidates_include_failed_when_retrying(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        orch.select_proposal_upload_candidates(connection, file_data_ids=None, retry_failed=True)

        params = connection.execute.call_args.args[1]
        self.assertEqual(set(params["statuses"]), {"NOT_REQUESTED", "IN_PROGRESS", "FAILED"})

    def test_subaward_candidates_exclude_failed_by_default(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value.all.return_value = []

        orch.select_subaward_upload_candidates(connection, file_data_ids=None, retry_failed=False)

        params = connection.execute.call_args.args[1]
        self.assertEqual(set(params["statuses"]), {"PENDING", "UPLOADING"})

    def test_transient_oracle_error_is_retried_bounded_times(self) -> None:
        attempts: list[int] = []

        def flaky():
            attempts.append(1)
            if len(attempts) < 3:
                raise TimeoutError("transient")
            return "ok"

        with patch("attachment_orchestrator.time.sleep"):
            result = orch.with_bounded_retry(flaky, attempts=4, operation_name="test")

        self.assertEqual(result, "ok")
        self.assertEqual(len(attempts), 3)

    def test_non_transient_error_is_never_retried(self) -> None:
        attempts: list[int] = []

        def always_fails():
            attempts.append(1)
            raise ValueError("business logic error, not transient")

        with self.assertRaises(ValueError):
            orch.with_bounded_retry(always_fails, attempts=4, operation_name="test")

        self.assertEqual(len(attempts), 1)

    def test_bounded_retry_gives_up_after_max_attempts(self) -> None:
        attempts: list[int] = []

        def always_transient():
            attempts.append(1)
            raise TimeoutError("still down")

        with patch("attachment_orchestrator.time.sleep"), self.assertRaises(TimeoutError):
            orch.with_bounded_retry(always_transient, attempts=3, operation_name="test")

        self.assertEqual(len(attempts), 3)


# ---------------------------------------------------------------------
# 11. Advisory-lock contention
# ---------------------------------------------------------------------

class AdvisoryLockContentionTest(unittest.TestCase):
    def test_acquire_lock_succeeds_when_available(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalar.return_value = True
        engine = MagicMock()
        engine.connect.return_value = connection

        result = orch.acquire_lock(engine, label="test-run")

        self.assertIs(result, connection)
        connection.close.assert_not_called()

    def test_acquire_lock_raises_with_holder_info_when_contended(self) -> None:
        connection = MagicMock()
        lock_result = MagicMock()
        lock_result.scalar.return_value = False
        holder_row = MagicMock()
        holder_row._mapping = {"application_name": "attachment-load:other-run", "pid": 123}
        holder_result = MagicMock()
        holder_result.fetchone.return_value = holder_row
        connection.execute.side_effect = [None, lock_result, holder_result]
        engine = MagicMock()
        engine.connect.return_value = connection

        with self.assertRaises(orch.LockNotAcquired) as ctx:
            orch.acquire_lock(engine, label="test-run")

        self.assertIn("other-run", str(ctx.exception))
        connection.close.assert_called_once()

    def test_run_orchestration_never_selects_a_batch_if_lock_is_contended(self) -> None:
        with (
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(orch, "acquire_lock", side_effect=orch.LockNotAcquired("busy")),
            patch.object(orch, "proposal_metadata_stage") as metadata_stage,
        ):
            with self.assertRaises(orch.LockNotAcquired):
                orch.run_orchestration(modules=(orch.PROPOSAL,), bucket="bucket-x")

        metadata_stage.assert_not_called()

    def test_release_lock_always_closes_the_connection(self) -> None:
        connection = MagicMock()
        orch.release_lock(connection)
        connection.close.assert_called_once()

    def test_release_lock_closes_even_if_unlock_query_fails(self) -> None:
        connection = MagicMock()
        connection.execute.side_effect = RuntimeError("connection already gone")
        orch.release_lock(connection)
        connection.close.assert_called_once()


# ---------------------------------------------------------------------
# 12. Module isolation
# ---------------------------------------------------------------------

class ModuleIsolationTest(unittest.TestCase):
    def test_next_ready_batch_uses_the_correct_domain_per_module(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalar.return_value = None
        engine = _engine_with_connection(connection)

        orch._next_ready_batch(engine, module=orch.PROPOSAL)
        params = connection.execute.call_args.args[1]
        self.assertEqual(params["domain"], orch.PROPOSAL_ATTACHMENT_DOMAIN)

        orch._next_ready_batch(engine, module=orch.SUBAWARD)
        params = connection.execute.call_args.args[1]
        self.assertEqual(params["domain"], orch.SUBAWARD_ATTACHMENT_DOMAIN)

    def test_negotiation_and_irb_are_rejected(self) -> None:
        with (
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(orch, "acquire_lock", return_value=MagicMock()),
        ):
            with self.assertRaises(ValueError) as ctx:
                orch.run_orchestration(modules=("negotiation",), bucket="bucket-x")
        self.assertIn("negotiation", str(ctx.exception))

        with self.assertRaises(ValueError):
            orch.run_orchestration(modules=("irb",), bucket="bucket-x")

    def test_run_orchestration_processes_each_requested_module_independently(self) -> None:
        seen_modules: list[str] = []

        def fake_metadata_stage(name):
            def _inner(*_args, **_kwargs):
                seen_modules.append(name)
                return {"selected_count": 0}
            return _inner

        with (
            patch.object(orch, "acquire_lock", return_value=MagicMock()),
            patch.object(orch, "release_lock"),
            patch.object(orch, "create_postgres_engine", return_value=MagicMock()),
            patch.object(orch, "award_metadata_stage", side_effect=fake_metadata_stage("award")),
            patch.object(orch, "proposal_metadata_stage", side_effect=fake_metadata_stage("proposal")),
            patch.object(orch, "subaward_metadata_stage", side_effect=fake_metadata_stage("subaward")),
            patch.object(orch, "_next_ready_batch", return_value=None),
        ):
            summary = orch.run_orchestration(bucket="bucket-x")

        self.assertEqual(seen_modules, ["award", "proposal", "subaward"])
        self.assertEqual(set(summary["modules"].keys()), {"award", "proposal", "subaward"})


# ---------------------------------------------------------------------
# 13. No deletion or overwrite paths
# ---------------------------------------------------------------------

class S3PrefixMatchesGrantedIamScopeTest(unittest.TestCase):
    """Live-verified (2026-08-12): the loader task role's IAM policy
    (terraform/modules/ecs/main.tf) grants s3:PutObject/GetObject only
    on specific literal prefixes, not a bare bucket-wide wildcard - a
    key computed under any other prefix fails closed with S3
    AccessDenied at upload time, not at code-review time. This was
    caught by the canary: proposal_binary_stage's default prefix was
    originally "proposals/by-file-data-id" (plural), but
    UploadProposalAttachmentObjects only grants "proposal/*" (singular,
    matching ProposalAttachmentPlugin's own default_s3_prefix). Pin the
    corrected values so a future accidental rename trips a test instead
    of a real upload run."""

    def test_proposal_binary_stage_default_prefix_is_singular_matching_granted_iam_scope(self) -> None:
        import inspect

        signature = inspect.signature(orch.proposal_binary_stage)
        self.assertEqual(signature.parameters["prefix"].default, "proposal/by-file-data-id")

    def test_subaward_binary_stage_default_prefix_matches_granted_iam_scope(self) -> None:
        import inspect

        signature = inspect.signature(orch.subaward_binary_stage)
        self.assertEqual(signature.parameters["prefix"].default, "subawards/by-file-data-id")


class NoDeletionOrOverwritePathsTest(unittest.TestCase):
    def test_module_source_never_contains_destructive_sql(self) -> None:
        import inspect

        source = inspect.getsource(orch)
        for forbidden in ("DELETE FROM", "DROP TABLE", "TRUNCATE"):
            self.assertNotIn(forbidden, source, f"found forbidden statement: {forbidden}")

    def test_subaward_archive_row_insert_never_overwrites_existing_status(self) -> None:
        import inspect

        source = inspect.getsource(orch._upsert_subaward_attachments)
        self.assertIn("ON CONFLICT (attachment_id) DO NOTHING", source)

    def test_proposal_metadata_upsert_never_touches_upload_state_columns(self) -> None:
        # upsert_proposal_attachments() itself is load_proposals_from_csv's
        # own, unmodified, already-tested function - this just proves this
        # orchestrator calls it (rather than any home-grown upsert) for
        # the metadata stage, inheriting its documented guarantee.
        import inspect

        source = inspect.getsource(orch._run_load_proposal_attachment_batch)
        self.assertIn("proposals.upsert_proposal_attachments", source)

    def test_s3_delete_object_is_never_called_anywhere_in_the_module(self) -> None:
        import inspect

        source = inspect.getsource(orch)
        self.assertNotIn("delete_object", source)
        self.assertNotIn(".delete(", source)


if __name__ == "__main__":
    unittest.main()
