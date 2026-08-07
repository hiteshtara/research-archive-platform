-- Global Search pgvector proof-of-concept - a deliberately EXPERIMENTAL
-- table, separate from any production search path. Never integrated into
-- GlobalSearchService until the experiment demonstrates a real relevance
-- improvement over lexical search (see docs/architecture/GLOBAL_SEARCH_V2
-- audit and the Performance Sprint's Step 1 baselines).
--
-- No foreign key into any domain table, by design - referenced only by
-- module + record_id + business_number, kept fully decoupled from domain-
-- table migrations, matching the archive.search_embedding design already
-- proposed for the (not yet built) production version.
--
-- vector extension confirmed available on this RDS instance
-- (PostgreSQL 17.9, pgvector 0.8.1) via a live aws bedrock-runtime
-- invoke-model test call before this migration was written - not assumed.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS archive.search_embedding_poc (
    search_embedding_poc_id BIGSERIAL PRIMARY KEY,
    module                   VARCHAR(50) NOT NULL,
    record_id                BIGINT NOT NULL,
    business_number          VARCHAR(255) NOT NULL,
    title                    TEXT,
    source_text              TEXT NOT NULL,
    source_hash              VARCHAR(64) NOT NULL,
    -- Titan Text Embeddings V2 (amazon.titan-embed-text-v2:0) default
    -- output dimension, confirmed live via a real invoke-model call
    -- (response embedding length == 1024) before this migration was
    -- written - not assumed from documentation alone.
    embedding                VECTOR(1024),
    embedding_model          VARCHAR(100) NOT NULL,
    generated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ix_search_embedding_poc_record
    ON archive.search_embedding_poc (module, record_id);

-- IVFFlat, not HNSW - db.t4g.micro (the real dev instance class,
-- confirmed live) has roughly 1GiB RAM, and HNSW graph construction is
-- memory-hungry. IVFFlat's `lists` parameter is tuned for a small PoC
-- population (~500-1000 rows); this index is not a claim about the
-- right choice at production scale, only a safe one for this
-- experiment on this instance.
CREATE INDEX IF NOT EXISTS ix_search_embedding_poc_vector
    ON archive.search_embedding_poc
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 10);
