from __future__ import annotations

import unittest

from load_protocols import (
    PERSONS_ORACLE_SQL,
    UNITS_ORACLE_SQL,
    VERSIONS_ORACLE_SQL,
)


class ProtocolLoaderFrameworkTest(unittest.TestCase):
    def test_oracle_extraction_sql_files_exist_and_are_readable(self) -> None:
        for sql_path in (VERSIONS_ORACLE_SQL, PERSONS_ORACLE_SQL, UNITS_ORACLE_SQL):
            self.assertTrue(
                sql_path.is_file(),
                f"expected Oracle extraction SQL at {sql_path}",
            )


if __name__ == "__main__":
    unittest.main()
