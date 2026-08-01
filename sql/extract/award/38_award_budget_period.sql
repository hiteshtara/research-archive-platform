SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_BUDGET_PERIOD_EXT is Award's real 1:1 extension of the
-- generic BUDGET_PERIODS table, also shared with Proposal Development.
-- Joined through to AWARD_BUDGET_EXT purely to resolve AWARD_ID for
-- filtering (AWARD_BUDGET_PERIOD_EXT/BUDGET_PERIODS carry no AWARD_ID
-- column of their own) - see
-- docs/architecture/AWARD_BUDGET_DESIGN.md. BUDGET_PERIOD_NUMBER is
-- aliased to BUDGET_PERIOD_ID (the authoritative Java field name);
-- BUDGET_PERIOD itself (the numeric period 1, 2, 3...) is a separate,
-- real column, not to be confused with the surrogate id.

SELECT
    abpe.BUDGET_PERIOD_NUMBER AS BUDGET_PERIOD_ID,
    bp.BUDGET_ID,
    abe.AWARD_ID,
    bp.BUDGET_PERIOD,

    bp.START_DATE,
    bp.END_DATE,

    bp.TOTAL_COST,
    bp.TOTAL_DIRECT_COST,
    bp.TOTAL_INDIRECT_COST,
    bp.TOTAL_COST_LIMIT,
    bp.COST_SHARING_AMOUNT,
    bp.UNDERRECOVERY_AMOUNT,
    bp.NUM_PARTICIPANTS AS NUMBER_OF_PARTICIPANTS,

    abpe.OBLIGATED_AMOUNT,
    abpe.TOTAL_FRINGE_AMOUNT,
    abpe.FRINGE_OVERRIDDEN,
    abpe.F_AND_A_OVERRIDDEN,

    bp.COMMENTS,

    abpe.UPDATE_TIMESTAMP,
    abpe.UPDATE_USER,
    abpe.VER_NBR

FROM AWARD_BUDGET_PERIOD_EXT abpe
JOIN BUDGET_PERIODS bp ON bp.BUDGET_PERIOD_NUMBER = abpe.BUDGET_PERIOD_NUMBER
JOIN AWARD_BUDGET_EXT abe ON abe.BUDGET_ID = bp.BUDGET_ID

ORDER BY abe.AWARD_ID, bp.BUDGET_ID, bp.BUDGET_PERIOD;
