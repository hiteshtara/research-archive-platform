"""Generic ETL batch framework: a deterministic, resumable manifest for
"select exactly N entities, then load/process exactly that membership"
workflows (archive.etl_batch/archive.etl_batch_item - see
database/migrations/V037__create_etl_batch_framework.sql and
docs/ETL_BATCH_FRAMEWORK.md for the full design rationale).

Domain-agnostic by construction: every function here takes an explicit
`domain`/`entity_type` pair and operates only on entity_key (a plain
BIGINT - e.g. an Award's award_id, or an attachment physical file's
file_id) and etl_batch(_item)'s own generic columns. It has no knowledge
of any domain's own tables (attachment_object, award_version, ...) -
domain code (e.g. load_award_attachments.py) is responsible for:
  - deciding *which* entity_keys to select (an Oracle scan, a Postgres
    query, whatever fits the domain)
  - the actual load/process step for each entity_key
  - any domain-specific status (e.g. attachment_object.upload_status),
    which is never mirrored into etl_batch_item.status - see the status
    ownership rule below.

etl_batch_item.status tracks exactly one thing: whether this batch's own
load/process step has run for this entity_key. Domain-specific downstream
state (e.g. an attachment's S3 upload progress) always stays solely owned
by the domain's own table; a domain scopes further processing to batch
membership by joining etl_batch_item to its own table on entity_key,
filtered by batch_id/domain/entity_type, exactly the way
load_award_attachments.select_upload_candidates joins etl_batch_item to
archive.attachment_object.
"""

from __future__ import annotations

import json
from collections.abc import Set as AbstractSet
from typing import Any, Protocol

import pandas as pd
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine


class _ClosableBatchIterator(Protocol):
    """The shape of OracleDataSource.read_batches()'s return value: a
    lazy iterator of DataFrames that must be closed (whether or not it
    was fully consumed) to release its underlying Oracle cursor."""

    def __iter__(self) -> _ClosableBatchIterator: ...

    def __next__(self) -> pd.DataFrame: ...

    def close(self) -> None: ...

BATCH_STATUS_CREATED = "CREATED"
BATCH_STATUS_METADATA_LOADING = "METADATA_LOADING"
BATCH_STATUS_READY = "READY"
BATCH_STATUS_PROCESSING = "PROCESSING"
BATCH_STATUS_PARTIAL = "PARTIAL"
BATCH_STATUS_COMPLETED = "COMPLETED"
BATCH_STATUS_FAILED = "FAILED"
BATCH_STATUS_ABANDONED = "ABANDONED"

ITEM_STATUS_PENDING = "PENDING"
ITEM_STATUS_PROCESSING = "PROCESSING"
ITEM_STATUS_COMPLETED = "COMPLETED"
ITEM_STATUS_FAILED = "FAILED"
ITEM_STATUS_MISSING_SOURCE = "MISSING_SOURCE"
ITEM_STATUS_SKIPPED = "SKIPPED"


def select_distinct_ascending_from_oracle_batches(
    batches: _ClosableBatchIterator,
    *,
    id_column: str,
    requested_size: int,
    excluded: AbstractSet[int] = frozenset(),
) -> list[int]:
    """Scan an OracleDataSource.read_batches()-shaped iterator (a lazy
    generator yielding DataFrames, with a .close() method) batch by
    batch, keeping the first `requested_size` distinct, non-excluded
    values from `id_column`, stopping as soon as enough are found - or
    the source is exhausted first, in which case a smaller list is
    returned (callers should warn on that; this function only selects,
    it doesn't decide what a short batch means). Always closes `batches`
    itself via try/finally, matching OracleDataSource's own resource-
    lifecycle contract, regardless of whether the source was fully
    consumed."""
    selected: list[int] = []
    seen: set[int] = set(excluded)
    try:
        for batch in batches:
            for value in pd.to_numeric(batch[id_column], errors="coerce"):
                if pd.isna(value):
                    continue
                candidate = int(value)
                if candidate in seen:
                    continue
                seen.add(candidate)
                selected.append(candidate)
                if len(selected) >= requested_size:
                    break
            if len(selected) >= requested_size:
                break
    finally:
        batches.close()

    selected.sort()
    return selected


def create_batch(
    engine: Engine,
    *,
    domain: str,
    entity_type: str,
    requested_size: int,
    selection_strategy: str,
    selected_keys: list[int],
    selection_parameters: dict[str, Any] | None = None,
    run_id: str | None = None,
) -> dict[str, Any]:
    """Persist a new batch and its exact, already-decided membership
    (`selected_keys`) transactionally. Selection itself - deciding which
    entity_keys - is entirely the caller's responsibility (e.g. via
    select_distinct_ascending_from_oracle_batches, or a Postgres query
    for a domain whose candidates live there); this function has no
    domain/Oracle-specific knowledge at all. Raises ValueError for a
    non-positive requested_size."""
    if requested_size <= 0:
        raise ValueError(
            f"requested_size must be positive, got {requested_size}"
        )

    if len(selected_keys) < requested_size:
        logger.warning(
            "Only {} of the requested {} distinct, eligible entities "
            "were available for domain={} entity_type={} - creating a "
            "smaller batch",
            len(selected_keys),
            requested_size,
            domain,
            entity_type,
        )

    with engine.begin() as connection:
        batch_id = connection.execute(
            text(
                """
                INSERT INTO archive.etl_batch (
                    domain, entity_type, requested_size, status,
                    selection_strategy, selection_parameters,
                    created_by_run_id
                ) VALUES (
                    :domain, :entity_type, :requested_size, :status,
                    :selection_strategy, :selection_parameters, :run_id
                )
                RETURNING batch_id
                """
            ),
            {
                "domain": domain,
                "entity_type": entity_type,
                "requested_size": requested_size,
                "status": BATCH_STATUS_CREATED,
                "selection_strategy": selection_strategy,
                "selection_parameters": (
                    json.dumps(selection_parameters)
                    if selection_parameters is not None
                    else None
                ),
                "run_id": run_id,
            },
        ).scalar_one()

        for ordinal, entity_key in enumerate(selected_keys, start=1):
            connection.execute(
                text(
                    """
                    INSERT INTO archive.etl_batch_item (
                        batch_id, entity_key, ordinal, status
                    ) VALUES (
                        :batch_id, :entity_key, :ordinal, :status
                    )
                    """
                ),
                {
                    "batch_id": batch_id,
                    "entity_key": entity_key,
                    "ordinal": ordinal,
                    "status": ITEM_STATUS_PENDING,
                },
            )

    logger.bind(
        stage="create_batch",
        batch_id=batch_id,
        domain=domain,
        entity_type=entity_type,
        run_id=run_id,
    ).info(
        "Created batch_id={} domain={} entity_type={} requested_size={} "
        "selected={} entity_keys={}",
        batch_id,
        domain,
        entity_type,
        requested_size,
        len(selected_keys),
        selected_keys,
    )

    return {
        "batch_id": int(batch_id),
        "domain": domain,
        "entity_type": entity_type,
        "requested_size": requested_size,
        "selected_count": len(selected_keys),
        "selected_keys": selected_keys,
    }


def assert_batch_matches(
    connection: Connection, batch_id: int, *, domain: str, entity_type: str
) -> None:
    """Raise RuntimeError if batch_id doesn't exist, or exists but was
    created for a different domain/entity_type. Now that batch_id is no
    longer namespaced per domain by a separate table, this is the
    defense against accidentally scoping one domain's load/upload step
    to another domain's batch."""
    row = connection.execute(
        text(
            "SELECT domain, entity_type FROM archive.etl_batch "
            "WHERE batch_id = :batch_id"
        ),
        {"batch_id": batch_id},
    ).mappings().one_or_none()

    if row is None:
        raise RuntimeError(f"batch_id={batch_id} does not exist")

    if row["domain"] != domain or row["entity_type"] != entity_type:
        raise RuntimeError(
            f"batch_id={batch_id} was created for domain={row['domain']!r} "
            f"entity_type={row['entity_type']!r}, not domain={domain!r} "
            f"entity_type={entity_type!r}"
        )


def load_batch_membership(
    connection: Connection, batch_id: int, *, domain: str, entity_type: str
) -> list[int]:
    """Return this batch's entity_key membership, in stable ascending
    ordinal order. Raises RuntimeError via assert_batch_matches if the
    batch doesn't exist or belongs to a different domain/entity_type."""
    assert_batch_matches(connection, batch_id, domain=domain, entity_type=entity_type)

    rows = connection.execute(
        text(
            "SELECT entity_key FROM archive.etl_batch_item "
            "WHERE batch_id = :batch_id ORDER BY ordinal"
        ),
        {"batch_id": batch_id},
    ).scalars()
    return [int(value) for value in rows]


def set_item_status(
    connection: Connection, batch_id: int, entity_key: int, *, status: str
) -> None:
    """Update one item's status - the generic equivalent of updating a
    domain-specific batch-membership status column, scoped to entity_key
    rather than any domain-specific column name. Takes a connection
    (not an engine) so it participates in the caller's own transaction -
    e.g. a metadata-load's dry_run rollback must roll this back too."""
    connection.execute(
        text(
            "UPDATE archive.etl_batch_item SET status = :status "
            "WHERE batch_id = :batch_id AND entity_key = :entity_key"
        ),
        {"status": status, "batch_id": batch_id, "entity_key": entity_key},
    )


def set_batch_status(connection: Connection, batch_id: int, *, status: str) -> None:
    """Plain status update with no timestamp side effects - takes a
    connection so it participates in the caller's own transaction (e.g.
    a metadata-load's dry_run rollback)."""
    connection.execute(
        text(
            "UPDATE archive.etl_batch SET status = :status "
            "WHERE batch_id = :batch_id"
        ),
        {"status": status, "batch_id": batch_id},
    )


def begin_batch_processing(
    engine: Engine, batch_id: int, *, domain: str, entity_type: str, status: str
) -> None:
    """Transition a batch into `status`, setting started_at only the
    first time (COALESCE) so a resumed run doesn't reset the original
    start time. Verifies domain/entity_type first (see
    assert_batch_matches)."""
    with engine.begin() as connection:
        assert_batch_matches(
            connection, batch_id, domain=domain, entity_type=entity_type
        )
        connection.execute(
            text(
                """
                UPDATE archive.etl_batch
                   SET status = :status,
                       started_at = COALESCE(started_at, CURRENT_TIMESTAMP)
                 WHERE batch_id = :batch_id
                """
            ),
            {"status": status, "batch_id": batch_id},
        )


def finish_batch_processing(engine: Engine, batch_id: int, *, status: str) -> None:
    """Transition a batch to a terminal-for-this-phase `status`, stamping
    completed_at. Called once per phase invocation (e.g. once an upload
    run finishes) - completed_at reflects the most recent phase to
    finish, not necessarily every phase's own completion."""
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.etl_batch
                   SET status = :status,
                       completed_at = CURRENT_TIMESTAMP
                 WHERE batch_id = :batch_id
                """
            ),
            {"status": status, "batch_id": batch_id},
        )


def show_batch(
    engine: Engine, batch_id: int, *, domain: str, entity_type: str
) -> dict[str, Any]:
    """Read-only status report for one batch, scoped to domain/entity_type
    for the same defense-in-depth reason load_batch_membership checks it.
    Reports only the generic item-status breakdown (PENDING/PROCESSING/
    COMPLETED/FAILED/MISSING_SOURCE/SKIPPED) - any domain-specific
    downstream status breakdown (e.g. an attachment's upload_status) is
    the caller's own responsibility to compute and merge into the
    returned dict, since this function has no knowledge of any domain's
    own tables. Never writes anything."""
    with engine.connect() as connection:
        batch_row = connection.execute(
            text(
                "SELECT batch_id, domain, entity_type, requested_size, "
                "status, created_at FROM archive.etl_batch "
                "WHERE batch_id = :batch_id"
            ),
            {"batch_id": batch_id},
        ).mappings().one_or_none()

        if batch_row is None:
            return {"batch_id": batch_id, "found": False}

        if batch_row["domain"] != domain or batch_row["entity_type"] != entity_type:
            raise RuntimeError(
                f"batch_id={batch_id} was created for domain="
                f"{batch_row['domain']!r} entity_type={batch_row['entity_type']!r}, "
                f"not domain={domain!r} entity_type={entity_type!r}"
            )

        total_items = connection.execute(
            text(
                "SELECT COUNT(*) FROM archive.etl_batch_item "
                "WHERE batch_id = :batch_id"
            ),
            {"batch_id": batch_id},
        ).scalar_one()

        status_rows = connection.execute(
            text(
                "SELECT status, COUNT(*) FROM archive.etl_batch_item "
                "WHERE batch_id = :batch_id GROUP BY status"
            ),
            {"batch_id": batch_id},
        ).all()
        item_status_counts: dict[str, int] = {row[0]: row[1] for row in status_rows}

    return {
        "batch_id": batch_row["batch_id"],
        "found": True,
        "domain": batch_row["domain"],
        "entity_type": batch_row["entity_type"],
        "requested_size": batch_row["requested_size"],
        "created_at": batch_row["created_at"],
        "status": batch_row["status"],
        "total_items": total_items,
        "pending": item_status_counts.get(ITEM_STATUS_PENDING, 0),
        "processing": item_status_counts.get(ITEM_STATUS_PROCESSING, 0),
        "completed": item_status_counts.get(ITEM_STATUS_COMPLETED, 0),
        "failed": item_status_counts.get(ITEM_STATUS_FAILED, 0),
        "missing_source": item_status_counts.get(ITEM_STATUS_MISSING_SOURCE, 0),
        "skipped": item_status_counts.get(ITEM_STATUS_SKIPPED, 0),
    }
