CREATE TABLE IF NOT EXISTS archive.document (
    document_id             BIGSERIAL PRIMARY KEY,
    record_id               BIGINT
                                REFERENCES archive.research_record(record_id)
                                ON DELETE CASCADE,

    source_document_id      VARCHAR(150),
    document_type           VARCHAR(200),
    document_category       VARCHAR(200),

    file_name               VARCHAR(1000) NOT NULL,
    original_file_name      VARCHAR(1000),
    mime_type               VARCHAR(255),
    file_extension          VARCHAR(50),
    file_size_bytes         BIGINT,

    s3_bucket               VARCHAR(255) NOT NULL,
    s3_key                  VARCHAR(1500) NOT NULL,

    checksum_sha256         VARCHAR(64),

    document_date           DATE,
    confidential_flag       BOOLEAN NOT NULL DEFAULT FALSE,
    visible_to_study_team   BOOLEAN NOT NULL DEFAULT FALSE,

    source_created_at       TIMESTAMPTZ,
    source_updated_at       TIMESTAMPTZ,

    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_document_s3
        UNIQUE (s3_bucket, s3_key)
);

CREATE INDEX IF NOT EXISTS ix_document_record
    ON archive.document (record_id);

CREATE INDEX IF NOT EXISTS ix_document_file_name_trgm
    ON archive.document
    USING GIN (file_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_document_type
    ON archive.document (document_type);

CREATE INDEX IF NOT EXISTS ix_document_checksum
    ON archive.document (checksum_sha256);
