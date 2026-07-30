from __future__ import annotations

import unittest
from unittest.mock import MagicMock

from archive_etl.config import startup_validation as sv


class ValidatePostgresReachableTest(unittest.TestCase):
    def test_passes_when_select_1_succeeds(self) -> None:
        engine = MagicMock()
        connection = MagicMock()
        engine.connect.return_value.__enter__.return_value = connection

        sv.validate_postgres_reachable(engine)

        connection.execute.assert_called_once()

    def test_raises_startup_validation_error_on_failure(self) -> None:
        engine = MagicMock()
        engine.connect.side_effect = RuntimeError("connection refused")

        with self.assertRaises(sv.StartupValidationError):
            sv.validate_postgres_reachable(engine)


class ValidateOracleReachableTest(unittest.TestCase):
    def test_passes_when_select_1_from_dual_succeeds(self) -> None:
        cursor = MagicMock()
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        connect_oracle = MagicMock(return_value=connection)

        sv.validate_oracle_reachable(connect_oracle)

        cursor.execute.assert_called_once_with("SELECT 1 FROM DUAL")
        connection.close.assert_called_once()

    def test_raises_startup_validation_error_when_connect_fails(self) -> None:
        connect_oracle = MagicMock(side_effect=RuntimeError("ORA-12541"))

        with self.assertRaises(sv.StartupValidationError):
            sv.validate_oracle_reachable(connect_oracle)

    def test_raises_and_still_closes_connection_when_query_fails(self) -> None:
        cursor = MagicMock()
        cursor.execute.side_effect = RuntimeError("ORA-00942")
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        connect_oracle = MagicMock(return_value=connection)

        with self.assertRaises(sv.StartupValidationError):
            sv.validate_oracle_reachable(connect_oracle)

        connection.close.assert_called_once()


class ValidateBucketExistsTest(unittest.TestCase):
    def test_passes_when_head_bucket_succeeds(self) -> None:
        s3_client = MagicMock()

        sv.validate_bucket_exists(s3_client, "my-bucket")

        s3_client.head_bucket.assert_called_once_with(Bucket="my-bucket")

    def test_raises_startup_validation_error_when_bucket_missing(self) -> None:
        s3_client = MagicMock()
        s3_client.head_bucket.side_effect = RuntimeError("404 Not Found")

        with self.assertRaises(sv.StartupValidationError):
            sv.validate_bucket_exists(s3_client, "my-bucket")


class ValidateTableExistsTest(unittest.TestCase):
    def test_passes_when_table_exists(self) -> None:
        engine = MagicMock()
        connection = MagicMock()
        connection.execute.return_value.scalar_one.return_value = True
        engine.connect.return_value.__enter__.return_value = connection

        sv.validate_table_exists(engine, "attachment_object")

    def test_raises_when_table_missing(self) -> None:
        engine = MagicMock()
        connection = MagicMock()
        connection.execute.return_value.scalar_one.return_value = False
        engine.connect.return_value.__enter__.return_value = connection

        with self.assertRaises(sv.StartupValidationError):
            sv.validate_table_exists(engine, "attachment_object")


class ValidateUploadStatusSchemaTest(unittest.TestCase):
    def _engine_with_constraint_definition(self, definition: str | None) -> MagicMock:
        engine = MagicMock()
        connection = MagicMock()
        connection.execute.return_value.scalar_one_or_none.return_value = definition
        engine.connect.return_value.__enter__.return_value = connection
        return engine

    def test_passes_when_all_expected_values_present(self) -> None:
        engine = self._engine_with_constraint_definition(
            "CHECK (upload_status = ANY (ARRAY["
            "'PENDING'::character varying, 'UPLOADING'::character varying, "
            "'UPLOADED'::character varying, 'FAILED'::character varying, "
            "'MISSING_SOURCE_CONTENT'::character varying]))"
        )

        sv.validate_upload_status_schema(engine)

    def test_raises_when_constraint_is_missing_entirely(self) -> None:
        engine = self._engine_with_constraint_definition(None)

        with self.assertRaises(sv.StartupValidationError):
            sv.validate_upload_status_schema(engine)

    def test_raises_when_constraint_is_missing_an_expected_value(self) -> None:
        # Simulates a database still on V035, before V036 added
        # UPLOADING/MISSING_SOURCE_CONTENT.
        engine = self._engine_with_constraint_definition(
            "CHECK (upload_status = ANY (ARRAY["
            "'PENDING'::character varying, 'SKIPPED'::character varying, "
            "'UPLOADED'::character varying, 'FAILED'::character varying]))"
        )

        with self.assertRaises(sv.StartupValidationError) as raised:
            sv.validate_upload_status_schema(engine)

        message = str(raised.exception)
        self.assertIn("UPLOADING", message)
        self.assertIn("MISSING_SOURCE_CONTENT", message)


class RunStartupValidationTest(unittest.TestCase):
    def _passing_engine(self) -> MagicMock:
        engine = MagicMock()
        connection = MagicMock()
        connection.execute.return_value.scalar_one.return_value = True
        connection.execute.return_value.scalar_one_or_none.return_value = (
            "CHECK (upload_status = ANY (ARRAY["
            "'PENDING'::character varying, 'UPLOADING'::character varying, "
            "'UPLOADED'::character varying, 'FAILED'::character varying, "
            "'MISSING_SOURCE_CONTENT'::character varying]))"
        )
        engine.connect.return_value.__enter__.return_value = connection
        return engine

    def test_passes_when_every_check_passes(self) -> None:
        engine = self._passing_engine()
        cursor = MagicMock()
        connection = MagicMock()
        connection.cursor.return_value.__enter__.return_value = cursor
        connect_oracle = MagicMock(return_value=connection)
        s3_client = MagicMock()

        sv.run_startup_validation(
            engine=engine,
            connect_oracle=connect_oracle,
            s3_client=s3_client,
            bucket="my-bucket",
        )

        s3_client.head_bucket.assert_called_once_with(Bucket="my-bucket")

    def test_skips_bucket_check_when_no_bucket_configured(self) -> None:
        engine = self._passing_engine()
        connect_oracle = MagicMock(return_value=MagicMock())
        s3_client = MagicMock()

        sv.run_startup_validation(
            engine=engine,
            connect_oracle=connect_oracle,
            s3_client=s3_client,
            bucket=None,
        )

        s3_client.head_bucket.assert_not_called()

    def test_fails_fast_on_postgres_before_touching_oracle(self) -> None:
        engine = MagicMock()
        engine.connect.side_effect = RuntimeError("connection refused")
        connect_oracle = MagicMock()

        with self.assertRaises(sv.StartupValidationError):
            sv.run_startup_validation(
                engine=engine,
                connect_oracle=connect_oracle,
                s3_client=MagicMock(),
                bucket="my-bucket",
            )

        connect_oracle.assert_not_called()


if __name__ == "__main__":
    unittest.main()
