SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- BUDGET_PERSONS is shared with Proposal Development, exactly like
-- BUDGET/BUDGET_PERIODS/BUDGET_DETAILS/etc - but it has no
-- Award-specific "_EXT" extension table of its own. Restricted to
-- Award budgets by joining BUDGET_PERSONS -> BUDGET -> AWARD_BUDGET_EXT
-- (the INNER JOIN itself is what excludes Proposal Development's own
-- BUDGET_PERSONS rows, since neither BUDGET_PERSONS nor BUDGET carry a
-- column distinguishing "which kind of budget this is"). AWARD_ID is
-- resolved via AWARD_BUDGET_EXT purely for filtering - it is not a
-- real column on BUDGET_PERSONS itself. PROPOSAL_NUMBER and
-- VERSION_NUMBER (a real DDL column distinct from VER_NBR) are real
-- Oracle columns with no corresponding OJB field-descriptor anywhere
-- in the checkout - deliberately excluded, see
-- docs/architecture/AWARD_COMPLETENESS_REPORT.md. HIERARCHY_PROPOSAL_NUMBER
-- is a bare, unenforced cross-reference into Proposal Development's own
-- EPS_PROPOSAL table, kept as-is (not resolved further), the same
-- convention already used for archive.award_budget_line_item's own
-- hierarchy_proposal_number.

SELECT
    bp.BUDGET_ID,
    bp.PERSON_SEQUENCE_NUMBER,
    abe.AWARD_ID,

    bp.EFFECTIVE_DATE,
    bp.JOB_CODE,
    bp.NON_EMPLOYEE_FLAG,
    bp.PERSON_ID,
    bp.APPOINTMENT_TYPE_CODE,
    bp.ROLODEX_ID,
    bp.TBN_ID,
    bp.CALCULATION_BASE,
    bp.PERSON_NAME,
    bp.SALARY_ANNIVERSARY_DATE,

    bp.HIERARCHY_PROPOSAL_NUMBER,
    bp.HIDE_IN_HIERARCHY AS HIDDEN_IN_HIERARCHY,

    bp.UPDATE_TIMESTAMP,
    bp.UPDATE_USER,
    bp.VER_NBR

FROM BUDGET_PERSONS bp
JOIN BUDGET b ON b.BUDGET_ID = bp.BUDGET_ID
JOIN AWARD_BUDGET_EXT abe ON abe.BUDGET_ID = b.BUDGET_ID

ORDER BY abe.AWARD_ID, bp.BUDGET_ID, bp.PERSON_SEQUENCE_NUMBER;
