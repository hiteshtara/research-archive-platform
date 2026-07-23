from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

import pandas as pd
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.pipeline.reconciliation import (
    Reconciler,
    no_reconciliation,
)
from archive_etl.pipeline.reporting import LoadReport
from archive_etl.upload.bulk_copy import bulk_copy_dataframe
from archive_etl.upload.migrations import apply_migrations


LoadOperation = Callable[["PostgreSQLLoadContext"], int]


class PostgreSQLLoadContext:
    def __init__(
        self,
        connection: Connection,
        load_id: int,
    ) -> None:
        self.connection = connection
        self.load_id = load_id

    def copy_dataframe(
        self,
        dataframe: pd.DataFrame,
        *,
        schema: str,
        table: str,
    ) -> int:
        return bulk_copy_dataframe(
            connection=self.connection,
            dataframe=dataframe,
            schema=schema,
            table=table,
        )

    def execute_many(
        self,
        statement: Any,
        rows: Sequence[Mapping[str, Any]],
        *,
        batch_size: int = 1000,
    ) -> int:
        for offset in range(0, len(rows), batch_size):
            self.connection.execute(
                statement,
                rows[offset:offset + batch_size],
            )
        return len(rows)


class PostgreSQLLoader:
    def __init__(
        self,
        engine: Engine,
        migrations_directory: str | Path,
    ) -> None:
        self.engine = engine
        self.migrations_directory = migrations_directory

    def apply_migrations(self) -> None:
        apply_migrations(
            self.engine,
            self.migrations_directory,
        )

    def load(
        self,
        *,
        domain: str,
        source_system: str,
        source_name: str,
        rows_read: int,
        operation: LoadOperation,
        reconciler: Reconciler = no_reconciliation,
    ) -> LoadReport:
        load_id: int | None = None
        try:
            with self.engine.begin() as connection:
                load_id = self._create_load_run(
                    connection,
                    domain=domain,
                    source_system=source_system,
                    source_name=source_name,
                    rows_read=rows_read,
                )
                context = PostgreSQLLoadContext(
                    connection,
                    load_id,
                )
                rows_loaded = operation(context)
                reconciliation = reconciler(connection)
                self._mark_complete(
                    connection,
                    load_id,
                    rows_loaded,
                )

            return LoadReport(
                domain=domain,
                load_id=load_id,
                rows_read=rows_read,
                rows_loaded=rows_loaded,
                reconciliation=reconciliation,
            )
        except Exception as error:
            if load_id is not None:
                self._mark_failed(load_id, str(error))
            raise

    def _create_load_run(
        self,
        connection: Connection,
        *,
        domain: str,
        source_system: str,
        source_name: str,
        rows_read: int,
    ) -> int:
        return int(
            connection.execute(
                text(
                    """
                    INSERT INTO archive.load_run (
                        domain,
                        source_system,
                        source_file_name,
                        rows_read,
                        status
                    )
                    VALUES (
                        :domain,
                        :source_system,
                        :source_name,
                        :rows_read,
                        'STARTED'
                    )
                    RETURNING load_id
                    """
                ),
                {
                    "domain": domain,
                    "source_system": source_system,
                    "source_name": source_name,
                    "rows_read": rows_read,
                },
            ).scalar_one()
        )

    def _mark_complete(
        self,
        connection: Connection,
        load_id: int,
        rows_loaded: int,
    ) -> None:
        connection.execute(
            text(
                """
                UPDATE archive.load_run
                   SET status = 'LOADED',
                       rows_staged = :rows_loaded,
                       rows_loaded = :rows_loaded,
                       rows_rejected = 0,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE load_id = :load_id
                """
            ),
            {
                "load_id": load_id,
                "rows_loaded": rows_loaded,
            },
        )

    def _mark_failed(
        self,
        load_id: int,
        error_message: str,
    ) -> None:
        with self.engine.begin() as connection:
            connection.execute(
                text(
                    """
                    UPDATE archive.load_run
                       SET status = 'FAILED',
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
