from __future__ import annotations

import unittest

from load_proposals_from_csv import AWARDS_ORACLE_SQL, VERSIONS_ORACLE_SQL


class ProposalLoaderFrameworkTest(unittest.TestCase):
    def test_oracle_extraction_sql_files_exist_and_are_readable(self) -> None:
        # proposal_people has no Oracle equivalent and is no longer loaded
        # at all (see the comment in load_proposals_from_csv.py) - it is
        # deliberately not checked here.
        for sql_path in (VERSIONS_ORACLE_SQL, AWARDS_ORACLE_SQL):
            self.assertTrue(
                sql_path.is_file(),
                f"expected Oracle extraction SQL at {sql_path}",
            )


if __name__ == "__main__":
    unittest.main()
