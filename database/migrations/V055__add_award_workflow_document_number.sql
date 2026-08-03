-- Adds the true Kuali workflow document number to archive.award_version,
-- distinct from the pre-existing modification_number column.
--
-- Investigation (real, live BU Oracle schema, not the open-source
-- reference alone): modification_number (KCOEUS.AWARD.MODIFICATION_NUMBER)
-- is a separate, genuine business field - for many real Awards (e.g.
-- 100567-00001, all 6 sequences) it is simply NULL. It is NOT the
-- identifier users mean by "the document number" or "the workflow
-- document" - that is KCOEUS.AWARD.DOCUMENT_NUMBER, which is NOT NULL
-- for every Award row and is the actual foreign key into Kuali's
-- workflow engine: KREW_DOC_HDR_T.DOC_HDR_ID (both confirmed live as
-- VARCHAR2(40) - a same-type text join, no numeric cast in either
-- direction, unlike the older open-source Kuali Research reference
-- schema, which shows DOC_HDR_ID as NUMBER(14) - BU's deployment has
-- since widened Rice/KEW ID columns to VARCHAR2(40), a documented Rice
-- schema evolution, not a mismatch to work around here). Verified for
-- award_number=100567-00001 that all 6 sequences have their own,
-- distinct, NOT NULL workflow document (511, 110249, 110655, 209160,
-- 245520, 328797 for award_id 511/115975/117767/508634/686179/1135067
-- respectively) - see docs/DECISIONS.md for the full investigation
-- writeup.
--
-- VARCHAR(40) matches KCOEUS.AWARD.DOCUMENT_NUMBER's confirmed live
-- Oracle width exactly (not a guess, unlike some other columns in this
-- schema where the true Oracle width was never confirmed - see V054's
-- header). Nullable here only because ETL loads land one row at a time
-- and this migration must apply cleanly to any existing archived rows
-- before the next incremental load backfills every one of them; Oracle
-- itself never has a NULL value for this column.
--
-- A plain B-tree index is sufficient (unlike modification_number's own
-- trigram index in V053): workflow document numbers are always looked
-- up by exact match (a user searching for a specific document number,
-- or the API resolving one to its exact Award version), never substring
-- search - see docs/architecture/AWARD_SEARCH_API_DESIGN.md's workflow
-- document number search behavior for why exact match is the only
-- required access pattern.
ALTER TABLE archive.award_version
    ADD COLUMN IF NOT EXISTS workflow_document_number VARCHAR(40);

CREATE INDEX IF NOT EXISTS ix_award_version_workflow_document_number
    ON archive.award_version (workflow_document_number);

COMMENT ON COLUMN archive.award_version.workflow_document_number IS
    'KCOEUS.AWARD.DOCUMENT_NUMBER - the Kuali workflow document number, '
    'the real foreign key into KREW_DOC_HDR_T.DOC_HDR_ID. Distinct from '
    'modification_number (a separate, often-NULL business field) - '
    'never conflate the two.';
