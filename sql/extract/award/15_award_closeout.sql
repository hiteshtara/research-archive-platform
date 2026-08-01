SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_CLOSEOUT carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly - no join needed, same flat shape as
-- 09_award_sponsor_terms.sql. AWARD_CLOSEOUT_ID is already singular
-- matching the archive column name exactly - no alias needed (see
-- 10_award_report_terms.sql's AWARD_REPORT_TERMS_ID for the class of
-- bug this was double-checked against). SEQUENCE_NUMBER here tracks
-- the owning AWARD row's own sequence_number (see V1804_005 in the
-- upstream Kuali migrations) - this row belongs to a specific Award
-- version, not the whole award_number family - see
-- docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md.

SELECT
    ac.AWARD_CLOSEOUT_ID,
    ac.AWARD_ID,
    ac.AWARD_NUMBER,
    ac.SEQUENCE_NUMBER,

    ac.CLOSEOUT_REPORT_CODE,
    ac.CLOSEOUT_REPORT_NAME,
    ac.DUE_DATE,
    ac.FINAL_SUBMISSION_DATE,
    ac.MULTIPLE,

    ac.UPDATE_TIMESTAMP,
    ac.UPDATE_USER,
    ac.VER_NBR

FROM AWARD_CLOSEOUT ac

ORDER BY
    ac.AWARD_ID,
    ac.AWARD_CLOSEOUT_ID;
