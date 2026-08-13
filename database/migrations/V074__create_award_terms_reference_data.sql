-- Shared reference-data lookups resolving the raw codes already stored
-- in archive.award_sponsor_term/award_report_term/
-- award_report_term_recipient into readable labels. Both
-- AwardSponsorTermResponse's own migration comment (V040) and
-- docs/architecture/AWARD_TERMS_DESIGN.md flagged these as deferred,
-- unverified lookups at the time - live-verified against BU Oracle
-- staging now (2026-08-14): all eight are small, static reference
-- tables (10-293 rows each), the same category of size as
-- archive.custom_attribute/custom_attribute_document (V064).
--
-- Deliberately no foreign keys from archive.award_sponsor_term/
-- award_report_term/award_report_term_recipient to these tables:
-- codes are stored bare and denormalized on the historical Award rows
-- exactly as extracted from Oracle, and an unresolved code (one with
-- no matching lookup row, e.g. because Oracle later adds a code this
-- archive hasn't refreshed yet) must remain a valid, displayable
-- historical value, never rejected by a load. Resolution happens via
-- LEFT JOIN at query time, matching archive.custom_attribute's own
-- established convention.

CREATE TABLE IF NOT EXISTS archive.sponsor_term_type (
    sponsor_term_type_code   VARCHAR(3) PRIMARY KEY,
    description              VARCHAR(200),

    source_update_timestamp  TIMESTAMP,
    source_update_user       VARCHAR(100),
    source_version_number    BIGINT,

    loaded_at                TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                  BIGINT REFERENCES archive.load_run(load_id)
);

-- SPONSOR_TERM_ID (Oracle's own surrogate PK on this lookup table) is
-- what archive.award_sponsor_term.sponsor_term_id actually points at -
-- SPONSOR_TERM_CODE is the separate, human-readable value Kuali's own
-- UI displays. These are different values in overlapping numeric
-- ranges (live-verified: SPONSOR_TERM_ID 370 -> SPONSOR_TERM_CODE 64) -
-- do not conflate them.
CREATE TABLE IF NOT EXISTS archive.sponsor_term (
    sponsor_term_id           BIGINT PRIMARY KEY,
    sponsor_term_code         VARCHAR(3),
    sponsor_term_type_code    VARCHAR(3),
    description               VARCHAR(2048),

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_sponsor_term_type_code
    ON archive.sponsor_term (sponsor_term_type_code);

CREATE TABLE IF NOT EXISTS archive.report (
    report_code               VARCHAR(3) PRIMARY KEY,
    description                VARCHAR(200),
    final_report_flag          VARCHAR(1),
    active_flag                 VARCHAR(1),

    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    source_version_number       BIGINT,

    loaded_at                   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                     BIGINT REFERENCES archive.load_run(load_id)
);

CREATE TABLE IF NOT EXISTS archive.report_class (
    report_class_code                VARCHAR(3) PRIMARY KEY,
    description                       VARCHAR(200),
    generate_report_requirements      VARCHAR(1),
    active_flag                        VARCHAR(1),

    source_update_timestamp            TIMESTAMP,
    source_update_user                 VARCHAR(100),
    source_version_number              BIGINT,

    loaded_at                          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                            BIGINT REFERENCES archive.load_run(load_id)
);

CREATE TABLE IF NOT EXISTS archive.frequency (
    frequency_code             VARCHAR(3) PRIMARY KEY,
    description                 VARCHAR(200),
    number_of_days               INTEGER,
    number_of_months             INTEGER,
    repeat_flag                   VARCHAR(1),
    advance_number_of_days        INTEGER,
    advance_number_of_months      INTEGER,
    active_flag                    VARCHAR(1),

    source_update_timestamp        TIMESTAMP,
    source_update_user             VARCHAR(100),
    source_version_number          BIGINT,

    loaded_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                        BIGINT REFERENCES archive.load_run(load_id)
);

CREATE TABLE IF NOT EXISTS archive.frequency_base (
    frequency_base_code         VARCHAR(3) PRIMARY KEY,
    description                  VARCHAR(200),
    regeneration_type_name        VARCHAR(12),
    active_flag                    VARCHAR(1),

    source_update_timestamp        TIMESTAMP,
    source_update_user             VARCHAR(100),
    source_version_number          BIGINT,

    loaded_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                        BIGINT REFERENCES archive.load_run(load_id)
);

-- OSP_DISTRIBUTION_CODE on archive.award_report_term names this
-- table's own PK column osp_distribution_code, matching Oracle's
-- AWARD_REPORT_TERMS.OSP_DISTRIBUTION_CODE -> DISTRIBUTION join
-- directly (the Oracle table itself is named DISTRIBUTION, not
-- OSP_DISTRIBUTION - archive.distribution mirrors Oracle's real name).
CREATE TABLE IF NOT EXISTS archive.distribution (
    osp_distribution_code       VARCHAR(3) PRIMARY KEY,
    description                  VARCHAR(200),
    active_flag                   VARCHAR(1),

    source_update_timestamp       TIMESTAMP,
    source_update_user            VARCHAR(100),
    source_version_number         BIGINT,

    loaded_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                       BIGINT REFERENCES archive.load_run(load_id)
);

CREATE TABLE IF NOT EXISTS archive.contact_type (
    contact_type_code           VARCHAR(3) PRIMARY KEY,
    description                  VARCHAR(200),

    source_update_timestamp       TIMESTAMP,
    source_update_user            VARCHAR(100),
    source_version_number         BIGINT,

    loaded_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                       BIGINT REFERENCES archive.load_run(load_id)
);
