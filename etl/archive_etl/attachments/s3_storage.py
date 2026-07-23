from __future__ import annotations

from pathlib import Path
from typing import Any

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from archive_etl.attachments.oracle_blob import retry


def create_s3_client(region: str, attempts: int):
    return boto3.client(
        "s3",
        region_name=region,
        config=Config(
            retries={"max_attempts": attempts, "mode": "standard"}
        ),
    )


def head_object(
    s3_client,
    bucket: str,
    key: str,
    attempts: int,
) -> dict[str, Any] | None:
    def operation() -> dict[str, Any] | None:
        try:
            return s3_client.head_object(Bucket=bucket, Key=key)
        except ClientError as error:
            status = error.response.get("ResponseMetadata", {}).get(
                "HTTPStatusCode"
            )
            if status == 404:
                return None
            raise

    return retry(
        operation,
        attempts=attempts,
        operation_name="S3 HEAD",
    )


def upload_object(
    s3_client,
    path: Path,
    bucket: str,
    key: str,
    object_metadata: dict[str, str],
    mime_type: str | None,
    sse: str,
    kms_key_id: str | None,
    attempts: int,
) -> None:
    extra_args: dict[str, Any] = {
        "Metadata": object_metadata,
        "ServerSideEncryption": sse,
    }
    if mime_type:
        extra_args["ContentType"] = mime_type
    if sse == "aws:kms":
        if not kms_key_id:
            raise RuntimeError("--kms-key-id is required with --sse aws:kms")
        extra_args["SSEKMSKeyId"] = kms_key_id

    retry(
        lambda: s3_client.upload_file(
            str(path),
            bucket,
            key,
            ExtraArgs=extra_args,
        ),
        attempts=attempts,
        operation_name="S3 upload",
    )


def checksum_matches(
    head: dict[str, Any] | None,
    byte_size: int,
    sha256: str,
) -> bool:
    if head is None:
        return False
    metadata = head.get("Metadata", {})
    return (
        head.get("ContentLength") == byte_size
        and metadata.get("sha256") == sha256
    )
