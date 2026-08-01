SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_HIERARCHY is version-agnostic (no SEQUENCE_NUMBER column at
-- all) and keyed by AWARD_NUMBER, not AWARD_ID - reclassified from
-- NOT APPLICABLE, see docs/architecture/KUALI_ARCHIVE_COVERAGE.md and
-- docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md. A row belongs to a
-- loaded Award family if its own AWARD_NUMBER matches - PARENT_AWARD_NUMBER/
-- ROOT_AWARD_NUMBER/ORIGINATING_AWARD_NUMBER are kept as bare,
-- unenforced reference columns (they may point at a different Award
-- family entirely, not necessarily loaded in the same batch).

SELECT
    ah.AWARD_HIERARCHY_ID,
    ah.ROOT_AWARD_NUMBER,
    ah.AWARD_NUMBER,
    ah.PARENT_AWARD_NUMBER,
    ah.ORIGINATING_AWARD_NUMBER,
    ah.ACTIVE,

    ah.UPDATE_TIMESTAMP,
    ah.UPDATE_USER,
    ah.VER_NBR

FROM AWARD_HIERARCHY ah

ORDER BY ah.AWARD_NUMBER, ah.AWARD_HIERARCHY_ID;
