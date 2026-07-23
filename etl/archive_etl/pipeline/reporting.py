from __future__ import annotations

from dataclasses import dataclass

from loguru import logger

from archive_etl.pipeline.reconciliation import ReconciliationResult


@dataclass(frozen=True)
class LoadReport:
    domain: str
    load_id: int
    rows_read: int
    rows_loaded: int
    reconciliation: ReconciliationResult


def report_load(report: LoadReport) -> None:
    logger.success(
        "{} load completed. load_id={} rows_read={:,} rows_loaded={:,}",
        report.domain,
        report.load_id,
        report.rows_read,
        report.rows_loaded,
    )
    if report.reconciliation.metrics:
        logger.info(
            "{} reconciliation: {}",
            report.domain,
            " ".join(
                f"{name}={value}"
                for name, value
                in report.reconciliation.metrics.items()
            ),
        )

