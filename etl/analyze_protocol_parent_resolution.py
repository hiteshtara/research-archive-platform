from __future__ import annotations

import argparse
import csv
import os
from pathlib import Path
from typing import Any

import oracledb


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SQL_DIRECTORY = (
    PROJECT_ROOT
    / "oracle"
    / "protocol"
    / "parent_resolution"
)
SUMMARY_SQL = (
    SQL_DIRECTORY / "protocol_child_parent_summary.sql"
)
EXAMPLES_SQL = (
    SQL_DIRECTORY / "protocol_child_parent_examples.sql"
)


def read_sql(path: Path) -> str:
    sql = path.read_text(encoding="utf-8").strip()
    if sql.endswith(";"):
        return sql[:-1].rstrip()
    return sql


def execute_query(
    connection: oracledb.Connection,
    path: Path,
) -> tuple[list[str], list[tuple[Any, ...]]]:
    with connection.cursor() as cursor:
        cursor.execute(read_sql(path))
        columns = [
            str(column[0]).lower()
            for column in cursor.description
        ]
        return columns, cursor.fetchall()


def print_table(
    title: str,
    columns: list[str],
    rows: list[tuple[Any, ...]],
) -> None:
    print(f"\n{title}")
    if not rows:
        print("(no rows)")
        return

    rendered = [
        ["" if value is None else str(value) for value in row]
        for row in rows
    ]
    widths = [
        max(
            len(columns[index]),
            *(len(row[index]) for row in rendered),
        )
        for index in range(len(columns))
    ]
    print(
        " | ".join(
            column.ljust(widths[index])
            for index, column in enumerate(columns)
        )
    )
    print("-+-".join("-" * width for width in widths))
    for row in rendered:
        print(
            " | ".join(
                value.ljust(widths[index])
                for index, value in enumerate(row)
            )
        )


def write_csv(
    path: Path,
    columns: list[str],
    rows: list[tuple[Any, ...]],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.writer(output)
        writer.writerow(columns)
        writer.writerows(rows)
    print(f"\nSummary CSV written to: {path}")


def required_environment() -> dict[str, str]:
    names = ["ORACLE_USER", "ORACLE_PASSWORD", "ORACLE_DSN"]
    missing = [name for name in names if not os.getenv(name)]
    if missing:
        raise RuntimeError(
            "Missing Oracle environment variables: "
            + ", ".join(missing)
        )
    return {name: os.environ[name] for name in names}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Analyze Protocol child parent resolution in Oracle. "
            "This runner performs SELECT queries only."
        )
    )
    parser.add_argument(
        "--output",
        type=Path,
        help="Optional CSV path for the aggregate summary.",
    )
    parser.add_argument(
        "--examples-output",
        type=Path,
        help="Optional CSV path for identifier-only mismatch examples.",
    )
    return parser.parse_args()


def main() -> None:
    arguments = parse_args()
    environment = required_environment()

    with oracledb.connect(
        user=environment["ORACLE_USER"],
        password=environment["ORACLE_PASSWORD"],
        dsn=environment["ORACLE_DSN"],
    ) as connection:
        summary_columns, summary_rows = execute_query(
            connection,
            SUMMARY_SQL,
        )
        example_columns, example_rows = execute_query(
            connection,
            EXAMPLES_SQL,
        )

    print_table(
        "Protocol child parent-resolution summary",
        summary_columns,
        summary_rows,
    )
    print_table(
        "Protocol child parent-resolution mismatch examples",
        example_columns,
        example_rows,
    )

    if arguments.output:
        write_csv(
            arguments.output,
            summary_columns,
            summary_rows,
        )
    if arguments.examples_output:
        write_csv(
            arguments.examples_output,
            example_columns,
            example_rows,
        )


if __name__ == "__main__":
    main()
