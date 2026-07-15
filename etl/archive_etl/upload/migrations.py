from __future__ import annotations

import re
from pathlib import Path

from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Engine


MIGRATION_PATTERN = re.compile(
    r"^V(?P<version>\d+)__(?P<description>.+)\.sql$"
)


def ensure_migration_table(engine: Engine) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS public.schema_migration (
                    version       INTEGER PRIMARY KEY,
                    description   VARCHAR(500) NOT NULL,
                    file_name     VARCHAR(500) NOT NULL,
                    installed_at  TIMESTAMPTZ NOT NULL
                                  DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
        )


def get_applied_versions(engine: Engine) -> set[int]:
    with engine.connect() as connection:
        rows = connection.execute(
            text("SELECT version FROM public.schema_migration")
        )

        return {int(row.version) for row in rows}


def apply_migrations(
    engine: Engine,
    migrations_directory: str | Path,
) -> None:
    directory = Path(migrations_directory)

    if not directory.exists():
        raise FileNotFoundError(
            f"Migration directory not found: {directory}"
        )

    ensure_migration_table(engine)
    applied_versions = get_applied_versions(engine)

    migrations: list[tuple[int, str, Path]] = []

    for migration_file in directory.glob("V*__*.sql"):
        match = MIGRATION_PATTERN.match(migration_file.name)

        if not match:
            continue

        version = int(match.group("version"))
        description = match.group("description").replace("_", " ")

        migrations.append(
            (version, description, migration_file)
        )

    migrations.sort(key=lambda item: item[0])

    for version, description, migration_file in migrations:
        if version in applied_versions:
            logger.info(
                "Migration V{:03d} already applied: {}",
                version,
                description,
            )
            continue

        logger.info(
            "Applying migration V{:03d}: {}",
            version,
            description,
        )

        sql_text = migration_file.read_text(encoding="utf-8")

        raw_connection = engine.raw_connection()

        try:
            cursor = raw_connection.cursor()
            cursor.execute(sql_text)
            cursor.execute(
                """
                INSERT INTO public.schema_migration (
                    version,
                    description,
                    file_name
                )
                VALUES (%s, %s, %s)
                """,
                (
                    version,
                    description,
                    migration_file.name,
                ),
            )

            raw_connection.commit()
            cursor.close()

        except Exception:
            raw_connection.rollback()
            raise

        finally:
            raw_connection.close()

        logger.info(
            "Migration V{:03d} completed",
            version,
        )
