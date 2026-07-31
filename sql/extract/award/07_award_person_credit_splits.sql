SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_PERSON_CREDIT_SPLITS has no AWARD_ID column of its own - only
-- AWARD_PERSON_ID. See 06_award_person_units.sql for why
-- AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER are denormalized through a join
-- back to AWARD_PERSONS. INV_CREDIT_TYPE_CODE is preserved without a
-- lookup join, matching the same bare-code convention already
-- established for CUSTOM_ATTRIBUTE_ID (05_award_custom_data.sql) - the
-- InvestigatorCreditType lookup has not been independently verified for
-- Award either.

SELECT
    apcs.AWARD_PERSON_CREDIT_SPLIT_ID,
    apcs.AWARD_PERSON_ID,
    ap.AWARD_ID,
    ap.AWARD_NUMBER,
    ap.SEQUENCE_NUMBER,

    apcs.INV_CREDIT_TYPE_CODE,
    apcs.CREDIT,

    apcs.UPDATE_TIMESTAMP,
    apcs.UPDATE_USER,
    apcs.VER_NBR

FROM AWARD_PERSON_CREDIT_SPLITS apcs
JOIN AWARD_PERSONS ap
    ON apcs.AWARD_PERSON_ID = ap.AWARD_PERSON_ID

ORDER BY
    ap.AWARD_ID,
    apcs.AWARD_PERSON_CREDIT_SPLIT_ID;
