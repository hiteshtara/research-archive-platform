"""Build the `aws ecs run-task --overrides` JSON for a one-off Award
Attachment loader task run.

Translates CLI pass-through flags (--file-id, --limit, --retry-failed,
--dry-run, --upload, --migrate-only) into the container command override
for the "loader" container in the research-archive-platform-dev-loader
task family (see terraform/modules/ecs/main.tf - not modified here), and
translates non-secret configuration (POSTGRES_SECRET_ID, ORACLE_SECRET_ID,
POSTGRES_HOST/PORT/DB, DATA_BUCKET_NAME, AWS_REGION) into the container's
environment override.

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
    limit: int | None = None,
    retry_failed: bool = False,
    dry_run: bool = False,
    upload: bool = False,
    migrate_only: bool = False,
    bucket: str | None = None,
    prefix: str | None = None,
) -> list[str]:
    """--ecs is always included: this command is only ever used for the
    ECS loader task, never local development."""
    command = ["load_award_attachments.py", "--ecs"]

    if migrate_only:
        command.append("--migrate-only")
    if upload:
        command.append("--upload")
    if dry_run:
        command.append("--dry-run")
    if limit is not None:
        command.extend(["--limit", str(limit)])
    if file_id is not None:
        command.extend(["--file-id", str(file_id)])
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
    data_bucket_name: str | None = None,
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
        "DATA_BUCKET_NAME": data_bucket_name,
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
    limit: int | None = None,
    retry_failed: bool = False,
    dry_run: bool = False,
    upload: bool = False,
    migrate_only: bool = False,
    bucket: str | None = None,
    prefix: str | None = None,
    postgres_secret_id: str | None = None,
    oracle_secret_id: str | None = None,
    postgres_host: str | None = None,
    postgres_port: str | None = None,
    postgres_db: str | None = None,
    data_bucket_name: str | None = None,
    aws_region: str | None = None,
) -> dict:
    container_override: dict[str, object] = {
        "name": CONTAINER_NAME,
        "command": build_container_command(
            file_id=file_id,
            limit=limit,
            retry_failed=retry_failed,
            dry_run=dry_run,
            upload=upload,
            migrate_only=migrate_only,
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
        data_bucket_name=data_bucket_name,
        aws_region=aws_region,
    )
    if environment:
        container_override["environment"] = environment

    return {"containerOverrides": [container_override]}


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file-id", type=int, default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--upload", action="store_true")
    parser.add_argument("--migrate-only", action="store_true")
    parser.add_argument("--bucket", type=str, default=None)
    parser.add_argument("--prefix", type=str, default=None)
    parser.add_argument("--postgres-secret-id", type=str, default=None)
    parser.add_argument("--oracle-secret-id", type=str, default=None)
    parser.add_argument("--postgres-host", type=str, default=None)
    parser.add_argument("--postgres-port", type=str, default=None)
    parser.add_argument("--postgres-db", type=str, default=None)
    parser.add_argument("--data-bucket-name", type=str, default=None)
    parser.add_argument("--aws-region", type=str, default=None)
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_args()
    overrides = build_run_task_overrides(
        file_id=args.file_id,
        limit=args.limit,
        retry_failed=args.retry_failed,
        dry_run=args.dry_run,
        upload=args.upload,
        migrate_only=args.migrate_only,
        bucket=args.bucket,
        prefix=args.prefix,
        postgres_secret_id=args.postgres_secret_id,
        oracle_secret_id=args.oracle_secret_id,
        postgres_host=args.postgres_host,
        postgres_port=args.postgres_port,
        postgres_db=args.postgres_db,
        data_bucket_name=args.data_bucket_name,
        aws_region=args.aws_region,
    )
    print(json.dumps(overrides))


if __name__ == "__main__":
    main()
