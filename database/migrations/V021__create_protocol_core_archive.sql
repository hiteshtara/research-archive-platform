CREATE TABLE IF NOT EXISTS archive.protocol_version (
    protocol_id                       BIGINT PRIMARY KEY,
    protocol_number                   VARCHAR(60) NOT NULL,
    sequence_number                   INTEGER NOT NULL,
    document_number                   VARCHAR(100),
    active                            VARCHAR(10),

    protocol_type_code                VARCHAR(30),
    protocol_type_description         VARCHAR(200),
    protocol_status_code              VARCHAR(30),
    protocol_status_description       VARCHAR(200),

    title                             TEXT,
    description                       TEXT,

    initial_submission_date           DATE,
    approval_date                     DATE,
    expiration_date                   DATE,
    last_approval_date                DATE,

    fda_application_number            VARCHAR(100),
    reference_number_1                VARCHAR(200),
    reference_number_2                VARCHAR(200),

    protocol_workflow_type             VARCHAR(100),
    rerouted_flag                     VARCHAR(10),

    source_create_timestamp           TIMESTAMP,
    source_create_user                VARCHAR(100),
    source_update_timestamp           TIMESTAMP,
    source_update_user                VARCHAR(100),
    source_version_number             BIGINT,
    source_object_id                  VARCHAR(100),

    archived_at                       TIMESTAMPTZ NOT NULL
                                          DEFAULT CURRENT_TIMESTAMP,
    load_run_id                       BIGINT
                                          REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_protocol_version_source_row
        UNIQUE (
            protocol_number,
            sequence_number,
            protocol_id
        )
);

CREATE INDEX IF NOT EXISTS ix_protocol_version_number
    ON archive.protocol_version (protocol_number);

CREATE INDEX IF NOT EXISTS ix_protocol_version_number_sequence
    ON archive.protocol_version (
        protocol_number,
        sequence_number DESC,
        source_update_timestamp DESC,
        protocol_id DESC
    );

CREATE INDEX IF NOT EXISTS ix_protocol_version_status
    ON archive.protocol_version (protocol_status_code);

CREATE INDEX IF NOT EXISTS ix_protocol_version_type
    ON archive.protocol_version (protocol_type_code);

CREATE INDEX IF NOT EXISTS ix_protocol_version_active
    ON archive.protocol_version (active);

CREATE INDEX IF NOT EXISTS ix_protocol_version_expiration
    ON archive.protocol_version (expiration_date);

CREATE INDEX IF NOT EXISTS ix_protocol_version_title_trgm
    ON archive.protocol_version
    USING GIN (title gin_trgm_ops);

CREATE OR REPLACE VIEW archive.v_protocol_latest AS
SELECT
    ranked.protocol_id,
    ranked.protocol_number,
    ranked.sequence_number,
    ranked.document_number,
    ranked.active,
    ranked.protocol_type_code,
    ranked.protocol_type_description,
    ranked.protocol_status_code,
    ranked.protocol_status_description,
    ranked.title,
    ranked.description,
    ranked.initial_submission_date,
    ranked.approval_date,
    ranked.expiration_date,
    ranked.last_approval_date,
    ranked.fda_application_number,
    ranked.reference_number_1,
    ranked.reference_number_2,
    ranked.protocol_workflow_type,
    ranked.rerouted_flag,
    ranked.source_create_timestamp,
    ranked.source_create_user,
    ranked.source_update_timestamp,
    ranked.source_update_user,
    ranked.source_version_number,
    ranked.source_object_id,
    ranked.archived_at,
    ranked.load_run_id
FROM (
    SELECT
        protocol_version.*,
        ROW_NUMBER() OVER (
            PARTITION BY protocol_number
            ORDER BY
                sequence_number DESC,
                source_update_timestamp DESC NULLS LAST,
                protocol_id DESC
        ) AS row_rank
    FROM archive.protocol_version
) ranked
WHERE ranked.row_rank = 1;

CREATE OR REPLACE VIEW archive.v_protocol_family AS
SELECT
    latest.protocol_number,
    versions.version_count,
    latest.protocol_id AS latest_protocol_id,
    latest.sequence_number AS latest_sequence_number,
    latest.title,
    latest.protocol_status_code,
    latest.protocol_status_description,
    latest.protocol_type_code,
    latest.protocol_type_description,
    latest.active,
    latest.expiration_date
FROM archive.v_protocol_latest latest
JOIN (
    SELECT
        protocol_number,
        COUNT(*) AS version_count
    FROM archive.protocol_version
    GROUP BY protocol_number
) versions
    ON versions.protocol_number = latest.protocol_number;
