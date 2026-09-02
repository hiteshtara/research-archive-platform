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


def discover_migrations(
    migrations_directory: str | Path,
) -> list[tuple[int, str, Path]]:
    directory = Path(migrations_directory)

    if not directory.exists():
        raise FileNotFoundError(
            f"Migration directory not found: {directory}"
        )

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
    return migrations


# Version numbers that are deliberately absent from the committed
# migration sequence. Each entry is a specific, justified historical
# gap - never a way to quieten an unexplained one.
#
# 73: V073__extend_subaward_attachment_archive_status.sql was written
#     but never committed to any git ref. Its schema change (widening
#     archive.subaward_attachment_archive.archive_status to include
#     PENDING/UPLOADING, plus DEFAULT 'PENDING') is implemented verbatim
#     by the committed V077, which states in its own header that it
#     deliberately does not depend on V073 so a clean checkout never
#     needs that file. Restoring V073 would also be unsafe: its
#     DROP CONSTRAINT has no IF EXISTS and would abort a fresh chain
#     wherever V019's constraint name differs. Version 73 is therefore
#     an intentional historical gap, not a missing executable migration.
INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS = {73}


def find_missing_migration_versions(
    migrations_directory: str | Path,
) -> list[int]:
    """Return version numbers with a gap in the on-disk migration sequence.

    Renumbering or deleting a migration file after it has shipped can leave a
    gap (e.g. V001, V002, V004 with V003 missing) that is easy to miss in
    review. This checks the files present on disk; it does not look at what
    has been applied to any particular database.

    Versions listed in INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS are
    excluded - each one is individually documented above. Every other gap
    is still reported exactly as before, so a genuinely lost or misnumbered
    migration remains just as visible.
    """
    versions = [version for version, _, _ in discover_migrations(migrations_directory)]

    if not versions:
        return []

    expected = set(range(versions[0], versions[-1] + 1))
    missing = expected - set(versions)
    return sorted(missing - INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS)


def apply_migrations(
    engine: Engine,
    migrations_directory: str | Path,
) -> None:
    migrations = discover_migrations(migrations_directory)

    missing_versions = find_missing_migration_versions(migrations_directory)
    if missing_versions:
        logger.warning(
            "Gap detected in migration sequence on disk - missing version(s): {}",
            ", ".join(f"V{version:03d}" for version in missing_versions),
        )

    ensure_migration_table(engine)
    applied_versions = get_applied_versions(engine)

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
