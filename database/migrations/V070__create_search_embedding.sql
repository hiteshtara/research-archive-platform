-- Production semantic-search index for Global Search. Separate from the
-- experimental archive.search_embedding_poc (V069), which is kept
-- permanently as the semantic-search regression benchmark and is never
-- read by this table or by GlobalSearchService.
--
-- Populated asynchronously by etl/build_search_embedding.py (an ECS
-- one-off task, mirroring build_search_embedding_poc.py's orchestration),
-- never during a live user search request. GlobalSearchService only ever
-- reads this table; it never writes it.
--
-- canonical_family_id is the stable identifier GlobalSearchService maps
-- into GlobalSearchItemResponse.recordId/awardId for a semantic result,
-- so that a semantic hit and its structured twin produce the same
-- module+identifier dedup key (see GlobalSearchService.deduplicate()).
-- It is deliberately NOT part of the uniqueness constraint below: a
-- family can have multiple embedded records (e.g. Award's own historical
-- rows) that all resolve to the same canonical_family_id.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS archive.search_embedding (
    search_embedding_id BIGSERIAL PRIMARY KEY,
    module               VARCHAR(50) NOT NULL,
    record_id            BIGINT NOT NULL,
    canonical_family_id  BIGINT NOT NULL,
    business_number      VARCHAR(255),
    source_text          TEXT NOT NULL,
    source_hash          VARCHAR(64) NOT NULL,
    -- Titan Text Embeddings V2 (amazon.titan-embed-text-v2:0), same
    -- 1024-dim output already confirmed live for the PoC table (V069).
    embedding             VECTOR(1024) NOT NULL,
    embedding_model       VARCHAR(100) NOT NULL,
    generated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ix_search_embedding_record
    ON archive.search_embedding (module, record_id);

CREATE INDEX IF NOT EXISTS ix_search_embedding_canonical_family
    ON archive.search_embedding (module, canonical_family_id);

-- No ANN (ivfflat/hnsw) index yet - production population is ~24.5K rows
-- across all four domains combined, small enough for an exact-scan
-- cosine search to stay well within the Global Search latency budget.
-- Add one once real row counts and query latency at that scale are
-- measured, not before.
