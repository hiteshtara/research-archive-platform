from __future__ import annotations

from pathlib import Path

import pytest

from archive_etl.upload.migrations import (
    discover_migrations,
    INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS,
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


# --- intentional-gap exclusion (V073) ---------------------------------
#
# V073 was never committed; V077 implements its schema change verbatim
# and states it deliberately does not depend on V073. Version 73 is an
# intentional historical gap. These tests pin that the exclusion is
# exactly that narrow - a genuinely missing migration must still be
# reported, or the detector stops earning its keep.


def _write(directory: Path, *versions: int) -> None:
    for version in versions:
        (directory / f"V{version:03d}__test_migration.sql").write_text(
            "SELECT 1;", encoding="utf-8"
        )


def test_exclusion_set_contains_only_v073() -> None:
    assert INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS == {73}


def test_v073_gap_is_not_reported(tmp_path: Path) -> None:
    """The real committed shape: 70,71,72,74..77 - 73 intentionally absent."""
    _write(tmp_path, 70, 71, 72, 74, 75, 76, 77)
    assert find_missing_migration_versions(tmp_path) == []


def test_genuine_gap_at_72_is_still_reported(tmp_path: Path) -> None:
    _write(tmp_path, 70, 71, 73, 74)
    assert find_missing_migration_versions(tmp_path) == [72]


def test_genuine_gap_at_75_is_still_reported(tmp_path: Path) -> None:
    _write(tmp_path, 74, 76, 77)
    assert find_missing_migration_versions(tmp_path) == [75]


def test_multiple_genuine_gaps_are_all_reported(tmp_path: Path) -> None:
    _write(tmp_path, 70, 74, 77)
    assert find_missing_migration_versions(tmp_path) == [71, 72, 75, 76]


def test_genuine_gaps_reported_even_when_v073_also_absent(tmp_path: Path) -> None:
    """73 is filtered out; the real gaps beside it are not."""
    _write(tmp_path, 70, 72, 74, 76)
    assert find_missing_migration_versions(tmp_path) == [71, 75]


def test_exclusion_does_not_apply_to_neighbouring_versions(tmp_path: Path) -> None:
    """72 is reported, 73 is not - proving the filter is version-specific
    and not a range or an off-by-one."""
    _write(tmp_path, 71, 74)
    assert find_missing_migration_versions(tmp_path) == [72]
