CREATE TABLE IF NOT EXISTS archive.award_version (
    award_id                    BIGINT PRIMARY KEY,
    award_number                VARCHAR(50) NOT NULL,
    sequence_number             INTEGER NOT NULL,
    award_sequence_status       VARCHAR(50),

    status_code                 VARCHAR(30),
    status_description          VARCHAR(300),

    title                       TEXT,

    sponsor_code                VARCHAR(30),
    sponsor_name                VARCHAR(500),

    prime_sponsor_code          VARCHAR(30),
    prime_sponsor_name          VARCHAR(500),

    lead_unit_number            VARCHAR(30),
    lead_unit_name              VARCHAR(500),

    proposal_number             VARCHAR(50),
    account_number              VARCHAR(50),
    sponsor_award_number        VARCHAR(200),

    award_effective_date        DATE,
    award_execution_date        DATE,
    begin_date                  DATE,
    closeout_date               DATE,

    transaction_type_code       VARCHAR(30),
    transaction_type            VARCHAR(300),
    modification_number         VARCHAR(100),

    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),

    is_current_version          BOOLEAN NOT NULL DEFAULT FALSE,

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_award_version_number_sequence
        UNIQUE (award_number, sequence_number)
);

CREATE INDEX IF NOT EXISTS ix_award_version_number
    ON archive.award_version (
        award_number,
        sequence_number DESC
    );

CREATE INDEX IF NOT EXISTS ix_award_version_current
    ON archive.award_version (
        award_number,
        is_current_version
    );

CREATE INDEX IF NOT EXISTS ix_award_version_sponsor
    ON archive.award_version (sponsor_code);

CREATE INDEX IF NOT EXISTS ix_award_version_status
    ON archive.award_version (status_description);

CREATE INDEX IF NOT EXISTS ix_award_version_account
    ON archive.award_version (account_number);


CREATE TABLE IF NOT EXISTS archive.award_amount_info (
    award_amount_info_id        BIGINT PRIMARY KEY,
    award_id                    BIGINT NOT NULL
                                    REFERENCES archive.award_version(award_id)
                                    ON DELETE CASCADE,

    award_number                VARCHAR(50) NOT NULL,
    sequence_number             INTEGER NOT NULL,

    anticipated_change_direct   NUMERIC(18,2),
    anticipated_change_indirect NUMERIC(18,2),

    anticipated_total_direct    NUMERIC(18,2),
    anticipated_total_indirect  NUMERIC(18,2),

    obligated_total_direct      NUMERIC(18,2),
    obligated_total_indirect    NUMERIC(18,2),

    anticipated_total_amount    NUMERIC(18,2),
    obligated_total_amount      NUMERIC(18,2),

    tnm_document_number         VARCHAR(100),
    source_version_number       BIGINT,

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_amount_award
    ON archive.award_amount_info (
        award_id,
        award_amount_info_id
    );

CREATE INDEX IF NOT EXISTS ix_award_amount_number
    ON archive.award_amount_info (
        award_number,
        sequence_number
    );


CREATE TABLE IF NOT EXISTS archive.award_person (
    award_person_id             BIGINT PRIMARY KEY,
    award_id                    BIGINT NOT NULL
                                    REFERENCES archive.award_version(award_id)
                                    ON DELETE CASCADE,

    award_number                VARCHAR(50) NOT NULL,
    sequence_number             INTEGER NOT NULL,

    person_id                   VARCHAR(50),
    rolodex_id                  BIGINT,
    full_name                   VARCHAR(500),

    contact_role_code           VARCHAR(50),
    key_person_project_role     VARCHAR(300),

    faculty_flag                VARCHAR(10),

    academic_year_effort        NUMERIC(10,4),
    calendar_year_effort        NUMERIC(10,4),
    summer_effort               NUMERIC(10,4),
    total_effort                NUMERIC(10,4),

    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_person_award
    ON archive.award_person (
        award_id,
        award_person_id
    );

CREATE INDEX IF NOT EXISTS ix_award_person_number
    ON archive.award_person (
        award_number,
        sequence_number
    );

CREATE INDEX IF NOT EXISTS ix_award_person_person
    ON archive.award_person (person_id);

CREATE INDEX IF NOT EXISTS ix_award_person_name
    ON archive.award_person (full_name);


CREATE TABLE IF NOT EXISTS archive.award_funding_proposal (
    award_funding_proposal_id   BIGINT PRIMARY KEY,
    award_id                    BIGINT NOT NULL
                                    REFERENCES archive.award_version(award_id)
                                    ON DELETE CASCADE,

    proposal_id                 BIGINT NOT NULL,
    active_flag                 VARCHAR(10),

    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    source_version_number       BIGINT,

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_award_funding_proposal
        UNIQUE (award_id, proposal_id)
);

CREATE INDEX IF NOT EXISTS ix_award_proposal_award
    ON archive.award_funding_proposal (award_id);

CREATE INDEX IF NOT EXISTS ix_award_proposal_proposal
    ON archive.award_funding_proposal (proposal_id);
