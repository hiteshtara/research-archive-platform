from __future__ import annotations

import io
import os
from pathlib import PurePosixPath
from typing import Any

import boto3
import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine


DATASETS = {
    "protocols": "irb_protocols.parquet",
    "submissions": "irb_submissions.parquet",
    "funding": "irb_funding_sources.parquet",
    "timeline": "irb_timeline_events.parquet",
}


def required_environment(name: str) -> str:
    value = os.getenv(name)

    if not value:
        raise RuntimeError(
            f"Required environment variable is missing: {name}"
        )

    return value


def find_latest_export_prefix(
    s3_client: Any,
    bucket_name: str,
    root_prefix: str,
) -> str:
    paginator = s3_client.get_paginator("list_objects_v2")
    prefixes: set[str] = set()

    for page in paginator.paginate(
        Bucket=bucket_name,
        Prefix=root_prefix,
    ):
        for item in page.get("Contents", []):
            key = item["Key"]

            if not key.lower().endswith(".parquet"):
                continue

            parent = str(PurePosixPath(key).parent)

            if parent.startswith(root_prefix.rstrip("/")):
                prefixes.add(parent)

    if not prefixes:
        raise RuntimeError(
            f"No composite exports found under "
            f"s3://{bucket_name}/{root_prefix}"
        )

    return sorted(prefixes)[-1]


def read_parquet(
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


def create_load_run(
    engine: Engine,
    bucket_name: str,
    export_prefix: str,
    total_rows: int,
) -> int:
    with engine.begin() as connection:
        return int(
            connection.execute(
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
                        'IRB_COMPOSITE',
                        'KUALI',
                        'PROTOCOL_COMPOSITE',
                        :bucket_name,
                        :export_prefix,
                        :rows_read,
                        'STARTED'
                    )
                    RETURNING load_id
                    """
                ),
                {
                    "bucket_name": bucket_name,
                    "export_prefix": export_prefix,
                    "rows_read": total_rows,
                },
            ).scalar_one()
        )


def prepare_protocols(
    dataframe: pd.DataFrame,
    load_id: int,
) -> pd.DataFrame:
    rename_map = {
        "proto_type_cd": "protocol_type_code",
        "proto_type_desc": "protocol_type",
        "proto_stat_cd": "protocol_status_code",
        "proto_stat_desc": "protocol_status",
        "pi_email_address": "pi_email",
        "pi_affil_typ_cd": "pi_affiliation_code",
        "pi_affil_typ_desc": "pi_affiliation",
        "fund_center_num": "fund_center_number",
        "school_num": "school_number",
        "irb_advisor1_id": "irb_advisor_id",
        "irb_recv_dt": "received_date",
        "irb_claim_dt": "claimed_date",
        "irb_determine_dt": "determination_date",
        "irb_approval_dt": "approval_date",
        "irb_new_exp_dt": "expiration_date",
        "irb_closure_dt": "closure_date",
        "irb_auth_dt": "authorization_date",
        "irb_rcd_keep_box": "record_storage_box",
        "max_expire_ind": "maximum_expiration_ind",
        "expired_or_less30": "expiration_status",
        "funding_src_cnt": "funding_source_count",
    }

    result = dataframe.rename(columns=rename_map).copy()
    result["load_id"] = load_id

    expected_columns = [
        "protocol_id",
        "protocol_base",
        "protocol_number",
        "sequence_number",
        "active_ind",
        "crc_protocol_num",
        "document_number",
        "title",
        "protocol_type_code",
        "protocol_type",
        "protocol_status_code",
        "protocol_status",
        "ohrp_categories",
        "summary_keywords",
        "pi_id",
        "pi_email",
        "pi_affiliation_code",
        "pi_affiliation",
        "fund_center_number",
        "school_number",
        "irb_analyst_id",
        "irb_advisor_id",
        "received_date",
        "claimed_date",
        "determination_date",
        "approval_date",
        "expiration_date",
        "closure_date",
        "authorization_date",
        "record_storage_box",
        "maximum_expiration_ind",
        "expiration_status",
        "working_days",
        "calendar_days",
        "irb_days",
        "pi_days",
        "funding_source_count",
        "load_id",
    ]

    missing = [
        column
        for column in expected_columns
        if column not in result.columns
    ]

    if missing:
        raise RuntimeError(
            "Protocol dataset is missing columns: "
            + ", ".join(missing)
        )

    return result[expected_columns]


def prepare_submissions(
    dataframe: pd.DataFrame,
    load_id: int,
) -> pd.DataFrame:
    result = dataframe.rename(
        columns={
            "submit_number": "submission_number",
            "submit_type_cd": "submission_type_code",
            "submit_type_desc": "submission_type",
            "submit_stat_cd": "submission_status_code",
            "submit_stat_desc": "submission_status",
            "event_type_cd": "event_type_code",
            "event_type_desc": "event_type",
            "review_type_cd": "review_type_code",
            "review_type_desc": "review_type",
        }
    ).copy()

    result["load_id"] = load_id

    return result[
        [
            "protocol_id",
            "protocol_base",
            "protocol_number",
            "sequence_number",
            "submission_number",
            "submission_type_code",
            "submission_type",
            "submission_status_code",
            "submission_status",
            "event_type_code",
            "event_type",
            "review_type_code",
            "review_type",
            "load_id",
        ]
    ]


def prepare_funding(
    dataframe: pd.DataFrame,
    load_id: int,
) -> pd.DataFrame:
    result = dataframe.copy()
    result["load_id"] = load_id

    return result[
        [
            "protocol_id",
            "protocol_base",
            "protocol_number",
            "funding_sequence",
            "funding_source",
            "load_id",
        ]
    ]


def prepare_timeline(
    dataframe: pd.DataFrame,
    load_id: int,
) -> pd.DataFrame:
    result = dataframe.copy()
    result["load_id"] = load_id

    result["event_date"] = pd.to_datetime(
        result["event_date"],
        errors="coerce",
    ).dt.date

    result = result[result["event_date"].notna()].copy()

    return result[
        [
            "protocol_id",
            "protocol_base",
            "protocol_number",
            "event_date",
            "event_type",
            "event_sequence",
            "source_column",
            "load_id",
        ]
    ]


def load_stage(
    engine: Engine,
    dataframe: pd.DataFrame,
    table_name: str,
    load_id: int,
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                f"""
                DELETE FROM archive.{table_name}
                WHERE load_id = :load_id
                """
            ),
            {"load_id": load_id},
        )

        dataframe.to_sql(
            name=table_name,
            schema="archive",
            con=connection,
            if_exists="append",
            index=False,
            method="multi",
            chunksize=500,
        )


def validate_stage(
    engine: Engine,
    load_id: int,
    expected: dict[str, int],
) -> None:
    table_map = {
        "protocols": "irb_protocol_version_stage",
        "submissions": "irb_submission_stage",
        "funding": "irb_funding_source_stage",
        "timeline": "irb_timeline_event_stage",
    }

    with engine.connect() as connection:
        for dataset_name, table_name in table_map.items():
            actual = int(
                connection.execute(
                    text(
                        f"""
                        SELECT COUNT(*)
                        FROM archive.{table_name}
                        WHERE load_id = :load_id
                        """
                    ),
                    {"load_id": load_id},
                ).scalar_one()
            )

            if actual != expected[dataset_name]:
                raise RuntimeError(
                    f"{dataset_name} staging count mismatch. "
                    f"Expected {expected[dataset_name]}, got {actual}."
                )

        duplicate_protocols = int(
            connection.execute(
                text(
                    """
                    SELECT COUNT(*)
                    FROM (
                        SELECT protocol_id
                        FROM archive.irb_protocol_version_stage
                        WHERE load_id = :load_id
                        GROUP BY protocol_id
                        HAVING COUNT(*) > 1
                    ) duplicate_protocols
                    """
                ),
                {"load_id": load_id},
            ).scalar_one()
        )

        if duplicate_protocols:
            raise RuntimeError(
                f"Found {duplicate_protocols} duplicate protocol IDs."
            )

        orphan_submissions = int(
            connection.execute(
                text(
                    """
                    SELECT COUNT(*)
                    FROM archive.irb_submission_stage submission
                    LEFT JOIN archive.irb_protocol_version_stage protocol
                      ON protocol.load_id = submission.load_id
                     AND protocol.protocol_id = submission.protocol_id
                    WHERE submission.load_id = :load_id
                      AND protocol.protocol_id IS NULL
                    """
                ),
                {"load_id": load_id},
            ).scalar_one()
        )

        orphan_funding = int(
            connection.execute(
                text(
                    """
                    SELECT COUNT(*)
                    FROM archive.irb_funding_source_stage funding
                    LEFT JOIN archive.irb_protocol_version_stage protocol
                      ON protocol.load_id = funding.load_id
                     AND protocol.protocol_id = funding.protocol_id
                    WHERE funding.load_id = :load_id
                      AND protocol.protocol_id IS NULL
                    """
                ),
                {"load_id": load_id},
            ).scalar_one()
        )

        orphan_timeline = int(
            connection.execute(
                text(
                    """
                    SELECT COUNT(*)
                    FROM archive.irb_timeline_event_stage timeline
                    LEFT JOIN archive.irb_protocol_version_stage protocol
                      ON protocol.load_id = timeline.load_id
                     AND protocol.protocol_id = timeline.protocol_id
                    WHERE timeline.load_id = :load_id
                      AND protocol.protocol_id IS NULL
                    """
                ),
                {"load_id": load_id},
            ).scalar_one()
        )

    if orphan_submissions or orphan_funding or orphan_timeline:
        raise RuntimeError(
            "Composite data contains orphan rows: "
            f"submissions={orphan_submissions}, "
            f"funding={orphan_funding}, "
            f"timeline={orphan_timeline}"
        )


def publish_snapshot(
    engine: Engine,
    load_id: int,
    counts: dict[str, int],
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text("DELETE FROM archive.irb_timeline_event")
        )
        connection.execute(
            text("DELETE FROM archive.irb_funding_source")
        )
        connection.execute(
            text("DELETE FROM archive.irb_submission")
        )
        connection.execute(
            text("DELETE FROM archive.irb_protocol_version")
        )

        connection.execute(
            text(
                """
                INSERT INTO archive.irb_protocol_version (
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    sequence_number,
                    active_ind,
                    crc_protocol_num,
                    document_number,
                    title,
                    protocol_type_code,
                    protocol_type,
                    protocol_status_code,
                    protocol_status,
                    ohrp_categories,
                    summary_keywords,
                    pi_id,
                    pi_email,
                    pi_affiliation_code,
                    pi_affiliation,
                    fund_center_number,
                    school_number,
                    irb_analyst_id,
                    irb_advisor_id,
                    received_date,
                    claimed_date,
                    determination_date,
                    approval_date,
                    expiration_date,
                    closure_date,
                    authorization_date,
                    record_storage_box,
                    maximum_expiration_ind,
                    expiration_status,
                    working_days,
                    calendar_days,
                    irb_days,
                    pi_days,
                    funding_source_count,
                    loaded_at,
                    load_id
                )
                SELECT
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    sequence_number,
                    active_ind,
                    crc_protocol_num,
                    document_number,
                    title,
                    protocol_type_code,
                    protocol_type,
                    protocol_status_code,
                    protocol_status,
                    ohrp_categories,
                    summary_keywords,
                    pi_id,
                    pi_email,
                    pi_affiliation_code,
                    pi_affiliation,
                    fund_center_number,
                    school_number,
                    irb_analyst_id,
                    irb_advisor_id,
                    received_date,
                    claimed_date,
                    determination_date,
                    approval_date,
                    expiration_date,
                    closure_date,
                    authorization_date,
                    record_storage_box,
                    maximum_expiration_ind,
                    expiration_status,
                    working_days,
                    calendar_days,
                    irb_days,
                    pi_days,
                    funding_source_count,
                    CURRENT_TIMESTAMP,
                    load_id
                FROM archive.irb_protocol_version_stage
                WHERE load_id = :load_id
                """
            ),
            {"load_id": load_id},
        )

        connection.execute(
            text(
                """
                INSERT INTO archive.irb_submission (
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    sequence_number,
                    submission_number,
                    submission_type_code,
                    submission_type,
                    submission_status_code,
                    submission_status,
                    event_type_code,
                    event_type,
                    review_type_code,
                    review_type,
                    load_id
                )
                SELECT
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    sequence_number,
                    submission_number,
                    submission_type_code,
                    submission_type,
                    submission_status_code,
                    submission_status,
                    event_type_code,
                    event_type,
                    review_type_code,
                    review_type,
                    load_id
                FROM archive.irb_submission_stage
                WHERE load_id = :load_id
                """
            ),
            {"load_id": load_id},
        )

        connection.execute(
            text(
                """
                INSERT INTO archive.irb_funding_source (
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    funding_sequence,
                    funding_source,
                    load_id
                )
                SELECT
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    funding_sequence,
                    funding_source,
                    load_id
                FROM archive.irb_funding_source_stage
                WHERE load_id = :load_id
                """
            ),
            {"load_id": load_id},
        )

        connection.execute(
            text(
                """
                INSERT INTO archive.irb_timeline_event (
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    event_date,
                    event_type,
                    event_sequence,
                    source_column,
                    load_id
                )
                SELECT
                    protocol_id,
                    protocol_base,
                    protocol_number,
                    event_date,
                    event_type,
                    event_sequence,
                    source_column,
                    load_id
                FROM archive.irb_timeline_event_stage
                WHERE load_id = :load_id
                """
            ),
            {"load_id": load_id},
        )

        rows_loaded = sum(counts.values())

        connection.execute(
            text(
                """
                UPDATE archive.load_run
                SET
                    rows_staged = :rows_loaded,
                    rows_loaded = :rows_loaded,
                    rows_rejected = 0,
                    status = 'LOADED',
                    completed_at = CURRENT_TIMESTAMP
                WHERE load_id = :load_id
                """
            ),
            {
                "load_id": load_id,
                "rows_loaded": rows_loaded,
            },
        )

        for table_name in [
            "irb_timeline_event_stage",
            "irb_funding_source_stage",
            "irb_submission_stage",
            "irb_protocol_version_stage",
        ]:
            connection.execute(
                text(
                    f"""
                    DELETE FROM archive.{table_name}
                    WHERE load_id = :load_id
                    """
                ),
                {"load_id": load_id},
            )


def mark_failed(
    engine: Engine,
    load_id: int,
    error: Exception,
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
                "error_message": str(error)[:4000],
            },
        )


def main() -> None:
    aws_region = os.getenv("AWS_REGION", "us-east-1")
    bucket_name = required_environment("DATA_BUCKET_NAME")
    root_prefix = os.getenv(
        "IRB_COMPOSITE_S3_PREFIX",
        "landing/irb-composite/",
    )

    engine = create_postgres_engine()

    apply_migrations(
        engine,
        "/app/database/migrations",
    )

    s3_client = boto3.client(
        "s3",
        region_name=aws_region,
    )

    export_prefix = find_latest_export_prefix(
        s3_client,
        bucket_name,
        root_prefix,
    )

    logger.info(
        "Using composite export: s3://{}/{}",
        bucket_name,
        export_prefix,
    )

    raw_datasets: dict[str, pd.DataFrame] = {}

    for dataset_name, file_name in DATASETS.items():
        object_key = f"{export_prefix}/{file_name}"

        dataframe = read_parquet(
            s3_client,
            bucket_name,
            object_key,
        )

        raw_datasets[dataset_name] = dataframe

        logger.info(
            "{} rows read for {}",
            len(dataframe),
            dataset_name,
        )

    counts = {
        name: len(dataframe)
        for name, dataframe in raw_datasets.items()
    }

    load_id = create_load_run(
        engine,
        bucket_name,
        export_prefix,
        sum(counts.values()),
    )

    try:
        protocols = prepare_protocols(
            raw_datasets["protocols"],
            load_id,
        )
        submissions = prepare_submissions(
            raw_datasets["submissions"],
            load_id,
        )
        funding = prepare_funding(
            raw_datasets["funding"],
            load_id,
        )
        timeline = prepare_timeline(
            raw_datasets["timeline"],
            load_id,
        )

        prepared = {
            "protocols": protocols,
            "submissions": submissions,
            "funding": funding,
            "timeline": timeline,
        }

        stage_tables = {
            "protocols": "irb_protocol_version_stage",
            "submissions": "irb_submission_stage",
            "funding": "irb_funding_source_stage",
            "timeline": "irb_timeline_event_stage",
        }

        for dataset_name, dataframe in prepared.items():
            load_stage(
                engine,
                dataframe,
                stage_tables[dataset_name],
                load_id,
            )

            logger.info(
                "{} rows staged for {}",
                len(dataframe),
                dataset_name,
            )

        prepared_counts = {
            name: len(dataframe)
            for name, dataframe in prepared.items()
        }

        validate_stage(
            engine,
            load_id,
            prepared_counts,
        )

        publish_snapshot(
            engine,
            load_id,
            prepared_counts,
        )

        logger.info(
            "Composite load completed successfully. "
            "load_id={}, counts={}",
            load_id,
            prepared_counts,
        )

    except Exception as error:
        mark_failed(
            engine,
            load_id,
            error,
        )
        raise


if __name__ == "__main__":
    main()
