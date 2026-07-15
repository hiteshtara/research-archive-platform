from __future__ import annotations

import io
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import boto3
import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


STAGING_COLUMNS = [
    "load_id",
    "source_row_number",
    "protocol_id",
    "study_id",
    "protocol_base",
    "protocol_number",
    "title",
    "protocol_type_code",
    "protocol_type",
    "protocol_status_code",
    "protocol_status",
    "approval_date",
    "pi_buid",
    "pi_first_name",
    "pi_last_name",
    "pi_full_name",
    "pi_email",
    "pi_buid_missing",
    "source_file_name",
    "source_extract_at",
]


def get_required_environment(name: str) -> str:
    value = os.getenv(name)

    if not value:
        raise RuntimeError(
            f"Required environment variable is missing: {name}"
        )

    return value


def find_latest_parquet_object(
    s3_client: Any,
    bucket_name: str,
    prefix: str,
) -> dict[str, Any]:
    paginator = s3_client.get_paginator("list_objects_v2")
    parquet_objects: list[dict[str, Any]] = []

    for page in paginator.paginate(
        Bucket=bucket_name,
        Prefix=prefix,
    ):
        for item in page.get("Contents", []):
            if item["Key"].lower().endswith(".parquet"):
                parquet_objects.append(item)

    if not parquet_objects:
        raise RuntimeError(
            f"No Parquet files found in "
            f"s3://{bucket_name}/{prefix}"
        )

    return max(
        parquet_objects,
        key=lambda item: item["LastModified"],
    )


def read_parquet_from_s3(
    s3_client: Any,
    bucket_name: str,
    object_key: str,
) -> pd.DataFrame:
    response = s3_client.get_object(
        Bucket=bucket_name,
        Key=object_key,
    )

    return pd.read_parquet(
        io.BytesIO(response["Body"].read())
    )


def normalize_boolean(value: object) -> bool:
    if pd.isna(value):
        return False

    if isinstance(value, bool):
        return value

    return str(value).strip().upper() in {
        "Y",
        "YES",
        "TRUE",
        "1",
    }


def prepare_stage_dataframe(
    dataframe: pd.DataFrame,
    load_id: int,
    source_file_name: str,
    source_extract_at: datetime,
) -> pd.DataFrame:
    stage = dataframe.copy()

    stage["load_id"] = load_id
    stage["source_row_number"] = range(2, len(stage) + 2)
    stage["source_file_name"] = source_file_name
    stage["source_extract_at"] = source_extract_at

    if "pi_buid_missing" in stage.columns:
        stage["pi_buid_missing"] = (
            stage["pi_buid_missing"]
            .map(normalize_boolean)
            .astype(bool)
        )
    else:
        stage["pi_buid_missing"] = (
            stage["pi_buid"].isna()
        )

    if "approval_date" in stage.columns:
        stage["approval_date"] = pd.to_datetime(
            stage["approval_date"],
            errors="coerce",
        ).dt.date

    numeric_columns = [
        "protocol_id",
        "protocol_type_code",
        "protocol_status_code",
    ]

    for column in numeric_columns:
        if column in stage.columns:
            stage[column] = pd.to_numeric(
                stage[column],
                errors="coerce",
            )

    missing_columns = [
        column
        for column in STAGING_COLUMNS
        if column not in stage.columns
    ]

    if missing_columns:
        raise RuntimeError(
            "Parquet file is missing staging columns: "
            + ", ".join(missing_columns)
        )

    return stage[STAGING_COLUMNS]


def create_load_run(
    engine: Engine,
    bucket_name: str,
    object_key: str,
    row_count: int,
) -> int:
    with engine.begin() as connection:
        load_id = connection.execute(
            text(
                """
                INSERT INTO archive.load_run (
                    domain,
                    source_system,
                    source_file_name,
                    source_s3_bucket,
                    source_s3_key,
                    rows_read,
                    status
                )
                VALUES (
                    'IRB',
                    'KUALI',
                    :source_file_name,
                    :source_s3_bucket,
                    :source_s3_key,
                    :rows_read,
                    'STARTED'
                )
                RETURNING load_id
                """
            ),
            {
                "source_file_name": Path(object_key).name,
                "source_s3_bucket": bucket_name,
                "source_s3_key": object_key,
                "rows_read": row_count,
            },
        ).scalar_one()

    return int(load_id)


def load_stage_table(
    engine: Engine,
    dataframe: pd.DataFrame,
    load_id: int,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                DELETE FROM archive.irb_protocol_stage
                WHERE load_id = :load_id
                """
            ),
            {"load_id": load_id},
        )

        dataframe.to_sql(
            name="irb_protocol_stage",
            schema="archive",
            con=connection,
            if_exists="append",
            index=False,
            method="multi",
            chunksize=250,
        )


def call_irb_load_procedure(
    engine: Engine,
    load_id: int,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "CALL archive.load_irb(:load_id)"
            ),
            {"load_id": load_id},
        )


def verify_load(
    engine: Engine,
    load_id: int,
    expected_rows: int,
) -> dict[str, object]:
    with engine.connect() as connection:
        production_rows = connection.execute(
            text(
                """
                SELECT COUNT(*)
                FROM archive.irb_protocol
                """
            )
        ).scalar_one()

        load_result = connection.execute(
            text(
                """
                SELECT
                    status,
                    rows_read,
                    rows_staged,
                    rows_loaded,
                    rows_rejected,
                    error_message
                FROM archive.load_run
                WHERE load_id = :load_id
                """
            ),
            {"load_id": load_id},
        ).mappings().one()

    if load_result["status"] != "LOADED":
        raise RuntimeError(
            f"Load {load_id} did not complete successfully: "
            f"{dict(load_result)}"
        )

    if int(load_result["rows_loaded"]) != expected_rows:
        raise RuntimeError(
            f"Load row count mismatch. Expected {expected_rows}; "
            f"loaded {load_result['rows_loaded']}."
        )

    return {
        "load_id": load_id,
        "production_rows": int(production_rows),
        **dict(load_result),
    }


def mark_load_failed(
    engine: Engine,
    load_id: int,
    error_message: str,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.load_run
                SET
                    status = 'FAILED',
                    completed_at = CURRENT_TIMESTAMP,
                    error_message = :error_message
                WHERE load_id = :load_id
                """
            ),
            {
                "load_id": load_id,
                "error_message": error_message[:4000],
            },
        )


def main() -> None:
    aws_region = os.getenv(
        "AWS_REGION",
        "us-east-1",
    )
    bucket_name = get_required_environment(
        "DATA_BUCKET_NAME"
    )
    prefix = os.getenv(
        "IRB_S3_PREFIX",
        "landing/irb/",
    )

    s3_client = boto3.client(
        "s3",
        region_name=aws_region,
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        "/app/database/migrations",
    )

    latest_object = find_latest_parquet_object(
        s3_client,
        bucket_name,
        prefix,
    )

    object_key = latest_object["Key"]

    logger.info(
        "Loading latest IRB export: s3://{}/{}",
        bucket_name,
        object_key,
    )

    dataframe = read_parquet_from_s3(
        s3_client,
        bucket_name,
        object_key,
    )

    logger.info(
        "Rows read from Parquet: {}",
        len(dataframe),
    )

    load_id = create_load_run(
        engine,
        bucket_name,
        object_key,
        len(dataframe),
    )

    try:
        source_extract_at = (
            latest_object["LastModified"]
            .astimezone(timezone.utc)
        )

        stage_dataframe = prepare_stage_dataframe(
            dataframe,
            load_id,
            Path(object_key).name,
            source_extract_at,
        )

        load_stage_table(
            engine,
            stage_dataframe,
            load_id,
        )

        logger.info(
            "Rows staged for load {}: {}",
            load_id,
            len(stage_dataframe),
        )

        call_irb_load_procedure(
            engine,
            load_id,
        )

        result = verify_load(
            engine,
            load_id,
            len(stage_dataframe),
        )

        logger.info(
            "Load completed successfully: {}",
            result,
        )

    except Exception as error:
        mark_load_failed(
            engine,
            load_id,
            str(error),
        )
        raise


if __name__ == "__main__":
    main()
