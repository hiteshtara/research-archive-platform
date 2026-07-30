"""List FAILED loads and show the command to rerun each one.

Each loader is idempotent (INSERT ... ON CONFLICT DO UPDATE, or a
TRUNCATE-then-reload for the legacy CSV loaders), so resuming a failed load
is just a matter of running the same command again once the underlying
problem (bad credentials, source data issue, network blip) is fixed. This
script does not rerun anything itself - it lists what failed and prints the
exact command an operator should run.

Usage:
    uv run python scripts/resume_failed_load.py
    uv run python scripts/resume_failed_load.py --domain AWARD
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from sqlalchemy import text  # noqa: E402

from archive_etl.config.settings import ConfigurationError  # noqa: E402
from archive_etl.upload.postgres import create_postgres_engine  # noqa: E402

# Maps the `domain` value stored in archive.load_run to the command that
# produced it, so a failed load can be pointed back at the right script.
RERUN_COMMAND_BY_DOMAIN = {
    "AWARD": "uv run python load_awards_from_csv.py",
    "NEGOTIATION": "uv run python load_negotiations_from_csv.py",
    "SUBAWARD": "uv run python load_subawards_from_csv.py",
    "PROPOSAL": "uv run python load_proposals_from_csv.py",
    "IRB": "uv run python load_from_s3.py",
    "IRB_COMPOSITE": "uv run python load_composite_from_s3.py",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domain", help="Only show failed loads for this domain.")
    parser.add_argument("--limit", type=int, default=20, help="Max rows to show (default: 20).")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    try:
        engine = create_postgres_engine()
    except ConfigurationError as error:
        print(f"Configuration error: {error}", file=sys.stderr)
        return 1

    query = "SELECT * FROM archive.load_run WHERE status = 'FAILED'"
    params: dict[str, object] = {"limit": args.limit}
    if args.domain:
        query += " AND domain = :domain"
        params["domain"] = args.domain
    query += " ORDER BY started_at DESC LIMIT :limit"

    with engine.connect() as connection:
        rows = connection.execute(text(query), params).mappings().all()

    if not rows:
        print("No failed loads found.")
        return 0

    for row in rows:
        command = RERUN_COMMAND_BY_DOMAIN.get(
            row["domain"], "(no known rerun command for this domain)"
        )
        print(f"load_id={row['load_id']} domain={row['domain']} started_at={row['started_at']}")
        print(f"  error_message={row['error_message']}")
        print(f"  rerun: {command}")
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
