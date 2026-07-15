CREATE TABLE IF NOT EXISTS archive.irb_protocol (
    record_id               BIGINT PRIMARY KEY
                                REFERENCES archive.research_record(record_id)
                                ON DELETE CASCADE,

    protocol_id             BIGINT NOT NULL,
    study_id                VARCHAR(50),
    protocol_base           VARCHAR(30) NOT NULL,
    protocol_number         VARCHAR(60) NOT NULL,

    protocol_type_code      VARCHAR(30),
    protocol_type           VARCHAR(150),

    protocol_status_code    VARCHAR(30),
    protocol_status         VARCHAR(200),

    review_type_code        VARCHAR(30),
    review_type             VARCHAR(150),

    approval_date           DATE,
    determination_date      DATE,
    effective_date          DATE,
    expiration_date         DATE,

    pi_buid                 VARCHAR(30),
    pi_first_name           VARCHAR(150),
    pi_last_name            VARCHAR(150),
    pi_full_name            VARCHAR(400),
    pi_email                VARCHAR(320),
    pi_buid_missing         BOOLEAN NOT NULL DEFAULT FALSE,

    responsible_unit        VARCHAR(100),
    organization_id         BIGINT
                                REFERENCES archive.organization(organization_id),

    active_flag             BOOLEAN NOT NULL DEFAULT FALSE,

    source_file_name        VARCHAR(500),
    source_row_number       INTEGER,
    source_extract_at       TIMESTAMPTZ,

    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_irb_protocol_id
        UNIQUE (protocol_id),

    CONSTRAINT ux_irb_protocol_base
        UNIQUE (protocol_base)
);

CREATE INDEX IF NOT EXISTS ix_irb_study_id
    ON archive.irb_protocol (study_id);

CREATE INDEX IF NOT EXISTS ix_irb_protocol_number
    ON archive.irb_protocol (protocol_number);

CREATE INDEX IF NOT EXISTS ix_irb_pi_buid
    ON archive.irb_protocol (pi_buid);

CREATE INDEX IF NOT EXISTS ix_irb_pi_name_trgm
    ON archive.irb_protocol
    USING GIN (pi_full_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_irb_status
    ON archive.irb_protocol (protocol_status);

CREATE INDEX IF NOT EXISTS ix_irb_approval_date
    ON archive.irb_protocol (approval_date);

CREATE UNLOGGED TABLE IF NOT EXISTS archive.irb_protocol_stage (
    load_id                 BIGINT NOT NULL,
    source_row_number       INTEGER,

    protocol_id             BIGINT,
    study_id                VARCHAR(50),
    protocol_base           VARCHAR(30),
    protocol_number         VARCHAR(60),
    title                   TEXT,

    protocol_type_code      VARCHAR(30),
    protocol_type           VARCHAR(150),

    protocol_status_code    VARCHAR(30),
    protocol_status         VARCHAR(200),

    approval_date           DATE,

    pi_buid                 VARCHAR(30),
    pi_first_name           VARCHAR(150),
    pi_last_name            VARCHAR(150),
    pi_full_name            VARCHAR(400),
    pi_email                VARCHAR(320),
    pi_buid_missing         BOOLEAN,

    source_file_name        VARCHAR(500),
    source_extract_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_irb_stage_load
    ON archive.irb_protocol_stage (load_id);
