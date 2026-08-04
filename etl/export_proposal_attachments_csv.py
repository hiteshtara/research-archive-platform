"""Export archive.proposal_attachment metadata rows to a CSV the
generic attachment binary pipeline (archive_etl.attachments.runner,
--module proposal) can read.

Unlike Subaward/Award's own attachment CSVs (a manual SQL*Plus export
run by a human on a BU VPN machine), this export reads directly from
archive.proposal_attachment - already loaded, Oracle-accurate, by
load_proposals_from_csv.py (see
docs/kuali-business-rules/InstitutionalProposal.md). No second Oracle
round trip, no manual step, and the binary pipeline can never drift
from what the metadata loader actually recorded.

Usage:
    python export_proposal_attachments_csv.py --output /path/to.csv [--proposal-id ID]
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from archive_etl.upload.postgres import create_postgres_engine

COLUMNS = [
    "proposal_attachment_id",
    "proposal_id",
    "proposal_number",
    "sequence_number",
    "attachment_number",
    "attachment_title",
    "file_name",
    "file_data_id",
    "content_type",
    "comments",
    "document_status_code",
    "source_update_timestamp",
]


def parse_args(arguments: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--proposal-id", type=int, default=None)
    return parser.parse_args(arguments)


def main() -> None:
    args = parse_args()

    engine = create_postgres_engine()
    where_clause = (
        "WHERE proposal_id = :proposal_id" if args.proposal_id else ""
    )

    with engine.connect() as connection:
        from sqlalchemy import text

        result = connection.execute(
            text(
                f"""
                SELECT {", ".join(COLUMNS)}
                FROM archive.proposal_attachment
                {where_clause}
                ORDER BY proposal_number, sequence_number, attachment_number
                """
            ),
            {"proposal_id": args.proposal_id} if args.proposal_id else {},
        )
        rows = result.mappings().all()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=COLUMNS)
        writer.writeheader()
        for row in rows:
            writer.writerow(dict(row))

    print(f"Wrote {len(rows)} row(s) to {args.output}")


if __name__ == "__main__":
    main()
