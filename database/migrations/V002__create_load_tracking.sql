CREATE TABLE IF NOT EXISTS archive.load_run (
    load_id              BIGSERIAL PRIMARY KEY,
    domain               VARCHAR(50) NOT NULL,
    source_system        VARCHAR(100) NOT NULL DEFAULT 'KUALI',
    source_file_name     VARCHAR(500),
    source_s3_bucket     VARCHAR(255),
    source_s3_key        VARCHAR(1500),

    started_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at         TIMESTAMPTZ,

    rows_read            INTEGER NOT NULL DEFAULT 0,
    rows_staged          INTEGER NOT NULL DEFAULT 0,
    rows_loaded          INTEGER NOT NULL DEFAULT 0,
    rows_rejected        INTEGER NOT NULL DEFAULT 0,

    status               VARCHAR(30) NOT NULL DEFAULT 'STARTED',
    validation_report    JSONB,
    error_message        TEXT,

    CONSTRAINT ck_load_run_status
        CHECK (
            status IN (
                'STARTED',
                'STAGED',
                'VALIDATED',
                'LOADED',
                'FAILED',
                'REJECTED'
            )
        )
);

CREATE INDEX IF NOT EXISTS ix_load_run_domain_started
    ON archive.load_run (domain, started_at DESC);

CREATE TABLE IF NOT EXISTS archive.load_rejection (
    rejection_id         BIGSERIAL PRIMARY KEY,
    load_id              BIGINT NOT NULL
                             REFERENCES archive.load_run(load_id),
    domain               VARCHAR(50) NOT NULL,
    source_row_number    INTEGER,
    business_key         VARCHAR(255),
    rejection_reason     TEXT NOT NULL,
    rejected_record      JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_load_rejection_load
    ON archive.load_rejection (load_id);
