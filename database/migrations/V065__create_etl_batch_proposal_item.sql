-- Proposal batching needs the same durable, resumable manifest
-- archive.etl_batch/etl_batch_item already gives Award (see
-- V037__create_etl_batch_framework.sql), but Proposal's natural batch
-- key is proposal_number - a string with significant leading zeros
-- (e.g. "01157400"), never losslessly representable as
-- etl_batch_item.entity_key (BIGINT). V037's own migration comment
-- already anticipated this: "A future domain with no numeric surrogate
-- key is the trigger for a domain-specific membership table alongside
-- this one, not for weakening entity_key into a fragile free-form
-- string now." This is that domain.
--
-- archive.etl_batch (the parent manifest, already domain-tagged and
-- entity_key-agnostic) is reused as-is - only a new membership table is
-- added, mirroring etl_batch_item's shape/status vocabulary exactly,
-- with proposal_number VARCHAR standing in for entity_key BIGINT.
-- started_at/last_error match etl_batch_item's own column names
-- (rather than "claimed_at"/"error") for consistency with the existing
-- table this one is modeled on - same meaning, same convention.

CREATE TABLE IF NOT EXISTS archive.etl_batch_proposal_item (
    batch_id         BIGINT NOT NULL
                          REFERENCES archive.etl_batch(batch_id),
    proposal_number  VARCHAR(50) NOT NULL,
    ordinal          INTEGER NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    last_error       TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,

    PRIMARY KEY (batch_id, proposal_number),

    CONSTRAINT ck_etl_batch_proposal_item_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'COMPLETED',
                'FAILED',
                'MISSING_SOURCE',
                'SKIPPED'
            )
        ),
    CONSTRAINT uq_etl_batch_proposal_item_ordinal
        UNIQUE (batch_id, ordinal)
);

CREATE INDEX IF NOT EXISTS ix_etl_batch_proposal_item_proposal_number
    ON archive.etl_batch_proposal_item (proposal_number);

CREATE INDEX IF NOT EXISTS ix_etl_batch_proposal_item_status
    ON archive.etl_batch_proposal_item (status);

COMMENT ON TABLE archive.etl_batch_proposal_item IS
    'Proposal-domain batch membership, keyed by proposal_number (not '
    'entity_key) - see this migration''s header for why. status tracks '
    'only whether this batch''s own family-load step has run for this '
    'proposal_number; each family is its own transaction, so one '
    'family''s failure never blocks or rolls back its siblings.';
