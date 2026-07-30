from __future__ import annotations

import unittest
from pathlib import Path

from load_proposals_from_csv import (
    AWARDS_ORACLE_SQL,
    DOWNLOAD_DIR,
    VERSIONS_ORACLE_SQL,
    parse_args,
)


class ProposalLoaderFrameworkTest(unittest.TestCase):
    def test_oracle_is_default_and_csv_is_explicit(self) -> None:
        defaults = parse_args([])
        self.assertFalse(defaults.csv)
        self.assertEqual(defaults.csv_dir, DOWNLOAD_DIR)

        explicit_csv = parse_args(["--csv"])
        self.assertTrue(explicit_csv.csv)

        custom_dir = parse_args(["--csv-dir", "/tmp/proposal-exports"])
        self.assertEqual(custom_dir.csv_dir, Path("/tmp/proposal-exports"))

    def test_oracle_and_csv_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            parse_args(["--oracle", "--csv"])

    def test_oracle_extraction_sql_files_exist_and_are_readable(self) -> None:
        # proposal_people.csv has no Oracle equivalent yet (see the comment
        # in load_proposals_from_csv.py) and is deliberately not checked here.
        for sql_path in (VERSIONS_ORACLE_SQL, AWARDS_ORACLE_SQL):
            self.assertTrue(
                sql_path.is_file(),
                f"expected Oracle extraction SQL at {sql_path}",
            )


if __name__ == "__main__":
    unittest.main()
