from __future__ import annotations

import json
import unittest

from loguru import logger

from archive_etl.utils.structured_logging import configure_structured_logging


class ConfigureStructuredLoggingTest(unittest.TestCase):
    def setUp(self) -> None:
        # Every test gets a clean loguru configuration and restores the
        # default (human-readable, stderr) handler afterward so this
        # doesn't leak structured JSON logging into other test modules.
        logger.remove()
        self.addCleanup(self._restore_default_logger)

    def _restore_default_logger(self) -> None:
        logger.remove()
        logger.configure(extra={})
        import sys

        logger.add(sys.stderr)

    def _capture_lines(self, run_id: str, log_calls) -> list[dict]:
        lines: list[str] = []
        configure_structured_logging(run_id)
        # Redirect the sink's stdout target by capturing prints instead -
        # simplest: monkeypatch builtins.print via contextlib redirect.
        import contextlib
        import io

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            log_calls()

        for line in buffer.getvalue().splitlines():
            if line.strip():
                lines.append(line)
        return [json.loads(line) for line in lines]

    def test_emits_one_json_object_per_line(self) -> None:
        records = self._capture_lines(
            "run-123", lambda: logger.info("hello world")
        )

        self.assertEqual(len(records), 1)
        record = records[0]
        self.assertEqual(record["message"], "hello world")
        self.assertEqual(record["level"], "INFO")
        self.assertEqual(record["run_id"], "run-123")
        self.assertIn("timestamp", record)

    def test_includes_bound_stage_file_id_status_and_elapsed_ms(self) -> None:
        def _log() -> None:
            logger.bind(
                stage="upload", file_id=9001, status="uploaded", elapsed_ms=12.5
            ).info("file_id={} upload succeeded", 9001)

        records = self._capture_lines("run-456", _log)

        self.assertEqual(len(records), 1)
        record = records[0]
        self.assertEqual(record["stage"], "upload")
        self.assertEqual(record["file_id"], 9001)
        self.assertEqual(record["status"], "uploaded")
        self.assertEqual(record["elapsed_ms"], 12.5)
        self.assertEqual(record["run_id"], "run-456")

    def test_run_id_is_present_on_every_call_without_rebinding(self) -> None:
        def _log() -> None:
            logger.info("first")
            logger.info("second")

        records = self._capture_lines("run-789", _log)

        self.assertEqual(len(records), 2)
        self.assertTrue(all(r["run_id"] == "run-789" for r in records))

    def test_omits_unbound_optional_fields(self) -> None:
        records = self._capture_lines("run-omit", lambda: logger.info("plain"))

        record = records[0]
        for field in ("stage", "file_id", "status", "elapsed_ms"):
            self.assertNotIn(field, record)

    def test_never_logs_sql_or_blob_content_as_a_dedicated_field(self) -> None:
        # This module has no "sql" or "blob"/"content" field in its
        # schema at all - the only fields it ever emits are the fixed
        # set below, which structurally excludes logging SQL text or
        # BLOB bytes as a queryable field.
        records = self._capture_lines(
            "run-schema",
            lambda: logger.bind(
                stage="upload", file_id=1, status="uploaded", elapsed_ms=1.0
            ).info("done"),
        )

        allowed_fields = {
            "timestamp",
            "level",
            "message",
            "run_id",
            "stage",
            "file_id",
            "status",
            "elapsed_ms",
        }
        self.assertTrue(set(records[0].keys()).issubset(allowed_fields))


if __name__ == "__main__":
    unittest.main()
