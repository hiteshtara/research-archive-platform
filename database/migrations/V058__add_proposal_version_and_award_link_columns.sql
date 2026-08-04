-- Corrective migration: adds columns proven missing during the
-- Institutional Proposal investigation
-- (docs/kuali-business-rules/InstitutionalProposal.md) - the same
-- "corrective migration, never a rewrite of an already-applied one"
-- precedent V048 established for Award Time & Money.
--
-- archive.proposal_version was missing PROPOSAL.DOCUMENT_NUMBER (the
-- real, per-version KEW workflow document number - see the
-- InstitutionalProposal.md "Object and workflow identity model"
-- section), PROPOSAL.STATUS_CODE/its denormalized PROPOSAL_STATUS
-- description, and PROPOSAL.UPDATE_USER (source_update_timestamp was
-- already captured; its paired user column was not) - all three
-- proven missing by cross-referencing docs/architecture/
-- PROPOSAL_ARCHIVE_COVERAGE.md's own audit against the live OJB
-- mapping.
--
-- archive.proposal_award was missing AWARD_FUNDING_PROPOSALS.ACTIVE
-- (the row-level active flag InstitutionalProposal.md's Award
-- relationship section explicitly requires preserving - the existing
-- ETL's own prepare_awards() was silently dropping it), the real
-- AWARD_FUNDING_PROPOSAL_ID surrogate key (needed for idempotent
-- UPSERT instead of the previous TRUNCATE-and-reload pattern), and its
-- own source_update_timestamp/source_update_user pair, mirroring every
-- other archived table's provenance columns.

ALTER TABLE archive.proposal_version
    ADD COLUMN IF NOT EXISTS document_number VARCHAR(50),
    ADD COLUMN IF NOT EXISTS status_code INTEGER,
    ADD COLUMN IF NOT EXISTS status_description VARCHAR(200),
    ADD COLUMN IF NOT EXISTS source_update_user VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_proposal_version_document_number
    ON archive.proposal_version(document_number);

ALTER TABLE archive.proposal_award
    ADD COLUMN IF NOT EXISTS award_funding_proposal_id BIGINT,
    ADD COLUMN IF NOT EXISTS active BOOLEAN,
    ADD COLUMN IF NOT EXISTS source_update_timestamp TIMESTAMP,
    ADD COLUMN IF NOT EXISTS source_update_user VARCHAR(100);

-- award_funding_proposal_id is AWARD_FUNDING_PROPOSALS' own real
-- Oracle PK - a partial unique index (nullable during transition,
-- table is empty today so this is not a real backfill concern) so the
-- loader can UPSERT by it instead of TRUNCATE-and-reload.
CREATE UNIQUE INDEX IF NOT EXISTS uq_proposal_award_funding_proposal_id
    ON archive.proposal_award(award_funding_proposal_id)
    WHERE award_funding_proposal_id IS NOT NULL;
