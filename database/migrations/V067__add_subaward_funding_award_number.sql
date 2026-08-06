-- SUBAWARD_FUNDING_SOURCE.AWARD_ID is the Kuali domain's only link to
-- Award (SubAwardFundingSource.awardId -> org.kuali.kra.award.home.Award,
-- proven from repository-subAward.xml), but it is a raw internal Award
-- version id, not a business identifier - it cannot be shown or resolved
-- to a current Award version without denormalizing the business
-- award_number at ETL time, exactly like Negotiation's
-- associated_document_id already does for its own Award/Proposal links.
-- Nullable and backfilled by re-running the funding load: existing rows
-- loaded before this migration simply have award_number = NULL until
-- their next incremental load.
ALTER TABLE archive.subaward_funding
    ADD COLUMN IF NOT EXISTS award_number VARCHAR(50);
