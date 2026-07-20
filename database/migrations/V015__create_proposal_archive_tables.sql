CREATE TABLE archive.proposal_version (
    proposal_id                    BIGINT NOT NULL,
    proposal_number                VARCHAR(50) NOT NULL,
    version_number                 INTEGER NOT NULL,

    title                          TEXT,
    proposal_sequence_status       VARCHAR(100),

    proposal_type_code             INTEGER,
    proposal_type                  VARCHAR(200),

    activity_type_code             INTEGER,
    activity_type                  VARCHAR(200),

    sponsor_code                   VARCHAR(50),
    sponsor_name                   VARCHAR(500),

    lead_unit_number               VARCHAR(50),
    lead_unit_name                 VARCHAR(500),

    principal_investigator_id      VARCHAR(100),
    principal_investigator_name    VARCHAR(500),

    initial_start_date             DATE,
    initial_end_date               DATE,
    initial_direct_cost            NUMERIC(18,2),
    initial_indirect_cost          NUMERIC(18,2),
    initial_total_cost             NUMERIC(18,2),

    total_start_date               DATE,
    total_end_date                 DATE,
    total_direct_cost              NUMERIC(18,2),
    total_indirect_cost            NUMERIC(18,2),
    total_cost                     NUMERIC(18,2),

    source_update_timestamp        TIMESTAMP,
    loaded_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (proposal_id, version_number)
);

CREATE INDEX idx_proposal_version_number
    ON archive.proposal_version(proposal_number);

CREATE INDEX idx_proposal_version_sponsor
    ON archive.proposal_version(sponsor_name);

CREATE INDEX idx_proposal_version_pi
    ON archive.proposal_version(principal_investigator_name);

CREATE INDEX idx_proposal_version_unit
    ON archive.proposal_version(lead_unit_number);

CREATE TABLE archive.proposal_person (
    proposal_id                    BIGINT NOT NULL,
    version_number                 INTEGER NOT NULL,
    person_id                      VARCHAR(100),
    full_name                      VARCHAR(500),
    role                           VARCHAR(100),
    principal_investigator         BOOLEAN NOT NULL DEFAULT FALSE,
    source_update_timestamp        TIMESTAMP,
    loaded_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_proposal_person_proposal
    ON archive.proposal_person(proposal_id, version_number);

CREATE INDEX idx_proposal_person_person_id
    ON archive.proposal_person(person_id);

CREATE INDEX idx_proposal_person_name
    ON archive.proposal_person(full_name);

CREATE TABLE archive.proposal_award (
    proposal_id                    BIGINT NOT NULL,
    award_id                       BIGINT,
    award_number                   VARCHAR(50),
    loaded_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_proposal_award
        UNIQUE (proposal_id, award_id, award_number)
);

CREATE INDEX idx_proposal_award_proposal
    ON archive.proposal_award(proposal_id);

CREATE INDEX idx_proposal_award_number
    ON archive.proposal_award(award_number);
