"""Award evidence indexing - Phase 2 implementation of
docs/architecture/AWARD_EVIDENCE_INDEXING_PHASE1_DESIGN.md.

Populates archive.search_embedding (V070/V071) with evidence-level rows
for 8 of the 9 approved READY document types, scoped to exactly one
Award number per run:

    AWARD_VERSION, AWARD_PERSON, AWARD_AMOUNT, AWARD_TERM,
    AWARD_COMMENT (comments only, notepad excluded), RELATED_PROPOSAL,
    RELATED_NEGOTIATION, RELATED_SUBAWARD

AWARD_SUMMARY - the 9th READY type - is deliberately NOT implemented
here. The Phase 1 design's Section 3 matrix marks it "Already
implemented - build_search_embedding.py's existing AWARD query/
build_source_text()", and that script's own UPSERT_SQL comment
describes the division of ownership explicitly: "this script's
summaries, plus Phase 1 evidence rows from build_evidence_embedding.py
can coexist per family." AWARD_SUMMARY is the pre-existing,
Global-Search-facing family-level row (see V071's own comment
distinguishing "a family-level Global Search summary row" from "a new
evidence-level row") - this script never writes, upserts, or hard-deletes
it, so it can never overwrite or reconcile away build_search_embedding.py's
own output.

Never processes AWARD_BUDGET, AWARD_TIME_AND_MONEY, AWARD_ATTACHMENT, or
AWARD_SAP_TRANSMISSION - those are explicitly out of scope for Phase 2 (see
the Phase 1 design's Section 3 exclusions). Never reads, extracts, or
indexes attachment binary/text content.

Idempotent by (module, document_type, exact_record_id) - the same
UNIQUE index V071 already created. Reuses build_search_embedding.py's
exact idempotency mechanism (source_hash comparison, skip-if-unchanged)
plus a second hash (source_row_hash, over the raw field values before
text formatting) per the Phase 1 design's Section 6.2.

Reconciliation (stale-row deletion) is hard-delete, per explicit
instruction - no tombstone column, no new migration. It is scoped to
exactly this run's (module='AWARD', document_type IN <requested types>,
parent_business_identifier=:award_number) and only ever runs after every
record in the current source set has been successfully processed - a
run that fails partway through never deletes anything, preserving
whatever was previously valid.

Never logs source_text, sensitive data, or credentials - only record
identifiers and counts.

Usage:
    uv run python build_evidence_embedding.py --ecs \
        --award-number 204713-00133 --dry-run
    uv run python build_evidence_embedding.py --ecs \
        --award-number 204713-00133 \
        --document-types AWARD_VERSION,AWARD_PERSON,AWARD_AMOUNT
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from typing import Any, Callable

import boto3
from loguru import logger
from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine

EMBEDDING_MODEL = "amazon.titan-embed-text-v2:0"
EMBEDDING_DIMENSIONS = 1024

APPROVED_DOCUMENT_TYPES: tuple[str, ...] = (
    "AWARD_VERSION",
    "AWARD_PERSON",
    "AWARD_AMOUNT",
    "AWARD_TERM",
    "AWARD_COMMENT",
    "RELATED_PROPOSAL",
    "RELATED_NEGOTIATION",
    "RELATED_SUBAWARD",
)


def _resolve_project_root():
    from pathlib import Path

    container_root = Path(__file__).resolve().parent
    if (container_root / "database").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()


# --- Per-type candidate queries, each scoped by :award_number ------------
# award_number is bound once per call; every query resolves its own join
# to archive.award_version, matching the Phase 1 design's Section 5 SQL
# fragments exactly (no invented columns).

DOCUMENT_TYPE_QUERIES: dict[str, str] = {
    "AWARD_VERSION": """
        SELECT av.award_id AS exact_record_id, av.award_number, av.sequence_number,
               av.title, av.status_description, av.sponsor_name, av.lead_unit_name,
               av.award_effective_date, av.begin_date, av.closeout_date,
               av.workflow_document_number
        FROM archive.award_version av
        WHERE av.award_number = :award_number
    """,
    "AWARD_PERSON": """
        SELECT ap.award_person_id AS exact_record_id, ap.award_id, av.award_number,
               av.sequence_number, ap.full_name, ap.contact_role_code,
               ap.key_person_project_role
        FROM archive.award_person ap
        JOIN archive.award_version av ON av.award_id = ap.award_id
        WHERE av.award_number = :award_number
    """,
    "AWARD_AMOUNT": """
        SELECT ai.award_amount_info_id AS exact_record_id, ai.award_id, av.award_number,
               av.sequence_number, ai.obligated_total_amount, ai.anticipated_total_amount,
               ai.tnm_document_number
        FROM archive.award_amount_info ai
        JOIN archive.award_version av ON av.award_id = ai.award_id
        WHERE av.award_number = :award_number
    """,
    "AWARD_COMMENT": """
        SELECT ac.award_comment_id AS exact_record_id, ac.award_id, av.award_number,
               av.sequence_number, ac.comment_type_code, ac.comments
        FROM archive.award_comment ac
        JOIN archive.award_version av ON av.award_id = ac.award_id
        WHERE av.award_number = :award_number
          AND ac.comments IS NOT NULL AND TRIM(ac.comments) <> ''
    """,
    "AWARD_TERM_SPONSOR": """
        SELECT ast.award_sponsor_term_id AS exact_record_id, ast.award_id, av.award_number,
               av.sequence_number, ast.sponsor_term_id
        FROM archive.award_sponsor_term ast
        JOIN archive.award_version av ON av.award_id = ast.award_id
        WHERE av.award_number = :award_number
    """,
    "AWARD_TERM_REPORT": """
        SELECT art.award_report_term_id AS exact_record_id, art.award_id, av.award_number,
               av.sequence_number, art.report_class_code, art.report_code,
               art.frequency_code, art.due_date
        FROM archive.award_report_term art
        JOIN archive.award_version av ON av.award_id = art.award_id
        WHERE av.award_number = :award_number
    """,
    "RELATED_PROPOSAL": """
        SELECT afp.award_funding_proposal_id AS exact_record_id, afp.award_id,
               av.award_number, av.sequence_number, pv.proposal_number, pv.title,
               afp.active_flag
        FROM archive.award_funding_proposal afp
        JOIN archive.award_version av ON av.award_id = afp.award_id
        JOIN archive.proposal_version pv ON pv.proposal_id = afp.proposal_id
        WHERE av.award_number = :award_number
    """,
    "RELATED_NEGOTIATION": """
        SELECT negotiation_id AS exact_record_id, document_number,
               negotiation_agreement_type_description, negotiator_full_name,
               negotiation_status_description, associated_document_id AS award_number
        FROM archive.negotiation
        WHERE negotiation_association_type_code = 'AWD'
          AND associated_document_id = :award_number
    """,
    "RELATED_SUBAWARD": """
        SELECT funding.subaward_funding_source_id AS exact_record_id,
               linked_subaward.subaward_code, current_subaward.status_description,
               current_subaward.document_number, funding.award_number AS award_number
        FROM archive.subaward_funding funding
        JOIN archive.subaward linked_subaward ON linked_subaward.subaward_id = funding.subaward_id
        LEFT JOIN archive.subaward current_subaward
            ON current_subaward.subaward_code = linked_subaward.subaward_code
            AND current_subaward.subaward_sequence_status = 'ACTIVE'
        WHERE funding.award_number = :award_number
    """,
}

# AWARD_TERM has two source tables/queries but one document_type - both
# feed into the same "AWARD_TERM" bucket at ingestion time.
_TERM_QUERY_KEYS = ("AWARD_TERM_SPONSOR", "AWARD_TERM_REPORT")


def _queries_for_document_type(document_type: str) -> list[str]:
    if document_type == "AWARD_TERM":
        return list(_TERM_QUERY_KEYS)
    return [document_type]


# --- Deterministic text builders ------------------------------------------
# Explicit field allowlist, stable order, null/blank omitted - never
# rendered as "None"/"null". One function per type; never shared/generic.


def _clause(label: str, value: Any, *, prefix: str = ", ") -> str:
    """Renders "{prefix}{label}{value}" - or nothing at all if value is
    None/blank. This is the single mechanism that prevents "begins None"
    -style output anywhere in this file."""
    if value is None:
        return ""
    text_value = str(value).strip()
    if not text_value:
        return ""
    return f"{prefix}{label}{text_value}"


def build_award_version_text(row: dict) -> str:
    doc = f" (document {row['workflow_document_number']})" if row.get("workflow_document_number") else ""
    text_value = f"Award {row['award_number']} version {row['sequence_number']}{doc}"
    if row.get("title"):
        text_value += f": {row['title']}."
    else:
        text_value += "."
    if row.get("sponsor_name"):
        text_value += f" Sponsor: {row['sponsor_name']}."
    if row.get("lead_unit_name"):
        text_value += f" Lead unit: {row['lead_unit_name']}."
    if row.get("status_description"):
        text_value += f" Status: {row['status_description']}."
    if row.get("award_effective_date"):
        text_value += f" Effective {row['award_effective_date']}"
        text_value += _clause("begins ", row.get("begin_date"))
        text_value += _clause("closes ", row.get("closeout_date"))
        text_value += "."
    return text_value


def build_award_person_text(row: dict) -> str:
    role_clause = f" — {row['key_person_project_role']}" if row.get("key_person_project_role") else ""
    role_code = row.get("contact_role_code") or "UNKNOWN"
    return (
        f"{row['full_name']}{role_clause} ({role_code}) on Award "
        f"{row['award_number']} version {row['sequence_number']}."
    )


def build_award_amount_text(row: dict) -> str:
    doc = f", document {row['tnm_document_number']}" if row.get("tnm_document_number") else ""
    obligated = row.get("obligated_total_amount")
    anticipated = row.get("anticipated_total_amount")
    text_value = (
        f"Award {row['award_number']} version {row['sequence_number']}, "
        f"amount record {row['exact_record_id']}{doc}: "
    )
    text_value += f"obligated ${obligated if obligated is not None else '0.00'}, "
    text_value += f"anticipated ${anticipated if anticipated is not None else '0.00'}."
    return text_value


def build_award_comment_text(row: dict) -> str:
    return (
        f"Comment ({row['comment_type_code']}) on Award {row['award_number']} "
        f"version {row['sequence_number']}: {row['comments']}"
    )


def build_award_term_sponsor_text(row: dict) -> str:
    return (
        f"Sponsor term {row['sponsor_term_id']} on Award {row['award_number']} "
        f"version {row['sequence_number']}."
    )


def build_award_term_report_text(row: dict) -> str:
    text_value = (
        f"Report term: class {row['report_class_code']}, code {row['report_code']}, "
        f"frequency {row['frequency_code']}"
    )
    text_value += _clause("due ", row.get("due_date"))
    text_value += f" on Award {row['award_number']} version {row['sequence_number']}."
    return text_value


def build_related_proposal_text(row: dict) -> str:
    inactive = "" if str(row.get("active_flag", "")).upper() in ("Y", "YES", "TRUE", "1") else " (inactive relationship)"
    return (
        f"Award {row['award_number']} version {row['sequence_number']} is funded by "
        f"Proposal {row['proposal_number']}: {row['title']}.{inactive}"
    )


def build_related_negotiation_text(row: dict) -> str:
    text_value = f"Negotiation {row['document_number']}"
    if row.get("negotiation_agreement_type_description"):
        text_value += f" ({row['negotiation_agreement_type_description']})"
    text_value += f" associated with Award {row['award_number']}"
    if row.get("negotiator_full_name"):
        text_value += f", negotiator {row['negotiator_full_name']}"
    if row.get("negotiation_status_description"):
        text_value += f", status {row['negotiation_status_description']}"
    return text_value + "."


def build_related_subaward_text(row: dict) -> str:
    doc = f" (document {row['document_number']})" if row.get("document_number") else ""
    text_value = f"Subaward {row['subaward_code']}{doc} is linked to Award {row['award_number']}"
    if row.get("status_description"):
        text_value += f", status {row['status_description']}"
    return text_value + "."


_TEXT_BUILDERS: dict[str, Callable[[dict], str]] = {
    "AWARD_VERSION": build_award_version_text,
    "AWARD_PERSON": build_award_person_text,
    "AWARD_AMOUNT": build_award_amount_text,
    "AWARD_COMMENT": build_award_comment_text,
    "AWARD_TERM_SPONSOR": build_award_term_sponsor_text,
    "AWARD_TERM_REPORT": build_award_term_report_text,
    "RELATED_PROPOSAL": build_related_proposal_text,
    "RELATED_NEGOTIATION": build_related_negotiation_text,
    "RELATED_SUBAWARD": build_related_subaward_text,
}


def build_evidence_text(query_key: str, row: dict) -> str:
    return _TEXT_BUILDERS[query_key](row)


# --- Hashing (Phase 1 design Section 6.2) ---------------------------------


def source_row_hash(row: dict) -> str:
    """SHA-256 over the ordered, delimited raw field values (before text
    formatting) - distinct from source_hash, which hashes the assembled
    text. Detects a source-row edit even when two different rows happen
    to produce identical embedded text."""
    ordered_keys = sorted(k for k in row if k != "exact_record_id")
    raw = "|".join(f"{k}={row[k]}" for k in ordered_keys)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def source_hash(text_value: str) -> str:
    return hashlib.sha256(text_value.encode("utf-8")).hexdigest()


# --- Embedding (population-time - mirrors build_search_embedding.py's
# own embed_text() exactly; a separate, independent implementation by
# this repo's own convention, not a shared import - see
# build_search_embedding_poc.py vs. build_search_embedding.py) ----------


def embed_text(bedrock_client: Any, text_value: str) -> list[float]:
    response = bedrock_client.invoke_model(
        modelId=EMBEDDING_MODEL,
        body=json.dumps({"inputText": text_value}),
    )
    payload = json.loads(response["body"].read())
    embedding = payload["embedding"]
    if len(embedding) != EMBEDDING_DIMENSIONS:
        raise ValueError(
            f"Expected a {EMBEDDING_DIMENSIONS}-dimension embedding, got "
            f"{len(embedding)}"
        )
    return embedding


def _vector_literal(embedding: list[float]) -> str:
    return "[" + ",".join(repr(value) for value in embedding) + "]"


# --- Persistence -----------------------------------------------------------

EXISTING_ROW_SQL = """
    SELECT source_hash, source_row_hash FROM archive.search_embedding
    WHERE module = 'AWARD' AND document_type = :document_type
      AND exact_record_id = :exact_record_id
"""

UPSERT_SQL = """
    INSERT INTO archive.search_embedding (
        module, document_type, record_id, canonical_family_id,
        business_number, parent_module, parent_business_identifier,
        exact_record_id, version_label, source_table, source_primary_key,
        source_row_hash, source_text, source_hash, embedding,
        embedding_model, generated_at
    ) VALUES (
        'AWARD', :document_type, :award_id, :award_id,
        :award_number, 'AWARD', :award_number,
        :exact_record_id, :version_label, :source_table, :source_primary_key,
        :source_row_hash, :source_text, :source_hash, CAST(:embedding AS vector),
        :embedding_model, now()
    )
    ON CONFLICT (module, document_type, exact_record_id) DO UPDATE SET
        record_id = EXCLUDED.record_id,
        canonical_family_id = EXCLUDED.canonical_family_id,
        business_number = EXCLUDED.business_number,
        version_label = EXCLUDED.version_label,
        source_row_hash = EXCLUDED.source_row_hash,
        source_text = EXCLUDED.source_text,
        source_hash = EXCLUDED.source_hash,
        embedding = EXCLUDED.embedding,
        embedding_model = EXCLUDED.embedding_model,
        generated_at = EXCLUDED.generated_at
    WHERE archive.search_embedding.source_hash != EXCLUDED.source_hash
"""

DELETE_STALE_SQL = """
    DELETE FROM archive.search_embedding
    WHERE module = 'AWARD'
      AND document_type = ANY(:document_types)
      AND parent_business_identifier = :award_number
      AND exact_record_id != ALL(:keep_ids)
    RETURNING document_type, exact_record_id
"""

SELECT_STALE_CANDIDATES_SQL = """
    SELECT document_type, exact_record_id
    FROM archive.search_embedding
    WHERE module = 'AWARD'
      AND document_type = ANY(:document_types)
      AND parent_business_identifier = :award_number
      AND exact_record_id != ALL(:keep_ids)
"""


_SOURCE_TABLE_BY_QUERY_KEY = {
    "AWARD_VERSION": "archive.award_version",
    "AWARD_PERSON": "archive.award_person",
    "AWARD_AMOUNT": "archive.award_amount_info",
    "AWARD_COMMENT": "archive.award_comment",
    "AWARD_TERM_SPONSOR": "archive.award_sponsor_term",
    "AWARD_TERM_REPORT": "archive.award_report_term",
    "RELATED_PROPOSAL": "archive.award_funding_proposal",
    "RELATED_NEGOTIATION": "archive.negotiation",
    "RELATED_SUBAWARD": "archive.subaward_funding",
}

# version_label is NULL for family-scoped types (RELATED_NEGOTIATION,
# RELATED_SUBAWARD - neither has a version concept, per the Phase 1
# design Section 5.8/5.9); every other type carries sequence_number.
_FAMILY_SCOPED_QUERY_KEYS = {"RELATED_NEGOTIATION", "RELATED_SUBAWARD"}


def _resolve_award_id(row: dict, award_id_cache: dict[str, int | None]) -> int | None:
    if "award_id" in row and row["award_id"] is not None:
        return int(row["award_id"])
    return award_id_cache.get("current")


def _exact_record_id_for(query_key: str, raw_record_id: int) -> int:
    """AWARD_TERM_SPONSOR and AWARD_TERM_REPORT share one document_type
    ("AWARD_TERM") but draw their primary keys from two independent
    Oracle sequences (SEQ_AWARD_SPONSOR_TERM vs. the award_report_term
    row's own sequence - see V040's migration comment) - nothing
    guarantees those two ID spaces never overlap. Both source IDs are
    always positive (BIGINT PRIMARY KEY), so negating report-term IDs
    guarantees the two source tables can never collide inside the same
    (module, document_type, exact_record_id) unique key. This never
    touches source_primary_key, which always keeps the real, positive,
    independently re-queryable database ID."""
    if query_key == "AWARD_TERM_REPORT":
        return -raw_record_id
    return raw_record_id


def populate_evidence(
    engine: Engine,
    embed_fn: Callable[[str], list[float]],
    award_number: str,
    document_types: list[str],
    dry_run: bool,
) -> dict[str, Any]:
    # Enforced here, not just at the CLI (main()) layer, so this function
    # is safe to call directly - an unapproved type never reaches a SQL
    # lookup keyed by it.
    invalid = set(document_types) - set(APPROVED_DOCUMENT_TYPES)
    if invalid:
        raise ValueError(f"Not approved for Phase 2: {sorted(invalid)}")

    totals = {
        "inserted": 0, "updated": 0, "unchanged": 0, "skipped": 0, "failed": 0,
        "deleted": 0, "proposed_deletions": [],
    }
    seen_keys: dict[str, set[int]] = {dt: set() for dt in document_types}
    run_had_failure = False

    with engine.connect() as connection:
        award_row = connection.execute(
            text(
                "SELECT award_id FROM archive.award_version "
                "WHERE award_number = :n AND is_primary_current = TRUE"
            ),
            {"n": award_number},
        ).fetchone()
    current_award_id = int(award_row.award_id) if award_row else None
    award_id_cache = {"current": current_award_id}

    for document_type in document_types:
        for query_key in _queries_for_document_type(document_type):
            sql = DOCUMENT_TYPE_QUERIES[query_key]
            with engine.connect() as connection:
                rows = connection.execute(text(sql), {"award_number": award_number}).mappings().all()

            logger.bind(
                stage="populate_evidence", document_type=document_type,
                query_key=query_key, award_number=award_number,
            ).info("{} candidate row(s) for {}", len(rows), query_key)

            for row in rows:
                row = dict(row)
                raw_record_id = int(row["exact_record_id"])
                exact_record_id = _exact_record_id_for(query_key, raw_record_id)
                seen_keys[document_type].add(exact_record_id)

                try:
                    text_value = build_evidence_text(query_key, row)
                    row_hash = source_row_hash(row)
                    text_hash = source_hash(text_value)

                    with engine.connect() as connection:
                        existing = connection.execute(
                            text(EXISTING_ROW_SQL),
                            {"document_type": document_type, "exact_record_id": exact_record_id},
                        ).fetchone()

                    if existing is not None and existing.source_row_hash == row_hash:
                        totals["unchanged"] += 1
                        continue

                    if dry_run:
                        totals["skipped"] += 1
                        continue

                    embedding = embed_fn(text_value)
                    award_id = _resolve_award_id(row, award_id_cache)
                    version_label = (
                        None if query_key in _FAMILY_SCOPED_QUERY_KEYS
                        else row.get("sequence_number")
                    )

                    with engine.begin() as connection:
                        result = connection.execute(
                            text(UPSERT_SQL),
                            {
                                "document_type": document_type,
                                "award_id": award_id,
                                "award_number": award_number,
                                "exact_record_id": exact_record_id,
                                "version_label": (
                                    str(version_label) if version_label is not None else None
                                ),
                                "source_table": _SOURCE_TABLE_BY_QUERY_KEY[query_key],
                                "source_primary_key": raw_record_id,
                                "source_row_hash": row_hash,
                                "source_text": text_value,
                                "source_hash": text_hash,
                                "embedding": _vector_literal(embedding),
                                "embedding_model": EMBEDDING_MODEL,
                            },
                        )
                        if result.rowcount == 0:
                            totals["unchanged"] += 1
                        elif existing is None:
                            totals["inserted"] += 1
                        else:
                            totals["updated"] += 1
                except Exception as error:  # noqa: BLE001
                    run_had_failure = True
                    totals["failed"] += 1
                    logger.bind(
                        stage="populate_evidence", document_type=document_type,
                        exact_record_id=exact_record_id,
                    ).error("record failed: {}", type(error).__name__)

    # Reconciliation only ever runs after every record in the current
    # source set was processed without an unhandled failure - a partial
    # failure preserves whatever was previously valid, per the approved
    # stale-row policy.
    if run_had_failure:
        totals["reconciliation_skipped_due_to_failure"] = True
        return totals

    with engine.connect() as connection:
        for document_type in document_types:
            keep_ids = list(seen_keys[document_type]) or [-1]
            candidates = connection.execute(
                text(SELECT_STALE_CANDIDATES_SQL),
                {
                    "document_types": [document_type],
                    "award_number": award_number,
                    "keep_ids": keep_ids,
                },
            ).fetchall()
            for candidate in candidates:
                totals["proposed_deletions"].append(
                    {"document_type": candidate.document_type, "exact_record_id": candidate.exact_record_id}
                )

    if dry_run or not totals["proposed_deletions"]:
        return totals

    with engine.begin() as connection:
        for document_type in document_types:
            keep_ids = list(seen_keys[document_type]) or [-1]
            deleted = connection.execute(
                text(DELETE_STALE_SQL),
                {
                    "document_types": [document_type],
                    "award_number": award_number,
                    "keep_ids": keep_ids,
                },
            ).fetchall()
            totals["deleted"] += len(deleted)

    return totals


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--award-number", required=True, help="Exactly one Award number - Phase 2 never processes production-wide")
    parser.add_argument(
        "--document-types", default=",".join(APPROVED_DOCUMENT_TYPES),
        help=f"Comma-separated subset of: {', '.join(APPROVED_DOCUMENT_TYPES)}",
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--ecs", action="store_true")
    args = parser.parse_args(argv)

    document_types = [d.strip() for d in args.document_types.split(",") if d.strip()]
    invalid = set(document_types) - set(APPROVED_DOCUMENT_TYPES)
    if invalid:
        parser.error(f"Not approved for Phase 2: {sorted(invalid)}")

    if args.ecs:
        configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=False)

    engine = create_postgres_engine()
    apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")

    bedrock_client = None if args.dry_run else boto3.client("bedrock-runtime")
    embed_fn = (lambda t: []) if args.dry_run else (lambda t: embed_text(bedrock_client, t))

    report = populate_evidence(engine, embed_fn, args.award_number, document_types, args.dry_run)

    print(json.dumps(
        {
            "award_number": args.award_number,
            "document_types": document_types,
            "dry_run": args.dry_run,
            **{k: v for k, v in report.items() if k != "proposed_deletions"},
            "proposed_deletion_count": len(report.get("proposed_deletions", [])),
            "proposed_deletions": report.get("proposed_deletions", []),
        },
        indent=2, default=str,
    ))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:  # noqa: BLE001
        logger.exception("build_evidence_embedding failed")
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
