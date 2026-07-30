from __future__ import annotations

import unittest
from pathlib import Path

from load_negotiations_from_csv import DOWNLOAD_DIR, ORACLE_SQL, parse_args


class NegotiationLoaderFrameworkTest(unittest.TestCase):
    def test_oracle_is_default_and_csv_is_explicit(self) -> None:
        defaults = parse_args([])
        self.assertFalse(defaults.csv)
        self.assertEqual(defaults.csv_dir, DOWNLOAD_DIR)

        explicit_csv = parse_args(["--csv"])
        self.assertTrue(explicit_csv.csv)

        custom_dir = parse_args(["--csv-dir", "/tmp/negotiation-exports"])
        self.assertEqual(custom_dir.csv_dir, Path("/tmp/negotiation-exports"))

    def test_oracle_and_csv_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            parse_args(["--oracle", "--csv"])

    def test_oracle_extraction_sql_files_exist_and_are_readable(self) -> None:
        for key, sql_path in ORACLE_SQL.items():
            self.assertTrue(
                sql_path.is_file(),
                f"expected Oracle extraction SQL for {key!r} at {sql_path}",
            )


if __name__ == "__main__":
    unittest.main()
