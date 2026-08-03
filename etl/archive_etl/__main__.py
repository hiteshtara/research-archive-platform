"""Unified CLI entrypoint for the Research Archive ETL.

    uv run python -m archive_etl <domain> [--limit N]
    uv run python -m archive_etl check [<domain>]

Oracle is the only supported source of structured data - there is no
--source/--csv selection. This is a thin dispatcher over the existing
per-domain loader scripts and the existing connectivity-check scripts under
scripts/ - it does not reimplement any loading, validation, or
connectivity-check logic, only translates one consistent command shape into
the arguments/functions those scripts already provide. Each domain script
remains independently runnable exactly as before (e.g. `uv run python
load_awards_from_csv.py --limit 10`).

`check <domain>` runs the standard Oracle/Postgres connectivity check, then
additionally exercises that domain's loader with a small `--limit`, so
Oracle extraction and parent-resolution/transform logic get a read-only
smoke test without a real load.
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
    "award-attachment": "load_award_attachments",
    "protocol": "load_protocols",
}

# Domain-specific extra CLI flags forwarded verbatim to the underlying
# loader, beyond the universal --limit every domain already gets.
# --limit alone already implies "no database write" for every domain
# that has nothing else listed here (see each domain's --limit help
# text). `default=None` on a non-store_true entry means "don't forward
# it at all unless explicitly given" - the underlying loader's own
# default then applies.
_EXTRA_DOMAIN_ARGUMENTS: dict[str, list[tuple[str, dict]]] = {
    "award": [
        (
            "--dry-run",
            {
                "action": "store_true",
                "help": "Forwarded to the underlying loader's --dry-run.",
            },
        ),
        (
            "--load-award-id",
            {
                "type": int,
                "default": None,
                "metavar": "AWARD_ID",
                "help": "Forwarded to the underlying loader's --load-award-id.",
            },
        ),
        (
            "--create-batch",
            {
                "type": int,
                "default": None,
                "metavar": "N",
                "help": "Forwarded to the underlying loader's --create-batch.",
            },
        ),
        (
            "--validation-overlap",
            {
                "action": "store_true",
                "help": (
                    "Forwarded to the underlying loader's "
                    "--validation-overlap."
                ),
            },
        ),
        (
            "--load-batch",
            {
                "type": int,
                "default": None,
                "metavar": "BATCH_ID",
                "help": "Forwarded to the underlying loader's --load-batch.",
            },
        ),
        (
            "--show-batch",
            {
                "type": int,
                "default": None,
                "metavar": "BATCH_ID",
                "help": "Forwarded to the underlying loader's --show-batch.",
            },
        ),
        (
            "--ecs",
            {
                "action": "store_true",
                "help": "Forwarded to the underlying loader's --ecs.",
            },
        ),
        (
            "--migrate-only",
            {
                "action": "store_true",
                "help": "Forwarded to the underlying loader's --migrate-only.",
            },
        ),
        (
            "--diff-award-versions",
            {
                "type": str,
                "default": None,
                "metavar": "AWARD_NUMBER",
                "help": (
                    "Forwarded to the underlying loader's "
                    "--diff-award-versions."
                ),
            },
        ),
    ],
    "award-attachment": [
        (
            "--dry-run",
            {
                "action": "store_true",
                "help": "Forwarded to the underlying loader's --dry-run.",
            },
        ),
        (
            "--upload",
            {
                "action": "store_true",
                "help": "Forwarded to the underlying loader's --upload.",
            },
        ),
        (
            "--bucket",
            {
                "type": str,
                "default": None,
                "help": "Forwarded to the underlying loader's --bucket.",
            },
        ),
        (
            "--prefix",
            {
                "type": str,
                "default": None,
                "help": "Forwarded to the underlying loader's --prefix.",
            },
        ),
        (
            "--file-id",
            {
                "type": int,
                "default": None,
                "help": "Forwarded to the underlying loader's --file-id.",
            },
        ),
        (
            "--load-file-id",
            {
                "type": int,
                "default": None,
                "help": "Forwarded to the underlying loader's --load-file-id.",
            },
        ),
        (
            "--retry-failed",
            {
                "action": "store_true",
                "help": (
                    "Forwarded to the underlying loader's --retry-failed."
                ),
            },
        ),
        (
            "--multipart-threshold-bytes",
            {
                "type": int,
                "default": None,
                "help": (
                    "Forwarded to the underlying loader's "
                    "--multipart-threshold-bytes."
                ),
            },
        ),
        (
            "--ecs",
            {
                "action": "store_true",
                "help": "Forwarded to the underlying loader's --ecs.",
            },
        ),
        (
            "--migrate-only",
            {
                "action": "store_true",
                "help": "Forwarded to the underlying loader's --migrate-only.",
            },
        ),
        (
            "--show-upload-status",
            {
                "action": "store_true",
                "help": (
                    "Forwarded to the underlying loader's "
                    "--show-upload-status."
                ),
            },
        ),
        (
            "--create-batch",
            {
                "type": int,
                "default": None,
                "metavar": "N",
                "help": "Forwarded to the underlying loader's --create-batch.",
            },
        ),
        (
            "--include-already-uploaded",
            {
                "action": "store_true",
                "help": (
                    "Forwarded to the underlying loader's "
                    "--include-already-uploaded."
                ),
            },
        ),
        (
            "--load-batch",
            {
                "type": int,
                "default": None,
                "metavar": "BATCH_ID",
                "help": "Forwarded to the underlying loader's --load-batch.",
            },
        ),
        (
            "--show-batch",
            {
                "type": int,
                "default": None,
                "metavar": "BATCH_ID",
                "help": "Forwarded to the underlying loader's --show-batch.",
            },
        ),
        (
            "--batch-id",
            {
                "type": int,
                "default": None,
                "metavar": "BATCH_ID",
                "help": "Forwarded to the underlying loader's --batch-id.",
            },
        ),
        (
            "--list-awards-with-attachments",
            {
                "action": "store_true",
                "help": (
                    "Forwarded to the underlying loader's "
                    "--list-awards-with-attachments."
                ),
            },
        ),
        (
            "--diff-award-attachments",
            {
                "type": int,
                "default": None,
                "metavar": "AWARD_ID",
                "help": (
                    "Forwarded to the underlying loader's "
                    "--diff-award-attachments."
                ),
            },
        ),
        (
            "--load-file-ids",
            {
                "type": str,
                "default": None,
                "metavar": "FILE_ID,FILE_ID,...",
                "help": (
                    "Forwarded to the underlying loader's "
                    "--load-file-ids."
                ),
            },
        ),
    ],
}

_CHECK_DOMAIN_LIMIT = 5


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m archive_etl",
        description=(
            "Unified entrypoint for the Research Archive ETL loaders. "
            "Oracle is the only supported source - there is no source "
            "selection. Each domain is also independently runnable as its "
            "own script (e.g. `uv run python load_awards_from_csv.py "
            "--limit 10`); this wraps the same scripts under one consistent "
            "command shape."
        ),
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_parser = subparsers.add_parser(
        "check",
        help=(
            "Validate Oracle and PostgreSQL connectivity (never prints "
            "secrets) without loading anything."
        ),
    )
    check_parser.add_argument(
        "domain",
        nargs="?",
        choices=sorted(DOMAIN_MODULES),
        default=None,
        help=(
            "Optional. Also run this domain's loader with a small --limit "
            "as a read-only smoke test of Oracle extraction and transform "
            "logic, on top of the standard connectivity check."
        ),
    )

    for domain in DOMAIN_MODULES:
        domain_parser = subparsers.add_parser(
            domain,
            help=f"Load {domain.replace('-', ' ').capitalize()} data from Oracle.",
        )
        domain_parser.add_argument(
            "--limit",
            type=int,
            default=None,
            help="Forwarded to the underlying loader's --limit.",
        )
        for flag, kwargs in _EXTRA_DOMAIN_ARGUMENTS.get(domain, []):
            domain_parser.add_argument(flag, **kwargs)

    return parser


def _run_domain(domain: str, args: argparse.Namespace) -> int:
    module = importlib.import_module(DOMAIN_MODULES[domain])

    forwarded: list[str] = []
    if args.limit is not None:
        forwarded.extend(["--limit", str(args.limit)])

    for flag, kwargs in _EXTRA_DOMAIN_ARGUMENTS.get(domain, []):
        dest = flag.lstrip("-").replace("-", "_")
        value = getattr(args, dest, None)
        if kwargs.get("action") == "store_true":
            if value:
                forwarded.append(flag)
        elif value is not None:
            forwarded.extend([flag, str(value)])

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


def _run_check(domain: str | None) -> int:
    from scripts.test_oracle_connection import main as check_oracle
    from scripts.test_postgres_connection import main as check_postgres

    print("=== Oracle connectivity ===")
    oracle_result = check_oracle()
    print()
    print("=== PostgreSQL connectivity ===")
    postgres_result = check_postgres()

    if oracle_result != 0 or postgres_result != 0:
        return 1

    if domain is not None:
        print()
        print(f"=== {domain.capitalize()} read-only smoke test (--limit {_CHECK_DOMAIN_LIMIT}) ===")
        module = importlib.import_module(DOMAIN_MODULES[domain])
        original_argv = sys.argv
        sys.argv = [
            f"{DOMAIN_MODULES[domain]}.py",
            "--limit",
            str(_CHECK_DOMAIN_LIMIT),
        ]
        try:
            module.main()
        finally:
            sys.argv = original_argv

    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.command == "check":
        return _run_check(args.domain)
    return _run_domain(args.command, args)


if __name__ == "__main__":
    raise SystemExit(main())
