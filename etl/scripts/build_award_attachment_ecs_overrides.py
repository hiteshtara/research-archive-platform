"""Build the `aws ecs run-task --overrides` JSON for a one-off Award
Attachment loader task run.

Translates CLI pass-through flags (--file-id, --limit, --retry-failed,
--dry-run, --upload) into the container command override for the
"loader" container in the research-archive-platform-dev-loader task
family (see terraform/modules/ecs/main.tf - not modified here). Kept as
a small, pure, independently testable function - the actual AWS
orchestration (build/push image, register task revision, run-task, wait,
stream logs) lives in scripts/run-award-attachment-loader.sh, which
shells out to this module only for this one translation step.

Usage:
    uv run python scripts/build_award_attachment_ecs_overrides.py \
        --upload --limit 10 --bucket my-bucket
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
    bucket: str | None = None,
    prefix: str | None = None,
) -> list[str]:
    """--ecs is always included: this command is only ever used for the
    ECS loader task, never local development."""
    command = ["load_award_attachments.py", "--ecs"]

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


def build_run_task_overrides(
    *,
    file_id: int | None = None,
    limit: int | None = None,
    retry_failed: bool = False,
    dry_run: bool = False,
    upload: bool = False,
    bucket: str | None = None,
    prefix: str | None = None,
) -> dict:
    return {
        "containerOverrides": [
            {
                "name": CONTAINER_NAME,
                "command": build_container_command(
                    file_id=file_id,
                    limit=limit,
                    retry_failed=retry_failed,
                    dry_run=dry_run,
                    upload=upload,
                    bucket=bucket,
                    prefix=prefix,
                ),
            }
        ]
    }


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file-id", type=int, default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--upload", action="store_true")
    parser.add_argument("--bucket", type=str, default=None)
    parser.add_argument("--prefix", type=str, default=None)
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_args()
    overrides = build_run_task_overrides(
        file_id=args.file_id,
        limit=args.limit,
        retry_failed=args.retry_failed,
        dry_run=args.dry_run,
        upload=args.upload,
        bucket=args.bucket,
        prefix=args.prefix,
    )
    print(json.dumps(overrides))


if __name__ == "__main__":
    main()
