SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_REP_TERMS_RECNT has no AWARD_ID column of its own - only
-- AWARD_REPORT_TERMS_ID. AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER are
-- denormalized through this join back to AWARD_REPORT_TERMS so the
-- loader's existing family-scoped reader
-- (read_award_children_matching_award_ids) can filter these rows the
-- same way it already does for archive.award_person_unit_credit_split.
-- Inner JOIN, not LEFT JOIN: AWARD_REPORT_TERMS_ID is NOT NULL on this
-- table in the Kuali OJB mapping. CONTACT_TYPE_CODE/ROLODEX_ID are
-- preserved without lookup joins, matching archive.award_person's own
-- bare-code convention for the same fields.

SELECT
    artr.AWARD_REP_TERMS_RECNT_ID AS AWARD_REPORT_TERM_RECIPIENT_ID,
    artr.AWARD_REPORT_TERMS_ID AS AWARD_REPORT_TERM_ID,
    art.AWARD_ID,
    art.AWARD_NUMBER,
    art.SEQUENCE_NUMBER,

    artr.CONTACT_ID,
    artr.CONTACT_TYPE_CODE,
    artr.ROLODEX_ID,
    artr.NUMBER_OF_COPIES,

    artr.UPDATE_TIMESTAMP,
    artr.UPDATE_USER,
    artr.VER_NBR

FROM AWARD_REP_TERMS_RECNT artr
JOIN AWARD_REPORT_TERMS art
    ON artr.AWARD_REPORT_TERMS_ID = art.AWARD_REPORT_TERMS_ID

ORDER BY
    art.AWARD_ID,
    artr.AWARD_REP_TERMS_RECNT_ID;
