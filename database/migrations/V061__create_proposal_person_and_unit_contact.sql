-- Institutional Proposal People & Units - the three tables in Kuali's
-- PROPOSAL_PERSONS/PROPOSAL_PERSON_UNITS/PROPOSAL_UNIT_CONTACTS group,
-- confirmed live against BU's real Oracle instance (columns/types/
-- fixture rows verified via a live schema + data probe). Mirrors
-- archive.award_person/archive.award_person_unit's shape (V011/V039)
-- since PROPOSAL_PERSONS and PROPOSAL_PERSON_UNITS are structurally
-- the same object graph as AWARD_PERSONS/AWARD_PERSON_UNITS, but these
-- are three DISTINCT tables preserved separately - never merged:
--
-- - archive.proposal_person: PROPOSAL_PERSONS itself (PI/MPI/COI/KP via
--   CONTACT_ROLE_CODE - the same shared vocabulary already proven for
--   archive.award_person.contact_role_code, live-confirmed identical
--   values PI/MPI/COI/KP on both sides).
-- - archive.proposal_person_unit: PROPOSAL_PERSON_UNITS, a person's
--   associated unit(s) with a per-person LEAD_UNIT_FLAG - a different
--   concept from PROPOSAL.LEAD_UNIT_NUMBER (the proposal's own lead
--   unit), though live-confirmed to agree for the PI in the reference
--   fixture (family 205: PI Lois K Horwitz's lead_unit_flag='Y' on
--   1262160000, matching PROPOSAL.LEAD_UNIT_NUMBER for both proposal_id
--   212 and 2986). PROPOSAL_PERSON_UNITS carries no PROPOSAL_ID column
--   of its own (only PROPOSAL_PERSON_ID) - proposal_id/proposal_number/
--   sequence_number are denormalized through an Oracle-side JOIN back
--   to PROPOSAL_PERSONS at extraction time, exactly mirroring
--   AWARD_PERSON_UNITS' own shape (see V039's own comment).
-- - archive.proposal_unit_contact: PROPOSAL_UNIT_CONTACTS, a genuinely
--   separate sibling table (not derived, unlike Award's own Unit
--   Contacts which has no persisted table and is instead derived from
--   UNIT_ADMINISTRATOR at request time - see V056/AWARD_CONTACTS_DESIGN.md).
--   Live-confirmed distinct from Proposal's own PI in the reference
--   fixture (family 205: unit contact Andrea Cozzi/U19663726 vs. PI
--   Lois K Horwitz/U56572816 - a different person entirely). Its
--   UNIT_ADMINISTRATOR_TYPE_CODE draws from the same small, already-
--   loaded archive.unit_administrator_type reference table (V056) -
--   live-confirmed code '1' = "Pre-Award - Department Administrator"
--   (default_group_flag='U', the same Unit-level group Award's own
--   derived Unit Contacts use) - reused via FK here rather than
--   duplicated.

CREATE TABLE IF NOT EXISTS archive.proposal_person (
    proposal_person_id       BIGINT PRIMARY KEY,
    proposal_id               BIGINT NOT NULL,
    proposal_number           VARCHAR(50) NOT NULL,
    sequence_number           INTEGER NOT NULL,

    person_id                 VARCHAR(40),
    rolodex_id                BIGINT,
    full_name                 VARCHAR(500),

    contact_role_code         VARCHAR(20),
    key_person_project_role   VARCHAR(200),

    faculty_flag              VARCHAR(10),
    academic_year_effort      NUMERIC(10,4),
    calendar_year_effort      NUMERIC(10,4),
    summer_effort             NUMERIC(10,4),
    total_effort               NUMERIC(10,4),

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_proposal_person_proposal
    ON archive.proposal_person (proposal_id);

CREATE INDEX IF NOT EXISTS ix_proposal_person_number
    ON archive.proposal_person (proposal_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_proposal_person_person
    ON archive.proposal_person (person_id);

CREATE INDEX IF NOT EXISTS ix_proposal_person_role
    ON archive.proposal_person (contact_role_code);


CREATE TABLE IF NOT EXISTS archive.proposal_person_unit (
    proposal_person_unit_id  BIGINT PRIMARY KEY,
    proposal_person_id       BIGINT NOT NULL
                                  REFERENCES archive.proposal_person(proposal_person_id)
                                  ON DELETE CASCADE,
    proposal_id               BIGINT NOT NULL,
    proposal_number            VARCHAR(50),
    sequence_number            INTEGER,

    unit_number                VARCHAR(20),
    lead_unit_flag              VARCHAR(10),

    source_update_timestamp    TIMESTAMP,
    source_update_user         VARCHAR(100),

    loaded_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                    BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_proposal_person_unit_proposal
    ON archive.proposal_person_unit (proposal_id);

CREATE INDEX IF NOT EXISTS ix_proposal_person_unit_person
    ON archive.proposal_person_unit (proposal_person_id);

CREATE INDEX IF NOT EXISTS ix_proposal_person_unit_number
    ON archive.proposal_person_unit (unit_number);


CREATE TABLE IF NOT EXISTS archive.proposal_unit_contact (
    proposal_unit_contact_id      BIGINT PRIMARY KEY,
    proposal_id                    BIGINT NOT NULL,
    proposal_number                 VARCHAR(50) NOT NULL,
    sequence_number                 INTEGER NOT NULL,

    person_id                       VARCHAR(40),
    full_name                       VARCHAR(500),
    unit_administrator_type_code     VARCHAR(3)
                                        REFERENCES archive.unit_administrator_type(unit_administrator_type_code),
    unit_contact_type                VARCHAR(20),

    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),

    loaded_at                        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                          BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_proposal_unit_contact_proposal
    ON archive.proposal_unit_contact (proposal_id);

CREATE INDEX IF NOT EXISTS ix_proposal_unit_contact_number
    ON archive.proposal_unit_contact (proposal_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_proposal_unit_contact_person
    ON archive.proposal_unit_contact (person_id);
