-- Award Attachment Archive (Sprint 1: foundation only).
--
-- Oracle-direct, metadata only - see etl/load_award_attachments.py. This
-- migration creates the schema for Award attachment metadata; it does not
-- store any blob content. Blob upload, SHA256, and multipart upload are
-- explicitly out of scope for this migration and the loader that
-- populates it - see docs/DECISIONS.md and the loader's own module
-- docstring for the sprint boundary.
--
-- Two tables:
--   - attachment_object: one row per distinct physical file
--     (KCOEUS.ATTACHMENT_FILE.FILE_ID), deduplicated. Multiple Award
--     attachment references may point at the same physical file - the
--     physical file is never duplicated here or in future S3 storage.
--   - award_attachment: one row per KCOEUS.AWARD_ATTACHMENT reference row
--     (the business/historical grain - repeated file_id values across rows
--     are expected and are not an error).
--
-- NOTE ON UNVERIFIED COLUMNS: AWARD_ATTACHMENT.TYPE_CODE has not been
-- independently confirmed against a live DESCRIBE in this repo's evidence
-- (docs/ATTACHMENT_MODULE_INVENTORY.md's Award contract table does not
-- list it) - it is assumed present by analogy with
-- KCOEUS.SUBAWARD_ATTACHMENTS.ATTACHMENT_TYPE_CODE and the general KC
-- `*_CODE` audit-column convention. If the column name is wrong, Oracle
-- will fail the extraction query loudly (ORA-00904) rather than silently
-- producing wrong data - verify during a live --limit run before trusting
-- this column. See the same caveat in oracle/award/export_award_attachments.sql.

CREATE TABLE IF NOT EXISTS archive.attachment_object (
    file_id                  BIGINT PRIMARY KEY,
    file_data_id             BIGINT,

    file_name                VARCHAR(500),
    content_type             VARCHAR(200),

    -- 'INLINE' when ATTACHMENT_FILE.FILE_DATA is populated, 'EXTERNAL' when
    -- only FILE_DATA.DATA (via file_data_id) is populated, NULL when
    -- neither source has a blob at all.
    blob_source              VARCHAR(20),
    file_size_bytes          BIGINT,

    -- Populated by a future sprint (blob upload) - always NULL from this
    -- loader today.
    sha256                   VARCHAR(64),
    s3_bucket                VARCHAR(200),
    s3_key                   VARCHAR(1000),
    s3_etag                  VARCHAR(200),

    -- 'SKIPPED' for files with no blob at all (blob_source IS NULL,
    -- structurally can never be uploaded); 'PENDING' for every other file
    -- awaiting a future upload sprint. 'UPLOADED'/'FAILED' are reserved for
    -- that future sprint - this loader never writes them.
    upload_status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    upload_attempts          INTEGER NOT NULL DEFAULT 0,
    last_error               TEXT,

    oracle_update_timestamp  TIMESTAMP,
    oracle_update_user       VARCHAR(100),

    uploaded_at              TIMESTAMPTZ,

    archived_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                  BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ck_attachment_object_blob_source
        CHECK (blob_source IS NULL OR blob_source IN ('INLINE', 'EXTERNAL')),
    CONSTRAINT ck_attachment_object_upload_status
        CHECK (
            upload_status IN ('PENDING', 'SKIPPED', 'UPLOADED', 'FAILED')
        )
);

CREATE INDEX IF NOT EXISTS ix_attachment_object_upload_status
    ON archive.attachment_object (upload_status);

COMMENT ON COLUMN archive.attachment_object.blob_source IS
    'INLINE when ATTACHMENT_FILE.FILE_DATA is populated, EXTERNAL when '
    'only FILE_DATA.DATA (via file_data_id) is populated, NULL when '
    'neither source has a blob (see investigation: 6 files affected)';

COMMENT ON COLUMN archive.attachment_object.upload_status IS
    'Set by etl/load_award_attachments.py to PENDING or SKIPPED only - '
    'UPLOADED/FAILED are written by a future blob-upload sprint, not this '
    'loader';

CREATE TABLE IF NOT EXISTS archive.award_attachment (
    award_attachment_id      BIGINT PRIMARY KEY,

    award_id                 BIGINT NOT NULL,
    award_number             VARCHAR(60) NOT NULL,
    sequence_number          INTEGER NOT NULL,

    document_id              VARCHAR(100),

    -- Nullable: an Award attachment reference with no resolvable file has
    -- not been observed but is not ruled out either - see
    -- oracle/award/export_award_attachments.sql.
    file_id                  BIGINT
                                 REFERENCES archive.attachment_object(
                                     file_id
                                 ),

    -- See the unverified-column note above this migration's header.
    type_code                VARCHAR(30),
    description               TEXT,
    document_status_code     VARCHAR(30),

    oracle_update_timestamp  TIMESTAMP,
    oracle_update_user       VARCHAR(100),

    archived_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                  BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_attachment_award
    ON archive.award_attachment (award_id, award_attachment_id);

CREATE INDEX IF NOT EXISTS ix_award_attachment_award_number_sequence
    ON archive.award_attachment (award_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_award_attachment_file
    ON archive.award_attachment (file_id);

COMMENT ON COLUMN archive.award_attachment.type_code IS
    'Not independently confirmed in this repo''s evidence - see this '
    'migration''s header comment and oracle/award/export_award_attachments.sql';
