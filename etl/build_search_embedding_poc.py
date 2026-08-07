"""Global Search semantic-search proof-of-concept: populates
archive.search_embedding_poc with embeddings for ~500-1000 current
records across Award, Proposal, Negotiation, and Subaward, built ONLY
from safe, high-value structured fields (title, PI/person, sponsor, lead
unit, status, module) - never attachments, comments, custom data, or
legal documents.

Deliberately EXPERIMENTAL and separate from production Global Search:
this never writes to or reads from GlobalSearchService's own tables, and
archive.search_embedding_poc has no foreign key into any domain table.
Does not modify production Global Search behavior in any way.

Uses AWS Bedrock (amazon.titan-embed-text-v2:0) - data stays inside
BU's AWS account/region, never sent to an external vendor. Requires the
loader task role's bedrock:InvokeModel grant (see
terraform/modules/ecs/main.tf's task_bedrock policy), added specifically
for this experiment.

Idempotent by (module, record_id): a record whose source_hash hasn't
changed since the last run is skipped, not re-embedded - avoiding
unnecessary Bedrock calls on rerun.

Usage:
    uv run python build_search_embedding_poc.py --ecs --limit-per-domain 200
    uv run python build_search_embedding_poc.py --ecs --limit-per-domain 5 --dry-run
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

import boto3
from sqlalchemy import text
from sqlalchemy.engine import Engine

from archive_etl.config.ecs import configure_ecs_environment
from archive_etl.upload.migrations import apply_migrations
from archive_etl.upload.postgres import create_postgres_engine

EMBEDDING_MODEL = "amazon.titan-embed-text-v2:0"
EMBEDDING_DIMENSIONS = 1024


def _resolve_project_root() -> Path:
    """Mirrors load_awards_from_csv.py's own _resolve_project_root()
    exactly (not shared code, same technique): the local repo checkout
    (this file at <repo>/etl/build_search_embedding_poc.py, project
    root one level up) vs. the ECS loader container image (this file
    copied flatly to /app/build_search_embedding_poc.py alongside
    database/migrations/ - see etl/Dockerfile.loader), where the
    project root is this file's own parent directory."""
    container_root = Path(__file__).resolve().parent
    if (container_root / "database").is_dir():
        return container_root
    return Path(__file__).resolve().parents[1]


PROJECT_ROOT = _resolve_project_root()


def _run_ecs_setup() -> None:
    configure_ecs_environment(boto3.client("secretsmanager"), include_oracle=False)


def build_source_text(
    module: str,
    business_number: str,
    title: str | None,
    person_name: str | None,
    sponsor: str | None,
    lead_unit: str | None,
    status: str | None,
) -> str:
    """Labeled concatenation of ONLY safe structured fields - never
    attachments, comments, custom data, or legal documents. Labels are
    included (not just raw values) because they give the embedding
    model real context, matching how a person would describe the
    record, not a bag of unlabeled words."""
    parts = [f"module: {module}", f"business number: {business_number}"]
    if title:
        parts.append(f"title: {title}")
    if person_name:
        parts.append(f"PI/person: {person_name}")
    if sponsor:
        parts.append(f"sponsor: {sponsor}")
    if lead_unit:
        parts.append(f"lead unit: {lead_unit}")
    if status:
        parts.append(f"status: {status}")
    return " | ".join(parts)


def source_hash(source_text: str) -> str:
    return hashlib.sha256(source_text.encode("utf-8")).hexdigest()


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
            f"{len(embedding)} - EMBEDDING_DIMENSIONS/the migration's "
            f"VECTOR(1024) column no longer match the model's real output."
        )
    return embedding


def _vector_literal(embedding: list[float]) -> str:
    """pgvector's text input format - avoids adding the `pgvector`
    Python package as a new dependency for one CAST."""
    return "[" + ",".join(repr(value) for value in embedding) + "]"


# --- Per-domain candidate selection (current/family-grain only, safe
# fields only) ------------------------------------------------------

DOMAIN_QUERIES: dict[str, str] = {
    "AWARD": """
        SELECT av.award_id AS record_id, av.award_number AS business_number,
               av.title, pi.full_name AS person_name, av.sponsor_name AS sponsor,
               av.lead_unit_name AS lead_unit, av.status_description AS status
        FROM archive.award_version av
        LEFT JOIN LATERAL (
            SELECT ap.full_name FROM archive.award_person ap
            WHERE ap.award_id = av.award_id
            ORDER BY CASE WHEN UPPER(TRIM(ap.contact_role_code)) = 'PI' THEN 0 ELSE 1 END,
                     ap.full_name NULLS LAST, ap.award_person_id
            LIMIT 1
        ) pi ON TRUE
        WHERE av.is_primary_current = TRUE AND av.title IS NOT NULL
        ORDER BY av.award_id
        LIMIT :limit
    """,
    "PROPOSAL": """
        WITH ranked AS (
            SELECT proposal_id, proposal_number, title, sponsor_name,
                   lead_unit_name, principal_investigator_name,
                   proposal_sequence_status,
                   ROW_NUMBER() OVER (
                       PARTITION BY proposal_number
                       ORDER BY version_number DESC,
                           source_update_timestamp DESC NULLS LAST,
                           proposal_id DESC
                   ) AS row_rank
            FROM archive.proposal_version
            WHERE title IS NOT NULL
        )
        SELECT proposal_id AS record_id, proposal_number AS business_number,
               title, principal_investigator_name AS person_name,
               sponsor_name AS sponsor, lead_unit_name AS lead_unit,
               proposal_sequence_status AS status
        FROM ranked
        WHERE row_rank = 1
        ORDER BY proposal_id
        LIMIT :limit
    """,
    "NEGOTIATION": """
        SELECT negotiation_id AS record_id, document_number AS business_number,
               negotiation_agreement_type_description AS title,
               negotiator_full_name AS person_name,
               NULL AS sponsor, NULL AS lead_unit,
               negotiation_status_description AS status
        FROM archive.negotiation
        WHERE document_number IS NOT NULL
        ORDER BY negotiation_id
        LIMIT :limit
    """,
    "SUBAWARD": """
        SELECT subaward_id AS record_id, subaward_code AS business_number,
               title, NULL AS person_name, award_sponsor_name AS sponsor,
               NULL AS lead_unit, status_description AS status
        FROM archive.subaward
        WHERE subaward_sequence_status = 'ACTIVE' AND title IS NOT NULL
        ORDER BY subaward_id
        LIMIT :limit
    """,
}

UPSERT_SQL = """
    INSERT INTO archive.search_embedding_poc (
        module, record_id, business_number, title, source_text,
        source_hash, embedding, embedding_model, generated_at
    ) VALUES (
        :module, :record_id, :business_number, :title, :source_text,
        :source_hash, CAST(:embedding AS vector), :embedding_model, now()
    )
    ON CONFLICT (module, record_id) DO UPDATE SET
        business_number = EXCLUDED.business_number,
        title = EXCLUDED.title,
        source_text = EXCLUDED.source_text,
        source_hash = EXCLUDED.source_hash,
        embedding = EXCLUDED.embedding,
        embedding_model = EXCLUDED.embedding_model,
        generated_at = EXCLUDED.generated_at
    WHERE archive.search_embedding_poc.source_hash != EXCLUDED.source_hash
"""

EXISTING_HASH_SQL = """
    SELECT source_hash FROM archive.search_embedding_poc
    WHERE module = :module AND record_id = :record_id
"""


def populate(engine: Engine, bedrock_client: Any, limit_per_domain: int, dry_run: bool) -> None:
    totals = {"embedded": 0, "skipped_unchanged": 0, "candidates": 0}

    for module, sql in DOMAIN_QUERIES.items():
        with engine.connect() as connection:
            rows = connection.execute(text(sql), {"limit": limit_per_domain}).mappings().all()

        print(f"=== {module}: {len(rows)} candidates ===")
        totals["candidates"] += len(rows)

        for row in rows:
            text_value = build_source_text(
                module=module,
                business_number=row["business_number"],
                title=row["title"],
                person_name=row["person_name"],
                sponsor=row["sponsor"],
                lead_unit=row["lead_unit"],
                status=row["status"],
            )
            hash_value = source_hash(text_value)

            with engine.connect() as connection:
                existing = connection.execute(
                    text(EXISTING_HASH_SQL),
                    {"module": module, "record_id": row["record_id"]},
                ).scalar()

            if existing == hash_value:
                totals["skipped_unchanged"] += 1
                continue

            if dry_run:
                print(json.dumps({
                    "module": module, "record_id": row["record_id"],
                    "business_number": row["business_number"],
                    "source_text": text_value, "source_hash": hash_value,
                }, default=str))
                continue

            embedding = embed_text(bedrock_client, text_value)

            with engine.begin() as connection:
                connection.execute(text(UPSERT_SQL), {
                    "module": module,
                    "record_id": row["record_id"],
                    "business_number": row["business_number"],
                    "title": row["title"],
                    "source_text": text_value,
                    "source_hash": hash_value,
                    "embedding": _vector_literal(embedding),
                    "embedding_model": EMBEDDING_MODEL,
                })
            totals["embedded"] += 1

    print("=== SUMMARY ===")
    print(json.dumps(totals, indent=2))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ecs", action="store_true")
    parser.add_argument(
        "--limit-per-domain", type=int, default=200,
        help="Max candidates to consider per domain (default 200; "
             "Subaward will always return fewer - only 27 families exist).",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Build and print source_text/source_hash for every candidate "
             "without calling Bedrock or writing to the database.",
    )
    arguments = parser.parse_args(argv)

    if arguments.ecs:
        _run_ecs_setup()

    engine = create_postgres_engine()
    apply_migrations(engine, PROJECT_ROOT / "database" / "migrations")

    bedrock_client = None if arguments.dry_run else boto3.client("bedrock-runtime")

    populate(engine, bedrock_client, arguments.limit_per_domain, arguments.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
