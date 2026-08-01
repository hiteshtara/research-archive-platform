SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_AMT_FNA_DISTRIBUTION: real child of BOTH Award and
-- AwardAmountInfo - an explicit OJB reference-descriptor FK to
-- AWARD_AMOUNT_INFO_ID confirms this, unlike most other Time and Money
-- relationships in this bundle. AWARD_ID is native (no join needed to
-- resolve it), and is nullable in Oracle's own base DDL. BUDGET_PERIOD
-- here is NUMBER(3) - a different physical type than
-- pending_transaction_extension.budget_period's own VARCHAR2(30); do
-- not assume the two can be joined or compared without normalizing
-- type first. AWARD_AMT_FNA_DISTRIBUTION_ID is aliased to the
-- authoritative Java field name, award_direct_fanda_distribution_id
-- (org.kuali.kra.award.timeandmoney.AwardDirectFandADistribution),
-- the same historical-business-terminology-rename treatment already
-- used for AWARD_EXEMPT_NUMBER_ID/20_award_fanda_rate.sql.

SELECT
    afd.AWARD_AMT_FNA_DISTRIBUTION_ID AS AWARD_DIRECT_FANDA_DISTRIBUTION_ID,
    afd.AWARD_ID,
    afd.AWARD_NUMBER,
    afd.SEQUENCE_NUMBER,
    afd.AMOUNT_SEQUENCE_NUMBER,
    afd.AWARD_AMOUNT_INFO_ID,
    afd.BUDGET_PERIOD,
    afd.START_DATE,
    afd.END_DATE,
    afd.DIRECT_COST,
    afd.INDIRECT_COST,

    afd.UPDATE_TIMESTAMP,
    afd.UPDATE_USER,
    afd.VER_NBR

FROM AWARD_AMT_FNA_DISTRIBUTION afd

ORDER BY afd.AWARD_ID, afd.AWARD_AMT_FNA_DISTRIBUTION_ID;
