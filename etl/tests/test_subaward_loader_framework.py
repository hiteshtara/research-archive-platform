from __future__ import annotations

import unittest

from load_subawards_from_csv import DATASETS


class SubawardLoaderFrameworkTest(unittest.TestCase):
    def test_every_dataset_has_a_matching_oracle_extraction_file(self) -> None:
        self.assertEqual(len(DATASETS), 11)
        for spec in DATASETS:
            self.assertTrue(
                spec.oracle_path.is_file(),
                f"expected Oracle extraction SQL for {spec.key!r} at {spec.oracle_path}",
            )


if __name__ == "__main__":
    unittest.main()
