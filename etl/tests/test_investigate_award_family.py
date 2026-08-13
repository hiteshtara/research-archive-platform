"""Regression tests for investigate_award_family.py's pure logic
(_chunked) - the actual Oracle queries require a live connection and
are exercised by running the script itself (locally or as a one-off
ECS task), not by these tests, matching run_search_diagnostics.py's
own testing convention.
"""

from __future__ import annotations

import investigate_award_family as investigate


def test_chunked_splits_values_into_groups_of_the_requested_size():
    values = list(range(7))

    chunks = list(investigate._chunked(values, size=3))

    assert chunks == [[0, 1, 2], [3, 4, 5], [6]]


def test_chunked_returns_nothing_for_an_empty_input():
    assert list(investigate._chunked([])) == []


def test_chunked_defaults_to_the_oracle_in_list_chunk_size():
    values = list(range(investigate.MAX_IN_LIST_SIZE + 1))

    chunks = list(investigate._chunked(values))

    assert len(chunks) == 2
    assert len(chunks[0]) == investigate.MAX_IN_LIST_SIZE
    assert len(chunks[1]) == 1
