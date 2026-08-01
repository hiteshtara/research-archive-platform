SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_BUDGET_LIMIT is standalone and Award-specific already (unlike
-- every other table in this bundle, it is NOT shared with Proposal
-- Development) - AWARD_ID is a direct, Oracle-enforced FK column
-- (V310_3_066), so no join is needed to resolve or confirm it. See
-- docs/architecture/AWARD_BUDGET_DESIGN.md.

SELECT
    abl.BUDGET_LIMIT_ID,
    abl.AWARD_ID,
    abl.BUDGET_ID,
    abl.LIMIT_TYPE AS LIMIT_TYPE_CODE,
    abl.LIMIT_AMOUNT,

    abl.UPDATE_TIMESTAMP,
    abl.UPDATE_USER,
    abl.VER_NBR

FROM AWARD_BUDGET_LIMIT abl

ORDER BY abl.AWARD_ID, abl.BUDGET_LIMIT_ID;
