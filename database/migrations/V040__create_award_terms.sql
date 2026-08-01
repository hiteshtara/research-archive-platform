-- Award Terms: the three real, currently-unarchived tables in Kuali's
-- Award Terms subsystem, confirmed against the upstream Kuali Coeus
-- source (org.kuali.kra.award.home.AwardSponsorTerm -> AWARD_SPONSOR_TERM,
-- org.kuali.kra.award.paymentreports.awardreports.AwardReportTerm ->
-- AWARD_REPORT_TERMS, org.kuali.kra.award.paymentreports.awardreports.
-- AwardReportTermRecipient -> AWARD_REP_TERMS_RECNT). See
-- docs/architecture/AWARD_TERMS_DESIGN.md.
--
-- AWARD_BASIS_OF_PAYMENT/AWARD_METHOD_OF_PAYMENT are deliberately NOT
-- part of this migration: they are pure code/description lookup tables
-- for two scalar fields on AWARD itself (basisOfPaymentCode/
-- methodOfPaymentCode), not child rows - capturing them would require a
-- TRUNCATE-path change (01_award_versions.sql + the full load's column
-- list) out of scope here. Recorded as an open, deferred follow-on in
-- the design doc, not silently dropped.
--
-- AWARD_SPONSOR_TERM and AWARD_REPORT_TERMS both carry AWARD_ID/
-- AWARD_NUMBER/SEQUENCE_NUMBER directly (unlike Award People's
-- children) - no Oracle-side join is needed to populate award_id here.
-- AWARD_REP_TERMS_RECNT has only AWARD_REPORT_TERMS_ID; award_id below
-- is denormalized through an Oracle-side JOIN back to AWARD_REPORT_TERMS
-- at extraction time, the same pattern already used for
-- archive.award_person_unit_credit_split.
--
-- award_sponsor_term_id and award_report_term_recipient_id draw from
-- their own dedicated Oracle sequences (SEQ_AWARD_SPONSOR_TERM,
-- SEQ_AWARD_REP_TERMS_RECNT_ID respectively), not the shared
-- SEQUENCE_AWARD_ID award_report_term_id itself uses - still safe,
-- table-scoped UPSERT conflict keys regardless.

CREATE TABLE IF NOT EXISTS archive.award_sponsor_term (
    award_sponsor_term_id     BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50),
    sequence_number           INTEGER,

    sponsor_term_id           BIGINT,

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_sponsor_term_award
    ON archive.award_sponsor_term (award_id, award_sponsor_term_id);

CREATE INDEX IF NOT EXISTS ix_award_sponsor_term_lookup
    ON archive.award_sponsor_term (sponsor_term_id);


CREATE TABLE IF NOT EXISTS archive.award_report_term (
    award_report_term_id      BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50),
    sequence_number           INTEGER,

    report_class_code         VARCHAR(50),
    report_code                VARCHAR(50),
    frequency_code             VARCHAR(50),
    frequency_base_code        VARCHAR(50),
    osp_distribution_code      VARCHAR(50),
    due_date                   DATE,

    source_update_timestamp    TIMESTAMP,
    source_update_user         VARCHAR(100),
    source_version_number      BIGINT,

    loaded_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                    BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_report_term_award
    ON archive.award_report_term (award_id, award_report_term_id);


CREATE TABLE IF NOT EXISTS archive.award_report_term_recipient (
    award_report_term_recipient_id  BIGINT PRIMARY KEY,
    award_report_term_id            BIGINT NOT NULL
                                        REFERENCES archive.award_report_term(award_report_term_id)
                                        ON DELETE CASCADE,
    award_id                        BIGINT NOT NULL
                                        REFERENCES archive.award_version(award_id)
                                        ON DELETE CASCADE,
    award_number                    VARCHAR(50),
    sequence_number                 INTEGER,

    contact_id                      BIGINT,
    contact_type_code               VARCHAR(50),
    rolodex_id                      BIGINT,
    number_of_copies                INTEGER,

    source_update_timestamp         TIMESTAMP,
    source_update_user              VARCHAR(100),
    source_version_number           BIGINT,

    loaded_at                       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                         BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_report_term_recipient_award
    ON archive.award_report_term_recipient (award_id, award_report_term_recipient_id);

CREATE INDEX IF NOT EXISTS ix_award_report_term_recipient_term
    ON archive.award_report_term_recipient (award_report_term_id);
