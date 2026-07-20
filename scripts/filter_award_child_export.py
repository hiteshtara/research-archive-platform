#!/usr/bin/env python3

import argparse
import csv
import shutil
from pathlib import Path


def normalized(value: str | None) -> str:
    return (value or "").strip()


def read_parent_award_ids(path: Path) -> set[str]:
    award_ids: set[str] = set()

    with path.open(
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as file:
        reader = csv.DictReader(file)

        if reader.fieldnames is None:
            raise RuntimeError(
                f"CSV has no header: {path}"
            )

        column_map = {
            column.strip().upper(): column
            for column in reader.fieldnames
        }

        award_id_column = column_map.get("AWARD_ID")

        if award_id_column is None:
            raise RuntimeError(
                f"AWARD_ID column not found in {path}"
            )

        for row in reader:
            award_id = normalized(
                row.get(award_id_column)
            )

            if award_id:
                award_ids.add(award_id)

    return award_ids


def filter_child_file(
    parent_path: Path,
    child_path: Path,
) -> None:
    valid_award_ids = read_parent_award_ids(
        parent_path
    )

    if not valid_award_ids:
        raise RuntimeError(
            "No Award IDs were found in the parent file"
        )

    backup_path = child_path.with_suffix(
        child_path.suffix + ".original"
    )

    if not backup_path.exists():
        shutil.copy2(
            child_path,
            backup_path,
        )

    temporary_path = child_path.with_suffix(
        child_path.suffix + ".filtered"
    )

    rows_read = 0
    rows_written = 0
    rejected_award_ids: set[str] = set()

    with child_path.open(
        "r",
        encoding="utf-8-sig",
        newline="",
    ) as source:
        reader = csv.DictReader(source)

        if reader.fieldnames is None:
            raise RuntimeError(
                f"CSV has no header: {child_path}"
            )

        column_map = {
            column.strip().upper(): column
            for column in reader.fieldnames
        }

        award_id_column = column_map.get("AWARD_ID")

        if award_id_column is None:
            raise RuntimeError(
                f"AWARD_ID column not found in {child_path}"
            )

        with temporary_path.open(
            "w",
            encoding="utf-8",
            newline="",
        ) as destination:
            writer = csv.DictWriter(
                destination,
                fieldnames=reader.fieldnames,
                extrasaction="ignore",
            )

            writer.writeheader()

            for row in reader:
                rows_read += 1

                award_id = normalized(
                    row.get(award_id_column)
                )

                if award_id in valid_award_ids:
                    writer.writerow(row)
                    rows_written += 1
                else:
                    rejected_award_ids.add(
                        award_id or "<blank>"
                    )

    temporary_path.replace(child_path)

    print(
        f"Parent Award IDs: "
        f"{len(valid_award_ids):,}"
    )

    print(
        f"Child rows read: "
        f"{rows_read:,}"
    )

    print(
        f"Child rows retained: "
        f"{rows_written:,}"
    )

    print(
        f"Child rows removed: "
        f"{rows_read - rows_written:,}"
    )

    print(
        f"Distinct rejected Award IDs: "
        f"{len(rejected_award_ids):,}"
    )

    if rejected_award_ids:
        print("\nRejected Award ID preview:")

        for award_id in sorted(
            rejected_award_ids
        )[:25]:
            print(award_id)

    print(
        f"\nOriginal backup: {backup_path}"
    )

    print(
        f"Filtered export: {child_path}"
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Filter an Award child CSV to the exact "
            "Award IDs present in award_versions.csv."
        )
    )

    parser.add_argument(
        "--parent",
        required=True,
        type=Path,
        help="Path to award_versions.csv",
    )

    parser.add_argument(
        "--child",
        required=True,
        type=Path,
        help="Path to an Award child CSV",
    )

    arguments = parser.parse_args()

    filter_child_file(
        arguments.parent.expanduser(),
        arguments.child.expanduser(),
    )


if __name__ == "__main__":
    main()
