from __future__ import annotations

import os
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

import oracledb
import pandas as pd
from loguru import logger

from archive_etl.pipeline.validation import normalize_columns


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
        required = ["ORACLE_USER", "ORACLE_PASSWORD", "ORACLE_DSN"]
        missing = [
            name
            for name in required
            if not self.environ.get(name)
        ]
        if missing:
            raise RuntimeError(
                "Missing Oracle environment variables: "
                + ", ".join(missing)
            )

        sql_text = self.sql_path.read_text(encoding="utf-8").strip()
        if sql_text.endswith(";"):
            sql_text = sql_text[:-1].rstrip()

        logger.info("Reading Oracle data using {}", self.sql_path)
        rows: list[tuple[Any, ...]] = []

        with self.connect(
            user=self.environ["ORACLE_USER"],
            password=self.environ["ORACLE_PASSWORD"],
            dsn=self.environ["ORACLE_DSN"],
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
