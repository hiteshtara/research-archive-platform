"""Build the `aws ecs run-task --overrides` JSON for a one-off Award
(core) loader task run.

Translates CLI pass-through flags (--migrate-only, --load-award-id,
--create-batch, --load-batch, --show-batch, --dry-run) into the container
command override for the "loader" container in the
research-archive-platform-dev-loader task family (see
terraform/modules/ecs/main.tf - not modified here), and translates
non-secret configuration (POSTGRES_SECRET_ID, ORACLE_SECRET_ID,
POSTGRES_HOST/PORT/DB, AWS_REGION) into the container's environment
override. --ecs is always included in the generated command - see
build_container_command's docstring.

Mirrors build_award_attachment_ecs_overrides.py's shape exactly, scoped to
the plain Award (core) loader (load_awards_from_csv.py, the "award"
domain under python -m archive_etl - see etl/archive_etl/__main__.py)
instead of the Award Attachment loader - no --upload/--file-id/
--load-file-id/--retry-failed/--bucket/--prefix/--batch-id/
--include-already-uploaded/--show-upload-status, none of which apply to
this loader.

Only secret *identifiers* (an ARN or name) ever appear here - never a
password, a DSN, or any secret JSON. There is deliberately no
--postgres-password/--oracle-password/--postgres-secret-value flag or
equivalent.

Kept as a small, pure, independently testable function - the actual AWS
orchestration (build/push image, register task revision, run-task, wait,
stream logs) lives in scripts/run-award-loader.sh, which shells out to
this module only for this one translation step.

Usage:
    uv run python scripts/build_award_ecs_overrides.py \
        --create-batch 10 \
        --postgres-secret-id arn:...:postgres \
        --oracle-secret-id arn:...:oracle
"""

from __future__ import annotations

import argparse
import json

CONTAINER_NAME = "loader"


def build_container_command(
    *,
    migrate_only: bool = False,
    load_award_id: int | None = None,
    create_batch: int | None = None,
    load_batch: int | None = None,
    show_batch: int | None = None,
    dry_run: bool = False,
) -> list[str]:
    """--ecs is always included: this command is only ever used for the
    ECS loader task, never local development - the same convention
    build_award_attachment_ecs_overrides.py's own command construction
    uses.

    Invoked via the unified module CLI (`python -m archive_etl award`),
    never a bare script filename - an ECS containerOverrides `command`
    replaces the container's CMD entirely, with no shell and no `uv run`
    wrapper to fall back on, so element 0 must be a real executable
    already on the container's PATH (`python` - see
    etl/Dockerfile.loader)."""
    command = ["python", "-m", "archive_etl", "award", "--ecs"]

    if migrate_only:
        command.append("--migrate-only")
    if load_award_id is not None:
        command.extend(["--load-award-id", str(load_award_id)])
    if create_batch is not None:
        command.extend(["--create-batch", str(create_batch)])
    if load_batch is not None:
        command.extend(["--load-batch", str(load_batch)])
    if show_batch is not None:
        command.extend(["--show-batch", str(show_batch)])
    if dry_run:
        command.append("--dry-run")

    return command


def build_environment_overrides(
    *,
    postgres_secret_id: str | None = None,
    oracle_secret_id: str | None = None,
    postgres_host: str | None = None,
    postgres_port: str | None = None,
    postgres_db: str | None = None,
    aws_region: str | None = None,
) -> list[dict[str, str]]:
    """Non-secret configuration only. POSTGRES_SECRET_ID/ORACLE_SECRET_ID
    are Secrets Manager *identifiers* (ARN or name), not credentials.
    Nothing that resolves to a password, a DSN, or a secret's JSON
    content is ever accepted or emitted here."""
    mapping = {
        "POSTGRES_SECRET_ID": postgres_secret_id,
        "ORACLE_SECRET_ID": oracle_secret_id,
        "POSTGRES_HOST": postgres_host,
        "POSTGRES_PORT": postgres_port,
        "POSTGRES_DB": postgres_db,
        "AWS_REGION": aws_region,
    }
    return [
        {"name": name, "value": value}
        for name, value in mapping.items()
        if value
    ]


def build_run_task_overrides(
    *,
    migrate_only: bool = False,
    load_award_id: int | None = None,
    create_batch: int | None = None,
    load_batch: int | None = None,
    show_batch: int | None = None,
    dry_run: bool = False,
    postgres_secret_id: str | None = None,
    oracle_secret_id: str | None = None,
    postgres_host: str | None = None,
    postgres_port: str | None = None,
    postgres_db: str | None = None,
    aws_region: str | None = None,
) -> dict:
    container_override: dict[str, object] = {
        "name": CONTAINER_NAME,
        "command": build_container_command(
            migrate_only=migrate_only,
            load_award_id=load_award_id,
            create_batch=create_batch,
            load_batch=load_batch,
            show_batch=show_batch,
            dry_run=dry_run,
        ),
    }

    environment = build_environment_overrides(
        postgres_secret_id=postgres_secret_id,
        oracle_secret_id=oracle_secret_id,
        postgres_host=postgres_host,
        postgres_port=postgres_port,
        postgres_db=postgres_db,
        aws_region=aws_region,
    )
    if environment:
        container_override["environment"] = environment

    return {"containerOverrides": [container_override]}


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--migrate-only", action="store_true")
    parser.add_argument("--load-award-id", type=int, default=None)
    parser.add_argument("--create-batch", type=int, default=None)
    parser.add_argument("--load-batch", type=int, default=None)
    parser.add_argument("--show-batch", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--postgres-secret-id", type=str, default=None)
    parser.add_argument("--oracle-secret-id", type=str, default=None)
    parser.add_argument("--postgres-host", type=str, default=None)
    parser.add_argument("--postgres-port", type=str, default=None)
    parser.add_argument("--postgres-db", type=str, default=None)
    parser.add_argument("--aws-region", type=str, default=None)
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_args()
    overrides = build_run_task_overrides(
        migrate_only=args.migrate_only,
        load_award_id=args.load_award_id,
        create_batch=args.create_batch,
        load_batch=args.load_batch,
        show_batch=args.show_batch,
        dry_run=args.dry_run,
        postgres_secret_id=args.postgres_secret_id,
        oracle_secret_id=args.oracle_secret_id,
        postgres_host=args.postgres_host,
        postgres_port=args.postgres_port,
        postgres_db=args.postgres_db,
        aws_region=args.aws_region,
    )
    print(json.dumps(overrides))


if __name__ == "__main__":
    main()
