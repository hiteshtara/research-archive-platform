from __future__ import annotations

import argparse
import os
import tempfile
from pathlib import Path
from typing import Sequence

from loguru import logger

from archive_etl.attachments.manifest import ManifestStore
from archive_etl.attachments.models import (
    ArchiveCounts,
    AttachmentRecord,
    MissingBlobError,
    safe_error_message,
    utc_now,
)
from archive_etl.attachments.oracle_blob import OracleBlobReader
from archive_etl.attachments.plugins.base import AttachmentPlugin
from archive_etl.attachments.plugins.award import AwardAttachmentPlugin
from archive_etl.attachments.plugins.irb import IrbProtocolAttachmentPlugin
from archive_etl.attachments.plugins.negotiation import (
    NegotiationAttachmentPlugin,
)
from archive_etl.attachments.plugins.subaward import (
    SubawardAttachmentPlugin,
)
from archive_etl.attachments.s3_storage import (
    checksum_matches,
    create_s3_client,
    head_object,
    upload_object,
)


PLUGINS: dict[str, AttachmentPlugin] = {
    "award": AwardAttachmentPlugin(),
    "irb": IrbProtocolAttachmentPlugin(),
    "negotiation": NegotiationAttachmentPlugin(),
    "subaward": SubawardAttachmentPlugin(),
}

UNSUPPORTED_MODULES = {
    "proposal": (
        "the Oracle source and direct FILE_DATA_ID join are verified, "
        "but no Proposal-specific PostgreSQL attachment archive table "
        "exists"
    ),
}

MODULE_NAMES = sorted(set(PLUGINS) | set(UNSUPPORTED_MODULES))


def process_attachment(
    record: AttachmentRecord,
    *,
    plugin: AttachmentPlugin,
    reader: OracleBlobReader,
    manifest: ManifestStore,
    s3_client,
    bucket: str,
    prefix: str,
    sse: str,
    kms_key_id: str | None,
    attempts: int,
    verify_only: bool,
    counts: ArchiveCounts,
) -> None:
    key = plugin.s3_key(prefix, record)
    file_data_id = plugin.file_data_id(record)

    if file_data_id is None:
        counts.missing_blob_count += 1
        if not verify_only:
            manifest.upsert(
                plugin.manifest_values(
                    record,
                    bucket,
                    key,
                    byte_size=None,
                    sha256=None,
                    status="MISSING",
                    archived_timestamp=None,
                    error_message=(
                        f"{plugin.file_reference_label} is missing"
                    ),
                )
            )
        logger.error(
            "Attachment {} has no {}",
            plugin.attachment_id(record),
            plugin.file_reference_label,
        )
        return

    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=(
                f"{plugin.module_name}-"
                f"{plugin.attachment_id(record)}-"
            ),
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)

        byte_size, sha256 = reader.stream_to_path(
            file_data_id,
            temporary_path,
        )
        counts.file_data_match_count += 1
        current_manifest = manifest.get(
            plugin.attachment_id(record)
        )
        head = head_object(s3_client, bucket, key, attempts)
        manifest_ok = plugin.manifest_matches(
            current_manifest,
            record,
            bucket,
            key,
            byte_size,
            sha256,
        )
        s3_ok = checksum_matches(head, byte_size, sha256)

        if verify_only:
            if not manifest_ok or not s3_ok:
                counts.checksum_mismatch_count += 1
            else:
                counts.resumed_count += 1
                counts.total_archived_bytes += byte_size
            return

        if manifest_ok and s3_ok:
            counts.resumed_count += 1
            counts.total_archived_bytes += byte_size
            return

        upload_object(
            s3_client,
            temporary_path,
            bucket,
            key,
            plugin.s3_object_metadata(record, sha256),
            record.mime_type,
            sse,
            kms_key_id,
            attempts,
        )
        uploaded_head = head_object(
            s3_client,
            bucket,
            key,
            attempts,
        )
        if not checksum_matches(uploaded_head, byte_size, sha256):
            raise RuntimeError(
                "uploaded S3 checksum metadata did not match"
            )

        manifest.upsert(
            plugin.manifest_values(
                record,
                bucket,
                key,
                byte_size=byte_size,
                sha256=sha256,
                status="ARCHIVED",
                archived_timestamp=utc_now(),
                error_message=None,
            )
        )
        counts.uploaded_count += 1
        counts.total_archived_bytes += byte_size
    except MissingBlobError:
        counts.missing_blob_count += 1
        if not verify_only:
            manifest.upsert(
                plugin.manifest_values(
                    record,
                    bucket,
                    key,
                    byte_size=None,
                    sha256=None,
                    status="MISSING",
                    archived_timestamp=None,
                    error_message=(
                        f"KCOEUS.{reader.reference_name} row or BLOB "
                        "is missing"
                    ),
                )
            )
        logger.error(
            "Missing {} BLOB for attachment {} and {} {}",
            reader.reference_name,
            plugin.attachment_id(record),
            plugin.file_reference_label,
            file_data_id,
        )
    except Exception as error:
        counts.failed_upload_count += 1
        if not verify_only:
            manifest.upsert(
                plugin.manifest_values(
                    record,
                    bucket,
                    key,
                    byte_size=None,
                    sha256=None,
                    status="FAILED",
                    archived_timestamp=None,
                    error_message=safe_error_message(error),
                )
            )
        logger.exception(
            "Attachment {} archival failed",
            plugin.attachment_id(record),
        )
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def verify_manifest_orphans(
    metadata_path: Path,
    manifest: ManifestStore,
    plugin: AttachmentPlugin,
    record_id: int | None,
    limit: int | None,
) -> int:
    attachment_ids = {
        plugin.attachment_id(record)
        for record in plugin.iter_records(
            metadata_path,
            record_id,
            limit,
        )
    }
    return sum(
        1
        for row in manifest.rows(record_id, limit)
        if row["attachment_id"] not in attachment_ids
    )


def build_parser(plugin: AttachmentPlugin) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Archive attachment BLOBs from local Oracle FILE_DATA "
            "to approved S3 storage."
        )
    )
    parser.add_argument(
        "--module",
        required=True,
        choices=[plugin.module_name],
    )
    parser.add_argument(
        "--metadata-csv",
        type=Path,
        default=plugin.default_metadata_csv,
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=plugin.default_manifest,
    )
    parser.add_argument(
        "--s3-bucket",
        default=os.getenv(plugin.bucket_environment_variable),
    )
    parser.add_argument(
        "--s3-prefix",
        default=os.getenv(
            plugin.prefix_environment_variable,
            plugin.default_s3_prefix,
        ),
    )
    parser.add_argument(
        "--aws-region",
        default=os.getenv("AWS_REGION", "us-east-1"),
    )
    parser.add_argument("--limit", type=int)
    parser.add_argument(
        "--verify-only",
        "--dry-run",
        dest="verify_only",
        action="store_true",
    )
    parser.add_argument("--sync-postgres", action="store_true")
    parser.add_argument("--max-retries", type=int, default=4)
    parser.add_argument(
        "--blob-chunk-size",
        type=int,
        default=1024 * 1024,
    )
    parser.add_argument(
        "--sse",
        choices=("AES256", "aws:kms"),
        default=os.getenv(plugin.sse_environment_variable, "AES256"),
    )
    parser.add_argument(
        "--kms-key-id",
        default=os.getenv(plugin.kms_environment_variable),
    )
    plugin.add_arguments(parser)
    return parser


def selected_plugin(argv: Sequence[str] | None) -> AttachmentPlugin:
    selector = argparse.ArgumentParser(add_help=False)
    selector.add_argument(
        "--module",
        required=True,
        choices=MODULE_NAMES,
    )
    selected, _ = selector.parse_known_args(argv)
    if selected.module in UNSUPPORTED_MODULES:
        selector.error(
            f"module '{selected.module}' is unavailable: "
            f"{UNSUPPORTED_MODULES[selected.module]}"
        )
    return PLUGINS[selected.module]


def log_counts(counts: ArchiveCounts) -> None:
    logger.info(
        "Attachment metadata count: {:,}",
        counts.attachment_metadata_count,
    )
    logger.info(
        "FILE_DATA match count: {:,}",
        counts.file_data_match_count,
    )
    logger.info("Uploaded count: {:,}", counts.uploaded_count)
    logger.info("Resumed count: {:,}", counts.resumed_count)
    logger.info("Missing BLOB count: {:,}", counts.missing_blob_count)
    logger.info(
        "Failed upload count: {:,}",
        counts.failed_upload_count,
    )
    logger.info(
        "Total archived bytes: {:,}",
        counts.total_archived_bytes,
    )
    logger.info(
        "Checksum mismatches: {:,}",
        counts.checksum_mismatch_count,
    )
    logger.info(
        "Manifest-to-attachment orphans: {:,}",
        counts.manifest_orphan_count,
    )


def run(argv: Sequence[str] | None = None) -> ArchiveCounts | None:
    plugin = selected_plugin(argv)
    args = build_parser(plugin).parse_args(argv)
    record_id = plugin.selected_record_id(args)

    if args.limit is not None and args.limit < 1:
        raise RuntimeError("--limit must be positive")
    if record_id is not None and record_id < 1:
        raise RuntimeError(
            f"--{plugin.module_name}-id must be positive"
        )

    manifest = plugin.create_manifest(args.manifest)
    if args.sync_postgres:
        try:
            synced = plugin.sync_postgres(manifest, record_id)
        finally:
            manifest.close()
        plugin.validate_sync_count(record_id, synced)
        return None

    if not args.metadata_csv.exists():
        raise FileNotFoundError(
            f"Attachment metadata CSV not found: {args.metadata_csv}"
        )
    if not args.s3_bucket:
        raise RuntimeError(
            "--s3-bucket or "
            f"{plugin.bucket_environment_variable} is required"
        )

    reader = plugin.create_blob_reader(
        args.max_retries,
        args.blob_chunk_size,
    )
    s3_client = create_s3_client(
        args.aws_region,
        args.max_retries,
    )
    counts = ArchiveCounts()

    try:
        for record in plugin.iter_records(
            args.metadata_csv,
            record_id,
            args.limit,
        ):
            counts.attachment_metadata_count += 1
            process_attachment(
                record,
                plugin=plugin,
                reader=reader,
                manifest=manifest,
                s3_client=s3_client,
                bucket=args.s3_bucket,
                prefix=args.s3_prefix,
                sse=args.sse,
                kms_key_id=args.kms_key_id,
                attempts=args.max_retries,
                verify_only=args.verify_only,
                counts=counts,
            )
            if counts.attachment_metadata_count % 100 == 0:
                logger.info(
                    "Processed {:,} attachment metadata rows",
                    counts.attachment_metadata_count,
                )

        counts.manifest_orphan_count = verify_manifest_orphans(
            args.metadata_csv,
            manifest,
            plugin,
            record_id,
            args.limit,
        )
    finally:
        reader.close()
        manifest.close()

    log_counts(counts)
    plugin.validate_counts(record_id, counts)

    if (
        counts.missing_blob_count
        or counts.failed_upload_count
        or counts.checksum_mismatch_count
        or counts.manifest_orphan_count
    ):
        raise RuntimeError(
            "Attachment archival completed with validation failures"
        )
    return counts
