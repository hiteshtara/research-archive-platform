from __future__ import annotations

from collections.abc import Iterable
from typing import Any

import pandas as pd


def normalize_column_name(column: str) -> str:
    return (
        column.strip()
        .lower()
        .replace(" ", "_")
        .replace("-", "_")
    )


def normalize_columns(dataframe: pd.DataFrame) -> None:
    dataframe.columns = [
        normalize_column_name(str(column))
        for column in dataframe.columns
    ]


def require_columns(
    dataframe: pd.DataFrame,
    required_columns: set[str],
    source_name: str,
) -> None:
    missing = sorted(required_columns - set(dataframe.columns))
    if missing:
        raise RuntimeError(
            f"{source_name} is missing columns: "
            + ", ".join(missing)
        )


def convert_numeric(
    dataframe: pd.DataFrame,
    columns: Iterable[str],
    *,
    source_name: str | None = None,
    reject_invalid: bool = False,
) -> None:
    for column in columns:
        if column not in dataframe.columns:
            continue
        original = dataframe[column]
        converted = pd.to_numeric(original, errors="coerce")
        if reject_invalid:
            invalid = original.notna() & converted.isna()
            if invalid.any():
                raise RuntimeError(
                    f"{source_name or 'source'} contains "
                    f"{int(invalid.sum())} invalid {column} values"
                )
        dataframe[column] = converted


def convert_dates(
    dataframe: pd.DataFrame,
    columns: Iterable[str],
    *,
    source_name: str | None = None,
    reject_invalid: bool = False,
    date_only: bool = False,
) -> None:
    for column in columns:
        if column not in dataframe.columns:
            continue
        original = dataframe[column]
        converted = pd.to_datetime(original, errors="coerce")
        if reject_invalid:
            invalid = original.notna() & converted.isna()
            if invalid.any():
                raise RuntimeError(
                    f"{source_name or 'source'} contains "
                    f"{int(invalid.sum())} invalid {column} values"
                )
        dataframe[column] = (
            converted.dt.date
            if date_only
            else converted
        )


def convert_boolean(value: Any) -> bool:
    if value is None or pd.isna(value):
        return False
    return str(value).strip().upper() in {
        "Y",
        "YES",
        "TRUE",
        "1",
    }

