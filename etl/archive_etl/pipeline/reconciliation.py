from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass

from sqlalchemy import text
from sqlalchemy.engine import Connection


@dataclass(frozen=True)
class ReconciliationResult:
    metrics: Mapping[str, int]

    @property
    def passed(self) -> bool:
        return all(value == 0 for value in self.metrics.values())


Reconciler = Callable[[Connection], ReconciliationResult]


def no_reconciliation(
    connection: Connection,
) -> ReconciliationResult:
    del connection
    return ReconciliationResult({})


def table_count_reconciler(
    *,
    schema: str,
    expected_counts: Mapping[str, int],
) -> Reconciler:
    def reconcile(
        connection: Connection,
    ) -> ReconciliationResult:
        metrics: dict[str, int] = {}
        for table, expected in expected_counts.items():
            actual = int(
                connection.execute(
                    text(
                        f'SELECT COUNT(*) FROM "{schema}"."{table}"'
                    )
                ).scalar_one()
            )
            metrics[f"{table}_row_count_difference"] = (
                actual - expected
            )
        return ReconciliationResult(metrics)

    return reconcile
