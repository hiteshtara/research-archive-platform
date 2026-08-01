SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_TRANSMISSION_CHILD is a BU-specific SAP-integration history
-- table (edu.bu.kuali.kra.bo.AwardTransmissionChild) representing one
-- hierarchy-child Award included in a specific transmission attempt.
-- AWARD_ID here is the CHILD Award, which routinely belongs to a
-- DIFFERENT award_number family than the parent AWARD_TRANSMISSION
-- row's own AWARD_ID - joined back to AWARD only to denormalize
-- SEQUENCE_NUMBER (AWARD_NUMBER is already a bare column on this
-- table and is selected as-is, not overridden by the join, since the
-- two must always agree by definition). TRANSMISSION_ID is
-- deliberately NOT joined back to AWARD_TRANSMISSION here - it is
-- carried through as a bare value, since the parent transmission's
-- own root Award family may be loaded in a separate
-- --load-award-id/--load-batch call entirely (see
-- docs/architecture/SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md).
-- OVERHEAD_KEY/BASE_CODE/OFF_CAMPUS are the actual F&A rate basis
-- values used for this transmission - passed through unmodified, per
-- the assessment's finding that these are frequently not
-- reconstructable from current Budget data at all.

SELECT
    atc.TRANSMISSION_CHILD_ID,
    atc.TRANSMISSION_ID,
    atc.AWARD_ID,
    atc.AWARD_NUMBER,
    a.SEQUENCE_NUMBER,

    atc.PARENT_DOC_NBR AS PARENT_DOCUMENT_NUMBER,
    atc.CHILD_DOC_NBR AS CHILD_DOCUMENT_NUMBER,
    atc.LEAD_UNIT_NBR AS LEAD_UNIT_NUMBER,
    atc.CHILD_TYPE,

    atc.OVERHEAD_KEY,
    atc.BASE_CODE,
    atc.OFF_CAMPUS,

    atc.UPDATE_TIMESTAMP,
    atc.UPDATE_USER,
    atc.VER_NBR

FROM AWARD_TRANSMISSION_CHILD atc
JOIN AWARD a ON a.AWARD_ID = atc.AWARD_ID

ORDER BY atc.AWARD_ID, atc.TRANSMISSION_CHILD_ID;
