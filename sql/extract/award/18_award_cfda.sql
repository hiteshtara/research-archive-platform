SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_CFDA carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER directly -
-- no join needed. AWARD_CFDA_ID is already singular matching the
-- archive column name exactly - no alias needed. Confirmed a REAL
-- child table (added by the upstream V1807_003__multi_cfda.sql
-- migration to let an Award carry multiple CFDA numbers), not an
-- enrichment/reference view - see
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
-- CFDA_NUMBER is a bare lookup code into the separate CFDA table -
-- kept unjoined.

SELECT
    ac.AWARD_CFDA_ID,
    ac.AWARD_ID,
    ac.AWARD_NUMBER,
    ac.SEQUENCE_NUMBER,

    ac.CFDA_NUMBER,
    ac.CFDA_DESCRIPTION,

    ac.UPDATE_TIMESTAMP,
    ac.UPDATE_USER,
    ac.VER_NBR

FROM AWARD_CFDA ac

ORDER BY
    ac.AWARD_ID,
    ac.AWARD_CFDA_ID;
