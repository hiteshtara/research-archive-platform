"""Durable, unattended, multi-module attachment-load orchestration.

Scope: Award, Proposal, and Subaward attachments only. Negotiation is
deliberately excluded - its metadata population is already 100% complete
(28,923 Oracle rows = 28,923 Postgres rows) and every one of its 26,581
MISSING rows is a confirmed, already-reconciled genuine absence of the
Oracle blob (KCOEUS.ATTACHMENT_FILE has no matching row for that FILE_ID
in 100% of sampled cases) - there is no new Negotiation work to select,
and re-attempting that already-terminal population would immediately
trip a >1% failure-rate stop condition on data that was never actually
new. IRB is excluded because no attachment table or loader exists for it
anywhere in this codebase. See
docs/architecture/ARCHIVE_ATTACHMENT_LOAD_INVENTORY.md for the full
preflight this design is based on.

Two checkpointed stages per module, always in this order within a
module: METADATA (bring missing archive.*_attachment reference rows in
from Oracle - never touches a BLOB, never touches S3) then BINARY
(stream physical file content from Oracle to S3 for files whose
metadata is already loaded - never re-reads a BLOB for a file already
durably verified UPLOADED). "Separate checkpoint namespace per module"
means each module's own etl_batch domain/entity_type pair, so Award
resuming never blocks or is confused with Proposal or Subaward resuming
- see MODULES below.

Award reuses load_award_attachments.py's existing, already-tested
functions unchanged (_run_create_batch/_run_load_batch for metadata,
_run_upload for binaries via a constructed argparse.Namespace) - that
module already implements everything this orchestrator needs for Award:
durable archive.attachment_object.upload_status tracking, the
INLINE/EXTERNAL blob_source distinction (see resolve_blob_location's own
docstring for the UUID-vs-numeric incident this fixed), and
etl_batch-based batching. Proposal and Subaward have no equivalent
batch-oriented loader yet (only a CSV-file-driven generic plugin
framework - see etl/archive_etl/attachments/runner.py), so this module
adds new, analogous functions for them, deliberately mirroring Award's
proven shape rather than inventing a new one - and reuses
load_proposals_from_csv.py's own prepare_attachments()/
upsert_proposal_attachments() for the actual Postgres UPSERT, since
those are already correct and already used in production.

Physical-file identity, independent of references: Award already has a
dedicated dedup table (archive.attachment_object, keyed by file_id).
Proposal and Subaward do not have a separate physical-file table - this
orchestrator treats file_data_id itself as the physical-file identity
for batching and upload-state purposes (GROUP BY file_data_id), and
marks every reference row sharing a file_data_id UPLOADED together in
one UPDATE, so a file referenced by many proposal/subaward versions is
still only streamed from Oracle and PUT to S3 once. Cross-module
sharing: empirically verified zero overlap between
KCOEUS.PROPOSAL_ATTACHMENTS.FILE_DATA_ID and
KCOEUS.SUBAWARD_ATTACHMENTS.FILE_DATA_ID (a live COUNT(*) INTERSECT
query returned 0 - see the inventory doc); Award draws from a
structurally different Oracle source (KCOEUS.ATTACHMENT_FILE.FILE_ID,
not KCOEUS.FILE_DATA.ID) so cannot share with either. Every query and
batch key in this module is nonetheless module-scoped (never a bare,
assumed-globally-unique file identifier), so a future overlap - if
Oracle's data ever changed - could never be silently double-uploaded or
double-counted.

etl_batch_item.entity_key is a plain BIGINT (see V037) and cannot hold a
UUID string directly. Award's file_id is already a BIGINT, so it uses
entity_key natively. For Proposal/Subaward, this orchestrator persists
the batch's real UUID file_data_id membership in
archive.etl_batch.selection_parameters (JSONB) instead, and uses a
synthetic per-batch ordinal (1..N) as entity_key purely to track each
member's own COMPLETED/MISSING_SOURCE status within that one batch -
never as a cross-batch identity (two different batches' ordinal "1" are
unrelated files). Cross-batch "already selected" exclusion is decided
directly from durable Postgres state (does this file_data_id already
have a row in archive.proposal_attachment / archive.subaward_attachment)
rather than from etl_batch_item, which is exactly the same "check
durable state first" principle applied to selection as well as to
upload-skip decisions - and is safe precisely because the single
advisory lock below means only one orchestration process ever selects
batches at a time, so there is no concurrent-batch race to guard against
via etl_batch_item the way Award's own three-way exclusion needs to.

Never mark a batch's BINARY stage complete until reconciliation passes:
Award's own _run_upload() already calls
batch_framework.finish_batch_processing(..., BATCH_STATUS_COMPLETED)
internally regardless of per-file outcomes (existing, unchanged
behavior - not modified here, since it is already correct for Award's
own resumability: a FAILED file remains selectable by a future
--retry-failed run regardless of the batch's own status). This
orchestrator adds its own, additional reconciliation gate on top,
independent of that internal marker: after every binary-stage batch
(all three modules), reconcile_batch() re-verifies S3 existence/size/
checksum for every row the batch just marked UPLOADED, plus a reference-
count check. Only if that reconciliation is completely clean does the
orchestration loop count the batch as done and continue; otherwise the
whole run stops (a real reconciliation failure is exactly the kind of
thing that should halt an unattended run, not be silently logged and
ignored). For Proposal/Subaward's own new upload-stage functions, this
orchestrator controls the batch-completion marker directly and only
sets it after reconciliation succeeds.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import uuid
from pathlib import Path
from typing import Any

import boto3
import oracledb
import pandas as pd
from botocore.exceptions import ClientError
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Connection, Engine

from archive_etl.batch import framework as batch_framework
from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.config.settings import require_oracle_environment
from archive_etl.pipeline.sources import OracleDataSource
from archive_etl.upload.postgres import create_postgres_engine
from archive_etl.utils.redaction import redact_error_message

import load_award_attachments as award_attachments
import load_proposals_from_csv as proposals

# --------------------------------------------------------------------
# Module scope
# --------------------------------------------------------------------

AWARD = "award"
PROPOSAL = "proposal"
SUBAWARD = "subaward"
SUPPORTED_MODULES = (AWARD, PROPOSAL, SUBAWARD)
# Negotiation and IRB are deliberately never included - see module
# docstring.

DEFAULT_BATCH_SIZE = 2000

# --------------------------------------------------------------------
# Advisory lock - one lock for the WHOLE orchestration task (not
# per-module), distinct from the Award-core-record load's own lock key
# (research-archive-platform:award-full-load) so the two are unrelated.
# --------------------------------------------------------------------

_LOCK_NAME = "research-archive-platform:attachment-load"
_LOCK_SQL = f"SELECT pg_try_advisory_lock(hashtext('{_LOCK_NAME}')::bigint)"
_UNLOCK_SQL = f"SELECT pg_advisory_unlock(hashtext('{_LOCK_NAME}')::bigint)"
_LOCK_HOLDER_SQL = """
    SELECT a.application_name, a.pid, a.query_start
    FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
    WHERE l.locktype = 'advisory'
"""


class LockNotAcquired(RuntimeError):
    """Another attachment-load orchestration is already running."""


def acquire_lock(engine: Engine, *, label: str) -> Connection:
    """Acquire the whole-task advisory lock on a dedicated connection
    that must be kept open for the entire orchestration run (session-
    level lock - released automatically if this connection ever drops,
    e.g. an ECS task kill, so a later run can always proceed). Raises
    LockNotAcquired (including the current holder's application_name)
    rather than blocking, so a concurrent launch fails fast instead of
    queuing."""
    connection = engine.connect()
    safe_label = label[:63].replace("'", "")
    connection.execute(text(f"SET application_name = '{safe_label}'"))
    connection.commit()
    got_lock = connection.execute(text(_LOCK_SQL)).scalar()
    if not got_lock:
        holder = connection.execute(text(_LOCK_HOLDER_SQL)).fetchone()
        connection.close()
        raise LockNotAcquired(
            f"Another attachment-load orchestration is already running: "
            f"{dict(holder._mapping) if holder else 'unknown holder'}"
        )
    return connection


def release_lock(connection: Connection) -> None:
    try:
        connection.execute(text(_UNLOCK_SQL))
        connection.commit()
    except Exception:
        pass
    finally:
        connection.close()


# --------------------------------------------------------------------
# Bounded retry for transient Oracle/PostgreSQL/S3 failures only.
# Never retries a business-logic outcome (missing source content,
# checksum mismatch) - those are recorded as FAILED/MISSING immediately,
# not retried blindly.
# --------------------------------------------------------------------

_TRANSIENT_EXCEPTION_NAMES = (
    "OperationalError",
    "InterfaceError",
    "DatabaseError",
    "TimeoutError",
    "ConnectionError",
    "EndpointConnectionError",
    "ConnectionClosedError",
    "ReadTimeoutError",
)


def is_transient(error: BaseException) -> bool:
    return type(error).__name__ in _TRANSIENT_EXCEPTION_NAMES


def with_bounded_retry(
    operation, *, attempts: int = 4, operation_name: str = "operation"
):
    """Retry `operation` (a zero-arg callable) up to `attempts` times,
    exponential backoff, but ONLY for exceptions classified transient by
    is_transient() - anything else propagates immediately on the first
    failure."""
    last_error: BaseException | None = None
    for attempt in range(1, attempts + 1):
        try:
            return operation()
        except Exception as error:  # noqa: BLE001 - classify, don't hide
            if not is_transient(error):
                raise
            last_error = error
            if attempt == attempts:
                break
            delay = min(2 ** (attempt - 1), 30)
            logger.warning(
                "{} failed on attempt {}/{} (transient: {}); retrying in {}s",
                operation_name,
                attempt,
                attempts,
                type(error).__name__,
                delay,
            )
            time.sleep(delay)
    assert last_error is not None
    raise last_error


# --------------------------------------------------------------------
# Project paths (mirrors load_award_attachments.py's own two-layout
# resolution: local checkout vs. the flat ECS loader image).
# --------------------------------------------------------------------


def _resolve_project_root() -> Path:
    container_root = Path(__file__).resolve().parent
    if (container_root / "oracle").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()

PROPOSAL_ATTACHMENT_FILE_IDS_SQL = (
    PROJECT_ROOT / "oracle" / "proposal" / "export_proposal_attachment_file_ids.sql"
)
PROPOSAL_ATTACHMENTS_SQL = (
    PROJECT_ROOT / "sql" / "extract" / "proposal" / "02_proposal_attachments.sql"
)
SUBAWARD_ATTACHMENT_FILE_IDS_SQL = (
    PROJECT_ROOT / "oracle" / "subaward" / "export_subaward_attachment_file_ids.sql"
)
SUBAWARD_ATTACHMENTS_SQL = (
    PROJECT_ROOT / "oracle" / "subaward" / "export_subaward_attachments.sql"
)

# --------------------------------------------------------------------
# etl_batch domain/entity_type per module - separate checkpoint
# namespaces, so one module's resume never touches another's.
# --------------------------------------------------------------------

PROPOSAL_ATTACHMENT_DOMAIN = "PROPOSAL_ATTACHMENT"
PROPOSAL_ATTACHMENT_ENTITY_TYPE = "PROPOSAL_ATTACHMENT_FILE"
SUBAWARD_ATTACHMENT_DOMAIN = "SUBAWARD_ATTACHMENT"
SUBAWARD_ATTACHMENT_ENTITY_TYPE = "SUBAWARD_ATTACHMENT_FILE"

# Award's own domain/entity_type constants, reused unchanged:
#   award_attachments.AWARD_ATTACHMENT_BATCH_DOMAIN
#   award_attachments.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE


# ======================================================================
# Award stage - thin wrappers over load_award_attachments.py. No new
# metadata/upload logic; this module never re-implements what already
# exists and is already tested there.
# ======================================================================


def award_metadata_stage(
    engine: Engine, *, batch_size: int, run_id: str
) -> dict[str, Any]:
    """One metadata batch: resume an incomplete one if present, else
    create+load the next. Returns {"selected_count": 0, ...} when the
    Award attachment metadata population is fully caught up."""
    resume_batch_id = _find_incomplete_batch(
        engine,
        domain=award_attachments.AWARD_ATTACHMENT_BATCH_DOMAIN,
        entity_type=award_attachments.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE,
    )
    if resume_batch_id is not None:
        batch_id = resume_batch_id
        selected_count = _batch_member_count(engine, batch_id)
        logger.info("Award metadata: resuming incomplete batch_id={}", batch_id)
    else:
        created = with_bounded_retry(
            lambda: award_attachments._run_create_batch(
                engine, batch_size, run_id=run_id
            ),
            operation_name="award create-batch",
        )
        batch_id = created["batch_id"]
        selected_count = created["selected_count"]
        if selected_count == 0:
            return {"batch_id": batch_id, "selected_count": 0, "stage": "metadata"}

    report = with_bounded_retry(
        lambda: award_attachments._run_load_batch(engine, batch_id, run_id=run_id),
        operation_name="award load-batch (metadata)",
    )
    report["stage"] = "metadata"
    report["selected_count"] = selected_count
    return report


def award_binary_stage(
    engine: Engine, *, bucket: str, batch_id: int, run_id: str
) -> dict[str, Any]:
    """Upload every PENDING/UPLOADING candidate in this batch. Reuses
    _run_upload() unchanged (including its own durable-state-first skip
    check and its internal BATCH_STATUS_COMPLETED marker) via a
    constructed argparse.Namespace - the same input shape the CLI itself
    builds."""
    arguments = argparse.Namespace(
        bucket=bucket,
        prefix=None,
        limit=None,
        file_id=None,
        batch_id=batch_id,
        retry_failed=False,
        multipart_threshold_bytes=None,
    )
    report = with_bounded_retry(
        lambda: award_attachments._run_upload(arguments, run_id=run_id),
        operation_name="award upload",
    )
    report["stage"] = "binary"
    return report


def _find_incomplete_batch(engine: Engine, *, domain: str, entity_type: str) -> int | None:
    with engine.connect() as connection:
        return connection.execute(
            text(
                "SELECT batch_id FROM archive.etl_batch "
                "WHERE domain = :domain AND entity_type = :entity_type "
                "AND status = 'CREATED' ORDER BY batch_id DESC LIMIT 1"
            ),
            {"domain": domain, "entity_type": entity_type},
        ).scalar()


def _batch_member_count(engine: Engine, batch_id: int) -> int:
    with engine.connect() as connection:
        return connection.execute(
            text("SELECT COUNT(*) FROM archive.etl_batch_item WHERE batch_id = :b"),
            {"b": batch_id},
        ).scalar()


# ======================================================================
# Proposal stage - new. Metadata UPSERT reuses
# load_proposals_from_csv.prepare_attachments()/upsert_proposal_attachments()
# unchanged; batching, upload-candidate selection, and state transitions
# are new, mirroring Award's shape.
# ======================================================================


def _proposal_excluded_file_data_ids(engine: Engine) -> set[str]:
    """Durable-state-first exclusion: a file_data_id already present in
    archive.proposal_attachment has its metadata loaded already,
    regardless of any batch-tracking history - the same principle
    Award's own three-way exclusion applies via its own criterion (c)."""
    with engine.connect() as connection:
        rows = connection.execute(
            text(
                "SELECT DISTINCT file_data_id FROM archive.proposal_attachment "
                "WHERE file_data_id IS NOT NULL"
            )
        ).scalars()
        return {str(value) for value in rows}


def _run_create_proposal_attachment_batch(
    engine: Engine, requested_size: int, *, run_id: str | None = None
) -> dict[str, Any]:
    """Select up to `requested_size` distinct, not-yet-loaded
    file_data_id values from Oracle, in stable ascending (lexicographic)
    order, and persist them in archive.etl_batch.selection_parameters
    (entity_key is a synthetic per-batch ordinal - see module docstring
    for why a UUID file_data_id cannot be the BIGINT entity_key
    directly)."""
    if requested_size <= 0:
        raise ValueError(f"requested_size must be positive, got {requested_size}")

    excluded = _proposal_excluded_file_data_ids(engine)
    selected: list[str] = []
    batches = OracleDataSource(PROPOSAL_ATTACHMENT_FILE_IDS_SQL).read_batches()
    try:
        for batch in batches:
            for value in batch["file_data_id"]:
                if value is None:
                    continue
                text_value = str(value).strip()
                if not text_value or text_value in excluded:
                    continue
                excluded.add(text_value)
                selected.append(text_value)
                if len(selected) >= requested_size:
                    break
            if len(selected) >= requested_size:
                break
    finally:
        batches.close()

    result = batch_framework.create_batch(
        engine,
        domain=PROPOSAL_ATTACHMENT_DOMAIN,
        entity_type=PROPOSAL_ATTACHMENT_ENTITY_TYPE,
        requested_size=requested_size,
        selection_strategy="ORACLE_SCAN_ASCENDING_FILE_DATA_ID_EXCL_LOADED",
        selected_keys=list(range(1, len(selected) + 1)),
        selection_parameters={"file_data_ids": selected},
        run_id=run_id,
    )
    return {
        "batch_id": result["batch_id"],
        "requested_size": requested_size,
        "selected_count": len(selected),
        "selected_file_data_ids": selected,
    }


def _batch_file_data_ids(engine: Engine, batch_id: int) -> list[str]:
    with engine.connect() as connection:
        row = connection.execute(
            text(
                "SELECT selection_parameters FROM archive.etl_batch WHERE batch_id = :b"
            ),
            {"b": batch_id},
        ).fetchone()
        if row is None or row.selection_parameters is None:
            return []
        return list(row.selection_parameters.get("file_data_ids", []))


def proposal_metadata_stage(
    engine: Engine, *, batch_size: int, run_id: str
) -> dict[str, Any]:
    resume_batch_id = _find_incomplete_batch(
        engine, domain=PROPOSAL_ATTACHMENT_DOMAIN, entity_type=PROPOSAL_ATTACHMENT_ENTITY_TYPE
    )
    if resume_batch_id is not None:
        batch_id = resume_batch_id
        file_data_ids = _batch_file_data_ids(engine, batch_id)
        logger.info("Proposal metadata: resuming incomplete batch_id={}", batch_id)
    else:
        created = with_bounded_retry(
            lambda: _run_create_proposal_attachment_batch(engine, batch_size, run_id=run_id),
            operation_name="proposal create-batch",
        )
        batch_id = created["batch_id"]
        file_data_ids = created["selected_file_data_ids"]
        if not file_data_ids:
            return {"batch_id": batch_id, "selected_count": 0, "stage": "metadata"}

    return _run_load_proposal_attachment_batch(engine, batch_id, file_data_ids, run_id=run_id)


def _run_load_proposal_attachment_batch(
    engine: Engine, batch_id: int, file_data_ids: list[str], *, run_id: str | None = None
) -> dict[str, Any]:
    """Idempotent metadata load for exactly this batch's file_data_id
    membership - never reads a BLOB, never touches S3. Reuses
    prepare_attachments()/upsert_proposal_attachments() unchanged; those
    functions already guarantee upload_status/s3_bucket/object_key/
    file_size/checksum/uploaded_at are never touched by a metadata
    reload (see upsert_proposal_attachments()'s own docstring), so
    re-running this is always safe."""
    if not file_data_ids:
        with engine.begin() as connection:
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )
        return {
            "batch_id": batch_id,
            "stage": "metadata",
            "selected_count": 0,
            "inserted": 0,
            "updated": 0,
            "unchanged": 0,
        }

    def _read() -> pd.DataFrame:
        return OracleDataSource(PROPOSAL_ATTACHMENTS_SQL).read_filtered(
            column="file_data_id", values=file_data_ids
        )

    raw = with_bounded_retry(_read, operation_name="proposal attachment metadata read")
    found_in_oracle = (
        {str(value) for value in raw["file_data_id"].dropna()} if not raw.empty else set()
    )
    prepared = proposals.prepare_attachments(raw) if not raw.empty else raw

    report: dict[str, Any] = {
        "batch_id": batch_id,
        "stage": "metadata",
        "selected_count": len(file_data_ids),
        "reference_rows_found": len(prepared),
    }

    upsert_failed = False
    with engine.begin() as connection:
        # The upsert runs inside a SAVEPOINT (not the outer transaction
        # directly): if it raises, Postgres marks the whole transaction
        # aborted and every later statement on the same connection -
        # including the per-item status bookkeeping below - would also
        # fail, unless the failure is contained to a nested SAVEPOINT
        # that can be rolled back on its own while the outer transaction
        # (and this connection) stay usable.
        try:
            with connection.begin_nested():
                upsert_result = (
                    proposals.upsert_proposal_attachments(connection, prepared)
                    if not prepared.empty
                    else {"inserted": 0, "updated": 0, "unchanged": 0}
                )
            report.update(upsert_result)
        except Exception as error:  # noqa: BLE001
            upsert_failed = True
            report["upsert_error"] = redact_error_message(str(error))

        # Per-item fidelity, mirroring award_attachments._run_load_batch:
        # found+upserted -> COMPLETED; selected but absent from Oracle's
        # returned result -> MISSING_SOURCE; found in Oracle but the
        # upsert itself failed -> FAILED (never silently folded into
        # COMPLETED). A batch only advances to READY when every selected
        # item's outcome is fully accounted for by this pass.
        for ordinal, file_data_id in enumerate(file_data_ids, start=1):
            if file_data_id not in found_in_oracle:
                status = batch_framework.ITEM_STATUS_MISSING_SOURCE
            elif upsert_failed:
                status = batch_framework.ITEM_STATUS_FAILED
            else:
                status = batch_framework.ITEM_STATUS_COMPLETED
            batch_framework.set_item_status(connection, batch_id, ordinal, status=status)

        if not upsert_failed:
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )
        # else: batch status is left untouched (stays CREATED) - safe to
        # retry on a future run; never advanced to READY with an
        # unexplained/failed upsert.

    report["batch_advanced_to_ready"] = not upsert_failed
    return report


_PROPOSAL_CANDIDATE_STATUSES = ("NOT_REQUESTED", "IN_PROGRESS")


def select_proposal_upload_candidates(
    connection: Connection, *, file_data_ids: list[str] | None, retry_failed: bool = False
) -> pd.DataFrame:
    """One representative row per distinct file_data_id needing upload
    work - GROUP BY file_data_id so a file referenced by many proposal
    versions is only ever selected once per batch, never once per
    reference row."""
    statuses = list(_PROPOSAL_CANDIDATE_STATUSES)
    if retry_failed:
        statuses.append("FAILED")

    query = """
        SELECT DISTINCT ON (file_data_id)
            file_data_id, file_name, content_type, upload_status,
            s3_bucket, object_key, file_size, checksum
        FROM archive.proposal_attachment
        WHERE upload_status = ANY(:statuses)
          AND file_data_id IS NOT NULL
    """
    params: dict[str, Any] = {"statuses": statuses}
    if file_data_ids is not None:
        query += " AND file_data_id = ANY(:file_data_ids)"
        params["file_data_ids"] = file_data_ids
    query += " ORDER BY file_data_id, proposal_attachment_id"

    result = connection.execute(text(query), params)
    return pd.DataFrame(result.mappings().all())


def mark_proposal_file_in_progress(engine: Engine, file_data_id: str) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "UPDATE archive.proposal_attachment SET upload_status = 'IN_PROGRESS' "
                "WHERE file_data_id = :file_data_id"
            ),
            {"file_data_id": file_data_id},
        )


def mark_proposal_file_uploaded(
    engine: Engine, file_data_id: str, *, bucket: str, key: str, sha256: str, byte_size: int
) -> None:
    """Marks every reference row sharing this file_data_id UPLOADED
    together, in one statement - the physical file was streamed and PUT
    exactly once; every sibling reference becomes durably UPLOADED at
    the same moment, never left behind in a stale status."""
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.proposal_attachment
                   SET upload_status = 'UPLOADED',
                       s3_bucket = :bucket,
                       object_key = :key,
                       checksum = :sha256,
                       file_size = :byte_size,
                       error_message = NULL,
                       uploaded_at = CURRENT_TIMESTAMP
                 WHERE file_data_id = :file_data_id
                """
            ),
            {
                "file_data_id": file_data_id,
                "bucket": bucket,
                "key": key,
                "sha256": sha256,
                "byte_size": byte_size,
            },
        )


def mark_proposal_file_failed(engine: Engine, file_data_id: str, error_message: str) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "UPDATE archive.proposal_attachment SET upload_status = 'FAILED', "
                "error_message = :error WHERE file_data_id = :file_data_id"
            ),
            {"file_data_id": file_data_id, "error": redact_error_message(error_message)},
        )


def mark_proposal_file_missing(engine: Engine, file_data_id: str) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "UPDATE archive.proposal_attachment SET upload_status = 'MISSING_SOURCE' "
                "WHERE file_data_id = :file_data_id"
            ),
            {"file_data_id": file_data_id},
        )


def proposal_binary_stage(
    engine: Engine,
    *,
    bucket: str,
    batch_id: int,
    # Must stay under "proposal/*" (singular) - that is the exact prefix
    # the loader task role's UploadProposalAttachmentObjects IAM
    # statement grants s3:PutObject/GetObject on (see
    # terraform/modules/ecs/main.tf), matching ProposalAttachmentPlugin's
    # own default_s3_prefix. A plural "proposals/..." prefix here is not
    # a cosmetic naming choice - it silently falls outside the granted
    # IAM resource pattern and every upload fails closed with S3
    # AccessDenied. Live-verified (2026-08-12): the original "proposals/
    # by-file-data-id" default caused exactly this failure against the
    # real dev bucket/task role; this corrected prefix does not collide
    # with ProposalAttachmentPlugin's own real key shape under the same
    # prefix ("proposal/{proposalNumber}/{sequenceNumber}/
    # {proposalAttachmentId}/{file}" - keyed by reference, not by
    # physical-file identity), since this orchestrator's own keys live
    # under the distinct "proposal/by-file-data-id/" sub-path.
    prefix: str = "proposal/by-file-data-id",
    run_id: str | None = None,
) -> dict[str, Any]:
    file_data_ids = _batch_file_data_ids(engine, batch_id)
    with engine.connect() as connection:
        candidates = select_proposal_upload_candidates(
            connection, file_data_ids=file_data_ids or None
        )

    report: dict[str, Any] = {
        "batch_id": batch_id,
        "stage": "binary",
        "physical_files_selected": len(candidates),
        "uploaded": 0,
        "skipped_already_uploaded": 0,
        "reused_from_s3": 0,
        "failed": 0,
        "missing_source_content": 0,
        "bytes_uploaded": 0,
    }

    oracle_connection: oracledb.Connection | None = None
    s3_client = boto3.client("s3")
    try:
        for row in candidates.itertuples(index=False):
            file_data_id = str(row.file_data_id)
            file_name = getattr(row, "file_name", None)
            key = f"{prefix.strip('/')}/{file_data_id}/{_safe_name(file_name, file_data_id)}"

            if (
                row.upload_status == "UPLOADED"
                and row.s3_bucket == bucket
                and row.object_key == key
            ):
                report["skipped_already_uploaded"] += 1
                continue

            # Check S3 directly - independent of Postgres's own status -
            # before ever opening an Oracle connection. Catches the
            # narrow crash window between a prior run's successful S3
            # PUT and its Postgres status UPDATE (Postgres would still
            # show IN_PROGRESS there, which the SQL filter alone would
            # re-select).
            try:
                existing = check_s3_existing_object(
                    s3_client,
                    bucket,
                    key,
                    expected_size=getattr(row, "file_size", None),
                    expected_sha256=getattr(row, "checksum", None) or None,
                )
            except S3ObjectMismatch as mismatch:
                # Never call put_object/multipart for a key we know
                # disagrees with a prior recorded expectation, and never
                # read Oracle to try again - stop the whole
                # orchestration so this can be investigated. No further
                # candidates in this batch are processed.
                report["s3_mismatch"] = {
                    "file_data_id": file_data_id,
                    "key": mismatch.key,
                    "reason": mismatch.reason,
                    "expected": mismatch.expected,
                    "actual": mismatch.actual,
                }
                break
            if existing is not None:
                mark_proposal_file_uploaded(
                    engine, file_data_id, bucket=bucket, key=key,
                    sha256=existing["sha256"], byte_size=existing["byte_size"],
                )
                report["reused_from_s3"] += 1
                continue

            if oracle_connection is None:
                credentials = require_oracle_environment()
                oracle_connection = oracledb.connect(
                    user=credentials["ORACLE_USER"],
                    password=credentials["ORACLE_PASSWORD"],
                    dsn=credentials["ORACLE_DSN"],
                )

            mark_proposal_file_in_progress(engine, file_data_id)
            try:
                byte_size, sha256 = with_bounded_retry(
                    lambda: _stream_file_data_to_s3(
                        oracle_connection, s3_client, file_data_id, bucket, key,
                        getattr(row, "content_type", None),
                    ),
                    operation_name=f"proposal file_data_id={file_data_id} upload",
                )
            except MissingBlob:
                mark_proposal_file_missing(engine, file_data_id)
                report["missing_source_content"] += 1
                continue
            except Exception as error:  # noqa: BLE001
                mark_proposal_file_failed(engine, file_data_id, str(error))
                report["failed"] += 1
                continue

            mark_proposal_file_uploaded(
                engine, file_data_id, bucket=bucket, key=key, sha256=sha256, byte_size=byte_size
            )
            report["uploaded"] += 1
            report["bytes_uploaded"] += byte_size
    finally:
        if oracle_connection is not None:
            oracle_connection.close()

    return report


# ======================================================================
# Subaward stage - new. Mirrors Proposal's shape, but state lives on
# archive.subaward_attachment_archive (split metadata/archive tables,
# like Award), not on the reference table itself.
# ======================================================================


def _subaward_excluded_file_data_ids(engine: Engine) -> set[str]:
    """Physical files that need no further batch-creation work: already
    confirmed ARCHIVED in archive.subaward_attachment_archive.

    NOT archive.subaward_attachment (metadata) - a file having metadata
    is a *prerequisite* for archiving it, not evidence it was archived.
    Querying metadata-presence here was a real, live-data-confirmed bug
    (2026-08-16 dry-run against the real, approved Subaward Code 3595
    pilot fixture): archive.subaward_attachment's metadata is already
    ~100% loaded for the whole Subaward population (a side effect of
    the unrelated Subaward --sync-all business-data sync, not of this
    orchestrator's own metadata stage - see
    docs/project-memory/CURRENT_STATE.md), so every candidate file was
    excluded before selection ever ran, for every Subaward Code, not
    just 3595 - candidate_file_data_id_count came back 0 instead of the
    expected 13. Excluding by confirmed ARCHIVED status instead means a
    file is only ever skipped once there is nothing left to do for it -
    re-selecting a metadata-loaded-but-not-yet-archived file into a new
    batch is safe regardless: _upsert_subaward_attachments's own
    metadata UPSERT and its ON CONFLICT (attachment_id) DO NOTHING
    archive-state insert are both already idempotent."""
    with engine.connect() as connection:
        rows = connection.execute(
            text(
                "SELECT DISTINCT file_data_id FROM archive.subaward_attachment_archive "
                "WHERE archive_status = 'ARCHIVED' AND file_data_id IS NOT NULL"
            )
        ).scalars()
        return {str(value) for value in rows}


def _loaded_subaward_ids(engine: Engine) -> list[int]:
    """The reachable population for attachment metadata: only
    subaward_ids that already have a core archive.subaward row can
    accept a new archive.subaward_attachment row at all (real FK, see
    V018) - archive.subaward's own population is itself far short of
    Oracle's full KCOEUS.SUBAWARD population (see module docstring), so
    this filter is not optional."""
    with engine.connect() as connection:
        return [
            int(value)
            for value in connection.execute(
                text("SELECT subaward_id FROM archive.subaward")
            ).scalars()
        ]


def _normalize_subaward_codes(codes: list[str] | None) -> list[str] | None:
    """Sorted, deduped, whitespace-trimmed form used both when persisting
    a batch's --subaward-code scope and when comparing a resume
    candidate's stored scope against what's requested now - so
    equivalent-but-differently-ordered/duplicated code lists are never
    treated as a mismatch. None/empty means "unscoped" (existing,
    unchanged behavior)."""
    if not codes:
        return None
    normalized = sorted({str(code).strip() for code in codes if str(code).strip()})
    return normalized or None


def _resolve_subaward_ids_for_codes(
    engine: Engine, subaward_codes: list[str]
) -> dict[int, str]:
    """Resolve each requested Subaward Code (the stable business
    identifier - archive.subaward.subaward_code) to every
    archive.subaward version (subaward_id) sharing it, via a
    parameterized query - Subaward's version-vs-family split mirrors
    Award's award_id-vs-award_number split (see CLAUDE.md's Award grain
    rules). Returns {subaward_id: subaward_code} for every matching
    version whether or not that version has itself been loaded into
    archive.subaward's core table yet - the cross-scope safety check
    below needs the full version set, not just the loaded subset (a
    same-code sibling version that just hasn't been loaded yet is still
    legitimately in scope)."""
    with engine.connect() as connection:
        rows = connection.execute(
            text(
                "SELECT subaward_id, subaward_code FROM archive.subaward "
                "WHERE subaward_code = ANY(:codes)"
            ),
            {"codes": list(subaward_codes)},
        ).fetchall()
    return {int(row.subaward_id): str(row.subaward_code) for row in rows}


def _batch_subaward_codes(engine: Engine, batch_id: int) -> list[str] | None:
    """The --subaward-code scope a Subaward batch was created with (or
    None for an unscoped batch, including every batch created before
    this option existed - selection_parameters simply has no
    'subaward_codes' key for those)."""
    with engine.connect() as connection:
        row = connection.execute(
            text("SELECT selection_parameters FROM archive.etl_batch WHERE batch_id = :b"),
            {"b": batch_id},
        ).fetchone()
        if row is None or row.selection_parameters is None:
            return None
        return row.selection_parameters.get("subaward_codes")


class SubawardCodeScopeMismatch(RuntimeError):
    """An incomplete Subaward metadata batch already exists
    (archive.etl_batch status=CREATED), but it was created under a
    different --subaward-code scope than the one requested now. Never
    silently resume it - that would finish writing metadata for a batch
    whose membership was decided under a different (possibly much
    broader, or entirely unrelated) scope, silently defeating a pilot's
    whole purpose - and never silently create a second, parallel CREATED
    batch for the same domain/entity_type either (exactly one CREATED
    batch is expected per module - see _find_incomplete_batch). The
    operator must resolve the existing batch first."""

    def __init__(
        self, *, batch_id: int, requested: list[str] | None, existing: list[str] | None
    ) -> None:
        self.batch_id = batch_id
        self.requested = requested
        self.existing = existing
        super().__init__(
            f"Incomplete Subaward attachment batch_id={batch_id} was created "
            f"with --subaward-code scope {existing!r}, which does not match "
            f"the requested scope {requested!r}. Resolve the existing batch "
            "before running a differently-scoped request."
        )


class CrossScopeFileSharingError(RuntimeError):
    """A physical file (file_data_id) selected for a --subaward-code-
    scoped batch is also referenced, in Oracle, by a Subaward version
    whose subaward_id falls outside every version of the requested
    Subaward Code(s). Raised before any write - see
    _run_create_subaward_attachment_batch, which checks this strictly
    before batch_framework.create_batch. A targeted/pilot scope must
    never silently archive a file that is inseparably shared with
    out-of-scope Subaward data, even when byte-for-byte identical."""

    def __init__(self, *, file_data_id: str, offending_subaward_ids: set[int]) -> None:
        self.file_data_id = file_data_id
        self.offending_subaward_ids = offending_subaward_ids
        super().__init__(
            f"file_data_id={file_data_id} is also referenced by "
            f"subaward_id(s) {sorted(offending_subaward_ids)} outside the "
            "requested --subaward-code scope"
        )


def _assert_no_cross_scope_file_sharing(
    engine: Engine, file_data_ids: list[str], in_scope_subaward_ids: set[int]
) -> None:
    """Parameterized re-query of the same Oracle source
    (SUBAWARD_ATTACHMENT_FILE_IDS_SQL), this time filtered by
    file_data_id rather than subaward_id, to see every subaward_id - in
    or out of scope - that references each candidate file. The initial
    subaward_id-scoped scan can never see an out-of-scope reference on
    its own, since it never asked Oracle about any subaward_id outside
    the requested scope in the first place; this is a separate,
    deliberate check for exactly that blind spot."""
    if not file_data_ids:
        return
    raw = with_bounded_retry(
        lambda: OracleDataSource(SUBAWARD_ATTACHMENT_FILE_IDS_SQL).read_filtered(
            column="file_data_id", values=file_data_ids
        ),
        operation_name="subaward cross-scope file-sharing check",
    )
    if raw.empty:
        return

    offenders: dict[str, set[int]] = {}
    for row in raw.itertuples(index=False):
        subaward_id = int(row.subaward_id)
        if subaward_id in in_scope_subaward_ids:
            continue
        offenders.setdefault(str(row.file_data_id), set()).add(subaward_id)

    if offenders:
        file_data_id = sorted(offenders)[0]
        raise CrossScopeFileSharingError(
            file_data_id=file_data_id, offending_subaward_ids=offenders[file_data_id]
        )


def _run_create_subaward_attachment_batch(
    engine: Engine,
    requested_size: int,
    *,
    run_id: str | None = None,
    subaward_codes: list[str] | None = None,
) -> dict[str, Any]:
    """subaward_codes (repeatable --subaward-code on the CLI) restricts
    selection to files referenced by the resolved Subaward version IDs
    for those codes (see _resolve_subaward_ids_for_codes), and fails -
    before batch_framework.create_batch or any other write - if a
    selected file is also referenced by a Subaward Code outside the
    requested set (see _assert_no_cross_scope_file_sharing). Passing
    None/empty (the default) preserves the exact prior unscoped
    behavior: every loaded subaward_id is eligible, no cross-scope check
    runs at all."""
    if requested_size <= 0:
        raise ValueError(f"requested_size must be positive, got {requested_size}")

    excluded = _subaward_excluded_file_data_ids(engine)
    loaded_subaward_ids = _loaded_subaward_ids(engine)

    normalized_codes = _normalize_subaward_codes(subaward_codes)
    resolved: dict[int, str] = {}
    if normalized_codes:
        resolved = _resolve_subaward_ids_for_codes(engine, normalized_codes)
        scoped_subaward_ids = [sid for sid in loaded_subaward_ids if sid in resolved]
    else:
        scoped_subaward_ids = loaded_subaward_ids

    selected: list[str] = []
    if scoped_subaward_ids:
        raw = with_bounded_retry(
            lambda: OracleDataSource(SUBAWARD_ATTACHMENT_FILE_IDS_SQL).read_filtered(
                column="subaward_id", values=scoped_subaward_ids
            ),
            operation_name="subaward attachment file_data_id scan",
        )
        for value in raw["file_data_id"] if not raw.empty else []:
            if value is None:
                continue
            text_value = str(value).strip()
            if not text_value or text_value in excluded:
                continue
            excluded.add(text_value)
            selected.append(text_value)
            if len(selected) >= requested_size:
                break

    if normalized_codes and selected:
        _assert_no_cross_scope_file_sharing(engine, selected, set(resolved.keys()))

    result = batch_framework.create_batch(
        engine,
        domain=SUBAWARD_ATTACHMENT_DOMAIN,
        entity_type=SUBAWARD_ATTACHMENT_ENTITY_TYPE,
        requested_size=requested_size,
        # archive.etl_batch.selection_strategy is VARCHAR(50) (V037) -
        # the original, more descriptive strategy names here
        # (ORACLE_SCAN_SUBAWARD_ID_SCOPED_FILE_DATA_ID_EXCL_LOADED /
        # ...CODE_SCOPED..., 55/57 chars) were a real, live-data-
        # confirmed defect: both exceed the column, and this INSERT had
        # simply never executed against real Postgres before (Subaward
        # binary-stage execution was deferred throughout - see the
        # module docstring). Never widen the column for this - keep
        # every strategy name under the existing limit instead.
        selection_strategy=(
            "SUBAWARD_CODE_SCOPE_EXCL_ARCHIVED"
            if normalized_codes
            else "SUBAWARD_ALL_EXCL_ARCHIVED"
        ),
        selected_keys=list(range(1, len(selected) + 1)),
        selection_parameters={
            "file_data_ids": selected,
            "subaward_codes": normalized_codes,
        },
        run_id=run_id,
    )
    return {
        "batch_id": result["batch_id"],
        "requested_size": requested_size,
        "selected_count": len(selected),
        "selected_file_data_ids": selected,
    }


def plan_subaward_batch(
    engine: Engine,
    requested_size: int,
    *,
    subaward_codes: list[str] | None = None,
    prefix: str = "subawards/by-file-data-id",
) -> dict[str, Any]:
    """Read-only preview of exactly what a real
    _run_create_subaward_attachment_batch call would select, with no
    side effects: never calls batch_framework.create_batch (no
    archive.etl_batch write), never upserts subaward_attachment
    metadata, never touches S3 or an Oracle BLOB. Still issues the same
    read-only Postgres/Oracle queries a real batch-create would (code
    resolution, the subaward_id-scoped file scan, and the cross-scope
    safety check) - a --dry-run should surface a scope problem before
    anyone attempts the real write, not hide it behind "nothing was
    actually checked"."""
    if requested_size <= 0:
        raise ValueError(f"requested_size must be positive, got {requested_size}")

    excluded = _subaward_excluded_file_data_ids(engine)
    loaded_subaward_ids = _loaded_subaward_ids(engine)
    normalized_codes = _normalize_subaward_codes(subaward_codes)

    resolved: dict[int, str] = {}
    unresolved_codes: list[str] = []
    if normalized_codes:
        resolved = _resolve_subaward_ids_for_codes(engine, normalized_codes)
        resolved_codes = set(resolved.values())
        unresolved_codes = [code for code in normalized_codes if code not in resolved_codes]
        scoped_subaward_ids = [sid for sid in loaded_subaward_ids if sid in resolved]
    else:
        scoped_subaward_ids = loaded_subaward_ids

    selected: list[str] = []
    if scoped_subaward_ids:
        raw = with_bounded_retry(
            lambda: OracleDataSource(SUBAWARD_ATTACHMENT_FILE_IDS_SQL).read_filtered(
                column="subaward_id", values=scoped_subaward_ids
            ),
            operation_name="subaward attachment file_data_id scan (dry-run)",
        )
        for value in raw["file_data_id"] if not raw.empty else []:
            if value is None:
                continue
            text_value = str(value).strip()
            if not text_value or text_value in excluded:
                continue
            excluded.add(text_value)
            selected.append(text_value)
            if len(selected) >= requested_size:
                break

    cross_scope_violation: dict[str, Any] | None = None
    if normalized_codes and selected:
        try:
            _assert_no_cross_scope_file_sharing(engine, selected, set(resolved.keys()))
        except CrossScopeFileSharingError as error:
            cross_scope_violation = {
                "file_data_id": error.file_data_id,
                "offending_subaward_ids": sorted(error.offending_subaward_ids),
            }

    return {
        "dry_run": True,
        "requested_subaward_codes": normalized_codes,
        "unresolved_subaward_codes": unresolved_codes,
        "resolved_subaward_version_count": len(resolved) if normalized_codes else None,
        "in_scope_loaded_subaward_id_count": len(scoped_subaward_ids),
        "candidate_file_data_id_count": len(selected),
        "destination_key_shape": f"{prefix.strip('/')}/{{file_data_id}}/{{safe_file_name}}",
        "cross_scope_violation": cross_scope_violation,
    }


def subaward_metadata_stage(
    engine: Engine, *, batch_size: int, run_id: str, subaward_codes: list[str] | None = None
) -> dict[str, Any]:
    """NOTE: archive.subaward's own core-record population is itself
    only 513 of 88,818 real Oracle subawards (0.6%) as of 2026-08-12 -
    subaward_attachment is a child table of that core loader
    (load_subawards_from_csv.py), not an independent source. This stage
    can only ever surface attachment metadata for subaward_ids that
    already have a core archive.subaward row - expanding that population
    is a separate, out-of-scope undertaking (see the inventory doc). In
    the current dev database this stage will correctly find ~0 new
    candidates, not because of a bug, but because the reachable
    population is already fully archived.

    subaward_codes (repeatable --subaward-code on the CLI) restricts a
    newly-created batch's selection - see
    _run_create_subaward_attachment_batch. When an incomplete batch is
    found instead, it is only resumed if it was created under the exact
    same --subaward-code scope (see SubawardCodeScopeMismatch) - this is
    what stops a scoped pilot run from silently finishing an unrelated,
    differently-scoped batch that happens to be sitting CREATED."""
    resume_batch_id = _find_incomplete_batch(
        engine, domain=SUBAWARD_ATTACHMENT_DOMAIN, entity_type=SUBAWARD_ATTACHMENT_ENTITY_TYPE
    )
    if resume_batch_id is not None:
        requested_scope = _normalize_subaward_codes(subaward_codes)
        existing_scope = _normalize_subaward_codes(_batch_subaward_codes(engine, resume_batch_id))
        if existing_scope != requested_scope:
            raise SubawardCodeScopeMismatch(
                batch_id=resume_batch_id, requested=requested_scope, existing=existing_scope
            )
        batch_id = resume_batch_id
        file_data_ids = _batch_file_data_ids(engine, batch_id)
        logger.info("Subaward metadata: resuming incomplete batch_id={}", batch_id)
    else:
        created = with_bounded_retry(
            lambda: _run_create_subaward_attachment_batch(
                engine, batch_size, run_id=run_id, subaward_codes=subaward_codes
            ),
            operation_name="subaward create-batch",
        )
        batch_id = created["batch_id"]
        file_data_ids = created["selected_file_data_ids"]
        if not file_data_ids:
            return {"batch_id": batch_id, "selected_count": 0, "stage": "metadata"}

    return _run_load_subaward_attachment_batch(engine, batch_id, file_data_ids, run_id=run_id)


def _run_load_subaward_attachment_batch(
    engine: Engine, batch_id: int, file_data_ids: list[str], *, run_id: str | None = None
) -> dict[str, Any]:
    if not file_data_ids:
        with engine.begin() as connection:
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )
        return {
            "batch_id": batch_id, "stage": "metadata", "selected_count": 0,
            "inserted": 0, "updated": 0, "unchanged": 0,
        }

    def _read() -> pd.DataFrame:
        return OracleDataSource(SUBAWARD_ATTACHMENTS_SQL).read_filtered(
            column="file_data_id", values=file_data_ids
        )

    raw = with_bounded_retry(_read, operation_name="subaward attachment metadata read")
    found_in_oracle = (
        {str(value) for value in raw["file_data_id"].dropna()} if not raw.empty else set()
    )

    report: dict[str, Any] = {
        "batch_id": batch_id, "stage": "metadata",
        "selected_count": len(file_data_ids), "reference_rows_found": len(raw),
    }

    upsert_failed = False
    with engine.begin() as connection:
        # Same SAVEPOINT isolation as the Proposal path (see
        # _run_load_proposal_attachment_batch) - not run/exercised yet
        # (Subaward execution is deferred), but kept in the same
        # corrected shape for when V073 and Subaward execution resume.
        try:
            with connection.begin_nested():
                upsert_result = (
                    _upsert_subaward_attachments(connection, raw)
                    if not raw.empty
                    else {"inserted": 0, "updated": 0, "unchanged": 0}
                )
            report.update(upsert_result)
        except Exception as error:  # noqa: BLE001
            upsert_failed = True
            report["upsert_error"] = redact_error_message(str(error))

        for ordinal, file_data_id in enumerate(file_data_ids, start=1):
            if file_data_id not in found_in_oracle:
                status = batch_framework.ITEM_STATUS_MISSING_SOURCE
            elif upsert_failed:
                status = batch_framework.ITEM_STATUS_FAILED
            else:
                status = batch_framework.ITEM_STATUS_COMPLETED
            batch_framework.set_item_status(connection, batch_id, ordinal, status=status)

        if not upsert_failed:
            batch_framework.set_batch_status(
                connection, batch_id, status=batch_framework.BATCH_STATUS_READY
            )

    report["batch_advanced_to_ready"] = not upsert_failed
    return report


_SUBAWARD_ATTACHMENT_COLUMNS = [
    "attachment_id", "subaward_id", "subaward_code", "sequence_number",
    "attachment_type_code", "attachment_type_description", "document_id",
    "file_data_id", "file_name", "mime_type", "document_status_code",
    "description", "last_update_timestamp", "last_update_user",
    "update_timestamp", "update_user", "ver_nbr", "obj_id",
]


def _upsert_subaward_attachments(connection: Connection, attachments: pd.DataFrame) -> dict[str, int]:
    """Idempotent UPSERT of archive.subaward_attachment metadata only -
    never touches archive.subaward_attachment_archive's own
    archive_status/s3_bucket/s3_key/byte_size/sha256 columns, which
    remain exclusively owned by the binary-upload stage below, exactly
    matching upsert_proposal_attachments()'s own contract.

    Defensively skips (never errors on) any row whose subaward_id has
    no core archive.subaward row - archive.subaward_attachment.subaward_id
    is a real FK (V018), and a file_data_id shared across multiple
    subaward_ids (see module docstring) can bring back a reference row
    for a NOT-loaded sibling subaward even though batch selection itself
    is already scoped to loaded subaward_ids - this is the last line of
    defense against that, not the primary filter."""
    present_columns = [c for c in _SUBAWARD_ATTACHMENT_COLUMNS if c in attachments.columns]
    inserted = updated = unchanged = 0
    skipped_no_core_record = 0
    for _, row in attachments.iterrows():
        values = {c: row.get(c) for c in present_columns}
        core_exists = connection.execute(
            text("SELECT 1 FROM archive.subaward WHERE subaward_id = :id"),
            {"id": values["subaward_id"]},
        ).fetchone()
        if core_exists is None:
            skipped_no_core_record += 1
            continue
        existing = connection.execute(
            text(
                "SELECT attachment_id FROM archive.subaward_attachment WHERE attachment_id = :id"
            ),
            {"id": values["attachment_id"]},
        ).fetchone()
        assignments = ", ".join(f"{c} = :{c}" for c in present_columns if c != "attachment_id")
        connection.execute(
            text(
                f"""
                INSERT INTO archive.subaward_attachment ({", ".join(present_columns)})
                VALUES ({", ".join(":" + c for c in present_columns)})
                ON CONFLICT (attachment_id) DO UPDATE SET {assignments}
                """
            ),
            values,
        )
        # Ensure a corresponding archive-state row exists (PENDING) -
        # never overwrites an existing archive-state row's own status.
        connection.execute(
            text(
                """
                INSERT INTO archive.subaward_attachment_archive (
                    attachment_id, subaward_id, subaward_code, sequence_number,
                    file_data_id, original_file_name, mime_type, archive_status,
                    manifest_updated_at
                ) VALUES (
                    :attachment_id, :subaward_id, :subaward_code, :sequence_number,
                    :file_data_id, :file_name, :mime_type, 'PENDING', CURRENT_TIMESTAMP
                )
                ON CONFLICT (attachment_id) DO NOTHING
                """
            ),
            values,
        )
        if existing is None:
            inserted += 1
        else:
            updated += 1
    return {
        "inserted": inserted,
        "updated": updated,
        "unchanged": unchanged,
        "skipped_no_core_record": skipped_no_core_record,
    }


_SUBAWARD_CANDIDATE_STATUSES = ("PENDING", "UPLOADING")


def select_subaward_upload_candidates(
    connection: Connection, *, file_data_ids: list[str] | None, retry_failed: bool = False
) -> pd.DataFrame:
    statuses = list(_SUBAWARD_CANDIDATE_STATUSES)
    if retry_failed:
        statuses.append("FAILED")

    query = """
        SELECT DISTINCT ON (saa.file_data_id)
            saa.file_data_id, saa.original_file_name, saa.mime_type,
            saa.archive_status, saa.s3_bucket, saa.s3_key, saa.byte_size,
            saa.sha256
        FROM archive.subaward_attachment_archive saa
        WHERE saa.archive_status = ANY(:statuses)
          AND saa.file_data_id IS NOT NULL
    """
    params: dict[str, Any] = {"statuses": statuses}
    if file_data_ids is not None:
        query += " AND saa.file_data_id = ANY(:file_data_ids)"
        params["file_data_ids"] = file_data_ids
    query += " ORDER BY saa.file_data_id, saa.attachment_id"

    result = connection.execute(text(query), params)
    return pd.DataFrame(result.mappings().all())


def mark_subaward_file_uploading(engine: Engine, file_data_id: str) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "UPDATE archive.subaward_attachment_archive SET archive_status = 'UPLOADING' "
                "WHERE file_data_id = :file_data_id"
            ),
            {"file_data_id": file_data_id},
        )


def mark_subaward_file_uploaded(
    engine: Engine, file_data_id: str, *, bucket: str, key: str, sha256: str, byte_size: int
) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                UPDATE archive.subaward_attachment_archive
                   SET archive_status = 'ARCHIVED', s3_bucket = :bucket, s3_key = :key,
                       sha256 = :sha256, byte_size = :byte_size, error_message = NULL,
                       archived_timestamp = CURRENT_TIMESTAMP
                 WHERE file_data_id = :file_data_id
                """
            ),
            {
                "file_data_id": file_data_id, "bucket": bucket, "key": key,
                "sha256": sha256, "byte_size": byte_size,
            },
        )


def mark_subaward_file_failed(engine: Engine, file_data_id: str, error_message: str) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "UPDATE archive.subaward_attachment_archive SET archive_status = 'FAILED', "
                "error_message = :error WHERE file_data_id = :file_data_id"
            ),
            {"file_data_id": file_data_id, "error": redact_error_message(error_message)},
        )


def mark_subaward_file_missing(engine: Engine, file_data_id: str) -> None:
    with engine.begin() as connection:
        connection.execute(
            text(
                "UPDATE archive.subaward_attachment_archive SET archive_status = 'MISSING' "
                "WHERE file_data_id = :file_data_id"
            ),
            {"file_data_id": file_data_id},
        )


def subaward_binary_stage(
    engine: Engine,
    *,
    bucket: str,
    batch_id: int,
    prefix: str = "subawards/by-file-data-id",
    run_id: str | None = None,
) -> dict[str, Any]:
    file_data_ids = _batch_file_data_ids(engine, batch_id)
    with engine.connect() as connection:
        candidates = select_subaward_upload_candidates(
            connection, file_data_ids=file_data_ids or None
        )

    report: dict[str, Any] = {
        "batch_id": batch_id, "stage": "binary",
        "physical_files_selected": len(candidates),
        "uploaded": 0, "skipped_already_uploaded": 0, "reused_from_s3": 0,
        "failed": 0, "missing_source_content": 0, "bytes_uploaded": 0,
    }

    oracle_connection: oracledb.Connection | None = None
    s3_client = boto3.client("s3")
    try:
        for row in candidates.itertuples(index=False):
            file_data_id = str(row.file_data_id)
            file_name = getattr(row, "original_file_name", None)
            key = f"{prefix.strip('/')}/{file_data_id}/{_safe_name(file_name, file_data_id)}"

            if (
                row.archive_status == "ARCHIVED"
                and row.s3_bucket == bucket
                and row.s3_key == key
            ):
                report["skipped_already_uploaded"] += 1
                continue

            try:
                existing = check_s3_existing_object(
                    s3_client,
                    bucket,
                    key,
                    expected_size=getattr(row, "byte_size", None),
                    expected_sha256=getattr(row, "sha256", None) or None,
                )
            except S3ObjectMismatch as mismatch:
                report["s3_mismatch"] = {
                    "file_data_id": file_data_id,
                    "key": mismatch.key,
                    "reason": mismatch.reason,
                    "expected": mismatch.expected,
                    "actual": mismatch.actual,
                }
                break
            if existing is not None:
                mark_subaward_file_uploaded(
                    engine, file_data_id, bucket=bucket, key=key,
                    sha256=existing["sha256"], byte_size=existing["byte_size"],
                )
                report["reused_from_s3"] += 1
                continue

            if oracle_connection is None:
                credentials = require_oracle_environment()
                oracle_connection = oracledb.connect(
                    user=credentials["ORACLE_USER"],
                    password=credentials["ORACLE_PASSWORD"],
                    dsn=credentials["ORACLE_DSN"],
                )

            mark_subaward_file_uploading(engine, file_data_id)
            try:
                byte_size, sha256 = with_bounded_retry(
                    lambda: _stream_file_data_to_s3(
                        oracle_connection, s3_client, file_data_id, bucket, key,
                        getattr(row, "mime_type", None),
                    ),
                    operation_name=f"subaward file_data_id={file_data_id} upload",
                )
            except MissingBlob:
                mark_subaward_file_missing(engine, file_data_id)
                report["missing_source_content"] += 1
                continue
            except Exception as error:  # noqa: BLE001
                mark_subaward_file_failed(engine, file_data_id, str(error))
                report["failed"] += 1
                continue

            mark_subaward_file_uploaded(
                engine, file_data_id, bucket=bucket, key=key, sha256=sha256, byte_size=byte_size
            )
            report["uploaded"] += 1
            report["bytes_uploaded"] += byte_size
    finally:
        if oracle_connection is not None:
            oracle_connection.close()

    return report


# ======================================================================
# Shared blob streaming (Proposal/Subaward - both resolve purely via
# KCOEUS.FILE_DATA, never the INLINE/ATTACHMENT_FILE path Award alone
# has). Reuses the same chunked-read/incremental-SHA256 shape as
# load_award_attachments.stream_upload's EXTERNAL branch.
# ======================================================================


class MissingBlob(RuntimeError):
    """KCOEUS.FILE_DATA has no row/BLOB for this file_data_id."""


class S3ObjectMismatch(RuntimeError):
    """An object exists at the deterministic key, but its size or (when
    both sides have one) its checksum disagrees with a previously
    recorded expectation. This is never a "not found" outcome and must
    never be treated as one: the caller must stop rather than retrieve
    from Oracle and overwrite an existing, disagreeing object. See
    check_s3_existing_object()."""

    def __init__(self, *, key: str, reason: str, expected: Any, actual: Any) -> None:
        self.key = key
        self.reason = reason
        self.expected = expected
        self.actual = actual
        super().__init__(
            f"S3 object mismatch at {key}: {reason} "
            f"(expected={expected!r}, actual={actual!r})"
        )


def check_s3_existing_object(
    s3_client: Any,
    bucket: str,
    key: str,
    *,
    expected_size: int | None,
    expected_sha256: str | None = None,
) -> dict[str, Any] | None:
    """HEAD-check the deterministic key directly against S3 - independent
    of whatever Postgres's own upload_status/archive_status column
    currently says. This is what actually proves "already verified S3
    objects are detected before any Oracle BLOB is retrieved" for the
    narrow crash window between a successful S3 PUT and the Postgres
    status UPDATE that was supposed to follow it: in that window
    Postgres still shows IN_PROGRESS/UPLOADING (so the SQL candidate
    filter alone would re-select it), but the object is already real and
    correct in S3 - this check catches that case before any Oracle
    connection is opened.

    Three, and only three, outcomes:
      - No object at `key`: returns None. Caller proceeds to Oracle and
        uploads normally.
      - An object exists and either matches every available expectation
        (size, and checksum when both sides have one) or there was no
        prior expectation to compare against at all (a genuinely new
        key, or this same crash-recovered run's own just-written
        object): returns {"byte_size", "sha256"} - sha256 comes from the
        object's own Metadata.sha256 tag when present, never fabricated
        when absent. Caller reuses it without reading Oracle.
      - An object exists but its size, or its checksum (only when BOTH
        the prior expectation and the object's own tag are present),
        disagrees with what was previously recorded: raises
        S3ObjectMismatch. This is NEVER folded into the "not found"
        case - the caller must stop, never call put_object/multipart
        upload for this key, and never read Oracle for it. A same-key
        collision or a corrupted prior write is a data-integrity
        condition, not a routine "go fetch it again" situation."""
    try:
        head = s3_client.head_object(Bucket=bucket, Key=key)
    except ClientError as error:
        error_code = error.response.get("Error", {}).get("Code")
        if error_code in ("404", "NoSuchKey", "NotFound"):
            return None
        raise

    actual_size = head.get("ContentLength")
    actual_sha256 = (head.get("Metadata") or {}).get("sha256") or None

    if (
        expected_size is not None
        and actual_size is not None
        and int(expected_size) != int(actual_size)
    ):
        raise S3ObjectMismatch(
            key=key, reason="size mismatch", expected=expected_size, actual=actual_size
        )
    if expected_sha256 and actual_sha256 and expected_sha256 != actual_sha256:
        raise S3ObjectMismatch(
            key=key,
            reason="sha256 mismatch",
            expected=expected_sha256,
            actual=actual_sha256,
        )

    return {"byte_size": actual_size, "sha256": actual_sha256 or ""}


def _stream_file_data_to_s3(
    oracle_connection: oracledb.Connection,
    s3_client: Any,
    file_data_id: str,
    bucket: str,
    key: str,
    content_type: str | None,
    *,
    chunk_size: int = 1024 * 1024,
) -> tuple[int, str]:
    with oracle_connection.cursor() as cursor:
        cursor.execute(
            "SELECT DATA FROM KCOEUS.FILE_DATA WHERE ID = :file_data_id",
            file_data_id=file_data_id,
        )
        row = cursor.fetchone()
        if row is None or row[0] is None:
            raise MissingBlob(f"FILE_DATA row or BLOB missing for {file_data_id}")
        blob = row[0]
        digest = hashlib.sha256()
        buffer = bytearray()
        offset = 1
        while True:
            chunk = blob.read(offset, chunk_size)
            if not chunk:
                break
            digest.update(chunk)
            buffer.extend(chunk)
            offset += len(chunk)

    sha256 = digest.hexdigest()
    extra_args: dict[str, Any] = {"Metadata": {"sha256": sha256}}
    if content_type:
        extra_args["ContentType"] = content_type
    s3_client.put_object(Bucket=bucket, Key=key, Body=bytes(buffer), **extra_args)
    return len(buffer), sha256


def _safe_name(file_name: str | None, fallback: str) -> str:
    if not file_name:
        return fallback
    keep = "".join(c if c.isalnum() or c in "._-" else "_" for c in file_name)
    return keep or fallback


# ======================================================================
# Reconciliation - never mark a binary-stage batch done until this
# passes. Re-verifies S3 existence/size/checksum against durable
# Postgres state for every file the batch just touched, plus a
# reference-row-count sanity check.
# ======================================================================


def reconcile_batch(
    engine: Engine, s3_client: Any, bucket: str, module: str, file_data_ids_or_none: list[Any] | None
) -> dict[str, Any]:
    """Read back every row this batch could have uploaded (by module's
    own durable state), HEAD-check S3 for each one marked done, and
    compare size/sha256. Returns {"clean": bool, "mismatches": [...],
    "checksum_unavailable": int}.

    Size comparison is always meaningful (ContentLength is always
    present on a HEAD response). Checksum comparison depends on the
    object carrying a Metadata.sha256 tag: this orchestrator's own
    uploads set it (_stream_file_data_to_s3), and Award's own
    stream_upload()/_multipart_upload() (load_award_attachments.py) now
    set it too (put_object Metadata for single-part; a post-completion
    same-key copy_object with MetadataDirective="REPLACE" for
    multipart, since S3 has no way to attach metadata unknown at
    create_multipart_upload time). So for every upload made by this
    orchestrator or by a from-now-on Award upload, sha256 is genuinely
    cross-checked against S3. Objects uploaded by Award *before* this
    fix have no Metadata.sha256 tag at all - those are never reread or
    overwritten solely to add one (that would mean re-touching already-
    correct historical data for no operational reason); this function
    verifies them by size only and counts them in
    "checksum_unavailable" rather than silently ignoring the gap or
    treating an absent tag as a mismatch."""
    mismatches: list[dict[str, Any]] = []
    checksum_unavailable = 0
    checked = 0

    if module == AWARD:
        query = (
            "SELECT file_id AS key, s3_bucket, s3_key, file_size_bytes AS size, sha256 "
            "FROM archive.attachment_object WHERE upload_status = 'UPLOADED'"
        )
        if file_data_ids_or_none:
            query += " AND file_id = ANY(:keys)"
    elif module == PROPOSAL:
        query = (
            "SELECT file_data_id AS key, s3_bucket, object_key AS s3_key, "
            "file_size AS size, checksum AS sha256 "
            "FROM archive.proposal_attachment WHERE upload_status = 'UPLOADED'"
        )
        if file_data_ids_or_none:
            query += " AND file_data_id = ANY(:keys)"
    else:
        query = (
            "SELECT file_data_id AS key, s3_bucket, s3_key, byte_size AS size, sha256 "
            "FROM archive.subaward_attachment_archive WHERE archive_status = 'ARCHIVED'"
        )
        if file_data_ids_or_none:
            query += " AND file_data_id = ANY(:keys)"

    params: dict[str, Any] = {}
    if file_data_ids_or_none:
        params["keys"] = file_data_ids_or_none

    with engine.connect() as connection:
        rows = connection.execute(text(query), params).fetchall()

    for row in rows:
        if row.s3_bucket != bucket or not row.s3_key:
            continue
        checked += 1
        try:
            head = s3_client.head_object(Bucket=bucket, Key=row.s3_key)
        except Exception as error:  # noqa: BLE001
            mismatches.append({"key": row.key, "reason": f"head_object failed: {error}"})
            continue
        actual_size = head.get("ContentLength")
        if row.size is not None and actual_size is not None and int(row.size) != int(actual_size):
            mismatches.append(
                {"key": row.key, "reason": "size mismatch", "postgres": row.size, "s3": actual_size}
            )
            continue
        expected_sha256 = (head.get("Metadata") or {}).get("sha256")
        if row.sha256 and expected_sha256:
            if row.sha256 != expected_sha256:
                mismatches.append(
                    {"key": row.key, "reason": "sha256 mismatch", "postgres": row.sha256, "s3": expected_sha256}
                )
        elif row.sha256 and not expected_sha256:
            # Postgres has a recorded digest (this orchestrator's own
            # uploads, or an Award upload made after the checksum fix)
            # but the S3 object itself carries no Metadata.sha256 tag -
            # a legacy, pre-fix Award object. Verified by size above;
            # never reread/overwritten solely to backfill the tag.
            checksum_unavailable += 1

    return {
        "module": module,
        "checked": checked,
        "mismatches": mismatches,
        "checksum_unavailable": checksum_unavailable,
        "clean": not mismatches,
    }


# ======================================================================
# Main orchestration loop
# ======================================================================


def run_orchestration(
    *,
    modules: tuple[str, ...] = SUPPORTED_MODULES,
    bucket: str,
    batch_size: int = DEFAULT_BATCH_SIZE,
    subaward_codes: list[str] | None = None,
) -> dict[str, Any]:
    """subaward_codes (repeatable --subaward-code on the CLI) is only
    ever consulted for the 'subaward' module's metadata stage - see
    subaward_metadata_stage. Passing it while 'subaward' isn't in
    `modules` is harmless (simply never read); award/proposal ignore it
    entirely."""
    for module in modules:
        if module not in SUPPORTED_MODULES:
            raise ValueError(
                f"Unsupported module {module!r} - only {SUPPORTED_MODULES} are "
                "implemented (Negotiation and IRB are deliberately excluded, "
                "see module docstring)"
            )

    run_id = str(uuid.uuid4())
    engine = create_postgres_engine()
    lock_connection = acquire_lock(engine, label=f"attachment-load:{run_id}")

    summary: dict[str, Any] = {"run_id": run_id, "modules": {}}
    s3_client = boto3.client("s3")

    try:
        for module in modules:
            module_summary: dict[str, Any] = {"metadata_batches": [], "binary_batches": []}

            # Stage 1: metadata, looped until exhausted.
            while True:
                if module == AWARD:
                    result = award_metadata_stage(engine, batch_size=batch_size, run_id=run_id)
                elif module == PROPOSAL:
                    result = proposal_metadata_stage(engine, batch_size=batch_size, run_id=run_id)
                else:
                    result = subaward_metadata_stage(
                        engine, batch_size=batch_size, run_id=run_id, subaward_codes=subaward_codes
                    )
                module_summary["metadata_batches"].append(result)
                logger.bind(module=module, stage="metadata").info("Batch: {}", result)

                if result.get("batch_advanced_to_ready") is False:
                    # Metadata reconciliation was incomplete/unexplained
                    # for this batch (the upsert itself failed) - the
                    # batch was deliberately left CREATED so it can be
                    # retried later, but retrying it automatically in
                    # this same run would loop forever on a persistent
                    # failure. Stop the whole orchestration instead.
                    summary["stopped_reason"] = (
                        f"Metadata upsert failed for {module} "
                        f"batch_id={result.get('batch_id')}: "
                        f"{result.get('upsert_error')}"
                    )
                    summary["modules"][module] = module_summary
                    logger.bind(module=module, stage="metadata").error(
                        "Stopping orchestration: {}", summary["stopped_reason"]
                    )
                    return summary

                if result.get("selected_count", 0) == 0:
                    break

            # Stage 2: binaries, looped until exhausted - only over
            # batches whose metadata stage is READY.
            while True:
                batch_id = _next_ready_batch(engine, module=module)
                if batch_id is None:
                    break
                if module == AWARD:
                    upload_report = award_binary_stage(
                        engine, bucket=bucket, batch_id=batch_id, run_id=run_id
                    )
                    keys = None
                elif module == PROPOSAL:
                    keys = _batch_file_data_ids(engine, batch_id)
                    upload_report = proposal_binary_stage(
                        engine, bucket=bucket, batch_id=batch_id, run_id=run_id
                    )
                else:
                    keys = _batch_file_data_ids(engine, batch_id)
                    upload_report = subaward_binary_stage(
                        engine, bucket=bucket, batch_id=batch_id, run_id=run_id
                    )

                module_summary["binary_batches"].append(upload_report)

                if upload_report.get("s3_mismatch"):
                    summary["stopped_reason"] = (
                        f"S3 object mismatch detected for {module} "
                        f"batch_id={batch_id}: {upload_report['s3_mismatch']}"
                    )
                    summary["modules"][module] = module_summary
                    logger.bind(module=module, stage="binary").error(
                        "Stopping orchestration: {}", summary["stopped_reason"]
                    )
                    return summary

                reconciliation = reconcile_batch(engine, s3_client, bucket, module, keys)
                upload_report["reconciliation"] = reconciliation
                logger.bind(module=module, stage="binary").info(
                    "Batch: {} reconciliation: {}", upload_report, reconciliation
                )

                if not reconciliation["clean"]:
                    summary["stopped_reason"] = (
                        f"Reconciliation failed for {module} batch_id={batch_id}: "
                        f"{reconciliation['mismatches']}"
                    )
                    summary["modules"][module] = module_summary
                    return summary

                if module in (PROPOSAL, SUBAWARD):
                    with engine.begin() as connection:
                        batch_framework.set_batch_status(
                            connection, batch_id, status=batch_framework.BATCH_STATUS_COMPLETED
                        )

                # Deliberately NOT "if physical_files_selected == 0: break"
                # here. Live incident (2026-08-12): _next_ready_batch can
                # return an older READY batch whose own candidates are
                # already fully UPLOADED (e.g. a pre-existing batch that
                # was never touched before this orchestrator existed) -
                # that is a legitimate, harmless no-op for THAT batch, not
                # proof that every other READY batch is also empty. A
                # batch-selected-zero break here silently ended a real
                # run's whole binary stage after processing exactly one
                # such no-op batch, leaving ~90,000 genuinely PENDING
                # Award files (and every newly-loaded Proposal file)
                # completely unprocessed while the run still exited 0.
                # The correct, sufficient termination condition is
                # already above: "if batch_id is None: break" - every
                # batch processed here (Award via _run_upload's own
                # unconditional finish_batch_processing call; Proposal/
                # Subaward via the explicit set_batch_status just above,
                # itself unconditional) always leaves READY status
                # regardless of how many candidates it held, so
                # _next_ready_batch is guaranteed to make forward
                # progress and eventually return None - no infinite-loop
                # risk from removing this check.

            summary["modules"][module] = module_summary
    finally:
        release_lock(lock_connection)

    return summary


def _next_ready_batch(engine: Engine, *, module: str) -> int | None:
    if module == AWARD:
        domain = award_attachments.AWARD_ATTACHMENT_BATCH_DOMAIN
        entity_type = award_attachments.AWARD_ATTACHMENT_BATCH_ENTITY_TYPE
    elif module == PROPOSAL:
        domain, entity_type = PROPOSAL_ATTACHMENT_DOMAIN, PROPOSAL_ATTACHMENT_ENTITY_TYPE
    else:
        domain, entity_type = SUBAWARD_ATTACHMENT_DOMAIN, SUBAWARD_ATTACHMENT_ENTITY_TYPE

    with engine.connect() as connection:
        return connection.execute(
            text(
                "SELECT batch_id FROM archive.etl_batch "
                "WHERE domain = :domain AND entity_type = :entity_type "
                "AND status = 'READY' ORDER BY batch_id ASC LIMIT 1"
            ),
            {"domain": domain, "entity_type": entity_type},
        ).scalar()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--modules", default=",".join(SUPPORTED_MODULES))
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--ecs", action="store_true")
    parser.add_argument(
        "--subaward-code",
        action="append",
        default=None,
        help=(
            "Restrict the Subaward module's batch selection to this "
            "Subaward Code (repeatable - pass multiple times for "
            "multiple codes). Only affects the 'subaward' module; "
            "ignored by award/proposal. An ECS containerOverrides "
            "command array delivers each occurrence as its own token, "
            "so every requested code survives regardless of how many "
            "are given."
        ),
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help=(
            "Read-only preview of the Subaward module's next batch "
            "selection (counts and destination-key shape) - performs no "
            "writes and does not run the real orchestration. Combine "
            "with --subaward-code to preview a scoped pilot batch."
        ),
    )
    args = parser.parse_args(argv)

    if args.ecs:
        configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=True)

    if args.dry_run:
        engine = create_postgres_engine()
        plan = plan_subaward_batch(engine, args.batch_size, subaward_codes=args.subaward_code)
        logger.info("Subaward dry-run plan: {}", plan)
        print(json.dumps(plan, indent=2, default=str))
        return 0 if not plan.get("cross_scope_violation") else 6

    modules = tuple(m.strip() for m in args.modules.split(",") if m.strip())
    try:
        summary = run_orchestration(
            modules=modules,
            bucket=args.bucket,
            batch_size=args.batch_size,
            subaward_codes=args.subaward_code,
        )
    except LockNotAcquired as error:
        logger.error(str(error))
        return 4

    logger.info("Attachment orchestration summary: {}", summary)
    return 0 if not summary.get("stopped_reason") else 6


if __name__ == "__main__":
    raise SystemExit(main())
