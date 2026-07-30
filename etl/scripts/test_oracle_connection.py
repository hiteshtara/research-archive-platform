"""Verify connectivity to the Kuali Oracle source database.

Reads ORACLE_USER / ORACLE_PASSWORD / ORACLE_DSN from the environment,
opens a connection (python-oracledb thin mode by default), and runs a
trivial query. Intended to be run before a real extraction to confirm
credentials and network access (e.g. BU VPN) are working.

Usage:
    uv run python scripts/test_oracle_connection.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import oracledb  # noqa: E402

from archive_etl.config.settings import (  # noqa: E402
    ConfigurationError,
    require_oracle_environment,
)


def main() -> int:
    try:
        credentials = require_oracle_environment()
    except ConfigurationError as error:
        print(f"Configuration error: {error}", file=sys.stderr)
        return 1

    print(
        f"Connecting to Oracle DSN '{credentials['ORACLE_DSN']}' "
        f"as '{credentials['ORACLE_USER']}'..."
    )

    try:
        with oracledb.connect(
            user=credentials["ORACLE_USER"],
            password=credentials["ORACLE_PASSWORD"],
            dsn=credentials["ORACLE_DSN"],
        ) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT 1 FROM DUAL")
                cursor.fetchone()
            server_version = connection.version
    except oracledb.Error as error:
        print(f"Connection failed: {error}", file=sys.stderr)
        return 1

    print("Connected successfully.")
    print(f"Oracle server version: {server_version}")
    print(f"oracledb client mode: {'thin' if oracledb.is_thin_mode() else 'thick'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
