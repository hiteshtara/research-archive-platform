"""Build the `aws ecs run-task --overrides` JSON for a one-off Award
Attachment loader task run.

Translates CLI pass-through flags (--file-id, --load-file-id, --limit,
--retry-failed, --dry-run, --upload, --migrate-only,
--show-upload-status) into the container command override
for the "loader" container in the research-archive-platform-dev-loader
task family (see terraform/modules/ecs/main.tf - not modified here), and
translates non-secret configuration (POSTGRES_SECRET_ID, ORACLE_SECRET_ID,
POSTGRES_HOST/PORT/DB, AWARD_ATTACHMENT_BUCKET_NAME, AWS_REGION) into the
container's environment override.

The generated command always starts with ["python", "-m", "archive_etl",
"award-attachment", "--ecs", ...] - never a bare "load_award_attachments.py"
filename. An ECS containerOverrides `command` replaces the container's
CMD entirely (no shell, no `uv run` wrapper), so element 0 must be a real
executable already on PATH inside the image.

Only secret *identifiers* (an ARN or name) ever appear here - never a
password, a DSN, or any secret JSON. There is deliberately no
--postgres-password/--oracle-password/--postgres-secret-value flag or
equivalent: this script has no way to accept a secret value even by
mistake.

Kept as a small, pure, independently testable function - the actual AWS
orchestration (build/push image, register task revision, run-task, wait,
stream logs) lives in scripts/run-award-attachment-loader.sh, which
shells out to this module only for this one translation step.

Usage:
    uv run python scripts/build_award_attachment_ecs_overrides.py \
        --upload --limit 10 --bucket my-bucket \
        --postgres-secret-id arn:...:postgres \
        --oracle-secret-id arn:...:oracle
"""

from __future__ import annotations

import argparse
import json

CONTAINER_NAME = "loader"


def build_container_command(
    *,
    file_id: int | None = None,
    load_file_id: int | None = None,
    limit: int | None = None,
    retry_failed: bool = False,
    dry_run: bool = False,
    upload: bool = False,
    migrate_only: bool = False,
    show_upload_status: bool = False,
    bucket: str | None = None,
    prefix: str | None = None,
) -> list[str]:
    """--ecs is always included: this command is only ever used for the
    ECS loader task, never local development.

    Invoked via the unified module CLI (`python -m archive_etl
    award-attachment`), never a bare script filename - an ECS
    containerOverrides `command` replaces the container's CMD entirely,
    with no shell and no `uv run` wrapper to fall back on, so element 0
    must be a real executable already on the container's PATH
    (`python` - see etl/Dockerfile.loader). A bare `load_award_attachments.py`
    is neither executable nor resolvable via PATH, which is exactly how a
    prior version of this command failed in production (`exec:
    "load_award_attachments.py": executable file not found in $PATH`)."""
    command = ["python", "-m", "archive_etl", "award-attachment", "--ecs"]

    if migrate_only:
        command.append("--migrate-only")
    if show_upload_status:
        command.append("--show-upload-status")
    if upload:
        command.append("--upload")
    if dry_run:
        command.append("--dry-run")
    if limit is not None:
        command.extend(["--limit", str(limit)])
    if file_id is not None:
        command.extend(["--file-id", str(file_id)])
    if load_file_id is not None:
        command.extend(["--load-file-id", str(load_file_id)])
    if retry_failed:
        command.append("--retry-failed")
    if bucket:
        command.extend(["--bucket", bucket])
    if prefix:
        command.extend(["--prefix", prefix])

    return command


def build_environment_overrides(
    *,
    postgres_secret_id: str | None = None,
    oracle_secret_id: str | None = None,
    postgres_host: str | None = None,
    postgres_port: str | None = None,
    postgres_db: str | None = None,
    award_attachment_bucket_name: str | None = None,
    aws_region: str | None = None,
) -> list[dict[str, str]]:
    """Non-secret configuration only. POSTGRES_SECRET_ID/ORACLE_SECRET_ID
    are Secrets Manager *identifiers* (ARN or name), not credentials -
    the loader fetches the actual username/password/dsn itself, at
    runtime, from Secrets Manager. Nothing that resolves to a password,
    a DSN, or a secret's JSON content is ever accepted or emitted here."""
    mapping = {
        "POSTGRES_SECRET_ID": postgres_secret_id,
        "ORACLE_SECRET_ID": oracle_secret_id,
        "POSTGRES_HOST": postgres_host,
        "POSTGRES_PORT": postgres_port,
        "POSTGRES_DB": postgres_db,
        "AWARD_ATTACHMENT_BUCKET_NAME": award_attachment_bucket_name,
        "AWS_REGION": aws_region,
    }
    return [
        {"name": name, "value": value}
        for name, value in mapping.items()
        if value
    ]


def build_run_task_overrides(
    *,
    file_id: int | None = None,
    load_file_id: int | None = None,
    limit: int | None = None,
    retry_failed: bool = False,
    dry_run: bool = False,
    upload: bool = False,
    migrate_only: bool = False,
    show_upload_status: bool = False,
    bucket: str | None = None,
    prefix: str | None = None,
    postgres_secret_id: str | None = None,
    oracle_secret_id: str | None = None,
    postgres_host: str | None = None,
    postgres_port: str | None = None,
    postgres_db: str | None = None,
    award_attachment_bucket_name: str | None = None,
    aws_region: str | None = None,
) -> dict:
    container_override: dict[str, object] = {
        "name": CONTAINER_NAME,
        "command": build_container_command(
            file_id=file_id,
            load_file_id=load_file_id,
            limit=limit,
            retry_failed=retry_failed,
            dry_run=dry_run,
            upload=upload,
            migrate_only=migrate_only,
            show_upload_status=show_upload_status,
            bucket=bucket,
            prefix=prefix,
        ),
    }

    environment = build_environment_overrides(
        postgres_secret_id=postgres_secret_id,
        oracle_secret_id=oracle_secret_id,
        postgres_host=postgres_host,
        postgres_port=postgres_port,
        postgres_db=postgres_db,
        award_attachment_bucket_name=award_attachment_bucket_name,
        aws_region=aws_region,
    )
    if environment:
        container_override["environment"] = environment

    return {"containerOverrides": [container_override]}


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file-id", type=int, default=None)
    parser.add_argument("--load-file-id", type=int, default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--upload", action="store_true")
    parser.add_argument("--migrate-only", action="store_true")
    parser.add_argument("--show-upload-status", action="store_true")
    parser.add_argument("--bucket", type=str, default=None)
    parser.add_argument("--prefix", type=str, default=None)
    parser.add_argument("--postgres-secret-id", type=str, default=None)
    parser.add_argument("--oracle-secret-id", type=str, default=None)
    parser.add_argument("--postgres-host", type=str, default=None)
    parser.add_argument("--postgres-port", type=str, default=None)
    parser.add_argument("--postgres-db", type=str, default=None)
    parser.add_argument("--award-attachment-bucket-name", type=str, default=None)
    parser.add_argument("--aws-region", type=str, default=None)
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_args()
    overrides = build_run_task_overrides(
        file_id=args.file_id,
        load_file_id=args.load_file_id,
        limit=args.limit,
        retry_failed=args.retry_failed,
        dry_run=args.dry_run,
        upload=args.upload,
        migrate_only=args.migrate_only,
        show_upload_status=args.show_upload_status,
        bucket=args.bucket,
        prefix=args.prefix,
        postgres_secret_id=args.postgres_secret_id,
        oracle_secret_id=args.oracle_secret_id,
        postgres_host=args.postgres_host,
        postgres_port=args.postgres_port,
        postgres_db=args.postgres_db,
        award_attachment_bucket_name=args.award_attachment_bucket_name,
        aws_region=args.aws_region,
    )
    print(json.dumps(overrides))


if __name__ == "__main__":
    main()
