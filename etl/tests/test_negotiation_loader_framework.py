from __future__ import annotations

import unittest

from load_negotiations_from_csv import ORACLE_SQL


class NegotiationLoaderFrameworkTest(unittest.TestCase):
    def test_oracle_extraction_sql_files_exist_and_are_readable(self) -> None:
        for key, sql_path in ORACLE_SQL.items():
            self.assertTrue(
                sql_path.is_file(),
                f"expected Oracle extraction SQL for {key!r} at {sql_path}",
            )


if __name__ == "__main__":
    unittest.main()
