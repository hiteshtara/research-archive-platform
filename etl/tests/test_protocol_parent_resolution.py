from __future__ import annotations

import unittest

from archive_etl.pipeline.protocol_parent_resolution import (
    AmbiguousParentError,
    MissingParentError,
    NumberSequenceParentResolver,
    OwnerChainParentResolver,
)


class NumberSequenceParentResolverTest(unittest.TestCase):
    def test_resolves_matching_protocol_number_and_sequence(self) -> None:
        resolver = NumberSequenceParentResolver(
            [{"protocol_number": "1602001993", "sequence_number": 4, "protocol_id": 114886}]
        )

        resolved = resolver.resolve(
            protocol_number="1602001993",
            sequence_number=4,
            source_protocol_id=114886,
        )

        self.assertEqual(resolved.protocol_id, 114886)
        self.assertFalse(resolved.source_protocol_id_differs)

    def test_detects_source_protocol_id_mismatch(self) -> None:
        # Mirrors the documented real-world case: PROTOCOL_PERSONS.PROTOCOL_ID
        # points at the current row (114886), but the business version
        # (protocol_number + sequence_number) resolves to a different,
        # historical protocol_id (9141).
        resolver = NumberSequenceParentResolver(
            [{"protocol_number": "1602001993", "sequence_number": 0, "protocol_id": 9141}]
        )

        resolved = resolver.resolve(
            protocol_number="1602001993",
            sequence_number=0,
            source_protocol_id=114886,
        )

        self.assertEqual(resolved.protocol_id, 9141)
        self.assertTrue(resolved.source_protocol_id_differs)

    def test_raises_missing_parent_error_when_no_version_matches(self) -> None:
        resolver = NumberSequenceParentResolver(
            [{"protocol_number": "1602001993", "sequence_number": 0, "protocol_id": 9141}]
        )

        with self.assertRaises(MissingParentError):
            resolver.resolve(
                protocol_number="1602001993",
                sequence_number=99,
                source_protocol_id=1,
            )

    def test_raises_ambiguous_parent_error_when_multiple_versions_match(self) -> None:
        resolver = NumberSequenceParentResolver(
            [
                {"protocol_number": "1602001993", "sequence_number": 0, "protocol_id": 1},
                {"protocol_number": "1602001993", "sequence_number": 0, "protocol_id": 2},
            ]
        )

        with self.assertRaises(AmbiguousParentError):
            resolver.resolve(
                protocol_number="1602001993",
                sequence_number=0,
                source_protocol_id=1,
            )


class OwnerChainParentResolverTest(unittest.TestCase):
    def test_resolves_protocol_id_from_owning_person(self) -> None:
        resolver = OwnerChainParentResolver(
            [{"protocol_person_id": 71, "protocol_id": 9141}]
        )

        self.assertEqual(
            resolver.resolve(protocol_person_id=71),
            9141,
        )

    def test_raises_missing_parent_error_when_owner_not_found(self) -> None:
        resolver = OwnerChainParentResolver(
            [{"protocol_person_id": 71, "protocol_id": 9141}]
        )

        with self.assertRaises(MissingParentError):
            resolver.resolve(protocol_person_id=999)


if __name__ == "__main__":
    unittest.main()
