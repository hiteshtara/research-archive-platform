SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- SUBCONTRACTING_BUD is the one structural exception in this bundle:
-- its own Oracle primary key IS award_number itself - no surrogate
-- ID, no AWARD_ID, no SEQUENCE_NUMBER exist for it at all, and there
-- is no Oracle-level FK to AWARD. Read via
-- read_award_children_matching_award_numbers (filters
-- WHERE AWARD_NUMBER IN (...)), not the shared award_id-based bounded
-- reader every other table in this schema uses - see
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
-- Goal-amount columns are aliased to their authoritative Java field
-- names (e.g. SDB_GOAL -> EIGHT_A_DISADVANTAGE_GOAL_AMOUNT, HBCU_GOAL
-- -> HISTORICAL_BLACK_COLLEGE_GOAL_AMOUNT) for readability.

SELECT
    sb.AWARD_NUMBER,

    sb.LARGE_BUSINESS_GOAL AS LARGE_BUSINESS_GOAL_AMOUNT,
    sb.SMALL_BUSINESS_GOAL AS SMALL_BUSINESS_GOAL_AMOUNT,
    sb.WOMAN_OWNED_GOAL AS WOMAN_OWNED_GOAL_AMOUNT,
    sb.SDB_GOAL AS EIGHT_A_DISADVANTAGE_GOAL_AMOUNT,
    sb.HUB_ZONE_GOAL AS HUB_ZONE_GOAL_AMOUNT,
    sb.VETERAN_OWNED_GOAL AS VETERAN_OWNED_GOAL_AMOUNT,
    sb.SDV_GOAL AS SERVICE_DISABLED_VETERAN_OWNED_GOAL_AMOUNT,
    sb.HBCU_GOAL AS HISTORICAL_BLACK_COLLEGE_GOAL_AMOUNT,
    sb.COMMENTS,

    sb.UPDATE_TIMESTAMP,
    sb.UPDATE_USER,
    sb.VER_NBR

FROM SUBCONTRACTING_BUD sb

ORDER BY
    sb.AWARD_NUMBER;
