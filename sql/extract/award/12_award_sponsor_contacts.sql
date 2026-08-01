SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_SPONSOR_CONTACTS carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly - no join needed, same flat shape as
-- 05_award_custom_data.sql/09_award_sponsor_terms.sql. AWARD_SPONSOR_CONTACT_ID
-- is already singular ("CONTACT", not "CONTACTS") matching the archive
-- column name exactly - no alias needed here (see
-- 10_award_report_terms.sql's AWARD_REPORT_TERMS_ID for the class of
-- bug this was double-checked against). CONTACT_ROLE_CODE is preserved
-- without a lookup join, matching the same bare-code convention already
-- established for CUSTOM_ATTRIBUTE_ID - the ContactType lookup has not
-- been independently verified for Award either.

SELECT
    asc_.AWARD_SPONSOR_CONTACT_ID,
    asc_.AWARD_ID,
    asc_.AWARD_NUMBER,
    asc_.SEQUENCE_NUMBER,

    asc_.ROLODEX_ID,
    asc_.FULL_NAME,
    asc_.CONTACT_ROLE_CODE,

    asc_.UPDATE_TIMESTAMP,
    asc_.UPDATE_USER,
    asc_.VER_NBR

FROM AWARD_SPONSOR_CONTACTS asc_

ORDER BY
    asc_.AWARD_ID,
    asc_.AWARD_SPONSOR_CONTACT_ID;
