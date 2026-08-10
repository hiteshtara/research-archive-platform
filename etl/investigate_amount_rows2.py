from __future__ import annotations

import uuid

import boto3
import oracledb
from loguru import logger

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.settings import require_oracle_environment
from archive_etl.config.startup_validation import validate_aws_identity, validate_oracle_reachable
from archive_etl.utils.structured_logging import configure_structured_logging


def _connect_oracle() -> oracledb.Connection:
    credentials = require_oracle_environment()
    return oracledb.connect(
        user=credentials["ORACLE_USER"],
        password=credentials["ORACLE_PASSWORD"],
        dsn=credentials["ORACLE_DSN"],
    )


def dump(cursor, sql, params, label):
    cursor.execute(sql, params)
    columns = [d[0] for d in cursor.description]
    rows = cursor.fetchall()
    print(f"=== {label} ({len(rows)} rows) ===")
    for row in rows:
        print(dict(zip(columns, row)))
    print()


def main() -> None:
    run_id = str(uuid.uuid4())
    configure_structured_logging(run_id)
    identity = validate_aws_identity(boto3.client("sts"))
    logger.bind(stage="startup").info("AWS identity: account={}", identity["account"])
    configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=True)
    validate_oracle_reachable(_connect_oracle)

    connection = _connect_oracle()
    try:
        cursor = connection.cursor()

        dump(
            cursor,
            """
            SELECT AWARD_AMOUNT_INFO_ID, AWARD_ID, SEQUENCE_NUMBER,
                   OBLIGATED_TOTAL_DIRECT, OBLIGATED_TOTAL_INDIRECT,
                   ANTICIPATED_TOTAL_DIRECT, ANTICIPATED_TOTAL_INDIRECT,
                   TNM_DOCUMENT_NUMBER, TRANSACTION_ID,
                   ORIGINATING_AWARD_VERSION, VER_NBR
            FROM AWARD_AMOUNT_INFO
            WHERE AWARD_ID = :award_id
            ORDER BY AWARD_AMOUNT_INFO_ID
            """,
            {"award_id": 3187665},
            "Fresh AWARD_AMOUNT_INFO rows for AWARD_ID=3187665",
        )

        dump(
            cursor,
            """
            SELECT TRANSACTION_ID, DOCUMENT_NUMBER, SOURCE_AWARD_NUMBER,
                   DESTINATION_AWARD_NUMBER, OBLIGATED_AMOUNT,
                   OBLIGATED_DIRECT_AMOUNT, OBLIGATED_INDIRECT_AMOUNT,
                   ANTICIPATED_AMOUNT, PROCESSED_FLAG, UPDATE_TIMESTAMP
            FROM PENDING_TRANSACTIONS
            WHERE DOCUMENT_NUMBER = :doc_number
            """,
            {"doc_number": "925932"},
            "PENDING_TRANSACTIONS for DOCUMENT_NUMBER=925932",
        )

        dump(
            cursor,
            """
            SELECT TRANSACTION_DETAIL_ID, AWARD_NUMBER, SEQUENCE_NUMBER,
                   TRANSACTION_ID, TNM_DOCUMENT_NUMBER, SOURCE_AWARD_NUMBER,
                   DESTINATION_AWARD_NUMBER, OBLIGATED_AMOUNT,
                   TRANSACTION_DETAIL_TYPE, UPDATE_TIMESTAMP
            FROM TRANSACTION_DETAILS
            WHERE TNM_DOCUMENT_NUMBER = :doc_number
            """,
            {"doc_number": "925932"},
            "TRANSACTION_DETAILS for TNM_DOCUMENT_NUMBER=925932",
        )

        dump(
            cursor,
            """
            SELECT aat.AWARD_AMOUNT_TRANSACTION_ID, aat.AWARD_NUMBER,
                   aat.TRANSACTION_ID AS DOCUMENT_NUMBER,
                   aat.TRANSACTION_TYPE_CODE, att.DESCRIPTION AS TRANSACTION_TYPE_DESCRIPTION,
                   aat.NOTICE_DATE, aat.COMMENTS, aat.UPDATE_TIMESTAMP, aat.VER_NBR
            FROM AWARD_AMOUNT_TRANSACTION aat
            LEFT JOIN AWARD_TRANSACTION_TYPE att
                   ON att.AWARD_TRANSACTION_TYPE_CODE = aat.TRANSACTION_TYPE_CODE
            WHERE aat.AWARD_NUMBER = :award_number
              AND aat.TRANSACTION_ID = :doc_number
            """,
            {"award_number": "204713-00133", "doc_number": "925932"},
            "AWARD_AMOUNT_TRANSACTION for AWARD_NUMBER=204713-00133, DOCUMENT_NUMBER=925932",
        )

        dump(
            cursor,
            """
            SELECT aat.AWARD_AMOUNT_TRANSACTION_ID, aat.AWARD_NUMBER,
                   aat.TRANSACTION_ID AS DOCUMENT_NUMBER,
                   aat.TRANSACTION_TYPE_CODE, att.DESCRIPTION AS TRANSACTION_TYPE_DESCRIPTION,
                   aat.NOTICE_DATE, aat.UPDATE_TIMESTAMP, aat.VER_NBR
            FROM AWARD_AMOUNT_TRANSACTION aat
            LEFT JOIN AWARD_TRANSACTION_TYPE att
                   ON att.AWARD_TRANSACTION_TYPE_CODE = aat.TRANSACTION_TYPE_CODE
            WHERE aat.AWARD_NUMBER = :award_number
            ORDER BY aat.AWARD_AMOUNT_TRANSACTION_ID
            """,
            {"award_number": "204713-00133"},
            "All AWARD_AMOUNT_TRANSACTION rows for AWARD_NUMBER=204713-00133",
        )
    finally:
        connection.close()


if __name__ == "__main__":
    main()
