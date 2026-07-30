"""Read-only startup validation for --ecs mode.

Fails fast, before processing any file, if PostgreSQL or Oracle aren't
reachable, the S3 bucket doesn't exist, the Award Attachment tables are
missing, or the upload_status CHECK constraint doesn't match the expected
migration (V036__extend_award_attachment_upload_status.sql). Every check
here is read-only (SELECT / HEAD only) - no writes anywhere, so this is
always safe to run under --dry-run too.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Engine

EXPECTED_UPLOAD_STATUS_VALUES = frozenset(
    {"PENDING", "UPLOADING", "UPLOADED", "FAILED", "MISSING_SOURCE_CONTENT"}
)

UPLOAD_STATUS_CONSTRAINT_NAME = "ck_attachment_object_upload_status"


class StartupValidationError(RuntimeError):
    """Raised when a startup validation check fails - the caller must
    abort immediately rather than proceed with a partially-verified
    environment."""


def validate_postgres_reachable(engine: Engine) -> None:
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
    except Exception as error:
        raise StartupValidationError(
            f"PostgreSQL is not reachable: {type(error).__name__}"
        ) from error


def validate_oracle_reachable(connect_oracle: Callable[[], Any]) -> None:
    try:
        connection = connect_oracle()
    except Exception as error:
        raise StartupValidationError(
            f"Oracle is not reachable: {type(error).__name__}"
        ) from error

    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1 FROM DUAL")
            cursor.fetchone()
    except Exception as error:
        raise StartupValidationError(
            f"Oracle is not reachable: {type(error).__name__}"
        ) from error
    finally:
        connection.close()


def validate_bucket_exists(s3_client: Any, bucket: str) -> None:
    try:
        s3_client.head_bucket(Bucket=bucket)
    except Exception as error:
        raise StartupValidationError(
            f"S3 bucket '{bucket}' does not exist or is not accessible: "
            f"{type(error).__name__}"
        ) from error


def validate_table_exists(engine: Engine, table_name: str) -> None:
    with engine.connect() as connection:
        exists = connection.execute(
            text(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'archive'
                      AND table_name = :table_name
                )
                """
            ),
            {"table_name": table_name},
        ).scalar_one()

    if not exists:
        raise StartupValidationError(
            f"archive.{table_name} does not exist - has the loader's "
            "migration been applied?"
        )


def validate_upload_status_schema(engine: Engine) -> None:
    with engine.connect() as connection:
        definition = connection.execute(
            text(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = :constraint_name
                """
            ),
            {"constraint_name": UPLOAD_STATUS_CONSTRAINT_NAME},
        ).scalar_one_or_none()

    if definition is None:
        raise StartupValidationError(
            f"archive.attachment_object's '{UPLOAD_STATUS_CONSTRAINT_NAME}' "
            "CHECK constraint was not found - has migration V036 been "
            "applied?"
        )

    missing = sorted(
        value for value in EXPECTED_UPLOAD_STATUS_VALUES if value not in definition
    )
    if missing:
        raise StartupValidationError(
            "archive.attachment_object's upload_status CHECK constraint "
            "is missing expected value(s): " + ", ".join(missing)
        )


def run_startup_validation(
    *,
    engine: Engine,
    connect_oracle: Callable[[], Any],
    s3_client: Any = None,
    bucket: str | None = None,
) -> None:
    """Run every check in order, raising StartupValidationError with a
    clear message on the first failure (fail fast). The bucket check is
    skipped (not failed) when no bucket is configured at all - metadata-
    only ECS runs don't need one."""
    logger.info("Running ECS startup validation")

    validate_postgres_reachable(engine)
    logger.info("Startup validation: PostgreSQL reachable")

    validate_oracle_reachable(connect_oracle)
    logger.info("Startup validation: Oracle reachable")

    if bucket:
        validate_bucket_exists(s3_client, bucket)
        logger.info("Startup validation: S3 bucket exists ({})", bucket)
    else:
        logger.info(
            "Startup validation: no bucket configured - skipping S3 "
            "bucket existence check"
        )

    validate_table_exists(engine, "attachment_object")
    validate_table_exists(engine, "award_attachment")
    logger.info("Startup validation: Award attachment tables present")

    validate_upload_status_schema(engine)
    logger.info(
        "Startup validation: upload_status schema matches expected "
        "migration"
    )

    logger.info("Startup validation passed")
