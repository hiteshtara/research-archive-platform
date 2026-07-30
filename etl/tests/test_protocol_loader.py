from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

import pandas as pd

import load_protocols as protocol_loader


class PrepareVersionsTest(unittest.TestCase):
    def test_accepts_valid_versions(self) -> None:
        dataframe = pd.DataFrame(
            [
                {
                    "protocol_id": 1,
                    "protocol_number": "P-1",
                    "sequence_number": 0,
                }
            ]
        )

        prepared = protocol_loader.prepare_versions(dataframe)

        self.assertEqual(prepared["protocol_id"].tolist(), [1])

    def test_rejects_duplicate_protocol_id(self) -> None:
        dataframe = pd.DataFrame(
            [
                {"protocol_id": 1, "protocol_number": "P-1", "sequence_number": 0},
                {"protocol_id": 1, "protocol_number": "P-2", "sequence_number": 0},
            ]
        )

        with self.assertRaises(RuntimeError):
            protocol_loader.prepare_versions(dataframe)

    def test_warns_on_repeated_number_sequence_pair(self) -> None:
        # Two different protocol_id rows sharing (protocol_number,
        # sequence_number) is exactly the "version grouping" ambiguity that
        # would later make NumberSequenceParentResolver raise
        # AmbiguousParentError for any child resolved against it.
        dataframe = pd.DataFrame(
            [
                {"protocol_id": 1, "protocol_number": "P-1", "sequence_number": 0},
                {"protocol_id": 2, "protocol_number": "P-1", "sequence_number": 0},
            ]
        )

        with patch.object(protocol_loader.logger, "warning") as warning:
            protocol_loader.prepare_versions(dataframe)

        warning.assert_called_once()


class PreparePersonsTest(unittest.TestCase):
    def _base_row(self, **overrides: object) -> dict:
        row = {
            "protocol_person_id": 1,
            "source_protocol_id": 1,
            "protocol_number": "P-1",
            "sequence_number": 0,
            "person_email_address": None,
            "rolodex_email_address": None,
            "protocol_person_role_description": None,
        }
        row.update(overrides)
        return row

    def test_prefers_person_email_over_rolodex(self) -> None:
        dataframe = pd.DataFrame(
            [
                self._base_row(
                    person_email_address="pi@bu.edu",
                    rolodex_email_address="fallback@example.com",
                )
            ]
        )

        prepared = protocol_loader.prepare_persons(dataframe)

        self.assertEqual(prepared["email_address"].tolist(), ["pi@bu.edu"])
        self.assertEqual(prepared["email_source"].tolist(), ["PERSON"])

    def test_falls_back_to_rolodex_email_when_person_email_missing(self) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(rolodex_email_address="fallback@example.com")]
        )

        prepared = protocol_loader.prepare_persons(dataframe)

        self.assertEqual(prepared["email_address"].tolist(), ["fallback@example.com"])
        self.assertEqual(prepared["email_source"].tolist(), ["ROLODEX"])

    def test_reports_missing_email_when_neither_source_has_one(self) -> None:
        dataframe = pd.DataFrame([self._base_row()])

        with patch.object(protocol_loader.logger, "warning") as warning:
            prepared = protocol_loader.prepare_persons(dataframe)

        self.assertIsNone(prepared["email_address"].iloc[0])
        self.assertIsNone(prepared["email_source"].iloc[0])
        warning.assert_any_call(
            "{} protocol person rows have no email address from either "
            "PROTOCOL_PERSONS or ROLODEX",
            1,
        )

    def test_is_pi_true_when_role_description_matches(self) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(protocol_person_role_description="Principal Investigator")]
        )

        prepared = protocol_loader.prepare_persons(dataframe)

        self.assertTrue(bool(prepared["is_pi"].iloc[0]))

    def test_is_pi_false_when_role_description_does_not_match(self) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(protocol_person_role_description="Co-Investigator")]
        )

        prepared = protocol_loader.prepare_persons(dataframe)

        self.assertFalse(bool(prepared["is_pi"].iloc[0]))

    def test_rejects_duplicate_protocol_person_id(self) -> None:
        dataframe = pd.DataFrame(
            [self._base_row(protocol_person_id=1), self._base_row(protocol_person_id=1)]
        )

        with self.assertRaises(RuntimeError):
            protocol_loader.prepare_persons(dataframe)


class ResolvePersonParentsTest(unittest.TestCase):
    def test_sets_resolved_protocol_id_and_counts_mismatches(self) -> None:
        versions = pd.DataFrame(
            [{"protocol_number": "P-1", "sequence_number": 0, "protocol_id": 9141}]
        )
        persons = pd.DataFrame(
            [
                {
                    "protocol_person_id": 1,
                    "source_protocol_id": 114886,
                    "protocol_number": "P-1",
                    "sequence_number": 0,
                }
            ]
        )

        mismatches = protocol_loader.resolve_person_parents(persons, versions)

        self.assertEqual(persons["protocol_id"].tolist(), [9141])
        self.assertEqual(mismatches, 1)

    def test_raises_runtime_error_on_missing_parent(self) -> None:
        versions = pd.DataFrame(
            [{"protocol_number": "P-1", "sequence_number": 0, "protocol_id": 1}]
        )
        persons = pd.DataFrame(
            [
                {
                    "protocol_person_id": 1,
                    "source_protocol_id": 1,
                    "protocol_number": "P-1",
                    "sequence_number": 99,
                }
            ]
        )

        with self.assertRaises(RuntimeError):
            protocol_loader.resolve_person_parents(persons, versions)


class ResolveUnitParentsTest(unittest.TestCase):
    def test_sets_protocol_id_from_owning_person(self) -> None:
        persons = pd.DataFrame(
            [{"protocol_person_id": 71, "protocol_id": 9141}]
        )
        units = pd.DataFrame([{"protocol_units_id": 1, "protocol_person_id": 71}])

        protocol_loader.resolve_unit_parents(units, persons)

        self.assertEqual(units["protocol_id"].tolist(), [9141])

    def test_raises_runtime_error_when_owner_missing(self) -> None:
        persons = pd.DataFrame([{"protocol_person_id": 71, "protocol_id": 9141}])
        units = pd.DataFrame([{"protocol_units_id": 1, "protocol_person_id": 999}])

        with self.assertRaises(RuntimeError):
            protocol_loader.resolve_unit_parents(units, persons)


class ClearExistingProtocolDataTest(unittest.TestCase):
    def test_truncates_all_three_tables_together(self) -> None:
        # A single TRUNCATE across all three tables (children first) is what
        # makes reruns idempotent - rerunning the loader always starts from
        # an empty, consistent state rather than accumulating duplicates.
        connection = MagicMock()

        protocol_loader.clear_existing_protocol_data(connection)

        statement = str(connection.execute.call_args.args[0])
        self.assertIn("archive.protocol_unit", statement)
        self.assertIn("archive.protocol_person", statement)
        self.assertIn("archive.protocol_version", statement)
        self.assertIn("TRUNCATE", statement)


class MarkLoadFailedRedactsErrorsTest(unittest.TestCase):
    def test_redacts_password_before_persisting(self) -> None:
        connection = MagicMock()
        engine = MagicMock()
        engine.begin.return_value.__enter__.return_value = connection

        protocol_loader.mark_load_failed(
            engine, load_id=1, error_message="connect failed: password=hunter2"
        )

        _, params = connection.execute.call_args.args
        self.assertNotIn("hunter2", params["error_message"])
        self.assertIn("[REDACTED]", params["error_message"])


class VerifyLoadedDataTest(unittest.TestCase):
    def test_raises_on_row_count_mismatch(self) -> None:
        connection = MagicMock()
        connection.execute.return_value.scalar_one.return_value = 5

        with self.assertRaises(RuntimeError):
            protocol_loader.verify_loaded_data(
                connection, {"protocol_version": 10}
            )

    def test_raises_on_orphan_person_rows(self) -> None:
        connection = MagicMock()
        # First three calls satisfy the row-count loop (matching), then the
        # orphan-persons check returns a nonzero count.
        connection.execute.return_value.scalar_one.side_effect = [1, 1]

        with self.assertRaises(RuntimeError):
            protocol_loader.verify_loaded_data(connection, {"protocol_version": 1})


class LimitIsReadOnlyTest(unittest.TestCase):
    def test_limit_never_creates_a_postgres_engine(self) -> None:
        versions = pd.DataFrame(
            [{"protocol_id": 1, "protocol_number": "P-1", "sequence_number": 0}]
        )
        persons = pd.DataFrame(
            [
                {
                    "protocol_person_id": 1,
                    "source_protocol_id": 1,
                    "protocol_number": "P-1",
                    "sequence_number": 0,
                }
            ]
        )
        units = pd.DataFrame(
            [
                {
                    "protocol_units_id": 1,
                    "protocol_person_id": 1,
                    "protocol_number": "P-1",
                    "sequence_number": 0,
                }
            ]
        )

        with (
            patch.object(
                protocol_loader,
                "OracleDataSource",
                side_effect=[
                    MagicMock(read=MagicMock(return_value=versions)),
                    MagicMock(read=MagicMock(return_value=persons)),
                    MagicMock(read=MagicMock(return_value=units)),
                ],
            ),
            patch.object(protocol_loader, "parse_args") as parse_args,
            patch.object(protocol_loader, "create_postgres_engine") as create_engine,
        ):
            parse_args.return_value = MagicMock(limit=10)
            protocol_loader.main()

        create_engine.assert_not_called()


if __name__ == "__main__":
    unittest.main()
