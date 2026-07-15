CREATE TABLE IF NOT EXISTS archive.irb_protocol_version (
    protocol_id            BIGINT PRIMARY KEY,
    protocol_base          VARCHAR(30) NOT NULL,
    protocol_number        VARCHAR(60) NOT NULL,
    sequence_number        INTEGER,

    active_ind             VARCHAR(10),
    crc_protocol_num       VARCHAR(20),
    document_number        VARCHAR(100),
    title                  TEXT,

    protocol_type_code     VARCHAR(30),
    protocol_type          VARCHAR(200),
    protocol_status_code   VARCHAR(30),
    protocol_status        VARCHAR(200),

    ohrp_categories        TEXT,
    summary_keywords       TEXT,

    pi_id                  VARCHAR(50),
    pi_email               VARCHAR(320),
    pi_affiliation_code    VARCHAR(30),
    pi_affiliation         VARCHAR(200),
    fund_center_number     VARCHAR(100),
    school_number          VARCHAR(100),

    irb_analyst_id         VARCHAR(100),
    irb_advisor_id         VARCHAR(100),

    received_date          DATE,
    claimed_date           DATE,
    determination_date     DATE,
    approval_date          DATE,
    expiration_date        DATE,
    closure_date           DATE,
    authorization_date     DATE,

    record_storage_box     VARCHAR(200),
    maximum_expiration_ind VARCHAR(10),
    expiration_status      VARCHAR(100),

    working_days           INTEGER,
    calendar_days          INTEGER,
    irb_days               INTEGER,
    pi_days                INTEGER,
    funding_source_count   INTEGER,

    loaded_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_irb_version_base
    ON archive.irb_protocol_version (
        protocol_base,
        sequence_number DESC
    );

CREATE INDEX IF NOT EXISTS ix_irb_version_number
    ON archive.irb_protocol_version (protocol_number);

CREATE INDEX IF NOT EXISTS ix_irb_version_pi
    ON archive.irb_protocol_version (pi_id);

CREATE INDEX IF NOT EXISTS ix_irb_version_status
    ON archive.irb_protocol_version (protocol_status);


CREATE TABLE IF NOT EXISTS archive.irb_submission (
    submission_id          BIGSERIAL PRIMARY KEY,
    protocol_id            BIGINT NOT NULL
                               REFERENCES archive.irb_protocol_version(protocol_id)
                               ON DELETE CASCADE,
    protocol_base          VARCHAR(30) NOT NULL,
    protocol_number        VARCHAR(60),
    sequence_number        INTEGER,
    submission_number      INTEGER,

    submission_type_code   VARCHAR(30),
    submission_type        VARCHAR(300),
    submission_status_code VARCHAR(30),
    submission_status      VARCHAR(200),

    event_type_code        VARCHAR(30),
    event_type             VARCHAR(200),
    review_type_code       VARCHAR(30),
    review_type            VARCHAR(200),

    loaded_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_irb_submission
        UNIQUE (
            protocol_id,
            submission_number,
            submission_type_code,
            event_type_code,
            review_type_code
        )
);

CREATE INDEX IF NOT EXISTS ix_irb_submission_protocol
    ON archive.irb_submission (protocol_id);

CREATE INDEX IF NOT EXISTS ix_irb_submission_base
    ON archive.irb_submission (
        protocol_base,
        submission_number
    );


CREATE TABLE IF NOT EXISTS archive.irb_funding_source (
    funding_source_id      BIGSERIAL PRIMARY KEY,
    protocol_id            BIGINT NOT NULL
                               REFERENCES archive.irb_protocol_version(protocol_id)
                               ON DELETE CASCADE,
    protocol_base          VARCHAR(30) NOT NULL,
    protocol_number        VARCHAR(60),
    funding_sequence       INTEGER,
    funding_source         VARCHAR(1000) NOT NULL,

    loaded_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_irb_funding_source
        UNIQUE (
            protocol_id,
            funding_source
        )
);

CREATE INDEX IF NOT EXISTS ix_irb_funding_protocol
    ON archive.irb_funding_source (protocol_id);

CREATE INDEX IF NOT EXISTS ix_irb_funding_base
    ON archive.irb_funding_source (protocol_base);

CREATE INDEX IF NOT EXISTS ix_irb_funding_name
    ON archive.irb_funding_source
    USING GIN (funding_source gin_trgm_ops);


CREATE TABLE IF NOT EXISTS archive.irb_timeline_event (
    timeline_event_id      BIGSERIAL PRIMARY KEY,
    protocol_id            BIGINT NOT NULL
                               REFERENCES archive.irb_protocol_version(protocol_id)
                               ON DELETE CASCADE,
    protocol_base          VARCHAR(30) NOT NULL,
    protocol_number        VARCHAR(60),

    event_date             DATE NOT NULL,
    event_type             VARCHAR(200) NOT NULL,
    event_sequence         INTEGER,
    source_column          VARCHAR(100),

    loaded_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_irb_timeline_event
        UNIQUE (
            protocol_id,
            event_date,
            event_type,
            event_sequence
        )
);

CREATE INDEX IF NOT EXISTS ix_irb_timeline_protocol
    ON archive.irb_timeline_event (
        protocol_id,
        event_date
    );

CREATE INDEX IF NOT EXISTS ix_irb_timeline_base
    ON archive.irb_timeline_event (
        protocol_base,
        event_date
    );
