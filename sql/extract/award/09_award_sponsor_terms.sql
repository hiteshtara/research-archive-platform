SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_SPONSOR_TERM carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly - no join needed, same flat shape as
-- 04_award_proposals.sql/05_award_custom_data.sql. SPONSOR_TERM_ID is
-- preserved without a lookup join, matching the same bare-code
-- convention already established for CUSTOM_ATTRIBUTE_ID/
-- INV_CREDIT_TYPE_CODE - the SponsorTerm lookup has not been
-- independently verified for Award either.

SELECT
    ast.AWARD_SPONSOR_TERM_ID,
    ast.AWARD_ID,
    ast.AWARD_NUMBER,
    ast.SEQUENCE_NUMBER,

    ast.SPONSOR_TERM_ID,

    ast.UPDATE_TIMESTAMP,
    ast.UPDATE_USER,
    ast.VER_NBR

FROM AWARD_SPONSOR_TERM ast

ORDER BY
    ast.AWARD_ID,
    ast.AWARD_SPONSOR_TERM_ID;
