"""Regression tests for run_search_diagnostics.py - the read-only Global
Search Performance Sprint diagnostics task. These cover only the pure
logic (sample-value extraction, suite/argument wiring); the actual SQL
against archive.* requires a live database and is exercised by running
the script itself (locally or as a one-off ECS task), not by these
tests.
"""

from __future__ import annotations

import pytest

import run_search_diagnostics as diagnostics


def test_sample_value_returns_the_first_non_empty_value():
    rows = [{"title": None}, {"title": ""}, {"title": "Diabetes Research"}]

    assert diagnostics._sample_value(rows, "title") == "Diabetes Research"


def test_sample_value_returns_none_when_every_row_is_empty_or_missing():
    rows = [{"title": None}, {"other_column": "x"}]

    assert diagnostics._sample_value(rows, "title") is None


def test_sample_value_handles_an_empty_row_list():
    assert diagnostics._sample_value([], "title") is None


def test_registered_suites_are_exactly_the_expected_set():
    assert set(diagnostics.SUITES.keys()) == {
        "global-search-baseline",
        "isolate-anomaly",
        "semantic-search-scope",
    }


def test_main_requires_either_suite_or_sql_file():
    with pytest.raises(SystemExit):
        diagnostics.main([])


def test_main_rejects_both_suite_and_sql_file_together():
    with pytest.raises(SystemExit):
        diagnostics.main([
            "--suite", "global-search-baseline",
            "--sql-file", "some-file.sql",
        ])
