-- Phase 1 of Award evidence indexing (see
-- docs/architecture/AWARD_EVIDENCE_INDEXING_PHASE1_DESIGN.md). Additive
-- only - no existing column is changed or dropped, and every new
-- column is nullable so a row written by the pre-Phase-1 code path
-- (build_search_embedding.py, unmodified) is still perfectly valid.
--
-- document_type distinguishes a family-level Global Search summary row
-- ('AWARD_SUMMARY'/'PROPOSAL_SUMMARY'/'NEGOTIATION_SUMMARY'/
-- 'SUBAWARD_SUMMARY') from a new evidence-level row
-- ('AWARD_VERSION'/'AWARD_PERSON'/'AWARD_AMOUNT'/'AWARD_COMMENT'/
-- 'AWARD_TERM' in this phase). GlobalSearchService.searchSemantic()
-- filters to the four *_SUMMARY values only - see that change's own
-- commit for the guard this backfill exists to make safe.

ALTER TABLE archive.search_embedding
    ADD COLUMN IF NOT EXISTS document_type             VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parent_module              VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parent_business_identifier VARCHAR(255),
    ADD COLUMN IF NOT EXISTS exact_record_id            BIGINT,
    ADD COLUMN IF NOT EXISTS version_label              VARCHAR(50),
    ADD COLUMN IF NOT EXISTS source_table               VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_primary_key         BIGINT,
    ADD COLUMN IF NOT EXISTS source_row_hash             VARCHAR(64);

-- Backfill every pre-existing row (all of which are, today, exactly
-- one current/family-grain summary row per module - see
-- build_search_embedding.py's DOMAIN_QUERIES) so document_type is
-- never NULL for a row that predates this migration.
UPDATE archive.search_embedding SET document_type = 'AWARD_SUMMARY',       parent_module = 'AWARD'       WHERE module = 'AWARD'       AND document_type IS NULL;
UPDATE archive.search_embedding SET document_type = 'PROPOSAL_SUMMARY',    parent_module = 'PROPOSAL'    WHERE module = 'PROPOSAL'    AND document_type IS NULL;
UPDATE archive.search_embedding SET document_type = 'NEGOTIATION_SUMMARY', parent_module = 'NEGOTIATION' WHERE module = 'NEGOTIATION' AND document_type IS NULL;
UPDATE archive.search_embedding SET document_type = 'SUBAWARD_SUMMARY',   parent_module = 'SUBAWARD'    WHERE module = 'SUBAWARD'    AND document_type IS NULL;

-- record_id is already the canonical current/family identifier for
-- every existing row (DOMAIN_QUERIES' own comment: "record_id is
-- already the canonical current/family identifier in each of these
-- queries"), so exact_record_id backfills verbatim, lossless.
UPDATE archive.search_embedding
SET exact_record_id = record_id,
    parent_business_identifier = business_number
WHERE exact_record_id IS NULL;

-- The old uniqueness assumed one row per (module, record_id) - true
-- only because every existing row was a summary. Widening it to
-- include document_type is what allows multiple document types per
-- family without a second table. Run only after the backfill above so
-- the old unique index still protects the table while backfilling.
DROP INDEX IF EXISTS archive.ix_search_embedding_record;
CREATE UNIQUE INDEX IF NOT EXISTS ix_search_embedding_module_type_record
    ON archive.search_embedding (module, document_type, exact_record_id);

-- Evidence retrieval will filter/join on these - separate, narrower
-- indexes rather than widening the existing canonical_family index, so
-- Global Search's own family-lookup query plan is untouched.
CREATE INDEX IF NOT EXISTS ix_search_embedding_document_type
    ON archive.search_embedding (document_type);
CREATE INDEX IF NOT EXISTS ix_search_embedding_parent
    ON archive.search_embedding (parent_module, parent_business_identifier);
CREATE INDEX IF NOT EXISTS ix_search_embedding_source_row
    ON archive.search_embedding (source_table, source_primary_key);
