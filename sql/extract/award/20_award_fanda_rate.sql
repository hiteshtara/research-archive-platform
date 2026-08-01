SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_IDC_RATE (archived as archive.award_fanda_rate - the business
-- object AwardFandaRate was renamed from "IDC Rate" to "F&A Rate"
-- without renaming the underlying Oracle table/columns) carries
-- AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER directly - no join needed.
-- Every column below that uses "IDC" in Oracle is aliased to its
-- authoritative Java field name ("fanda") at the SQL boundary - a
-- deliberate historical business-terminology rename, not a bug. See
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
-- IDC_RATE_TYPE_CODE is a bare lookup code (to FandaRateType/
-- IDC_RATE_TYPE) - kept unjoined.

SELECT
    air.AWARD_IDC_RATE_ID AS AWARD_FANDA_RATE_ID,
    air.AWARD_ID,
    air.AWARD_NUMBER,
    air.SEQUENCE_NUMBER,

    air.APPLICABLE_IDC_RATE AS APPLICABLE_FANDA_RATE,
    air.IDC_RATE_TYPE_CODE AS FANDA_RATE_TYPE_CODE,
    air.FISCAL_YEAR,
    air.ON_CAMPUS_FLAG,
    air.UNDERRECOVERY_OF_IDC AS UNDERRECOVERY_OF_INDIRECT_COST,
    air.SOURCE_ACCOUNT,
    air.DESTINATION_ACCOUNT,
    air.START_DATE,
    air.END_DATE,

    air.UPDATE_TIMESTAMP,
    air.UPDATE_USER,
    air.VER_NBR

FROM AWARD_IDC_RATE air

ORDER BY
    air.AWARD_ID,
    air.AWARD_IDC_RATE_ID;
