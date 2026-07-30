"""Print the archive.load_run row-count summary for one or more loads.

Reports rows_read / rows_staged / rows_loaded / rows_rejected alongside the
final status, and flags any load where the numbers don't add up (rows_read
should equal rows_loaded + rows_rejected once a load has finished).

Usage:
    uv run python scripts/reconcile_load.py --load-id 42
    uv run python scripts/reconcile_load.py --domain AWARD --limit 5
    uv run python scripts/reconcile_load.py --latest
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import text  # noqa: E402

from archive_etl.config.settings import ConfigurationError  # noqa: E402
from archive_etl.upload.postgres import create_postgres_engine  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--load-id", type=int, help="Specific load_id to reconcile.")
    group.add_argument("--domain", help="Report the most recent loads for this domain.")
    group.add_argument(
        "--latest",
        action="store_true",
        help="Report the single most recent load, any domain.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=10,
        help="Max rows to show for --domain (default: 10).",
    )
    return parser.parse_args()


def print_row(row: dict) -> None:
    read = row["rows_read"] or 0
    loaded = row["rows_loaded"] or 0
    rejected = row["rows_rejected"] or 0
    mismatch = row["status"] == "LOADED" and read != loaded + rejected

    print(f"load_id={row['load_id']} domain={row['domain']} status={row['status']}")
    print(f"  started_at={row['started_at']} completed_at={row['completed_at']}")
    print(
        f"  rows_read={read} rows_staged={row['rows_staged']} "
        f"rows_loaded={loaded} rows_rejected={rejected}"
    )
    if mismatch:
        print(
            f"  MISMATCH: rows_read ({read}) != "
            f"rows_loaded + rows_rejected ({loaded + rejected})"
        )
    if row["error_message"]:
        print(f"  error_message={row['error_message']}")
    print()


def main() -> int:
    args = parse_args()

    try:
        engine = create_postgres_engine()
    except ConfigurationError as error:
        print(f"Configuration error: {error}", file=sys.stderr)
        return 1

    if args.load_id is not None:
        query = text(
            "SELECT * FROM archive.load_run WHERE load_id = :load_id"
        )
        params = {"load_id": args.load_id}
    elif args.domain:
        query = text(
            "SELECT * FROM archive.load_run WHERE domain = :domain "
            "ORDER BY started_at DESC LIMIT :limit"
        )
        params = {"domain": args.domain, "limit": args.limit}
    else:
        query = text("SELECT * FROM archive.load_run ORDER BY started_at DESC LIMIT 1")
        params = {}

    with engine.connect() as connection:
        rows = connection.execute(query, params).mappings().all()

    if not rows:
        print("No matching load_run rows found.")
        return 1

    for row in rows:
        print_row(dict(row))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
