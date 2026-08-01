SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_PAYMENT_SCHEDULE carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly - no join needed. AWARD_PAYMENT_SCHEDULE_ID is already
-- singular matching the archive column name exactly - no alias needed
-- (see 10_award_report_terms.sql's AWARD_REPORT_TERMS_ID for the class
-- of bug this was double-checked against). AWARD_REPORT_TERM_ID is a
-- real, nullable column (added by upstream migration V1802_013) and is
-- ALSO already singular despite the plural-named parent table/PK
-- (AWARD_REPORT_TERMS/AWARD_REPORT_TERMS_ID) it optionally references -
-- confirmed directly against that ALTER TABLE statement, not assumed.
-- LAST_UPDATE_USER/LAST_UPDATE_TIMESTAMP (added by upstream migration
-- V320_123) are a second, distinct audit-stamp pair from UPDATE_USER/
-- UPDATE_TIMESTAMP - both are real persisted columns, kept separate in
-- the archive rather than collapsed into one. AWARD_REPORT_TERM_DESC
-- and LAST_UPDATE_TIMESTAMP/LAST_UPDATE_USER are aliased below to their
-- archive column names directly, since they are specific to this one
-- table (unlike UPDATE_TIMESTAMP/UPDATE_USER/VER_NBR, which every
-- Award child table shares and which are renamed generically via
-- _CHILD_COLUMN_RENAMES instead). See
-- docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md.

SELECT
    aps.AWARD_PAYMENT_SCHEDULE_ID,
    aps.AWARD_ID,
    aps.AWARD_NUMBER,
    aps.SEQUENCE_NUMBER,

    aps.AWARD_REPORT_TERM_ID,
    aps.AWARD_REPORT_TERM_DESC AS AWARD_REPORT_TERM_DESCRIPTION,
    aps.DUE_DATE,
    aps.AMOUNT,
    aps.SUBMIT_DATE,
    aps.SUBMITTED_BY,
    aps.SUBMITTED_BY_PERSON_ID,
    aps.INVOICE_NUMBER,
    aps.STATUS_DESCRIPTION,
    aps.STATUS,
    aps.REPORT_STATUS_CODE,
    aps.OVERDUE,

    aps.UPDATE_TIMESTAMP,
    aps.UPDATE_USER,
    aps.LAST_UPDATE_TIMESTAMP AS SOURCE_LAST_UPDATE_TIMESTAMP,
    aps.LAST_UPDATE_USER AS SOURCE_LAST_UPDATE_USER,
    aps.VER_NBR

FROM AWARD_PAYMENT_SCHEDULE aps

ORDER BY
    aps.AWARD_ID,
    aps.AWARD_PAYMENT_SCHEDULE_ID;
