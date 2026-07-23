from __future__ import annotations

import unittest
from unittest.mock import MagicMock

import pandas as pd

from archive_etl.pipeline.postgres import PostgreSQLLoadContext
from load_protocol_personnel import (
    PERSON_COLUMNS,
    prepare_persons,
    prepare_units,
    upsert_dataframe,
    validate_relationships,
)


class ProtocolPersonnelLoaderTest(unittest.TestCase):
    def test_person_contract_excludes_sensitive_columns(self) -> None:
        source = pd.DataFrame(
            [
                {
                    "protocol_person_id": "10",
                    "source_protocol_id": "999",
                    "protocol_number": "000100",
                    "sequence_number": "2",
                    "person_name": "Researcher",
                    "ssn": "must-not-load",
                    "date_of_birth": "2000-01-01",
                    "home_address": "must-not-load",
                }
            ]
        )

        prepared = prepare_persons(source)

        self.assertEqual(prepared.columns.tolist(), PERSON_COLUMNS)
        self.assertNotIn("ssn", prepared.columns)
        self.assertNotIn("date_of_birth", prepared.columns)
        self.assertNotIn("home_address", prepared.columns)

    def test_exact_version_and_person_relationships_validate(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "999",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                        "person_id": "P1",
                    }
                ]
            )
        )
        units = prepare_units(
            pd.DataFrame(
                [
                    {
                        "protocol_units_id": "20",
                        "protocol_person_id": "10",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                        "unit_number": "UNIT",
                        "person_id": "P1",
                    }
                ]
            )
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]

        report = validate_relationships(
            PostgreSQLLoadContext(connection, 7),
            persons,
            units,
        )
        self.assertEqual(
            persons["protocol_id"].tolist(),
            [100],
        )
        self.assertEqual(
            persons["source_protocol_id"].tolist(),
            [999],
        )
        self.assertEqual(
            report.source_protocol_id_differs_from_resolved_protocol_id,
            1,
        )

    def test_version_mismatch_fails(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "100",
                        "protocol_number": "000100",
                        "sequence_number": "3",
                    }
                ]
            )
        )
        units = prepare_units(
            pd.DataFrame(
                columns=[
                    "protocol_units_id",
                    "protocol_person_id",
                    "protocol_number",
                    "sequence_number",
                ]
            )
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]

        with self.assertRaisesRegex(
            RuntimeError,
            "missing=1, ambiguous=0",
        ):
            validate_relationships(
                PostgreSQLLoadContext(connection, 7),
                persons,
                units,
            )

    def test_ambiguous_number_and_sequence_fails(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "999",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                    }
                ]
            )
        )
        units = prepare_units(
            pd.DataFrame(
                columns=[
                    "protocol_units_id",
                    "protocol_person_id",
                    "protocol_number",
                    "sequence_number",
                ]
            )
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            },
            {
                "protocol_id": 101,
                "protocol_number": "000100",
                "sequence_number": 2,
            },
        ]

        with self.assertRaisesRegex(
            RuntimeError,
            "missing=0, ambiguous=1",
        ):
            validate_relationships(
                PostgreSQLLoadContext(connection, 7),
                persons,
                units,
            )

    def test_matching_source_protocol_id_is_reported_as_match(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "100",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                    }
                ]
            )
        )
        units = prepare_units(
            pd.DataFrame(
                columns=[
                    "protocol_units_id",
                    "protocol_person_id",
                    "protocol_number",
                    "sequence_number",
                ]
            )
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]

        report = validate_relationships(
            PostgreSQLLoadContext(connection, 7),
            persons,
            units,
        )

        self.assertEqual(persons["protocol_id"].tolist(), [100])
        self.assertEqual(
            report.source_protocol_id_differs_from_resolved_protocol_id,
            0,
        )

    def test_unit_version_fields_are_audit_only(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "100",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                        "person_id": "P1",
                    }
                ]
            )
        )
        units = prepare_units(
            pd.DataFrame(
                [
                    {
                        "protocol_units_id": "20",
                        "protocol_person_id": "10",
                        "protocol_number": "000100",
                        "sequence_number": "1",
                        "person_id": "P1",
                    }
                ]
            )
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]

        report = validate_relationships(
            PostgreSQLLoadContext(connection, 7),
            persons,
            units,
        )

        self.assertEqual(
            report.unit_number_sequence_audit_mismatches,
            1,
        )
        self.assertEqual(
            report.unit_person_identity_inconsistencies,
            0,
        )

    def test_unit_without_person_parent_fails(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "100",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                    }
                ]
            )
        )
        units = prepare_units(
            pd.DataFrame(
                [
                    {
                        "protocol_units_id": "20",
                        "protocol_person_id": "11",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                    }
                ]
            )
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]

        with self.assertRaisesRegex(
            RuntimeError,
            "orphan protocol_person_id 11",
        ):
            validate_relationships(
                PostgreSQLLoadContext(connection, 7),
                persons,
                units,
            )

    def test_unexplained_unit_person_identity_mismatch_fails(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "100",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                        "person_id": "P1",
                    }
                ]
            )
        )
        units = prepare_units(
            pd.DataFrame(
                [
                    {
                        "protocol_units_id": "20",
                        "protocol_person_id": "10",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                        "person_id": "P2",
                    }
                ]
            )
        )
        connection = MagicMock()
        connection.execute.return_value.mappings.return_value = [
            {
                "protocol_id": 100,
                "protocol_number": "000100",
                "sequence_number": 2,
            }
        ]

        with self.assertRaisesRegex(
            RuntimeError,
            "unexplained person identity mismatch",
        ):
            validate_relationships(
                PostgreSQLLoadContext(connection, 7),
                persons,
                units,
            )

    def test_upsert_is_idempotent_by_physical_key(self) -> None:
        persons = prepare_persons(
            pd.DataFrame(
                [
                    {
                        "protocol_person_id": "10",
                        "source_protocol_id": "999",
                        "protocol_number": "000100",
                        "sequence_number": "2",
                    }
                ]
            )
        )
        connection = MagicMock()

        loaded = upsert_dataframe(
            PostgreSQLLoadContext(connection, 7),
            persons,
            table="protocol_person",
            primary_key="protocol_person_id",
        )

        self.assertEqual(loaded, 1)
        sql = str(connection.execute.call_args.args[0])
        self.assertIn(
            "ON CONFLICT (protocol_person_id) DO UPDATE",
            sql,
        )


if __name__ == "__main__":
    unittest.main()
