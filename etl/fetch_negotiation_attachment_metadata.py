"""Fetch Negotiation attachment metadata from Oracle into the CSV shape
etl/archive_etl/attachments/plugins/negotiation.py already expects.

The generic attachment plugin framework (archive_etl/attachments/runner.py)
reads attachment metadata from a CSV file rather than Oracle directly - by
design, since some modules' metadata csv historically came from a manual
SQL*Plus export. Negotiation never had that manual step automated, so this
script is the automated equivalent: it runs
oracle/negotiation/export_negotiation_attachments.sql (optionally filtered
to specific --negotiation-id values via OracleDataSource.read_filtered) and
writes the result to --output in exactly the column shape the plugin's
NegotiationAttachmentPlugin.iter_records requires. It never touches S3 or
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
    load_negotiations_from_csv.py's own _resolve_project_root()."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "oracle").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()
EXPORT_SQL = (
    PROJECT_ROOT / "oracle" / "negotiation"
    / "export_negotiation_attachments.sql"
)

REQUIRED_COLUMNS = [
    "attachment_id",
    "activity_id",
    "negotiation_id",
    "document_number",
    "associated_document_id",
    "file_id",
    "file_name",
    "content_type",
    "description",
    "restricted",
    "update_timestamp",
]


def parse_args(
    arguments: list[str] | None = None,
) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fetch Negotiation attachment metadata from Oracle into a "
            "CSV for the generic attachment plugin."
        )
    )
    parser.add_argument(
        "--negotiation-id",
        type=int,
        action="append",
        default=None,
        help=(
            "Restrict to one Negotiation ID. Repeatable. Omit to fetch "
            "every archived Negotiation's attachment metadata."
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

    if arguments.negotiation_id:
        dataframe = source.read_filtered(
            column="negotiation_id",
            values=arguments.negotiation_id,
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
