from __future__ import annotations

import os
import re
from collections.abc import Callable, Generator, Mapping
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
        so a caller can stop fetching further batches early (e.g. a bounded
        --limit sample) without ever reading the rest of the result set.
        Callers that iterate this generator and stop before exhausting it
        should call its .close() method (e.g. in a try/finally) so the
        Oracle cursor/connection are released promptly rather than waiting
        on garbage collection.

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
