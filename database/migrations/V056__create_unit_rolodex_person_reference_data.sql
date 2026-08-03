-- Shared reference-data entities backing the Award Contacts feature
-- (Unit Details / Central Administration Contacts / Unit Contacts /
-- Sponsor Contacts). Per explicit architectural direction: Award
-- references these, never duplicates them. All four tables verified
-- live against BU's real Oracle instance (columns/types/row counts
-- confirmed via a live schema probe, not the open-source reference
-- schema, which differs from BU's real deployed schema in several
-- widened VARCHAR2 lengths - see docs/architecture/AWARD_CONTACTS_DESIGN.md
-- for the same precedent with archive.award_unit_contact/
-- archive.award_sponsor_contact).
--
-- archive.unit / archive.unit_administrator / archive.unit_administrator_type
-- are full reference-data loads (Oracle UNIT=~5.1K rows,
-- UNIT_ADMINISTRATOR=~1K rows, UNIT_ADMINISTRATOR_TYPE=11 rows as of
-- verification) - small and bounded, unlike Award's per-version data.
--
-- archive.rolodex is also a full reference-data load (~12.5K rows as of
-- verification) - external (non-BU-person) contacts referenced by
-- AWARD_SPONSOR_CONTACTS.ROLODEX_ID, small enough to load in full
-- rather than filter per-Award.
--
-- archive.person is NOT a full dump of BU's identity system (Rice KIM's
-- KRIM_PRNCPL_T/KRIM_ENTITY_T could be enormous - every BU affiliate).
-- It is deliberately scoped, this phase, to exactly the person_ids
-- referenced by archive.unit_administrator and
-- archive.award_unit_contact (a targeted, bind-variable Oracle read,
-- never a full-table scan of KRIM_PRNCPL_T - see the same "targeted,
-- not full scan" convention already established for
-- --diff-award-versions). Real BU join chain, confirmed live:
-- KRIM_PRNCPL_T.PRNCPL_ID = person_id, KRIM_PRNCPL_T.ENTITY_ID joins to
-- KRIM_ENTITY_NM_T (WHERE DFLT_IND='Y') for name,
-- KRIM_ENTITY_EMAIL_T (WHERE DFLT_IND='Y') for email, and
-- KRIM_ENTITY_PHONE_T (WHERE DFLT_IND='Y') for phone - never a `PERSON`
-- or `KC_PERSON` table, which do not exist in BU's real schema.

CREATE TABLE IF NOT EXISTS archive.unit (
    unit_number             VARCHAR(20) PRIMARY KEY,
    unit_name               VARCHAR(60),
    parent_unit_number      VARCHAR(20),
    organization_id         VARCHAR(8),
    active                  BOOLEAN,

    source_update_timestamp TIMESTAMP,
    source_update_user      VARCHAR(60),
    source_version_number   BIGINT,

    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_unit_parent_unit_number
    ON archive.unit (parent_unit_number);

CREATE TABLE IF NOT EXISTS archive.unit_administrator_type (
    unit_administrator_type_code VARCHAR(3) PRIMARY KEY,
    description                  VARCHAR(200) NOT NULL,
    default_group_flag           VARCHAR(1),
    multiples_flag               VARCHAR(1),

    source_update_timestamp      TIMESTAMP,
    source_update_user           VARCHAR(60),
    source_version_number        BIGINT,

    loaded_at                    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                      BIGINT REFERENCES archive.load_run(load_id)
);

CREATE TABLE IF NOT EXISTS archive.unit_administrator (
    unit_number                  VARCHAR(20) NOT NULL
                                      REFERENCES archive.unit(unit_number)
                                      ON DELETE CASCADE,
    person_id                    VARCHAR(40) NOT NULL,
    unit_administrator_type_code VARCHAR(3) NOT NULL
                                      REFERENCES archive.unit_administrator_type(unit_administrator_type_code),

    source_update_timestamp      TIMESTAMP,
    source_update_user           VARCHAR(60),
    source_version_number        BIGINT,

    loaded_at                    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                      BIGINT REFERENCES archive.load_run(load_id),

    PRIMARY KEY (unit_number, person_id, unit_administrator_type_code)
);

CREATE INDEX IF NOT EXISTS ix_unit_administrator_person
    ON archive.unit_administrator (person_id);

-- "Central Administration Contacts" is a derived Kuali concept, not a
-- persisted table (see the real Award.initCentralAdminContacts() trace
-- in AWARD_CONTACTS_DESIGN.md's Central Admin section) - so it is never
-- archived as its own table. The API derives it, at request time, from
-- award_version.lead_unit_number joined through this exact table pair
-- filtered to default_group_flag = 'C', reproducing that Java method's
-- own query rather than inventing a new rule.

CREATE TABLE IF NOT EXISTS archive.rolodex (
    rolodex_id              BIGINT PRIMARY KEY,
    last_name               VARCHAR(20),
    first_name              VARCHAR(20),
    middle_name             VARCHAR(20),
    suffix                  VARCHAR(10),
    prefix                  VARCHAR(10),
    title                   VARCHAR(35),
    organization            VARCHAR(200),
    phone_number            VARCHAR(20),
    email_address           VARCHAR(60),
    address_line_1          VARCHAR(80),
    address_line_2          VARCHAR(80),
    address_line_3          VARCHAR(80),
    city                    VARCHAR(30),
    county                  VARCHAR(30),
    state                   VARCHAR(30),
    postal_code             VARCHAR(15),
    country_code            VARCHAR(3),
    owned_by_unit           VARCHAR(20),
    active                  BOOLEAN,
    delete_flag             VARCHAR(1),

    source_update_timestamp TIMESTAMP,
    source_update_user      VARCHAR(60),
    source_version_number   BIGINT,

    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id)
);

CREATE TABLE IF NOT EXISTS archive.person (
    person_id                VARCHAR(40) PRIMARY KEY,
    first_name               VARCHAR(40),
    middle_name              VARCHAR(40),
    last_name                VARCHAR(80),
    full_name                VARCHAR(200),
    email_address            VARCHAR(200),
    phone_number             VARCHAR(20),

    loaded_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                  BIGINT REFERENCES archive.load_run(load_id)
);

COMMENT ON TABLE archive.person IS
    'Deliberately NOT a full dump of every BU identity - scoped to '
    'person_ids referenced by archive.unit_administrator and '
    'archive.award_unit_contact. Resolved from Rice KIM '
    '(KRIM_PRNCPL_T/KRIM_ENTITY_NM_T/KRIM_ENTITY_EMAIL_T/'
    'KRIM_ENTITY_PHONE_T), not a PERSON/KC_PERSON table - neither '
    'exists in BU''s real schema.';
