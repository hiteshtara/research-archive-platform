"""Regression test proving V071 (extend archive.search_embedding for Award
evidence indexing) is present in the COMMITTED migration chain and that a
fresh chain reproduces dev RDS's real schema.

Why this exists: V071 was applied to dev RDS but never committed to git, so
a fresh clone could not reproduce dev's schema from committed migrations
alone. That gap surfaced as 17 failures in
tests/test_build_evidence_embedding.py ("column document_type does not
exist") and as a permanent
"Gap detected in migration sequence on disk - missing version(s): V071,
V073" warning on every loader run.

Deliberately builds the chain from `git ls-files database/migrations`, not
the raw source tree - mirroring
test_v077_subaward_attachment_archive_migration.py - so this test can never
silently pass because of an untracked file sitting in a working tree. Run
with V071 staged (`git add`) if run before it is committed, since
`git ls-files` reflects the index.

Skips entirely when no local PostgreSQL is reachable.
"""

from __future__ import annotations

import getpass
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine

from archive_etl.upload.migrations import (
    INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS,
    apply_migrations,
    find_missing_migration_versions,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
TARGET_VERSION = 71

_MIGRATION_VERSION_PATTERN = re.compile(r"^V(\d+)__")

POSTGRES_HOST = os.environ.get("PYTEST_POSTGRES_HOST", "localhost")
POSTGRES_PORT = os.environ.get("PYTEST_POSTGRES_PORT", "5432")
POSTGRES_USER = os.environ.get("PYTEST_POSTGRES_USER", getpass.getuser())
MAINTENANCE_DB = os.environ.get("PYTEST_POSTGRES_MAINTENANCE_DB", "postgres")

V071_COLUMNS = {
    "document_type",
    "parent_module",
    "parent_business_identifier",
    "exact_record_id",
    "version_label",
    "source_table",
    "source_primary_key",
    "source_row_hash",
}


def _migration_version(path: Path) -> int | None:
    match = _MIGRATION_VERSION_PATTERN.match(path.name)
    return int(match.group(1)) if match else None


def _maintenance_engine() -> Engine:
    return create_engine(
        f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
        f"{POSTGRES_PORT}/{MAINTENANCE_DB}"
    )


def _postgres_available() -> bool:
    try:
        engine = _maintenance_engine()
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        engine.dispose()
    except Exception:
        return False
    return True


def _git_tracked_migration_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--", "database/migrations"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=True,
    )
    paths = [REPO_ROOT / line for line in result.stdout.splitlines() if line.strip()]
    return [path for path in paths if path.exists()]


def _clean_migrations_dir() -> Path:
    destination = Path(tempfile.mkdtemp(prefix="clean_migrations_v071_"))
    for source_path in _git_tracked_migration_files():
        shutil.copy2(source_path, destination / source_path.name)
    return destination


def _create_throwaway_database(name: str) -> None:
    maintenance = _maintenance_engine()
    with maintenance.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'CREATE DATABASE "{name}"'))
    maintenance.dispose()


def _drop_throwaway_database(name: str) -> None:
    maintenance = _maintenance_engine()
    with maintenance.connect() as connection:
        connection.execution_options(isolation_level="AUTOCOMMIT")
        connection.execute(text(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)'))
    maintenance.dispose()


class V071CommittedChainTest(unittest.TestCase):
    """Git-only assertions - no database required."""

    def test_v071_is_tracked_in_git(self):
        names = {p.name for p in _git_tracked_migration_files()}
        v071 = [n for n in names if n.startswith("V071__")]
        self.assertEqual(
            len(v071), 1,
            "V071 must be committed so a fresh clone reproduces dev's schema",
        )

    def test_v073_is_not_reintroduced(self):
        """V077 already implements V073's schema change. Committing V073
        would run a DROP CONSTRAINT without IF EXISTS ahead of V077 on a
        fresh database."""
        names = {p.name for p in _git_tracked_migration_files()}
        self.assertEqual(
            [n for n in names if n.startswith("V073__")], [],
            "V073 is superseded by V077 and must not be committed",
        )

    def test_committed_chain_reports_no_missing_versions(self):
        """V071 is restored, and 73 is registered in
        INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS (V077 owns that
        schema change), so the committed chain reports nothing missing.

        This assertion previously expected [73]; that was correct only
        while the gap was unregistered. The gap itself has not moved -
        V073 is still deliberately uncommitted - it is now declared
        rather than reported."""
        clean = _clean_migrations_dir()
        try:
            self.assertEqual(find_missing_migration_versions(clean), [])
            self.assertIn(
                73, INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS,
                "73 must be silent only because it is explicitly declared",
            )
        finally:
            shutil.rmtree(clean, ignore_errors=True)


@unittest.skipUnless(_postgres_available(), "local PostgreSQL not reachable")
class V071FreshChainSchemaTest(unittest.TestCase):
    """Applies the committed chain to a throwaway database and asserts it
    reproduces dev RDS's verified search_embedding schema."""

    def setUp(self):
        self.database = f"v071_test_{uuid.uuid4().hex[:12]}"
        _create_throwaway_database(self.database)
        self.engine = create_engine(
            f"postgresql+psycopg://{POSTGRES_USER}@{POSTGRES_HOST}:"
            f"{POSTGRES_PORT}/{self.database}"
        )
        self.migrations = _clean_migrations_dir()
        apply_migrations(self.engine, self.migrations)

    def tearDown(self):
        self.engine.dispose()
        shutil.rmtree(self.migrations, ignore_errors=True)
        _drop_throwaway_database(self.database)

    def _columns(self) -> set[str]:
        with self.engine.connect() as c:
            return {
                r[0] for r in c.execute(text(
                    "SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema='archive' AND table_name='search_embedding'"
                ))
            }

    def _indexes(self) -> set[str]:
        with self.engine.connect() as c:
            return {
                r[0] for r in c.execute(text(
                    "SELECT indexname FROM pg_indexes "
                    "WHERE schemaname='archive' AND tablename='search_embedding'"
                ))
            }

    def test_fresh_chain_creates_every_v071_column(self):
        self.assertTrue(
            V071_COLUMNS.issubset(self._columns()),
            f"missing: {sorted(V071_COLUMNS - self._columns())}",
        )

    def test_new_unique_index_exists(self):
        self.assertIn("ix_search_embedding_module_type_record", self._indexes())

    def test_obsolete_v070_index_is_gone(self):
        """V071 replaces (module, record_id) uniqueness with
        (module, document_type, exact_record_id)."""
        self.assertNotIn("ix_search_embedding_record", self._indexes())

    def test_supporting_evidence_indexes_exist(self):
        indexes = self._indexes()
        for name in (
            "ix_search_embedding_document_type",
            "ix_search_embedding_parent",
            "ix_search_embedding_source_row",
        ):
            self.assertIn(name, indexes)

    def test_evidence_code_can_query_document_type(self):
        """The exact shape AwardEvidenceRetrievalRepository issues -
        this is what failed with 'column document_type does not exist'."""
        with self.engine.connect() as c:
            rows = c.execute(text(
                "SELECT document_type, exact_record_id, parent_module, "
                "       source_table, source_primary_key, source_row_hash "
                "FROM archive.search_embedding "
                "WHERE document_type IN ('AWARD_VERSION','AWARD_PERSON')"
            )).fetchall()
        self.assertEqual(rows, [])

    def test_v071_recorded_in_schema_migration(self):
        with self.engine.connect() as c:
            versions = {
                r[0] for r in c.execute(
                    text("SELECT version FROM public.schema_migration")
                )
            }
        self.assertIn(TARGET_VERSION, versions)
        self.assertNotIn(73, versions)


if __name__ == "__main__":
    unittest.main()
