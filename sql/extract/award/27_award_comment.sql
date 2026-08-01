SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_COMMENT carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly (all nullable at the Oracle DDL level - the WHERE AWARD_ID
-- IN (...) bind-variable filter this query is wrapped in at read time
-- means any row actually extracted already has a non-null award_id) -
-- no join needed. AWARD_COMMENT_ID is already singular matching the
-- archive column name exactly - no alias needed. Confirmed distinct
-- from AWARD_NOTEPAD: a specific-Award-version-scoped record (real
-- backfilled SEQUENCE_NUMBER), not a whole-family one. See
-- docs/architecture/AWARD_COMMENT_DESIGN.md.
-- COMMENT_TYPE_CODE is a bare lookup code into the separate
-- COMMENT_TYPE table - kept unjoined.

SELECT
    ac.AWARD_COMMENT_ID,
    ac.AWARD_ID,
    ac.AWARD_NUMBER,
    ac.SEQUENCE_NUMBER,

    ac.COMMENT_TYPE_CODE,
    ac.CHECKLIST_PRINT_FLAG,
    ac.COMMENTS,

    ac.UPDATE_TIMESTAMP,
    ac.UPDATE_USER,
    ac.VER_NBR

FROM AWARD_COMMENT ac

ORDER BY
    ac.AWARD_ID,
    ac.AWARD_COMMENT_ID;
