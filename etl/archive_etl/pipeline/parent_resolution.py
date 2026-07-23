from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any


class ParentResolutionStrategy(str, Enum):
    NUMBER_SEQUENCE = "NUMBER_SEQUENCE"
    DIRECT_PROTOCOL_ID = "DIRECT_PROTOCOL_ID"
    OWNER_CHAIN = "OWNER_CHAIN"


class ParentResolutionError(RuntimeError):
    pass


class MissingParentError(ParentResolutionError):
    pass


class AmbiguousParentError(ParentResolutionError):
    pass


@dataclass(frozen=True)
class ResolvedParent:
    protocol_id: int
    source_protocol_id_differs: bool


class NumberSequenceParentResolver:
    strategy = ParentResolutionStrategy.NUMBER_SEQUENCE

    def __init__(
        self,
        parent_rows: Iterable[Mapping[str, Any]],
    ) -> None:
        self._parents: dict[tuple[str, int], list[int]] = {}
        for row in parent_rows:
            key = (
                str(row["protocol_number"]),
                int(row["sequence_number"]),
            )
            self._parents.setdefault(key, []).append(
                int(row["protocol_id"])
            )

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
                "missing parent for protocol_number="
                f"{protocol_number}, sequence_number={sequence_number}"
            )
        if len(matches) > 1:
            raise AmbiguousParentError(
                "ambiguous parent for protocol_number="
                f"{protocol_number}, sequence_number={sequence_number}: "
                f"{matches}"
            )
        resolved = matches[0]
        return ResolvedParent(
            protocol_id=resolved,
            source_protocol_id_differs=(
                source_protocol_id != resolved
            ),
        )
