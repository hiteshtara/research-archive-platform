SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_BUDGET_DETAILS_EXT is Award's real 1:1 extension of the
-- generic BUDGET_DETAILS table, also shared with Proposal Development.
-- BUDGET_DETAILS.BUDGET_ID is a direct column, so AWARD_BUDGET_EXT is
-- joined straight off it to resolve AWARD_ID for filtering. Several
-- columns are aliased to their authoritative Java field names:
-- SUBMIT_COST_SHARING -> submit_cost_sharing_flag,
-- IS_FORMULATED_COST_ELELMENT (a real Oracle column name, misspelled
-- upstream, not a typo introduced here) -> formulated_cost_element_flag,
-- HIDE_IN_HIERARCHY -> hidden_in_hierarchy. See
-- docs/architecture/AWARD_BUDGET_DESIGN.md.

SELECT
    abde.BUDGET_DETAILS_ID AS BUDGET_LINE_ITEM_ID,
    bd.BUDGET_PERIOD_NUMBER AS BUDGET_PERIOD_ID,
    bd.BUDGET_ID,
    abe.AWARD_ID,
    bd.BUDGET_PERIOD,
    bd.LINE_ITEM_NUMBER,

    bd.BUDGET_CATEGORY_CODE,
    bd.COST_ELEMENT,
    bd.LINE_ITEM_DESCRIPTION,
    bd.GROUP_NAME,
    bd.BASED_ON_LINE_ITEM,
    bd.LINE_ITEM_SEQUENCE,

    bd.START_DATE,
    bd.END_DATE,

    bd.LINE_ITEM_COST,
    bd.COST_SHARING_AMOUNT,
    bd.UNDERRECOVERY_AMOUNT,
    abde.OBLIGATED_AMOUNT,
    bd.QUANTITY,

    bd.ON_OFF_CAMPUS_FLAG,
    bd.APPLY_IN_RATE_FLAG,
    bd.SUBMIT_COST_SHARING AS SUBMIT_COST_SHARING_FLAG,
    bd.IS_FORMULATED_COST_ELELMENT AS FORMULATED_COST_ELEMENT_FLAG,

    bd.SUBAWARD_NUMBER,
    bd.HIERARCHY_PROPOSAL_NUMBER,
    bd.HIDE_IN_HIERARCHY AS HIDDEN_IN_HIERARCHY,

    bd.BUDGET_JUSTIFICATION,

    abde.UPDATE_TIMESTAMP,
    abde.UPDATE_USER,
    abde.VER_NBR

FROM AWARD_BUDGET_DETAILS_EXT abde
JOIN BUDGET_DETAILS bd ON bd.BUDGET_DETAILS_ID = abde.BUDGET_DETAILS_ID
JOIN AWARD_BUDGET_EXT abe ON abe.BUDGET_ID = bd.BUDGET_ID

ORDER BY abe.AWARD_ID, bd.BUDGET_PERIOD_NUMBER, bd.LINE_ITEM_NUMBER;
