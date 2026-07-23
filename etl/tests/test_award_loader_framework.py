from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import pandas as pd

from load_awards_from_csv import prepare_versions, read_csv


class AwardLoaderFrameworkTest(unittest.TestCase):
    def test_shared_csv_source_preserves_award_preparation(self) -> None:
        with TemporaryDirectory() as directory:
            path = Path(directory) / "award_versions.csv"
            pd.DataFrame(
                [
                    {
                        "AWARD ID": "101",
                        "AWARD-NUMBER": "000001",
                        "SEQUENCE_NUMBER": "1",
                        "TITLE": "Award",
                        "AWARD_SEQUENCE_STATUS": "ACTIVE",
                        "UPDATE_TIMESTAMP": "2025-01-02 03:04:05",
                    }
                ]
            ).to_csv(path, index=False)

            prepared = prepare_versions(read_csv(path))

        self.assertEqual(prepared["award_id"].tolist(), [101])
        self.assertEqual(
            prepared["award_number"].tolist(),
            ["000001"],
        )
        self.assertEqual(
            prepared["is_primary_current"].tolist(),
            [True],
        )


if __name__ == "__main__":
    unittest.main()
