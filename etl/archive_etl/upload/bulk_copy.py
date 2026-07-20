from __future__ import annotations

import csv
import io
import time
from typing import Any

import pandas as pd
from loguru import logger
from sqlalchemy.engine import Connection


def _copy_value(value: Any) -> Any:
    """
    Convert pandas values into PostgreSQL COPY-safe values.
    """

    if pd.isna(value):
        return None

    if isinstance(value, pd.Timestamp):
        return value.to_pydatetime()

    # Nullable integer columns are often represented by pandas as
    # floats, for example 9005.0. PostgreSQL BIGINT requires 9005.
    if isinstance(value, float) and value.is_integer():
        return int(value)

    return value


def bulk_copy_dataframe(
    connection: Connection,
    dataframe: pd.DataFrame,
    schema: str,
    table: str,
) -> int:
    """
    Bulk load a DataFrame into PostgreSQL using COPY.
    """

    start = time.perf_counter()

    buffer = io.StringIO()

    writer = csv.writer(
        buffer,
        quoting=csv.QUOTE_MINIMAL,
        lineterminator="\n",
    )

    writer.writerow(dataframe.columns.tolist())

    for row in dataframe.itertuples(
        index=False,
        name=None,
    ):
        writer.writerow(
            [_copy_value(value) for value in row]
        )

    buffer.seek(0)

    raw_connection = connection.connection.driver_connection

    columns = ", ".join(
        f'"{column}"'
        for column in dataframe.columns
    )

    copy_sql = f"""
        COPY "{schema}"."{table}" ({columns})
        FROM STDIN
        WITH (
            FORMAT CSV,
            HEADER TRUE,
            NULL ''
        )
    """

    with raw_connection.cursor() as cursor:
        with cursor.copy(copy_sql) as copy:
            while chunk := buffer.read(1024 * 1024):
                copy.write(chunk)

    elapsed = time.perf_counter() - start

    logger.info(
        "COPY {}.{} completed: {:,} rows in {:.2f} seconds",
        schema,
        table,
        len(dataframe),
        elapsed,
    )

    return len(dataframe)
