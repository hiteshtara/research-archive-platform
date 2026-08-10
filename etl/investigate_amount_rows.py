"""Read-only Oracle investigation: why does award_id=3187665 have 4
AWARD_AMOUNT_INFO rows competing for "the" current amount, and which
one is authoritative? Developer aid, never writes anything.
"""

from __future__ import annotations

import boto3
from loguru import logger

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.settings import require_oracle_environment
from archive_etl.config.startup_validation import validate_aws_identity, validate_oracle_reachable
from archive_etl.utils.structured_logging import configure_structured_logging
import oracledb
import uuid


def _connect_oracle() -> oracledb.Connection:
    credentials = require_oracle_environment()
    return oracledb.connect(
        user=credentials["ORACLE_USER"],
        password=credentials["ORACLE_PASSWORD"],
        dsn=credentials["ORACLE_DSN"],
    )


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

        print("=== AWARD_AMOUNT_INFO rows for AWARD_ID=3187665 ===")
        cursor.execute(
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
        )
        columns = [d[0] for d in cursor.description]
        rows = cursor.fetchall()
        for row in rows:
            print(dict(zip(columns, row)))

        print()
        print("=== Related PENDING_TRANSACTIONS/TRANSACTION_DETAILS, if any TRANSACTION_ID resolves ===")
        transaction_ids = sorted({row[8] for row in rows if row[8] is not None})
        print("distinct TRANSACTION_IDs:", transaction_ids)
        for tid in transaction_ids:
            cursor.execute(
                """
                SELECT TRANSACTION_ID, SOURCE_AWARD_NUMBER, DESTINATION_AWARD_NUMBER,
                       TRANSACTION_STATUS_CODE, TRANSACTION_TYPE_CODE
                FROM PENDING_TRANSACTIONS
                WHERE TRANSACTION_ID = :tid
                """,
                {"tid": tid},
            )
            pt_columns = [d[0] for d in cursor.description]
            for row in cursor.fetchall():
                print("PENDING_TRANSACTIONS:", dict(zip(pt_columns, row)))

            cursor.execute(
                """
                SELECT TRANSACTION_DETAIL_ID, AWARD_NUMBER, SEQUENCE_NUMBER,
                       TRANSACTION_DETAIL_TYPE, SOURCE_AWARD_NUMBER,
                       DESTINATION_AWARD_NUMBER, OBLIGATED_AMOUNT, ANTICIPATED_AMOUNT
                FROM TRANSACTION_DETAILS
                WHERE TRANSACTION_ID = :tid
                """,
                {"tid": tid},
            )
            td_columns = [d[0] for d in cursor.description]
            for row in cursor.fetchall():
                print("TRANSACTION_DETAILS:", dict(zip(td_columns, row)))

        print()
        print("=== AWARD row itself for context (status, sequence, is_current) ===")
        cursor.execute(
            """
            SELECT AWARD_ID, AWARD_NUMBER, SEQUENCE_NUMBER, AWARD_SEQUENCE_STATUS,
                   UPDATE_TIMESTAMP
            FROM AWARD
            WHERE AWARD_ID = :award_id
            """,
            {"award_id": 3187665},
        )
        award_columns = [d[0] for d in cursor.description]
        for row in cursor.fetchall():
            print(dict(zip(award_columns, row)))
    finally:
        connection.close()


if __name__ == "__main__":
    main()
