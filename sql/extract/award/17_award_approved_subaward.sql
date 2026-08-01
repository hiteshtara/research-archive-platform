SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_APPROVED_SUBAWARDS carries AWARD_ID/AWARD_NUMBER/
-- SEQUENCE_NUMBER directly - no join needed. AWARD_APPROVED_SUBAWARD_ID
-- is already singular matching the archive column name exactly - no
-- alias needed (see 10_award_report_terms.sql's
-- AWARD_REPORT_TERMS_ID for the class of bug this was double-checked
-- against). AWARD_ID is nullable at the Oracle DDL level for this one
-- table (unlike every other Award child table archived so far) - the
-- WHERE AWARD_ID IN (...) bind-variable filter this query is wrapped
-- in at read time means any row actually extracted already has a
-- non-null award_id. ORGANIZATION_ID is a bare Oracle-side code (FK to
-- ORGANIZATION in Kuali) - kept unjoined, consistent with every other
-- bare lookup code in this schema. See
-- docs/architecture/AWARD_REPORTING_SUBAWARD_SUMMARY_DESIGN.md.

SELECT
    aas.AWARD_APPROVED_SUBAWARD_ID,
    aas.AWARD_ID,
    aas.AWARD_NUMBER,
    aas.SEQUENCE_NUMBER,

    aas.ORGANIZATION_NAME,
    aas.ORGANIZATION_ID,
    aas.AMOUNT,

    aas.UPDATE_TIMESTAMP,
    aas.UPDATE_USER,
    aas.VER_NBR

FROM AWARD_APPROVED_SUBAWARDS aas

ORDER BY
    aas.AWARD_ID,
    aas.AWARD_APPROVED_SUBAWARD_ID;
