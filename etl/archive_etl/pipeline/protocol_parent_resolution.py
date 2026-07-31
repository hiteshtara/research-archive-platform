"""Protocol-specific parent resolution.

Reuses the general algorithm from the (deleted) Protocol Archive
implementation as a design reference, not its code: a fresh, minimal module
implementing only the two resolution strategies this rebuild actually
needs, rather than reviving the old multi-strategy enum framework.

NUMBER_SEQUENCE (protocol_person -> protocol_version): a child row's own
Oracle PROTOCOL_ID does not reliably identify its historical version - see
docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md (~14.83% mismatch rate measured
for Personnel). The archive parent is instead resolved from the exact
(protocol_number, sequence_number) tuple, which is unique per version.

OWNER_CHAIN (protocol_unit -> protocol_person): PROTOCOL_UNITS.PROTOCOL_ID
does not exist in Oracle at all - PROTOCOL_PERSON_ID is the verified
physical foreign key (confirmed in the KC OJB descriptor). A unit's archive
parent is simply its owning person's already-resolved protocol_id.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from typing import Any


class ParentResolutionError(RuntimeError):
    """Raised when a child row's archive parent cannot be resolved."""


class MissingParentError(ParentResolutionError):
    """Raised when no protocol_version row matches a child's parent key."""


class AmbiguousParentError(ParentResolutionError):
    """Raised when more than one protocol_version row matches a parent key."""


@dataclass(frozen=True)
class ResolvedParent:
    protocol_id: int
    source_protocol_id_differs: bool


class NumberSequenceParentResolver:
    """Resolves a child row's protocol_id from (protocol_number, sequence_number).

    Built once per load from the freshly-loaded protocol_version rows, then
    used to resolve every protocol_person row's true archive parent.
    """

    def __init__(self, parent_rows: Iterable[Mapping[Any, Any]]) -> None:
        self._parents: dict[tuple[str, int], list[int]] = {}
        for row in parent_rows:
            key = (
                str(row["protocol_number"]),
                int(row["sequence_number"]),
            )
            self._parents.setdefault(key, []).append(int(row["protocol_id"]))

    def resolve(
        self,
        *,
        protocol_number: str,
        sequence_number: int,
        source_protocol_id: int,
    ) -> ResolvedParent:
        key = (protocol_number, sequence_number)
        matches = self._parents.get(key, [])
        if not matches:
            raise MissingParentError(
                "missing protocol_version parent for protocol_number="
                f"{protocol_number}, sequence_number={sequence_number}"
            )
        if len(matches) > 1:
            raise AmbiguousParentError(
                "ambiguous protocol_version parent for protocol_number="
                f"{protocol_number}, sequence_number={sequence_number}: "
                f"{matches}"
            )
        resolved_protocol_id = matches[0]
        return ResolvedParent(
            protocol_id=resolved_protocol_id,
            source_protocol_id_differs=(
                source_protocol_id != resolved_protocol_id
            ),
        )


class OwnerChainParentResolver:
    """Resolves a protocol_unit row's protocol_id from its owning person.

    Built once per load from the freshly-resolved protocol_person rows
    (protocol_person_id -> protocol_id), then used to resolve every
    protocol_unit row.
    """

    def __init__(self, persons: Iterable[Mapping[Any, Any]]) -> None:
        self._owner_protocol_id: dict[int, int] = {
            int(row["protocol_person_id"]): int(row["protocol_id"])
            for row in persons
        }

    def resolve(self, *, protocol_person_id: int) -> int:
        try:
            return self._owner_protocol_id[protocol_person_id]
        except KeyError:
            raise MissingParentError(
                "missing protocol_person owner for protocol_person_id="
                f"{protocol_person_id}"
            ) from None
