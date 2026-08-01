-- Corrective migration: adds the two AWARD_AMOUNT_INFO columns Time
-- and Money's own money-routing algorithm sets on every new row it
-- creates, neither of which archive.award_amount_info has captured
-- since Phase 4A (V011) - a pre-existing gap this bundle closes rather
-- than duplicating award_amount_info as a new table. See
-- docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md.
--
-- transaction_id is a NUMERIC surrogate key tracing back to a specific
-- PENDING_TRANSACTIONS/TRANSACTION_DETAILS row - distinct in both type
-- and meaning from AWARD_AMOUNT_TRANSACTION's own confusingly-named
-- VARCHAR2 "TRANSACTION_ID" column (which actually stores a Time and
-- Money document number, archived separately below as
-- archive.award_amount_transaction.document_number - never the same
-- archive field name as this one). One PendingTransaction can produce
-- MULTIPLE archive.award_amount_info rows sharing the same
-- transaction_id (one per Award hop along the hierarchy path, and
-- potentially once each for a pending and an active Award version) -
-- this column is therefore deliberately NOT unique.
--
-- originating_award_version records the Award sequence_number a
-- Time-and-Money-created snapshot row belongs to. Only rows where both
-- transaction_id and the already-archived tnm_document_number are
-- non-null are Time-and-Money-created rows, as opposed to the original
-- row created when the Award was first entered (neither set).
--
-- Not a rewrite of the already-applied V011 - the same precedent V013
-- and V047 both established.

ALTER TABLE archive.award_amount_info
    ADD COLUMN IF NOT EXISTS transaction_id BIGINT,
    ADD COLUMN IF NOT EXISTS originating_award_version INTEGER;

CREATE INDEX IF NOT EXISTS ix_award_amount_info_transaction
    ON archive.award_amount_info (transaction_id);
