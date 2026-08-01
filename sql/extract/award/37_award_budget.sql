SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_BUDGET_EXT is Award's real 1:1 extension of the generic BUDGET
-- table, which is SHARED with Proposal Development budgets - the
-- INNER JOIN to AWARD_BUDGET_EXT is what excludes Proposal-only BUDGET
-- rows (BUDGET itself carries no column distinguishing "which kind of
-- budget this is"). See docs/architecture/AWARD_BUDGET_DESIGN.md.
-- AWARD_ID was added to AWARD_BUDGET_EXT by a later migration
-- (V600_046) and is NOT NULL there today. AWARD_BUDGET_STATUS_CODE/
-- AWARD_BUDGET_TYPE_CODE are denormalized via LEFT JOIN to their own
-- tiny lookup tables. previous_obligated_total (a real OJB field with
-- no DDL evidence anywhere in this checkout) and BUDGET's own
-- FINAL_VERSION_FLAG (a real DDL column with no OJB mapping at all)
-- are deliberately excluded - see the design doc's Traps.

SELECT
    abe.BUDGET_ID,
    abe.AWARD_ID,
    abe.DOCUMENT_NUMBER,

    abe.AWARD_BUDGET_STATUS_CODE,
    abs.DESCRIPTION AS AWARD_BUDGET_STATUS_DESCRIPTION,
    abe.AWARD_BUDGET_TYPE_CODE,
    abt.DESCRIPTION AS AWARD_BUDGET_TYPE_DESCRIPTION,

    b.VERSION_NUMBER AS BUDGET_VERSION_NUMBER,
    b.BUDGET_NAME AS NAME,
    abe.DESCRIPTION,
    abe.BUDGET_INITIATOR,

    b.START_DATE,
    b.END_DATE,

    b.TOTAL_COST,
    b.TOTAL_DIRECT_COST,
    b.TOTAL_INDIRECT_COST,
    b.TOTAL_COST_LIMIT,
    b.COST_SHARING_AMOUNT,
    b.UNDERRECOVERY_AMOUNT,
    b.RESIDUAL_FUNDS,
    abe.OBLIGATED_AMOUNT,
    abe.OBLIGATED_TOTAL,

    b.OH_RATE_CLASS_CODE,
    b.OH_RATE_TYPE_CODE,
    b.UR_RATE_CLASS_CODE,
    b.MODULAR_BUDGET_FLAG,
    b.ON_OFF_CAMPUS_FLAG,
    b.SUBMIT_COST_SHARING AS SUBMIT_COST_SHARING_FLAG,
    b.PARENT_DOCUMENT_TYPE_CODE,
    b.BUDGET_ADJUSTMENT_DOC_NBR AS BUDGET_ADJUSTMENT_DOCUMENT_NUMBER,

    b.COMMENTS,
    b.BUDGET_JUSTIFICATION,

    abe.UPDATE_TIMESTAMP,
    abe.UPDATE_USER,
    abe.VER_NBR

FROM AWARD_BUDGET_EXT abe
JOIN BUDGET b ON b.BUDGET_ID = abe.BUDGET_ID

LEFT JOIN AWARD_BUDGET_STATUS abs
       ON abs.AWARD_BUDGET_STATUS_CODE = abe.AWARD_BUDGET_STATUS_CODE

LEFT JOIN AWARD_BUDGET_TYPE abt
       ON abt.AWARD_BUDGET_TYPE_CODE = abe.AWARD_BUDGET_TYPE_CODE

ORDER BY abe.AWARD_ID, abe.BUDGET_ID;
