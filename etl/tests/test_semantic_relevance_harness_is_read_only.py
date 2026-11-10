"""The measurement harness cannot write, and that is checked rather than claimed.

QA TC-041's measurement reads a shared DEV database. "It only does SELECTs"
is an easy thing to say about a script and a hard thing to be sure of six
months later, so the properties are asserted against the source.

These are static checks. They cannot prove what Postgres will do - the
runtime guard does that, by binding default_transaction_read_only at connect
and VERIFYING it before any query - but they catch the change that would
quietly turn a read-only tool into something else.
"""

from __future__ import annotations

import ast
import pathlib
import re
import unittest

HARNESS = pathlib.Path(__file__).resolve().parents[1] / "measure_semantic_relevance.py"
COMPARISON = pathlib.Path(__file__).resolve().parents[1] / "compare_semantic_relevance.py"

WRITE_VERBS = (
    "INSERT",
    "UPDATE",
    "DELETE",
    "TRUNCATE",
    "DROP",
    "ALTER",
    "CREATE TABLE",
    "CREATE INDEX",
    "GRANT",
    "REVOKE",
    "COPY",
)


class HarnessIsReadOnlyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = HARNESS.read_text()

    def sql_statements(self) -> list[str]:
        """Every string literal in the file that looks like SQL.

        Anchored on literals that START with SELECT or SHOW, which is what a
        statement in this file looks like. Two earlier versions of this check
        were wrong in instructive ways: scanning the whole file failed on the
        harness's own docstring - which says, accurately, "No INSERT, UPDATE,
        DELETE or DDL" - and on Python's `from x import y`; then merely
        CONTAINING "SELECT" still matched that docstring, because it also says
        "One SELECT per query". A check that cannot tell prose from a
        statement reports the documentation as the defect.
        """
        tree = ast.parse(self.source)
        return [
            node.value
            for node in ast.walk(tree)
            if isinstance(node, ast.Constant)
            and isinstance(node.value, str)
            and re.match(r"\s*(SELECT|SHOW)\b", node.value, re.IGNORECASE)
        ]

    def test_there_is_sql_to_check(self) -> None:
        """Otherwise every assertion below passes by finding nothing."""
        self.assertGreaterEqual(len(self.sql_statements()), 2)

    def test_no_sql_statement_writes(self) -> None:
        for statement in self.sql_statements():
            upper = statement.upper()
            for verb in WRITE_VERBS:
                self.assertNotIn(
                    verb, upper, f"{verb} in: {statement.strip()[:70]}"
                )

    def test_the_sql_reads_only_the_embedding_table(self) -> None:
        """One table. A measurement that can read anything is not a
        measurement, it is an export tool."""
        tables: set[str] = set()
        for statement in self.sql_statements():
            tables.update(
                re.findall(r"FROM\s+([a-z_]+\.[a-z_]+)", statement, re.IGNORECASE)
            )
        self.assertEqual(tables, {"archive.search_embedding"})

    def test_the_sql_selects_no_identifying_column(self) -> None:
        """The output must not be able to carry record content.

        The query selects the distance and nothing else - not the module, not
        the record id, not the business number - so even a mistake in the
        output code has nothing identifying to leak.
        """
        for statement in self.sql_statements():
            for column in ("module", "record_id", "canonical_family_id", "business_number"):
                self.assertNotIn(
                    column,
                    statement,
                    f"{column} selected in: {statement.strip()[:70]}",
                )

    def test_it_binds_the_read_only_guard_at_connect(self) -> None:
        """Setting it on a connection already in use silently does nothing.

        That has caught this project before, which is why it goes in
        connect_args rather than being issued as a statement afterwards.
        """
        self.assertIn("default_transaction_read_only=on", self.source)
        self.assertIn("connect_args", self.source)

    def test_it_verifies_the_guard_rather_than_assuming_it(self) -> None:
        self.assertIn("SHOW default_transaction_read_only", self.source)
        self.assertIn("Refusing to run", self.source)

    def test_it_refuses_production(self) -> None:
        self.assertIn("PRODUCTION_NAMES", self.source)
        for name in ("prod", "production", "prd"):
            self.assertIn(f'"{name}"', self.source)

    def test_it_refuses_to_run_without_an_explicit_acknowledgement(self) -> None:
        self.assertIn("--confirm-read-only", self.source)
        self.assertIn("confirm_read_only", self.source)

    def test_it_skips_the_queries_the_api_itself_skips(self) -> None:
        """Embedding an identifier-shaped query would measure a code path
        nobody runs - the API skips the semantic leg for those entirely."""
        self.assertIn("IDENTIFIER_SHAPED", self.source)

    def test_the_comparison_tool_touches_no_database_at_all(self) -> None:
        """It reads the JSON the harness wrote. It should not be able to
        reach the database even if someone pointed it at one."""
        source = COMPARISON.read_text()
        for forbidden in ("create_engine", "psycopg", "boto3", "connect("):
            self.assertNotIn(forbidden, source)

    def test_the_output_note_states_what_is_absent(self) -> None:
        """Someone reading the evidence file months later needs to know what
        it does NOT contain without re-reading the harness."""
        self.assertIn("No record content", self.source)


if __name__ == "__main__":
    unittest.main()
