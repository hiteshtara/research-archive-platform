"""Fetch Subaward attachment metadata from Oracle into the CSV shape
etl/archive_etl/attachments/plugins/subaward.py already expects.

Unlike Negotiation, oracle/subaward/export_subaward_attachments.sql
already existed - this script is only a bridge so a targeted (family-
scoped) attachment load doesn't need a manual SQL*Plus export step and
doesn't need to reach for the plugin's own --subaward-id filter (which
only accepts a single physical subaward_id, not a whole SUBAWARD_CODE
family across every version). Filtering by --subaward-code here instead
produces a CSV already scoped to every version in the family, so the
plugin can run with no --subaward-id filter at all. Never touches S3 or
Postgres - that remains archive_attachments.py's job, unchanged.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import boto3

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.pipeline.sources import OracleDataSource


def _resolve_project_root() -> Path:
    """Same local-checkout-vs-ECS-container resolution as
    load_subawards_from_csv.py's own _resolve_project_root()."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "oracle").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()
EXPORT_SQL = (
    PROJECT_ROOT / "oracle" / "subaward" / "export_subaward_attachments.sql"
)

REQUIRED_COLUMNS = [
    "attachment_id",
    "subaward_id",
    "subaward_code",
    "sequence_number",
    "attachment_type_code",
    "attachment_type_description",
    "document_id",
    "file_data_id",
    "file_name",
    "mime_type",
    "document_status_code",
    "description",
    "last_update_timestamp",
    "last_update_user",
    "update_timestamp",
    "update_user",
    "ver_nbr",
    "obj_id",
]


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fetch Subaward attachment metadata from Oracle into a CSV "
            "for the generic attachment plugin, scoped to a family."
        )
    )
    parser.add_argument(
        "--subaward-code",
        action="append",
        default=None,
        help=(
            "Restrict to one Subaward family (repeatable). Omit to "
            "fetch every archived Subaward's attachment metadata."
        ),
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="Path to write the metadata CSV to.",
    )
    parser.add_argument(
        "--ecs",
        action="store_true",
        help=(
            "Resolve Oracle credentials from Secrets Manager via the "
            "ECS task's environment instead of requiring a local export."
        ),
    )
    return parser.parse_args(arguments)


def main() -> None:
    arguments = parse_args()

    if arguments.ecs:
        configure_ecs_environment(
            boto3.client("secretsmanager"),
            include_oracle=True,
        )

    source = OracleDataSource(EXPORT_SQL)

    if arguments.subaward_code:
        dataframe = source.read_filtered(
            column="subaward_code",
            values=arguments.subaward_code,
        )
    else:
        dataframe = source.read()

    missing = sorted(set(REQUIRED_COLUMNS) - set(dataframe.columns))
    if missing:
        raise RuntimeError(
            f"{EXPORT_SQL.name} is missing columns: " + ", ".join(missing)
        )

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    dataframe[REQUIRED_COLUMNS].to_csv(arguments.output, index=False)
    print(
        f"Wrote {len(dataframe)} attachment metadata rows to "
        f"{arguments.output}"
    )


if __name__ == "__main__":
    main()
