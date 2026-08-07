"""Regression tests for build_search_embedding_poc.py's pure logic -
source-text construction, hashing, and the pgvector text-literal format.
The actual Bedrock call and database writes require live AWS/Postgres
access and are exercised by running the script itself, not by these
tests.
"""

from __future__ import annotations

import build_search_embedding_poc as poc


def test_build_source_text_includes_every_present_field_with_labels():
    result = poc.build_source_text(
        module="AWARD",
        business_number="100803-00001",
        title="Neuroimaging Genetics of PTSD",
        person_name="Jane Smith",
        sponsor="NIH",
        lead_unit="Medicine",
        status="Active",
    )

    assert result == (
        "module: AWARD | business number: 100803-00001 | "
        "title: Neuroimaging Genetics of PTSD | PI/person: Jane Smith | "
        "sponsor: NIH | lead unit: Medicine | status: Active"
    )


def test_build_source_text_omits_missing_fields_rather_than_inserting_blanks():
    result = poc.build_source_text(
        module="NEGOTIATION",
        business_number="367756",
        title=None,
        person_name="William Segarra",
        sponsor=None,
        lead_unit=None,
        status="Executed",
    )

    assert result == (
        "module: NEGOTIATION | business number: 367756 | "
        "PI/person: William Segarra | status: Executed"
    )
    assert "title:" not in result
    assert "sponsor:" not in result
    assert "lead unit:" not in result


def test_source_hash_is_deterministic_sha256():
    text_value = "module: AWARD | business number: 100803-00001"

    first = poc.source_hash(text_value)
    second = poc.source_hash(text_value)

    assert first == second
    assert len(first) == 64  # sha256 hex digest length


def test_source_hash_changes_when_source_text_changes():
    original = poc.source_hash("title: A")
    changed = poc.source_hash("title: B")

    assert original != changed


def test_vector_literal_formats_a_python_list_as_pgvector_text_input():
    assert poc._vector_literal([0.1, 0.2, 0.3]) == "[0.1,0.2,0.3]"


def test_vector_literal_handles_an_empty_list():
    assert poc._vector_literal([]) == "[]"


def test_embedding_dimensions_matches_the_migrations_vector_column():
    # database/migrations/V069__create_search_embedding_poc.sql declares
    # embedding VECTOR(1024) - this constant must stay in sync with it.
    assert poc.EMBEDDING_DIMENSIONS == 1024
