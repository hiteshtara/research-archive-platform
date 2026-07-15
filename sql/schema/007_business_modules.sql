CREATE TABLE IF NOT EXISTS archive.award (
    record_id               BIGINT PRIMARY KEY
                                REFERENCES archive.research_record(record_id)
                                ON DELETE CASCADE,
    award_number            VARCHAR(100) NOT NULL,
    account_number          VARCHAR(100),
    sponsor_code            VARCHAR(100),
    sponsor_name            VARCHAR(500),
    sponsor_id              BIGINT REFERENCES archive.sponsor(sponsor_id),
    obligated_amount        NUMERIC(18,2),
    anticipated_amount      NUMERIC(18,2),
    award_start_date        DATE,
    award_end_date          DATE,
    prime_sponsor_code      VARCHAR(100),
    prime_sponsor_name      VARCHAR(500),
    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_award_number UNIQUE (award_number)
);

CREATE INDEX IF NOT EXISTS ix_award_sponsor_code
    ON archive.award (sponsor_code);

CREATE INDEX IF NOT EXISTS ix_award_dates
    ON archive.award (award_start_date, award_end_date);

CREATE TABLE IF NOT EXISTS archive.proposal (
    record_id               BIGINT PRIMARY KEY
                                REFERENCES archive.research_record(record_id)
                                ON DELETE CASCADE,
    proposal_number         VARCHAR(100) NOT NULL,
    proposal_type_code      VARCHAR(50),
    proposal_type           VARCHAR(200),
    sponsor_code            VARCHAR(100),
    sponsor_name            VARCHAR(500),
    sponsor_id              BIGINT REFERENCES archive.sponsor(sponsor_id),
    requested_amount        NUMERIC(18,2),
    submission_date         DATE,
    deadline_date           DATE,
    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_proposal_number UNIQUE (proposal_number)
);

CREATE INDEX IF NOT EXISTS ix_proposal_sponsor_code
    ON archive.proposal (sponsor_code);

CREATE TABLE IF NOT EXISTS archive.negotiation (
    record_id               BIGINT PRIMARY KEY
                                REFERENCES archive.research_record(record_id)
                                ON DELETE CASCADE,
    negotiation_id          VARCHAR(100) NOT NULL,
    negotiation_number      VARCHAR(100),
    agreement_type_code     VARCHAR(50),
    agreement_type          VARCHAR(200),
    negotiation_status      VARCHAR(200),
    start_date              DATE,
    completed_date          DATE,
    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_negotiation_id UNIQUE (negotiation_id)
);

CREATE TABLE IF NOT EXISTS archive.subaward (
    record_id               BIGINT PRIMARY KEY
                                REFERENCES archive.research_record(record_id)
                                ON DELETE CASCADE,
    subaward_number         VARCHAR(100) NOT NULL,
    parent_award_number     VARCHAR(100),
    organization_name       VARCHAR(500),
    organization_id         BIGINT
                                REFERENCES archive.organization(organization_id),
    obligated_amount        NUMERIC(18,2),
    anticipated_amount      NUMERIC(18,2),
    start_date              DATE,
    end_date                DATE,
    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_subaward_number UNIQUE (subaward_number)
);

CREATE INDEX IF NOT EXISTS ix_subaward_parent_award
    ON archive.subaward (parent_award_number);
