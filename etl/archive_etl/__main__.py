"""Unified CLI entrypoint for the Research Archive ETL.

    uv run python -m archive_etl <domain> [--source oracle|csv] [--limit N] [--csv-dir PATH]
    uv run python -m archive_etl check

This is a thin dispatcher over the existing per-domain loader scripts and
the existing connectivity-check scripts under scripts/ - it does not
reimplement any loading, validation, or connectivity-check logic, only
translates one consistent command shape into the arguments/functions those
scripts already provide. Each domain script remains independently runnable
exactly as before (e.g. `uv run python load_awards_from_csv.py --oracle`).
"""

from __future__ import annotations

import argparse
import importlib
import sys

DOMAIN_MODULES: dict[str, str] = {
    "award": "load_awards_from_csv",
    "negotiation": "load_negotiations_from_csv",
    "subaward": "load_subawards_from_csv",
    "proposal": "load_proposals_from_csv",
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m archive_etl",
        description=(
            "Unified entrypoint for the Research Archive ETL loaders. "
            "Each domain is also independently runnable as its own script "
            "(e.g. `uv run python load_awards_from_csv.py --oracle`); this "
            "wraps the same scripts under one consistent command shape."
        ),
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser(
        "check",
        help=(
            "Validate Oracle and PostgreSQL connectivity (never prints "
            "secrets) without loading anything."
        ),
    )

    for domain in DOMAIN_MODULES:
        domain_parser = subparsers.add_parser(
            domain,
            help=f"Load {domain.capitalize()} data.",
        )
        domain_parser.add_argument(
            "--source",
            choices=["oracle", "csv"],
            default=None,
            help=(
                "Override SOURCE_MODE for this run. Defaults to the "
                "SOURCE_MODE environment variable (itself defaulting to "
                "'oracle') - see the underlying script's own --oracle/--csv "
                "flags, which this forwards to."
            ),
        )
        domain_parser.add_argument(
            "--csv-dir",
            default=None,
            help="Forwarded to the underlying loader's --csv-dir.",
        )
        domain_parser.add_argument(
            "--limit",
            type=int,
            default=None,
            help="Forwarded to the underlying loader's --limit.",
        )

    return parser


def _run_domain(domain: str, args: argparse.Namespace) -> int:
    module = importlib.import_module(DOMAIN_MODULES[domain])

    forwarded: list[str] = []
    if args.source == "oracle":
        forwarded.append("--oracle")
    elif args.source == "csv":
        forwarded.append("--csv")
    if args.csv_dir is not None:
        forwarded.extend(["--csv-dir", args.csv_dir])
    if args.limit is not None:
        forwarded.extend(["--limit", str(args.limit)])

    # Rewrite sys.argv rather than changing each loader's main()/parse_args()
    # signature, so every domain script stays exactly as it is today (and
    # keeps working unchanged when run directly, not through this CLI).
    original_argv = sys.argv
    sys.argv = [f"{DOMAIN_MODULES[domain]}.py", *forwarded]
    try:
        module.main()
    finally:
        sys.argv = original_argv
    return 0


def _run_check() -> int:
    from scripts.test_oracle_connection import main as check_oracle
    from scripts.test_postgres_connection import main as check_postgres

    print("=== Oracle connectivity ===")
    oracle_result = check_oracle()
    print()
    print("=== PostgreSQL connectivity ===")
    postgres_result = check_postgres()

    if oracle_result != 0 or postgres_result != 0:
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.command == "check":
        return _run_check()
    return _run_domain(args.command, args)


if __name__ == "__main__":
    raise SystemExit(main())
