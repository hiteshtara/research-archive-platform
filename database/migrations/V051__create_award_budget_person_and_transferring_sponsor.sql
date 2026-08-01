-- Final Award gap bundle: the two remaining ARCHIVE_REQUIRED objects
-- identified by docs/architecture/AWARD_COMPLETENESS_REPORT.md.
--
-- archive.award_budget_person: BUDGET_PERSONS is shared with Proposal
-- Development, exactly like the six generic Budget tables already
-- merged in V050 - but it has no Award-specific "_EXT" extension
-- table at all (confirmed: no AwardBudgetPerson*.xml/.java exists
-- anywhere in the checkout). It is archived standalone, the same
-- shape already used for AWD_BGT_PER_SUM_CALC_AMT/AWARD_BUDGET_LIMIT,
-- scoped to Award only by the extraction SQL's join through BUDGET to
-- AWARD_BUDGET_EXT (see sql/extract/award/45_award_budget_person.sql).
-- Oracle's own PK is composite (BUDGET_ID, PERSON_SEQUENCE_NUMBER) -
-- there is no surrogate id - mirrored here verbatim as a composite
-- Postgres PK. budget_id has a real, Oracle-enforced FK
-- (V300_258__schema-constraints.sql: FK_BUDGET_PERSONS) to the
-- generic BUDGET table; since archive.award_budget already merges
-- AWARD_BUDGET_EXT + BUDGET at that same shared PK value, the archive
-- FK points at archive.award_budget(budget_id) directly. Two DDL-only
-- columns with no OJB field-descriptor and no Java field at all -
-- PROPOSAL_NUMBER and VERSION_NUMBER (distinct from VER_NBR, which
-- does map and is captured as source_version_number) - are
-- deliberately excluded, the same "no corroborating evidence" rule
-- already applied to BUDGET.FINAL_VERSION_FLAG and
-- previousObligatedTotal in V050. HIERARCHY_PROPOSAL_NUMBER has a real
-- Oracle FK to Proposal Development's own EPS_PROPOSAL table (out of
-- this project's scope) and is kept as a bare, unenforced column, the
-- same convention already used for archive.award_budget_line_item's
-- own hierarchy_proposal_number.
--
-- archive.award_transferring_sponsor: a real, Oracle-enforced,
-- per-Award-version child table (AWARD_TRANSFERRING_SPONSOR),
-- structurally identical to the already-archived
-- archive.award_sponsor_term - one lookup-code FK, one row per Award
-- version, no children. sponsor_name is denormalized via LEFT JOIN
-- SPONSOR, the same convention archive.award_version.sponsor_name
-- already uses (see sql/extract/award/01_award_versions.sql).
--
-- See docs/architecture/AWARD_COMPLETENESS_REPORT.md for the full
-- research/classification behind both tables.

CREATE TABLE IF NOT EXISTS archive.award_budget_person (
    budget_id                  BIGINT NOT NULL
                                   REFERENCES archive.award_budget(budget_id)
                                   ON DELETE CASCADE,
    person_sequence_number     INTEGER NOT NULL,

    effective_date             DATE,
    job_code                   VARCHAR(20),
    non_employee_flag          VARCHAR(10),
    person_id                  VARCHAR(50),
    appointment_type_code      VARCHAR(10),
    rolodex_id                 INTEGER,
    tbn_id                     VARCHAR(20),
    calculation_base           NUMERIC(14, 2),
    person_name                VARCHAR(200),
    salary_anniversary_date    DATE,
    hierarchy_proposal_number  VARCHAR(20),
    hidden_in_hierarchy        VARCHAR(10),

    source_update_timestamp    TIMESTAMP,
    source_update_user         VARCHAR(100),
    source_version_number      BIGINT,

    loaded_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                    BIGINT REFERENCES archive.load_run(load_id),

    PRIMARY KEY (budget_id, person_sequence_number)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_person_budget
    ON archive.award_budget_person (budget_id);


CREATE TABLE IF NOT EXISTS archive.award_transferring_sponsor (
    award_transferring_sponsor_id  BIGINT PRIMARY KEY,
    award_id                       BIGINT NOT NULL
                                        REFERENCES archive.award_version(award_id)
                                        ON DELETE CASCADE,
    award_number                   VARCHAR(50),
    sequence_number                INTEGER,

    sponsor_code                   VARCHAR(20),
    sponsor_name                   VARCHAR(200),

    source_update_timestamp        TIMESTAMP,
    source_update_user             VARCHAR(100),
    source_version_number          BIGINT,

    loaded_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                        BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_transferring_sponsor_award
    ON archive.award_transferring_sponsor (award_id, award_transferring_sponsor_id);
