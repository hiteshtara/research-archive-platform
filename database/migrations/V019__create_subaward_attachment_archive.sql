CREATE TABLE IF NOT EXISTS archive.subaward_attachment_archive (
    attachment_id                   BIGINT PRIMARY KEY
                                        REFERENCES archive.subaward_attachment(attachment_id)
                                        ON DELETE CASCADE,
    subaward_id                     BIGINT NOT NULL
                                        REFERENCES archive.subaward(subaward_id)
                                        ON DELETE CASCADE,
    subaward_code                   VARCHAR(100) NOT NULL,
    sequence_number                 INTEGER NOT NULL,
    file_data_id                    VARCHAR(100),
    original_file_name              TEXT,
    mime_type                       VARCHAR(255),
    document_id                     BIGINT,
    attachment_source_update_timestamp TIMESTAMP,
    attachment_last_update_timestamp   TIMESTAMP,
    s3_bucket                       VARCHAR(255),
    s3_key                          TEXT,
    byte_size                       BIGINT,
    sha256                          VARCHAR(64),
    archive_status                  VARCHAR(30) NOT NULL,
    archived_timestamp              TIMESTAMPTZ,
    error_message                   TEXT,
    manifest_updated_at             TIMESTAMPTZ NOT NULL
                                        DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_subaward_attachment_archive_status
        CHECK (
            archive_status IN (
                'ARCHIVED',
                'MISSING',
                'FAILED'
            )
        ),
    CONSTRAINT ck_subaward_attachment_archive_sha256
        CHECK (
            sha256 IS NULL
            OR sha256 ~ '^[0-9a-f]{64}$'
        ),
    CONSTRAINT ck_subaward_attachment_archive_size
        CHECK (
            byte_size IS NULL
            OR byte_size >= 0
        ),
    CONSTRAINT ux_subaward_attachment_archive_object
        UNIQUE (s3_bucket, s3_key)
);

CREATE INDEX IF NOT EXISTS ix_subaward_attachment_archive_parent
    ON archive.subaward_attachment_archive (subaward_id, attachment_id);

CREATE INDEX IF NOT EXISTS ix_subaward_attachment_archive_file_data
    ON archive.subaward_attachment_archive (file_data_id);

CREATE INDEX IF NOT EXISTS ix_subaward_attachment_archive_status
    ON archive.subaward_attachment_archive (archive_status);
