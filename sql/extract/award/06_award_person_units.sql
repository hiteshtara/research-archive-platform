SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_PERSON_UNITS has no AWARD_ID column of its own - only
-- AWARD_PERSON_ID. AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER are
-- denormalized through this join back to AWARD_PERSONS so the loader's
-- existing family-scoped reader (read_award_children_matching_award_ids)
-- can filter these rows the same way it already does for
-- award_amount_info/award_person/award_funding_proposal/
-- award_custom_data. Inner JOIN, not LEFT JOIN: AWARD_PERSON_ID is
-- NOT NULL on this table in the Kuali OJB mapping.

SELECT
    apu.AWARD_PERSON_UNIT_ID,
    apu.AWARD_PERSON_ID,
    ap.AWARD_ID,
    ap.AWARD_NUMBER,
    ap.SEQUENCE_NUMBER,

    apu.UNIT_NUMBER,
    apu.LEAD_UNIT_FLAG,

    apu.UPDATE_TIMESTAMP,
    apu.UPDATE_USER,
    apu.VER_NBR

FROM AWARD_PERSON_UNITS apu
JOIN AWARD_PERSONS ap
    ON apu.AWARD_PERSON_ID = ap.AWARD_PERSON_ID

ORDER BY
    ap.AWARD_ID,
    apu.AWARD_PERSON_UNIT_ID;
