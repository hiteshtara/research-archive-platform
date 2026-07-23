"""Backward-compatible Subaward attachment archive entry point."""

from __future__ import annotations

import sys

from archive_etl.attachments.runner import run


def main() -> None:
    run(["--module", "subaward", *sys.argv[1:]])


if __name__ == "__main__":
    main()
