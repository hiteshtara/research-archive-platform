SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWD_BGT_PER_SUM_CALC_AMT is standalone (no generic Proposal-shared
-- counterpart) - joined through BUDGET_PERIODS to AWARD_BUDGET_EXT
-- purely to resolve AWARD_ID for filtering and to confirm this period
-- belongs to an Award (not Proposal) budget. This one table serves
-- TWO logical roles distinguished only by RATE_CLASS_TYPE ('E' =
-- fringe/employee-benefit amounts, 'O' = F&A/overhead amounts) - kept
-- as one table here, matching Kuali's own single-table, query-filtered
-- design. See docs/architecture/AWARD_BUDGET_DESIGN.md.

SELECT
    sca.AWD_BGT_PER_SUM_CALC_AMT_ID AS AWARD_BUDGET_PERIOD_SUMMARY_CALCULATED_AMOUNT_ID,
    sca.BUDGET_PERIOD_ID,
    abe.AWARD_ID,

    sca.COST_ELEMENT,
    sca.ON_OFF_CAMPUS_FLAG,
    sca.RATE_CLASS_TYPE,
    sca.CALCULATED_COST,
    sca.CALCULATED_COST_SHARING,

    sca.UPDATE_TIMESTAMP,
    sca.UPDATE_USER,
    sca.VER_NBR

FROM AWD_BGT_PER_SUM_CALC_AMT sca
JOIN BUDGET_PERIODS bp ON bp.BUDGET_PERIOD_NUMBER = sca.BUDGET_PERIOD_ID
JOIN AWARD_BUDGET_EXT abe ON abe.BUDGET_ID = bp.BUDGET_ID

ORDER BY abe.AWARD_ID, sca.BUDGET_PERIOD_ID, sca.AWD_BGT_PER_SUM_CALC_AMT_ID;
