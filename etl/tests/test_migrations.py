from __future__ import annotations

from pathlib import Path

import pytest

from archive_etl.upload.migrations import (
    discover_migrations,
    find_missing_migration_versions,
)


def _write_migration(directory: Path, version: int, description: str) -> None:
    (directory / f"V{version:03d}__{description}.sql").write_text(
        "SELECT 1;", encoding="utf-8"
    )


def test_discover_migrations_sorts_by_version(tmp_path: Path) -> None:
    _write_migration(tmp_path, 2, "second")
    _write_migration(tmp_path, 1, "first")

    migrations = discover_migrations(tmp_path)

    assert [version for version, _, _ in migrations] == [1, 2]


def test_discover_migrations_ignores_non_matching_files(tmp_path: Path) -> None:
    _write_migration(tmp_path, 1, "first")
    (tmp_path / "README.md").write_text("not a migration", encoding="utf-8")

    migrations = discover_migrations(tmp_path)

    assert len(migrations) == 1


def test_discover_migrations_raises_when_directory_missing(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        discover_migrations(tmp_path / "does-not-exist")


def test_find_missing_migration_versions_returns_empty_when_contiguous(
    tmp_path: Path,
) -> None:
    _write_migration(tmp_path, 1, "first")
    _write_migration(tmp_path, 2, "second")
    _write_migration(tmp_path, 3, "third")

    assert find_missing_migration_versions(tmp_path) == []


def test_find_missing_migration_versions_detects_gap(tmp_path: Path) -> None:
    _write_migration(tmp_path, 1, "first")
    _write_migration(tmp_path, 2, "second")
    _write_migration(tmp_path, 4, "fourth")

    assert find_missing_migration_versions(tmp_path) == [3]


def test_find_missing_migration_versions_empty_directory(tmp_path: Path) -> None:
    assert find_missing_migration_versions(tmp_path) == []
