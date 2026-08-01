-- Award People expansion: the three remaining tables in Kuali's
-- org.kuali.kra.award.contacts.AwardPerson object graph, confirmed
-- against the upstream Kuali Coeus source
-- (org.kuali.kra.award.contacts.AwardPersonUnit -> AWARD_PERSON_UNITS,
-- org.kuali.kra.award.contacts.AwardPersonCreditSplit ->
-- AWARD_PERSON_CREDIT_SPLITS, org.kuali.kra.award.contacts.
-- AwardPersonUnitCreditSplit -> AWARD_PERS_UNIT_CRED_SPLITS). See
-- docs/architecture/AWARD_PEOPLE_EXPANSION_DESIGN.md.
--
-- None of these three Oracle tables carry an AWARD_ID column of their
-- own (AWARD_PERSON_UNITS/AWARD_PERSON_CREDIT_SPLITS reference only
-- AWARD_PERSON_ID; AWARD_PERS_UNIT_CRED_SPLITS references only
-- AWARD_PERSON_UNIT_ID, two hops from Award). award_id/award_number/
-- sequence_number below are denormalized through an Oracle-side JOIN
-- back to AWARD_PERSONS at extraction time, so every child table keeps
-- the same family-scoped-query shape as award_amount_info/award_person/
-- award_custom_data - see sql/extract/award/06-08_*.sql.
--
-- All four PKs (AWARD_PERSON_UNIT_ID, AWARD_PERSON_CREDIT_SPLIT_ID,
-- APU_CREDIT_SPLIT_ID) draw from the same shared SEQUENCE_AWARD_ID
-- Oracle sequence as every other Award child table - safe, globally
-- unique UPSERT conflict keys.
--
-- lead_unit_flag/credit are modeled as VARCHAR(10) and NUMERIC(10,2)
-- respectively, matching archive.award_person.faculty_flag's existing
-- Y/N-as-VARCHAR convention and the OJB mapping's
-- OjbScaleTwoDecimalFieldConversion (exactly two decimal places).

CREATE TABLE IF NOT EXISTS archive.award_person_unit (
    award_person_unit_id     BIGINT PRIMARY KEY,
    award_person_id          BIGINT NOT NULL
                                 REFERENCES archive.award_person(award_person_id)
                                 ON DELETE CASCADE,
    award_id                 BIGINT NOT NULL
                                 REFERENCES archive.award_version(award_id)
                                 ON DELETE CASCADE,

    award_number             VARCHAR(50),
    sequence_number          INTEGER,

    unit_number               VARCHAR(30),
    lead_unit_flag            VARCHAR(10),

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_person_unit_award
    ON archive.award_person_unit (award_id, award_person_unit_id);

CREATE INDEX IF NOT EXISTS ix_award_person_unit_person
    ON archive.award_person_unit (award_person_id);

CREATE INDEX IF NOT EXISTS ix_award_person_unit_number
    ON archive.award_person_unit (unit_number);


CREATE TABLE IF NOT EXISTS archive.award_person_credit_split (
    award_person_credit_split_id  BIGINT PRIMARY KEY,
    award_person_id               BIGINT NOT NULL
                                      REFERENCES archive.award_person(award_person_id)
                                      ON DELETE CASCADE,
    award_id                      BIGINT NOT NULL
                                      REFERENCES archive.award_version(award_id)
                                      ON DELETE CASCADE,

    award_number                  VARCHAR(50),
    sequence_number               INTEGER,

    inv_credit_type_code          VARCHAR(50),
    credit                        NUMERIC(10,2),

    source_update_timestamp       TIMESTAMP,
    source_update_user            VARCHAR(100),
    source_version_number         BIGINT,

    loaded_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                       BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_person_credit_split_award
    ON archive.award_person_credit_split (award_id, award_person_credit_split_id);

CREATE INDEX IF NOT EXISTS ix_award_person_credit_split_person
    ON archive.award_person_credit_split (award_person_id);


CREATE TABLE IF NOT EXISTS archive.award_person_unit_credit_split (
    award_person_unit_credit_split_id  BIGINT PRIMARY KEY,
    award_person_unit_id               BIGINT NOT NULL
                                           REFERENCES archive.award_person_unit(award_person_unit_id)
                                           ON DELETE CASCADE,
    award_id                           BIGINT NOT NULL
                                           REFERENCES archive.award_version(award_id)
                                           ON DELETE CASCADE,

    award_number                       VARCHAR(50),
    sequence_number                    INTEGER,

    inv_credit_type_code               VARCHAR(50),
    credit                             NUMERIC(10,2),

    source_update_timestamp            TIMESTAMP,
    source_update_user                 VARCHAR(100),
    source_version_number              BIGINT,

    loaded_at                          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                            BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_person_unit_credit_split_award
    ON archive.award_person_unit_credit_split (award_id, award_person_unit_credit_split_id);

CREATE INDEX IF NOT EXISTS ix_award_person_unit_credit_split_unit
    ON archive.award_person_unit_credit_split (award_person_unit_id);
