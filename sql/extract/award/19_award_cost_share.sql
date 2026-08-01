SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_COST_SHARE carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly (all nullable at the Oracle DDL level, unlike most Award
-- child tables - the WHERE AWARD_ID IN (...) bind-variable filter
-- this query is wrapped in at read time means any row actually
-- extracted already has a non-null award_id). AWARD_COST_SHARE_ID is
-- already singular matching the archive column name exactly - no
-- alias needed. No hierarchical child rows exist for this table. See
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
-- UNIT_NUMBER and COST_SHARE_TYPE_CODE are bare lookup codes (to Unit
-- and CostShareType respectively) - kept unjoined.
--
-- FISCAL_YEAR is deliberately NOT selected here: the generic Kuali
-- Coeus source tree's bootstrap DDL shows a FISCAL_YEAR column on
-- AWARD_COST_SHARE, but real BU Oracle does not have one - confirmed
-- against the actual BU schema, not the generic source tree, per the
-- discipline established throughout this project of trusting real
-- DDL over any single source. archive.award_cost_share.fiscal_year
-- (added by V044) is left in place as a harmless, always-null column
-- rather than rewriting an already-shipped migration - see
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.

SELECT
    acs.AWARD_COST_SHARE_ID,
    acs.AWARD_ID,
    acs.AWARD_NUMBER,
    acs.SEQUENCE_NUMBER,

    acs.PROJECT_PERIOD,
    acs.COST_SHARE_PERCENTAGE,
    acs.COST_SHARE_TYPE_CODE,
    acs.UNIT_NUMBER,
    acs.SOURCE,
    acs.DESTINATION,
    acs.COMMITMENT_AMOUNT,
    acs.COST_SHARE_MET,
    acs.VERIFICATION_DATE,

    acs.UPDATE_TIMESTAMP,
    acs.UPDATE_USER,
    acs.VER_NBR

FROM AWARD_COST_SHARE acs

ORDER BY
    acs.AWARD_ID,
    acs.AWARD_COST_SHARE_ID;
