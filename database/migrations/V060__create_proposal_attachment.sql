-- Institutional Proposal Attachments - metadata AND binary-archival
-- lifecycle in one table (per explicit instruction), unlike Subaward's
-- two-table split (archive.subaward_attachment +
-- archive.subaward_attachment_archive). Source proven live against
-- Oracle (see docs/kuali-business-rules/InstitutionalProposal.md and
-- this migration's own commit):
--
-- - PROPOSAL_ATTACHMENTS.FILE_DATA_ID -> KCOEUS.FILE_DATA.ID, the same
--   FILE_DATA/FILE_DATA_ID shape Subaward uses (FileDataBlobReader) -
--   NOT Award/Negotiation/Protocol's ATTACHMENT_FILE/FILE_ID shape.
-- - Multiple PROPOSAL_ATTACHMENTS rows routinely share one
--   FILE_DATA_ID: live-verified 149,432 distinct FILE_DATA_ID values
--   across 405,779 total rows (~2.7x reuse) - a new Proposal version
--   copies forward its prior version's attachment metadata rows
--   (new PROPOSAL_ATTACHMENTS_ID) while pointing at the SAME
--   underlying Oracle blob, never duplicating it. proposal_attachment_id
--   is still the correct PK/UPSERT key (one row per real historical
--   reference, matching the business/historical grain convention used
--   throughout this project - never deduplicate valid historical rows).
-- - DOCUMENT_STATUS_CODE is 'A' for all 405,779 rows in Oracle, no
--   exceptions found - proven uniform, not filtered on here since it
--   has no observed effect on archival eligibility.

CREATE TABLE IF NOT EXISTS archive.proposal_attachment (
    proposal_attachment_id   BIGINT PRIMARY KEY,
    proposal_id              BIGINT NOT NULL,
    proposal_number          VARCHAR(50) NOT NULL,
    sequence_number          INTEGER NOT NULL,

    attachment_number        INTEGER,
    attachment_title         VARCHAR(300),
    attachment_type_code     INTEGER,
    file_name                VARCHAR(500),
    content_type             VARCHAR(200),
    comments                 TEXT,
    document_status_code     VARCHAR(10),
    file_data_id              VARCHAR(100),

    source_update_timestamp  TIMESTAMP,
    source_update_user       VARCHAR(100),

    -- Binary-archival lifecycle - populated by the generic attachment
    -- pipeline (archive_etl.attachments.runner + ProposalAttachmentPlugin),
    -- never by the metadata loader above. NOT_REQUESTED is the only
    -- value the metadata loader itself ever writes; every other value
    -- is written exclusively by the binary loader.
    upload_status             VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUESTED',
    s3_bucket                 VARCHAR(255),
    object_key                TEXT,
    file_size                 BIGINT,
    checksum                  VARCHAR(64),
    uploaded_at                TIMESTAMPTZ,
    error_message              TEXT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ck_proposal_attachment_upload_status
        CHECK (
            upload_status IN (
                'NOT_REQUESTED',
                'IN_PROGRESS',
                'UPLOADED',
                'MISSING_SOURCE',
                'FAILED'
            )
        ),
    CONSTRAINT ck_proposal_attachment_checksum
        CHECK (checksum IS NULL OR checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_proposal_attachment_file_size
        CHECK (file_size IS NULL OR file_size >= 0)
);

CREATE INDEX IF NOT EXISTS ix_proposal_attachment_proposal
    ON archive.proposal_attachment (proposal_id);

CREATE INDEX IF NOT EXISTS ix_proposal_attachment_number
    ON archive.proposal_attachment (proposal_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_proposal_attachment_file_data
    ON archive.proposal_attachment (file_data_id);

CREATE INDEX IF NOT EXISTS ix_proposal_attachment_upload_status
    ON archive.proposal_attachment (upload_status);
