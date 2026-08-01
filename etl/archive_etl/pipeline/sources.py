from __future__ import annotations

import os
import re
import time
from collections.abc import Callable, Generator, Mapping, Sequence
from pathlib import Path
from typing import Any

import oracledb
import pandas as pd
from loguru import logger

from archive_etl.config.settings import require_oracle_environment
from archive_etl.pipeline.validation import normalize_columns

# Some extraction SQL files are shared with a manual SQL*Plus export
# workflow and begin with SQL*Plus session directives (SET PAGESIZE, SET
# LINESIZE, ...) that format that tool's console output. These aren't SQL
# statements at all, so a DB-API cursor can't execute them - strip any
# leading lines that look like a SQL*Plus SET command before running the
# query through oracledb. This never touches the SELECT statement itself.
_SQLPLUS_SET_LINE = re.compile(r"^\s*SET\s+\w+.*$", re.IGNORECASE)

# Column names passed to OracleDataSource.read_filtered are interpolated
# directly into the wrapping SQL text (Oracle has no bind-variable syntax
# for identifiers, only values) - always a fixed literal chosen by our own
# code, never user input, but validated defensively anyway since it's the
# one part of that query that isn't bound.
_SAFE_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

# Oracle's SQL parser rejects an IN-list (literal or bind variable) with
# more than 1000 elements (ORA-01795) - this is the hard ceiling for
# read_filtered's chunk_size, not a tunable performance knob.
MAX_ORACLE_IN_LIST_SIZE = 1000


def _strip_sqlplus_directives(sql_text: str) -> str:
    lines = sql_text.splitlines()
    index = 0
    while index < len(lines) and (
        not lines[index].strip()
        or _SQLPLUS_SET_LINE.match(lines[index])
    ):
        index += 1
    return "\n".join(lines[index:])


def _materialize_oracle_value(value: Any) -> Any:
    if isinstance(value, oracledb.LOB):
        return value.read()
    return value


class CsvDataSource:
    def __init__(
        self,
        path: Path,
        *,
        null_values: list[str] | None = None,
        replacements: Mapping[Any, Any] | None = None,
    ) -> None:
        self.path = path
        self.name = path.name
        self.null_values = null_values or ["", "NULL", "null"]
        self.replacements = replacements

    def read(self) -> pd.DataFrame:
        if not self.path.exists():
            raise FileNotFoundError(f"CSV not found: {self.path}")

        logger.info("Reading {}", self.path)
        dataframe = pd.read_csv(
            self.path,
            dtype=str,
            keep_default_na=True,
            na_values=self.null_values,
            low_memory=False,
        )
        normalize_columns(dataframe)

        if self.replacements:
            dataframe = dataframe.replace(self.replacements)

        logger.info(
            "{} rows read from {}",
            len(dataframe),
            self.path.name,
        )
        return dataframe


class OracleDataSource:
    def __init__(
        self,
        sql_path: Path,
        *,
        connect: Callable[..., Any] = oracledb.connect,
        environ: Mapping[str, str] = os.environ,
        fetch_size: int = 5000,
    ) -> None:
        self.sql_path = sql_path
        self.connect = connect
        self.environ = environ
        self.fetch_size = fetch_size
        self.name = f"oracle:{sql_path.name}"

    def read(self) -> pd.DataFrame:
        credentials = require_oracle_environment(self.environ)

        sql_text = _strip_sqlplus_directives(
            self.sql_path.read_text(encoding="utf-8")
        ).strip()
        if sql_text.endswith(";"):
            sql_text = sql_text[:-1].rstrip()

        logger.info("Reading Oracle data using {}", self.sql_path)
        rows: list[tuple[Any, ...]] = []

        with self.connect(
            user=credentials["ORACLE_USER"],
            password=credentials["ORACLE_PASSWORD"],
            dsn=credentials["ORACLE_DSN"],
        ) as connection:
            with connection.cursor() as cursor:
                cursor.arraysize = self.fetch_size
                cursor.execute(sql_text)
                columns = [
                    str(column[0])
                    for column in cursor.description
                ]

                while batch := cursor.fetchmany(self.fetch_size):
                    rows.extend(
                        tuple(
                            _materialize_oracle_value(value)
                            for value in row
                        )
                        for row in batch
                    )

        dataframe = pd.DataFrame(rows, columns=columns)
        normalize_columns(dataframe)
        logger.info(
            "{} rows read from {}",
            len(dataframe),
            self.name,
        )
        return dataframe

    def read_batches(self) -> Generator[pd.DataFrame, None, None]:
        """Yield one normalized DataFrame per fetchmany() batch, without
        accumulating the full result set in memory.

        This is an addition alongside read(), not a replacement - read()
        is unchanged and still used by every existing loader. This exists
        so a caller can stop fetching further batches early (e.g. a
        bounded --limit sample) without ever reading the rest of the
        result set. Callers that break out of iterating this generator
        early should call its .close() method (e.g. in a try/finally) so
        the Oracle cursor/connection are released promptly rather than
        waiting on garbage collection.

        Unlike read(), this yields nothing at all for a query that returns
        zero rows - there is no batch to yield. Callers that need "zero
        rows with the right columns" behavior should use read() instead.
        """
        credentials = require_oracle_environment(self.environ)

        sql_text = _strip_sqlplus_directives(
            self.sql_path.read_text(encoding="utf-8")
        ).strip()
        if sql_text.endswith(";"):
            sql_text = sql_text[:-1].rstrip()

        logger.info("Reading Oracle data using {} (batched)", self.sql_path)

        with self.connect(
            user=credentials["ORACLE_USER"],
            password=credentials["ORACLE_PASSWORD"],
            dsn=credentials["ORACLE_DSN"],
        ) as connection:
            with connection.cursor() as cursor:
                cursor.arraysize = self.fetch_size
                cursor.execute(sql_text)
                columns = [
                    str(column[0])
                    for column in cursor.description
                ]

                while batch := cursor.fetchmany(self.fetch_size):
                    rows = [
                        tuple(
                            _materialize_oracle_value(value)
                            for value in row
                        )
                        for row in batch
                    ]
                    frame = pd.DataFrame(rows, columns=columns)
                    normalize_columns(frame)
                    yield frame

    def read_filtered(
        self,
        *,
        column: str,
        values: Sequence[Any],
        chunk_size: int = MAX_ORACLE_IN_LIST_SIZE,
    ) -> pd.DataFrame:
        """Resolve only the rows matching `column IN (<values>)`, pushed
        down to Oracle as a bind-variable WHERE clause wrapped around
        this source's own SELECT, instead of read()/read_batches()'s
        full-table scan + client-side pandas filter. One Oracle round
        trip per chunk of at most `chunk_size` values (Oracle's IN-list
        limit is 1000 elements, ORA-01795, for both literals and bind
        variables - `chunk_size` cannot safely exceed
        MAX_ORACLE_IN_LIST_SIZE). Values are always passed as bind
        variables, never string-interpolated into the SQL text; `column`
        is a fixed identifier chosen by the caller (Oracle has no bind
        syntax for identifiers) and is validated against
        `_SAFE_IDENTIFIER` before use.

        Returns an empty DataFrame without opening a connection at all
        if `values` is empty. Logs each chunk's elapsed time. Purely
        additive - read()/read_batches() are unchanged and remain the
        full-load path's implementation."""
        if not _SAFE_IDENTIFIER.match(column):
            raise ValueError(f"unsafe column name for read_filtered: {column!r}")
        if chunk_size <= 0 or chunk_size > MAX_ORACLE_IN_LIST_SIZE:
            raise ValueError(
                f"chunk_size must be in 1..{MAX_ORACLE_IN_LIST_SIZE}, "
                f"got {chunk_size}"
            )

        unique_values = list(dict.fromkeys(values))
        if not unique_values:
            return pd.DataFrame()

        credentials = require_oracle_environment(self.environ)

        sql_text = _strip_sqlplus_directives(
            self.sql_path.read_text(encoding="utf-8")
        ).strip()
        if sql_text.endswith(";"):
            sql_text = sql_text[:-1].rstrip()

        chunks = [
            unique_values[start : start + chunk_size]
            for start in range(0, len(unique_values), chunk_size)
        ]

        collected: list[pd.DataFrame] = []
        with self.connect(
            user=credentials["ORACLE_USER"],
            password=credentials["ORACLE_PASSWORD"],
            dsn=credentials["ORACLE_DSN"],
        ) as connection:
            with connection.cursor() as cursor:
                cursor.arraysize = self.fetch_size
                for chunk_index, chunk in enumerate(chunks):
                    bind_names = [f"b{position}" for position in range(len(chunk))]
                    filtered_sql = (
                        "SELECT * FROM (\n"
                        f"{sql_text}\n"
                        ") filtered_source WHERE "
                        f"{column} IN ({', '.join(':' + name for name in bind_names)})"
                    )
                    params = dict(zip(bind_names, chunk, strict=True))

                    started = time.perf_counter()
                    cursor.execute(filtered_sql, params)
                    columns = [str(item[0]) for item in cursor.description]

                    rows: list[tuple[Any, ...]] = []
                    while batch := cursor.fetchmany(self.fetch_size):
                        rows.extend(
                            tuple(
                                _materialize_oracle_value(value)
                                for value in row
                            )
                            for row in batch
                        )
                    elapsed_ms = (time.perf_counter() - started) * 1000

                    logger.info(
                        "Filtered Oracle read {} chunk {}/{} ({}={} value(s)): "
                        "{} row(s) in {:.1f}ms",
                        self.name,
                        chunk_index + 1,
                        len(chunks),
                        column,
                        len(chunk),
                        len(rows),
                        elapsed_ms,
                    )

                    if rows:
                        frame = pd.DataFrame(rows, columns=columns)
                        normalize_columns(frame)
                        collected.append(frame)

        if not collected:
            return pd.DataFrame()
        return pd.concat(collected, ignore_index=True)

    def read_filtered_any_column(
        self,
        *,
        columns: Sequence[str],
        values: Sequence[Any],
        chunk_size: int = MAX_ORACLE_IN_LIST_SIZE,
    ) -> pd.DataFrame:
        """Like read_filtered, but for a table with no single column that
        identifies "does this row belong to the requested set" -
        PENDING_TRANSACTIONS has SOURCE_AWARD_NUMBER/
        DESTINATION_AWARD_NUMBER instead of a bare AWARD_NUMBER, and a
        transaction belongs to a loaded Award if it appears on EITHER
        side. Builds `WHERE col1 IN (...) OR col2 IN (...) OR ...`, the
        same `values` chunk bound under a distinct bind-variable prefix
        per column so chunk_size still bounds each individual IN-list to
        Oracle's 1000-element ceiling - one Oracle round trip per chunk,
        matching read_filtered's cost profile exactly (just evaluated
        against more than one column per round trip)."""
        for column in columns:
            if not _SAFE_IDENTIFIER.match(column):
                raise ValueError(
                    f"unsafe column name for read_filtered_any_column: {column!r}"
                )
        if not columns:
            raise ValueError("read_filtered_any_column requires at least one column")
        if chunk_size <= 0 or chunk_size > MAX_ORACLE_IN_LIST_SIZE:
            raise ValueError(
                f"chunk_size must be in 1..{MAX_ORACLE_IN_LIST_SIZE}, "
                f"got {chunk_size}"
            )

        unique_values = list(dict.fromkeys(values))
        if not unique_values:
            return pd.DataFrame()

        credentials = require_oracle_environment(self.environ)

        sql_text = _strip_sqlplus_directives(
            self.sql_path.read_text(encoding="utf-8")
        ).strip()
        if sql_text.endswith(";"):
            sql_text = sql_text[:-1].rstrip()

        chunks = [
            unique_values[start : start + chunk_size]
            for start in range(0, len(unique_values), chunk_size)
        ]

        collected: list[pd.DataFrame] = []
        with self.connect(
            user=credentials["ORACLE_USER"],
            password=credentials["ORACLE_PASSWORD"],
            dsn=credentials["ORACLE_DSN"],
        ) as connection:
            with connection.cursor() as cursor:
                cursor.arraysize = self.fetch_size
                for chunk_index, chunk in enumerate(chunks):
                    params: dict[str, Any] = {}
                    clauses = []
                    for column_index, column in enumerate(columns):
                        bind_names = [
                            f"c{column_index}_{position}"
                            for position in range(len(chunk))
                        ]
                        clauses.append(
                            f"{column} IN ("
                            + ", ".join(":" + name for name in bind_names)
                            + ")"
                        )
                        params.update(zip(bind_names, chunk, strict=True))

                    filtered_sql = (
                        "SELECT * FROM (\n"
                        f"{sql_text}\n"
                        ") filtered_source WHERE "
                        + " OR ".join(clauses)
                    )

                    started = time.perf_counter()
                    cursor.execute(filtered_sql, params)
                    result_columns = [
                        str(item[0]) for item in cursor.description
                    ]

                    rows: list[tuple[Any, ...]] = []
                    while batch := cursor.fetchmany(self.fetch_size):
                        rows.extend(
                            tuple(
                                _materialize_oracle_value(value)
                                for value in row
                            )
                            for row in batch
                        )
                    elapsed_ms = (time.perf_counter() - started) * 1000

                    logger.info(
                        "Filtered Oracle read {} chunk {}/{} "
                        "({}={} value(s)): {} row(s) in {:.1f}ms",
                        self.name,
                        chunk_index + 1,
                        len(chunks),
                        "/".join(columns),
                        len(chunk),
                        len(rows),
                        elapsed_ms,
                    )

                    if rows:
                        frame = pd.DataFrame(rows, columns=result_columns)
                        normalize_columns(frame)
                        collected.append(frame)

        if not collected:
            return pd.DataFrame()
        return pd.concat(collected, ignore_index=True)
