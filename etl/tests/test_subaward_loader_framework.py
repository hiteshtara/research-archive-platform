from __future__ import annotations

import unittest
from pathlib import Path

from load_subawards_from_csv import DATASETS, DOWNLOAD_DIR, parse_args


class SubawardLoaderFrameworkTest(unittest.TestCase):
    def test_oracle_is_default_and_csv_is_explicit(self) -> None:
        defaults = parse_args([])
        self.assertFalse(defaults.csv)
        self.assertEqual(defaults.csv_dir, DOWNLOAD_DIR)

        explicit_csv = parse_args(["--csv"])
        self.assertTrue(explicit_csv.csv)

        custom_dir = parse_args(["--csv-dir", "/tmp/subaward-exports"])
        self.assertEqual(custom_dir.csv_dir, Path("/tmp/subaward-exports"))

    def test_oracle_and_csv_are_mutually_exclusive(self) -> None:
        with self.assertRaises(SystemExit):
            parse_args(["--oracle", "--csv"])

    def test_every_dataset_has_a_matching_oracle_extraction_file(self) -> None:
        self.assertEqual(len(DATASETS), 11)
        for spec in DATASETS:
            self.assertTrue(
                spec.oracle_path.is_file(),
                f"expected Oracle extraction SQL for {spec.key!r} at {spec.oracle_path}",
            )

    def test_csv_path_honors_a_custom_csv_dir(self) -> None:
        custom_dir = Path("/tmp/subaward-exports")
        spec = DATASETS[0]
        self.assertEqual(spec.csv_path(custom_dir), custom_dir / spec.file_name)


if __name__ == "__main__":
    unittest.main()
