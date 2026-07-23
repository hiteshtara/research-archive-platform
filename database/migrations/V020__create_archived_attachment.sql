CREATE TABLE IF NOT EXISTS archive.archived_attachment (
    archived_attachment_id       BIGSERIAL PRIMARY KEY,
    module_code                  VARCHAR(30) NOT NULL,
    source_attachment_id         BIGINT NOT NULL,
    parent_record_id             BIGINT NOT NULL,
    business_key                 VARCHAR(150),
    sequence_number              INTEGER,
    source_file_id               VARCHAR(150),
    document_id                  VARCHAR(150),
    original_file_name           TEXT,
    content_type                 VARCHAR(255),
    description                  TEXT,
    source_update_timestamp      TIMESTAMP,
    source_last_update_timestamp TIMESTAMP,
    s3_bucket                    VARCHAR(255),
    s3_key                       TEXT,
    byte_size                    BIGINT,
    sha256                       VARCHAR(64),
    archive_status               VARCHAR(30) NOT NULL,
    archived_timestamp           TIMESTAMPTZ,
    error_message                TEXT,
    source_metadata              JSONB NOT NULL DEFAULT '{}'::JSONB,
    manifest_updated_at          TIMESTAMPTZ NOT NULL
                                      DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_archived_attachment_module
        CHECK (
            module_code IN (
                'AWARD',
                'PROPOSAL',
                'NEGOTIATION',
                'IRB_PROTOCOL',
                'IRB_PERSONNEL'
            )
        ),
    CONSTRAINT ck_archived_attachment_status
        CHECK (archive_status IN ('ARCHIVED', 'MISSING', 'FAILED')),
    CONSTRAINT ck_archived_attachment_sha256
        CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_archived_attachment_size
        CHECK (byte_size IS NULL OR byte_size >= 0),
    CONSTRAINT ux_archived_attachment_source
        UNIQUE (module_code, source_attachment_id),
    CONSTRAINT ux_archived_attachment_object
        UNIQUE (s3_bucket, s3_key)
);

CREATE INDEX IF NOT EXISTS ix_archived_attachment_parent
    ON archive.archived_attachment (
        module_code,
        parent_record_id,
        sequence_number
    );

CREATE INDEX IF NOT EXISTS ix_archived_attachment_business_key
    ON archive.archived_attachment (module_code, business_key);

CREATE INDEX IF NOT EXISTS ix_archived_attachment_source_file
    ON archive.archived_attachment (module_code, source_file_id);

CREATE INDEX IF NOT EXISTS ix_archived_attachment_status
    ON archive.archived_attachment (module_code, archive_status);

CREATE INDEX IF NOT EXISTS ix_archived_attachment_source_metadata
    ON archive.archived_attachment
    USING GIN (source_metadata);
