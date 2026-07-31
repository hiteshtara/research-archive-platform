SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_PERS_UNIT_CRED_SPLITS is two hops from Award: it carries only
-- AWARD_PERSON_UNIT_ID, not AWARD_PERSON_ID or AWARD_ID. This join
-- chains through AWARD_PERSON_UNITS up to AWARD_PERSONS to denormalize
-- AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER, same reasoning as
-- 06_award_person_units.sql/07_award_person_credit_splits.sql. Both
-- joins are inner JOINs: AWARD_PERSON_UNIT_ID and AWARD_PERSON_ID are
-- NOT NULL in the Kuali OJB mapping at every hop.

SELECT
    apucs.APU_CREDIT_SPLIT_ID AS AWARD_PERSON_UNIT_CREDIT_SPLIT_ID,
    apucs.AWARD_PERSON_UNIT_ID,
    ap.AWARD_ID,
    ap.AWARD_NUMBER,
    ap.SEQUENCE_NUMBER,

    apucs.INV_CREDIT_TYPE_CODE,
    apucs.CREDIT,

    apucs.UPDATE_TIMESTAMP,
    apucs.UPDATE_USER,
    apucs.VER_NBR

FROM AWARD_PERS_UNIT_CRED_SPLITS apucs
JOIN AWARD_PERSON_UNITS apu
    ON apucs.AWARD_PERSON_UNIT_ID = apu.AWARD_PERSON_UNIT_ID
JOIN AWARD_PERSONS ap
    ON apu.AWARD_PERSON_ID = ap.AWARD_PERSON_ID

ORDER BY
    ap.AWARD_ID,
    apucs.APU_CREDIT_SPLIT_ID;
