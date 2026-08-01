SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_TRANSFERRING_SPONSOR carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly - no join needed to resolve those, same flat shape as
-- 09_award_sponsor_terms.sql. SPONSOR_NAME is denormalized via LEFT
-- JOIN SPONSOR, the same convention already used for
-- 01_award_versions.sql's own sponsor_name/prime_sponsor_name columns
-- - kept consistent here rather than leaving SPONSOR_CODE as a bare
-- code the way 09_award_sponsor_terms.sql/12_award_sponsor_contacts.sql
-- do for their own (unverified) lookup codes, since the real Sponsor
-- lookup convention is already established and proven for Award.

SELECT
    ats.AWARD_TRANSFERRING_SPONSOR_ID,
    ats.AWARD_ID,
    ats.AWARD_NUMBER,
    ats.SEQUENCE_NUMBER,

    ats.SPONSOR_CODE,
    sp.SPONSOR_NAME,

    ats.UPDATE_TIMESTAMP,
    ats.UPDATE_USER,
    ats.VER_NBR

FROM AWARD_TRANSFERRING_SPONSOR ats
LEFT JOIN SPONSOR sp ON sp.SPONSOR_CODE = ats.SPONSOR_CODE

ORDER BY
    ats.AWARD_ID,
    ats.AWARD_TRANSFERRING_SPONSOR_ID;
