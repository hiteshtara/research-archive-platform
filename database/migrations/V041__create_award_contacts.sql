-- Award Contacts: AWARD_SPONSOR_CONTACTS and AWARD_UNIT_CONTACTS,
-- confirmed against both the upstream Kuali Coeus OJB mapping
-- (org.kuali.kra.award.contacts.AwardSponsorContact ->
-- AWARD_SPONSOR_CONTACTS, org.kuali.kra.award.contacts.AwardUnitContact
-- -> AWARD_UNIT_CONTACTS) and the real Oracle bootstrap DDL
-- (coeus-db V300_107__schema.sql, V510_060__KC_TBL_AWARD_UNIT_CONTACTS.sql).
-- See docs/architecture/AWARD_CONTACTS_DESIGN.md.
--
-- archive.award_unit_contact previously existed (V014) and was dropped
-- (V033) because no verified Oracle extraction existed at the time.
-- That V014 schema included several columns with NO basis in the real
-- table (unit_name, parent_unit_number/name, project_role,
-- primary_title, directory_title, office_location, email_address,
-- office_phone, phone_extension) - this is a corrected, narrower,
-- double-verified re-creation, not a restoration of that guessed
-- schema. AWARD_CENTRAL_ADMIN_CONTACTS is deliberately NOT included
-- here: it is not a real table at all (confirmed via
-- AwardCentralAdminContact.java's own doc comment and
-- AwardCentralAdminContactsBean's transient, never-persisted rollup of
-- UNIT_ADMINISTRATOR data) - see the design doc's Findings.
--
-- Both tables carry AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER directly and
-- draw their own surrogate PK from the shared SEQUENCE_AWARD_ID - the
-- same flat, no-join shape as archive.award_custom_data/
-- award_sponsor_term/award_report_term. Neither PK column has a
-- plural/singular naming mismatch against its table name (unlike
-- AWARD_REPORT_TERMS_ID), so no aliasing is required at the SQL
-- boundary - still double-checked in the extraction SQL and by a
-- regression test given the recency of that exact bug class.

CREATE TABLE IF NOT EXISTS archive.award_sponsor_contact (
    award_sponsor_contact_id  BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50),
    sequence_number           INTEGER,

    rolodex_id                BIGINT,
    full_name                 VARCHAR(500),
    contact_role_code         VARCHAR(50),

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_sponsor_contact_award
    ON archive.award_sponsor_contact (award_id, award_sponsor_contact_id);

CREATE INDEX IF NOT EXISTS ix_award_sponsor_contact_rolodex
    ON archive.award_sponsor_contact (rolodex_id);


CREATE TABLE IF NOT EXISTS archive.award_unit_contact (
    award_unit_contact_id            BIGINT PRIMARY KEY,
    award_id                         BIGINT NOT NULL
                                         REFERENCES archive.award_version(award_id)
                                         ON DELETE CASCADE,
    award_number                     VARCHAR(50),
    sequence_number                  INTEGER,

    person_id                        VARCHAR(50),
    full_name                        VARCHAR(500),
    unit_contact_type                VARCHAR(50),
    unit_administrator_type_code     VARCHAR(50),
    unit_administrator_unit_number   VARCHAR(30),
    default_unit_contact             VARCHAR(10),

    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),
    source_version_number            BIGINT,

    loaded_at                        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                          BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_award
    ON archive.award_unit_contact (award_id, award_unit_contact_id);

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_person
    ON archive.award_unit_contact (person_id);

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_unit
    ON archive.award_unit_contact (unit_administrator_unit_number);
