-- Award Reporting and Subaward Summary: the three real, currently-
-- unarchived tables confirmed against the upstream Kuali Coeus source
-- (org.kuali.kra.award.paymentreports.closeout.AwardCloseout ->
-- AWARD_CLOSEOUT, org.kuali.kra.award.paymentreports.paymentschedule.
-- AwardPaymentSchedule -> AWARD_PAYMENT_SCHEDULE,
-- org.kuali.kra.award.home.approvedsubawards.AwardApprovedSubaward ->
-- AWARD_APPROVED_SUBAWARDS). See
-- docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md.
--
-- All three carry AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER directly (no
-- Oracle-side join needed) and, per V1804_005 (a real upstream backfill
-- migration), SEQUENCE_NUMBER is kept in lockstep with the owning
-- AWARD row's own sequence_number - these rows belong to a specific
-- Award *version*, not the whole award_number family (unlike
-- AwardNotepad). award_id is still the correct UPSERT/family-widening
-- key: it already identifies the exact version row.
--
-- award_closeout_id, award_approved_subaward_id draw from their own
-- dedicated Oracle sequences (SEQ_AWARD_AWARD_CLOSEOUT,
-- SEQ_AWARD_APPROVED_SUBAWARD_ID); award_payment_schedule_id shares
-- SEQUENCE_AWARD_ID. All three are still safe, table-scoped UPSERT
-- conflict keys regardless of which sequence assigned them.

CREATE TABLE IF NOT EXISTS archive.award_closeout (
    award_closeout_id         BIGINT PRIMARY KEY,
    award_id                  BIGINT NOT NULL
                                  REFERENCES archive.award_version(award_id)
                                  ON DELETE CASCADE,
    award_number              VARCHAR(50),
    sequence_number           INTEGER,

    closeout_report_code      VARCHAR(10),
    closeout_report_name      VARCHAR(200),
    due_date                  DATE,
    final_submission_date     DATE,
    multiple_flag             VARCHAR(10),

    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,

    loaded_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                   BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_closeout_award
    ON archive.award_closeout (award_id, award_closeout_id);


-- award_report_term_id is a real, nullable cross-reference to
-- archive.award_report_term(award_report_term_id) (Oracle FK
-- FK3_AWARD_PAYMENT_SCHEDULE, added by upstream migration V1802_013,
-- referencing AWARD_REPORT_TERMS(AWARD_REPORT_TERMS_ID)). Deliberately
-- NOT enforced as a physical FK here: it is an optional link into a
-- table populated by a separate, earlier bundle
-- (AWARD_TERMS_DESIGN.md), not a containment relationship, and bare
-- cross-references to other business objects are treated the same way
-- elsewhere in this schema (e.g. award_sponsor_term.sponsor_term_id,
-- award_report_term_recipient.contact_id). Indexed for lookups only.
CREATE TABLE IF NOT EXISTS archive.award_payment_schedule (
    award_payment_schedule_id     BIGINT PRIMARY KEY,
    award_id                      BIGINT NOT NULL
                                      REFERENCES archive.award_version(award_id)
                                      ON DELETE CASCADE,
    award_number                  VARCHAR(50),
    sequence_number                INTEGER,

    award_report_term_id           BIGINT,
    award_report_term_description  VARCHAR(200),
    due_date                        DATE,
    amount                          NUMERIC(12, 2),
    submit_date                     DATE,
    submitted_by                    VARCHAR(20),
    submitted_by_person_id          VARCHAR(50),
    invoice_number                  VARCHAR(20),
    status_description              VARCHAR(200),
    status                          VARCHAR(10),
    report_status_code              VARCHAR(10),
    overdue                         NUMERIC(15, 5),

    source_update_timestamp         TIMESTAMP,
    source_update_user              VARCHAR(100),
    source_last_update_timestamp    TIMESTAMP,
    source_last_update_user         VARCHAR(100),
    source_version_number           BIGINT,

    loaded_at                       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                         BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_payment_schedule_award
    ON archive.award_payment_schedule (award_id, award_payment_schedule_id);

CREATE INDEX IF NOT EXISTS ix_award_payment_schedule_report_term
    ON archive.award_payment_schedule (award_report_term_id);


-- AWARD_ID is nullable at the Oracle DDL level for this one table
-- (unlike every other Award child table archived so far), but any row
-- this ETL actually extracts is, by construction, matched via
-- WHERE AWARD_ID IN (...) against the requested family - so award_id
-- is NOT NULL here without narrowing what gets archived. organization_id
-- is a bare Oracle-side code (FK to ORGANIZATION in Kuali) - kept
-- unjoined, consistent with every other bare lookup code in this schema.
CREATE TABLE IF NOT EXISTS archive.award_approved_subaward (
    award_approved_subaward_id  BIGINT PRIMARY KEY,
    award_id                    BIGINT NOT NULL
                                    REFERENCES archive.award_version(award_id)
                                    ON DELETE CASCADE,
    award_number                VARCHAR(50),
    sequence_number              INTEGER,

    organization_name           VARCHAR(200),
    organization_id              VARCHAR(20),
    amount                       NUMERIC(12, 2),

    source_update_timestamp      TIMESTAMP,
    source_update_user           VARCHAR(100),
    source_version_number        BIGINT,

    loaded_at                    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                      BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_approved_subaward_award
    ON archive.award_approved_subaward (award_id, award_approved_subaward_id);
