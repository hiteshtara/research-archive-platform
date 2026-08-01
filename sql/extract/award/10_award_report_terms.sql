SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_REPORT_TERMS carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly - no join needed. REPORT_CLASS_CODE/REPORT_CODE/
-- FREQUENCY_CODE/FREQUENCY_BASE_CODE/OSP_DISTRIBUTION_CODE are
-- preserved without lookup joins, matching the same bare-code
-- convention already established for CUSTOM_ATTRIBUTE_ID - none of
-- these small lookups have been independently verified for Award.
--
-- The Oracle PK column is AWARD_REPORT_TERMS_ID (plural "TERMS",
-- matching the table name) even though the Kuali Java field is the
-- singular awardReportTermId (confirmed in repository-award.xml's
-- AwardReportTerm class-descriptor) - the archive column/loader field
-- is singular (award_report_term_id), matching every other table in
-- this subsystem and the FK alias already used in
-- 11_award_report_term_recipients.sql. Aliased here so the extraction
-- output matches that contract exactly.

SELECT
    art.AWARD_REPORT_TERMS_ID AS AWARD_REPORT_TERM_ID,
    art.AWARD_ID,
    art.AWARD_NUMBER,
    art.SEQUENCE_NUMBER,

    art.REPORT_CLASS_CODE,
    art.REPORT_CODE,
    art.FREQUENCY_CODE,
    art.FREQUENCY_BASE_CODE,
    art.OSP_DISTRIBUTION_CODE,
    art.DUE_DATE,

    art.UPDATE_TIMESTAMP,
    art.UPDATE_USER,
    art.VER_NBR

FROM AWARD_REPORT_TERMS art

ORDER BY
    art.AWARD_ID,
    art.AWARD_REPORT_TERMS_ID;
