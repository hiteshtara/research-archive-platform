"""Local benchmark for the Award incremental-load Oracle read-path
optimization: bind-variable WHERE pushdown (OracleDataSource.
read_filtered) vs full-table-scan + client-side pandas filter (the
approach every Award bounded reader used before this change).

There is no real Oracle or AWS/RDS access available to benchmark this
against directly, so this builds a synthetic, in-memory "Oracle"
dataset at a realistic scale (tens of thousands of Award families) and
a fake oracledb-shaped connection with a small artificial per-fetch
round-trip latency standing in for a real Oracle instance over the
network. Two things are measured:

1. Read-layer microbenchmark: for the Award version source specifically,
   OracleDataSource.read_batches() + the OLD client-side filter (
   reproduced here only for comparison - it no longer exists anywhere
   in production code) vs OracleDataSource.read_filtered() (the current
   production method), resolving 1, 100, and 1000 award_ids' worth of
   award_numbers.
2. End-to-end macrobenchmark: the real, current
   _run_load_award_id/_run_load_award_batch (unmodified, imported
   directly) against a real local throwaway PostgreSQL database, for
   1, 100, and 1000 award_ids, using the same fake Oracle connection
   for every one of the 13 Award extraction sources.

Usage:
    uv run python scripts/benchmark_award_load_performance.py
"""

from __future__ import annotations

import random
import re
import sys
import time
import uuid
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pandas as pd  # noqa: E402
from sqlalchemy import create_engine, text  # noqa: E402

import load_awards_from_csv as award_loader  # noqa: E402
from archive_etl.pipeline.sources import OracleDataSource  # noqa: E402
from archive_etl.upload.migrations import apply_migrations  # noqa: E402

TOTAL_FAMILIES = 20_000
FETCH_LATENCY_SECONDS = 0.002  # simulated per-fetchmany Oracle round trip
REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO_ROOT / "database" / "migrations"

POSTGRES_HOST = "localhost"
POSTGRES_PORT = "5432"
POSTGRES_USER = __import__("getpass").getuser()

_COMMENT_LINE = re.compile(r"^\s*--")
_SQLPLUS_SET_LINE = re.compile(r"^\s*SET\s+\w+", re.IGNORECASE)
_ORACLE_ENVIRON = {
    "ORACLE_USER": "benchmark",
    "ORACLE_PASSWORD": "benchmark",
    "ORACLE_DSN": "benchmark",
}


def _split_top_level_commas(text: str) -> list[str]:
    """Split a SELECT column list on commas, but only at paren-depth 0 -
    a naive str.split(",") breaks on expressions like
    NVL(aai.ANTICIPATED_TOTAL_DIRECT, 0) (02_award_amounts.sql), whose
    own internal comma isn't a column separator."""
    parts = []
    depth = 0
    current: list[str] = []
    for char in text:
        if char == "(":
            depth += 1
            current.append(char)
        elif char == ")":
            depth -= 1
            current.append(char)
        elif char == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(char)
    parts.append("".join(current))
    return parts


def _select_columns(sql_path: Path) -> list[str]:
    """Parse a real extraction SQL file's SELECT list into the exact
    (uppercase) column names Oracle would report via cursor.description
    - same logic as the SQL/transform contract test in
    tests/test_award_incremental_upsert.py, reimplemented here
    independently to keep this script self-contained."""
    lines = [
        line
        for line in sql_path.read_text(encoding="utf-8").splitlines()
        if not _COMMENT_LINE.match(line) and not _SQLPLUS_SET_LINE.match(line)
    ]
    joined = "\n".join(lines)
    match = re.search(r"SELECT\s+(.*?)\s+FROM\s", joined, re.IGNORECASE | re.DOTALL)
    if match is None:
        raise AssertionError(f"could not find a SELECT ... FROM in {sql_path}")

    columns = []
    for raw_expr in _split_top_level_commas(match.group(1)):
        expr = raw_expr.strip()
        if not expr:
            continue
        as_match = re.search(r"\bAS\b\s+([A-Za-z0-9_]+)\s*$", expr, re.IGNORECASE)
        name = as_match.group(1) if as_match else expr.split(".")[-1]
        columns.append(name.upper())
    return columns


# --- Fake Oracle connection --------------------------------------------


class _FakeCursor:
    def __init__(self, dataframe: pd.DataFrame, latency_seconds: float) -> None:
        self._dataframe = dataframe
        self._latency = latency_seconds
        self.arraysize = 5000
        self.description: list[tuple[str]] = []
        self._pending_rows: list[tuple] = []

    def __enter__(self) -> _FakeCursor:
        return self

    def __exit__(self, *exc_info: object) -> None:
        return None

    def execute(self, sql: str, params: dict[str, object] | None = None) -> None:
        if params:
            match = re.search(r"WHERE\s+(\w+)\s+IN", sql, re.IGNORECASE)
            assert match is not None, f"no WHERE ... IN clause in: {sql}"
            column = match.group(1)
            values = set(params.values())
            result = self._dataframe[self._dataframe[column].isin(values)]
        else:
            result = self._dataframe
        self.description = [(column,) for column in result.columns]
        self._pending_rows = list(result.itertuples(index=False, name=None))

    def fetchmany(self, size: int) -> list[tuple]:
        time.sleep(self._latency)
        batch, self._pending_rows = (
            self._pending_rows[:size],
            self._pending_rows[size:],
        )
        return batch


class _FakeConnection:
    def __init__(self, dataframe: pd.DataFrame, latency_seconds: float) -> None:
        self._dataframe = dataframe
        self._latency = latency_seconds

    def __enter__(self) -> _FakeConnection:
        return self

    def __exit__(self, *exc_info: object) -> None:
        return None

    def cursor(self) -> _FakeCursor:
        return _FakeCursor(self._dataframe, self._latency)


def _fake_oracle_source(
    sql_path: Path, dataframe: pd.DataFrame, *, latency_seconds: float
) -> OracleDataSource:
    def _connect(user: str, password: str, dsn: str) -> _FakeConnection:
        return _FakeConnection(dataframe, latency_seconds)

    return OracleDataSource(
        sql_path, connect=_connect, environ=_ORACLE_ENVIRON, fetch_size=5000
    )


# --- Synthetic dataset ---------------------------------------------------


def _fill_generic_row(
    columns: list[str],
    *,
    own_id: int,
    award_id: int,
    award_number: str,
) -> dict[str, object]:
    row: dict[str, object] = {}
    for column in columns:
        if column == "AWARD_ID":
            row[column] = award_id
        elif column == "AWARD_NUMBER":
            row[column] = award_number
        elif column == "SEQUENCE_NUMBER":
            row[column] = 0
        elif column == "PERSON_ID":
            # A real VARCHAR business key, never numeric-converted by
            # any prepare_* function - unlike every other *_ID column
            # below, which are all genuinely numeric (surrogate PKs or
            # ROLODEX_ID/CUSTOM_ATTRIBUTE_ID-style codes).
            row[column] = f"P{own_id}"
        elif column.endswith("_ID"):
            # The table's own surrogate PK (whatever it's named) -
            # every child table's first *_ID column in its SELECT list
            # is its own PK per docs/architecture/AWARD_*_DESIGN.md.
            row[column] = own_id
        elif "DATE" in column or "TIMESTAMP" in column:
            row[column] = "2025-01-01"
        elif column == "VER_NBR":
            row[column] = 1
        elif column == "UPDATE_USER":
            row[column] = "benchmark"
        else:
            row[column] = f"BENCH-{own_id}"
    return row


def _build_versions(total_families: int) -> tuple[pd.DataFrame, list[int]]:
    columns = _select_columns(award_loader.VERSIONS_ORACLE_SQL)
    rng = random.Random(1234)
    rows: list[dict[str, object]] = []
    primary_award_ids: list[int] = []
    next_award_id = 1

    for family_index in range(total_families):
        award_number = f"A-{family_index:07d}"
        version_count = 2 if rng.random() < 0.2 else 1
        family_award_ids = []
        for sequence_number in range(version_count):
            award_id = next_award_id
            next_award_id += 1
            family_award_ids.append(award_id)
            is_current = sequence_number == version_count - 1
            row: dict[str, object] = {}
            for column in columns:
                if column == "AWARD_ID":
                    row[column] = award_id
                elif column == "AWARD_NUMBER":
                    row[column] = award_number
                elif column == "SEQUENCE_NUMBER":
                    row[column] = sequence_number
                elif column == "AWARD_SEQUENCE_STATUS":
                    row[column] = "ACTIVE"
                elif column == "TITLE":
                    row[column] = f"Synthetic Award {family_index}"
                elif column == "IS_CURRENT_VERSION":
                    row[column] = "Y" if is_current else "N"
                elif "DATE" in column:
                    row[column] = "2025-01-01"
                elif column == "UPDATE_TIMESTAMP":
                    row[column] = "2025-01-01 00:00:00"
                elif column == "UPDATE_USER":
                    row[column] = "benchmark"
                elif column in ("STATUS_CODE", "TRANSACTION_TYPE_CODE"):
                    row[column] = "1"
                else:
                    row[column] = None
            rows.append(row)
        primary_award_ids.append(family_award_ids[-1])

    return pd.DataFrame(rows, columns=columns), primary_award_ids


def _build_sparse_child_table(
    sql_path: Path,
    primary_award_ids: list[int],
    *,
    density: float,
    rng: random.Random,
) -> pd.DataFrame:
    columns = _select_columns(sql_path)
    rows: list[dict[str, object]] = []
    next_id = 1
    for family_index, award_id in enumerate(primary_award_ids):
        if rng.random() > density:
            continue
        award_number = f"A-{family_index:07d}"
        rows.append(
            _fill_generic_row(
                columns, own_id=next_id, award_id=award_id, award_number=award_number
            )
        )
        next_id += 1
    return pd.DataFrame(rows, columns=columns)


def build_synthetic_oracle(
    total_families: int,
) -> tuple[dict[Path, pd.DataFrame], list[int]]:
    print(f"Building synthetic Oracle dataset ({total_families:,} families)...")
    versions_df, primary_award_ids = _build_versions(total_families)
    rng = random.Random(5678)

    sources: dict[Path, pd.DataFrame] = {award_loader.VERSIONS_ORACLE_SQL: versions_df}
    for sql_path, density in (
        (award_loader.AMOUNTS_ORACLE_SQL, 1.0),
        (award_loader.PEOPLE_ORACLE_SQL, 1.0),
        (award_loader.PROPOSALS_ORACLE_SQL, 0.8),
        (award_loader.CUSTOM_DATA_ORACLE_SQL, 0.3),
        (award_loader.SPONSOR_TERMS_ORACLE_SQL, 0.3),
        (award_loader.REPORT_TERMS_ORACLE_SQL, 0.3),
        (award_loader.SPONSOR_CONTACTS_ORACLE_SQL, 0.2),
        (award_loader.UNIT_CONTACTS_ORACLE_SQL, 0.2),
    ):
        sources[sql_path] = _build_sparse_child_table(
            sql_path, primary_award_ids, density=density, rng=rng
        )

    # Grandchild tables (person_units, person_credit_splits,
    # person_unit_credit_splits, report_term_recipients) need real FK
    # integrity against a non-award_version parent - left empty here
    # rather than hand-building a second cascade of synthetic parent
    # rows; they're the smallest tables in the domain in practice too,
    # so this doesn't materially change what's being measured (the
    # read-path fix applies identically regardless of row count, and an
    # empty result is unaffected either way).
    for sql_path in (
        award_loader.PERSON_UNITS_ORACLE_SQL,
        award_loader.PERSON_CREDIT_SPLITS_ORACLE_SQL,
        award_loader.PERSON_UNIT_CREDIT_SPLITS_ORACLE_SQL,
        award_loader.REPORT_TERM_RECIPIENTS_ORACLE_SQL,
    ):
        sources[sql_path] = pd.DataFrame(columns=_select_columns(sql_path))

    for sql_path, dataframe in sources.items():
        print(f"  {sql_path.name}: {len(dataframe):,} rows")

    return sources, primary_award_ids


# --- Part 1: read-layer microbenchmark (versions source only) -----------


def _old_style_scan_and_filter(
    source: OracleDataSource, award_id: int
) -> str | None:
    """Reproduces, for comparison only, the full-table-scan +
    client-side pandas filter every bounded Award reader used before
    this optimization - no longer present anywhere in production code."""
    batches = source.read_batches()
    try:
        for batch in batches:
            if batch.empty:
                continue
            ids = pd.to_numeric(batch["award_id"], errors="coerce")
            match = batch[ids == award_id]
            if not match.empty:
                return str(match.iloc[0]["award_number"])
    finally:
        batches.close()
    return None


def benchmark_read_layer(
    versions_df: pd.DataFrame, primary_award_ids: list[int]
) -> None:
    print("\n=== Part 1: read-layer microbenchmark (award_number resolution) ===")
    print(f"{'award_ids':>10}  {'old (full scan)':>18}  {'new (bind vars)':>18}  {'speedup':>10}")

    for count in (1, 100, 1000):
        sample = primary_award_ids[:count]

        old_source = _fake_oracle_source(
            award_loader.VERSIONS_ORACLE_SQL,
            versions_df,
            latency_seconds=FETCH_LATENCY_SECONDS,
        )
        started = time.perf_counter()
        for award_id in sample:
            _old_style_scan_and_filter(old_source, award_id)
        old_elapsed = time.perf_counter() - started

        new_source = _fake_oracle_source(
            award_loader.VERSIONS_ORACLE_SQL,
            versions_df,
            latency_seconds=FETCH_LATENCY_SECONDS,
        )
        started = time.perf_counter()
        award_loader.read_award_numbers_for_award_ids(new_source, set(sample))
        new_elapsed = time.perf_counter() - started

        speedup = old_elapsed / new_elapsed if new_elapsed > 0 else float("inf")
        print(
            f"{count:>10}  {old_elapsed * 1000:>15.1f}ms  "
            f"{new_elapsed * 1000:>15.1f}ms  {speedup:>9.1f}x"
        )


# --- Part 2: end-to-end macrobenchmark -----------------------------------


def _create_throwaway_database() -> tuple[str, str]:
    db_name = f"bench_award_load_{uuid.uuid4().hex[:12]}"
    admin = create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:{POSTGRES_PORT}/postgres"
    )
    with admin.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'CREATE DATABASE "{db_name}"'))
    admin.dispose()
    url = f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:{POSTGRES_PORT}/{db_name}"
    return db_name, url


def _drop_database(db_name: str) -> None:
    admin = create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:{POSTGRES_PORT}/postgres"
    )
    with admin.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'DROP DATABASE IF EXISTS "{db_name}"'))
    admin.dispose()


def _create_batch(engine, award_ids: list[int]) -> int:
    with engine.begin() as connection:
        batch_id = connection.execute(
            text(
                "INSERT INTO archive.etl_batch "
                "(domain, entity_type, requested_size, status, "
                "selection_strategy) "
                "VALUES ('AWARD', 'AWARD', :size, 'CREATED', 'BENCHMARK') "
                "RETURNING batch_id"
            ),
            {"size": len(award_ids)},
        ).scalar_one()
        for ordinal, award_id in enumerate(award_ids, start=1):
            connection.execute(
                text(
                    "INSERT INTO archive.etl_batch_item "
                    "(batch_id, entity_key, ordinal, status) "
                    "VALUES (:batch_id, :award_id, :ordinal, 'PENDING')"
                ),
                {"batch_id": batch_id, "award_id": award_id, "ordinal": ordinal},
            )
    return int(batch_id)


def benchmark_end_to_end(
    sources: dict[Path, pd.DataFrame], primary_award_ids: list[int]
) -> None:
    print(
        "\n=== Part 2: end-to-end macrobenchmark "
        "(_run_load_award_id / bulk _run_load_award_batch) ==="
    )

    def _source_dispatch(sql_path: Path) -> OracleDataSource:
        return _fake_oracle_source(
            sql_path, sources[sql_path], latency_seconds=FETCH_LATENCY_SECONDS
        )

    db_name, url = _create_throwaway_database()
    try:
        engine = create_engine(url)
        apply_migrations(engine, MIGRATIONS_DIR)

        with patch.object(
            award_loader, "OracleDataSource", side_effect=_source_dispatch
        ):
            single_award_id = primary_award_ids[0]
            started = time.perf_counter()
            report = award_loader._run_load_award_id(engine, single_award_id)
            elapsed = time.perf_counter() - started
            print(
                f"{'1 family (--load-award-id)':>32}: "
                f"{elapsed * 1000:>10.1f}ms wall "
                f"(reported elapsed_ms={report['elapsed_ms']:.1f})"
            )

            for count in (10, 100, 1000):
                batch_award_ids = primary_award_ids[1 : 1 + count]
                batch_id = _create_batch(engine, batch_award_ids)

                started = time.perf_counter()
                first_report = award_loader._run_load_award_batch(engine, batch_id)
                first_elapsed = time.perf_counter() - started
                print(
                    f"{f'{count} families (--load-batch, first load)':>42}: "
                    f"{first_elapsed * 1000:>10.1f}ms wall "
                    f"(reported elapsed_ms={first_report['elapsed_ms']:.1f}, "
                    f"families_loaded={first_report['families_loaded']}, "
                    f"inserted={first_report['inserted']})"
                )

                # Immediate rerun - the idempotency proof: every table
                # must report inserted=0 updated=0 and unchanged>0 (or
                # unchanged=0 only if that table genuinely had zero
                # rows for this batch in the first place).
                started = time.perf_counter()
                rerun_report = award_loader._run_load_award_batch(engine, batch_id)
                rerun_elapsed = time.perf_counter() - started
                print(
                    f"{f'{count} families (--load-batch, rerun)':>42}: "
                    f"{rerun_elapsed * 1000:>10.1f}ms wall "
                    f"(reported elapsed_ms={rerun_report['elapsed_ms']:.1f}, "
                    f"inserted={rerun_report['inserted']}, "
                    f"updated={rerun_report['updated']}, "
                    f"unchanged={rerun_report['unchanged']})"
                )
                assert rerun_report["inserted"] == 0, "rerun must insert nothing"
                assert rerun_report["updated"] == 0, "rerun must update nothing"
                assert rerun_report["unchanged"] > 0, "rerun must report unchanged rows"
    finally:
        engine.dispose()
        _drop_database(db_name)


def main() -> int:
    sources, primary_award_ids = build_synthetic_oracle(TOTAL_FAMILIES)
    benchmark_read_layer(sources[award_loader.VERSIONS_ORACLE_SQL], primary_award_ids)
    benchmark_end_to_end(sources, primary_award_ids)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
