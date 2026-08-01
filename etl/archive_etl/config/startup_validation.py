"""Read-only startup-validation checks for --ecs mode.

Each function here checks one thing - PostgreSQL reachable, Oracle
reachable, an S3 bucket exists, an archive table exists, or the
upload_status CHECK constraint matches the expected migration
(V036__extend_award_attachment_upload_status.sql) - and raises
StartupValidationError with a clear message if it fails. Every check is
read-only (SELECT / HEAD only); no writes happen anywhere in this module.

load_award_attachments.py's _run_ecs_setup() orchestrates these in the
specific order --ecs mode requires (identity -> secrets -> PostgreSQL
connectivity -> [--migrate-only: migrate + validate + exit] -> Oracle
connectivity -> bucket -> tables -> schema) rather than calling a single
combined function here, since --migrate-only needs to run a subset of
these checks in the middle of that sequence, not the same order every
time.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from botocore.exceptions import BotoCoreError, ClientError
from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.utils.redaction import redact_error_message

EXPECTED_UPLOAD_STATUS_VALUES = frozenset(
    {"PENDING", "UPLOADING", "UPLOADED", "FAILED", "MISSING_SOURCE_CONTENT"}
)

UPLOAD_STATUS_CONSTRAINT_NAME = "ck_attachment_object_upload_status"


class StartupValidationError(RuntimeError):
    """Raised when a startup validation check fails - the caller must
    abort immediately rather than proceed with a partially-verified
    environment."""


def validate_aws_identity(sts_client: Any) -> dict[str, str]:
    """Fail closed before any --ecs work if AWS credentials are missing
    or invalid - never proceed against an unverified identity. Generic
    across every --ecs loader (no Award/Award-Attachment-specific
    content) - takes an already-constructed STS client rather than
    calling boto3.client("sts") itself, so callers can supply a fake in
    tests without patching boto3 globally."""
    try:
        identity = sts_client.get_caller_identity()
    except (BotoCoreError, ClientError) as error:
        raise StartupValidationError(
            "AWS identity validation failed - refusing to proceed: "
            f"{redact_error_message(str(error))}"
        ) from error
    return {
        "account": str(identity.get("Account", "")),
        "arn": str(identity.get("Arn", "")),
    }


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
