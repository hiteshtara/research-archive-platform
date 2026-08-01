SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_TRANSMISSION is a BU-specific SAP-integration history table
-- (edu.bu.kuali.kra.bo.AwardTransmission) with no AWARD_NUMBER/
-- SEQUENCE_NUMBER columns of its own - joined back to AWARD here to
-- denormalize them, the same join-to-denormalize pattern already used
-- for AWARD_EXTENSION/AWARD_CGB. AWARD_ID is the ROOT/primary Award of
-- the transmitted hierarchy at the time of this specific attempt -
-- real BU Oracle can reassign this column in place to a later Award
-- version (AwardServiceImpl.updateTransmissionHistory), so this
-- extraction captures whatever AWARD_ID Oracle shows today, the same
-- "capture what's there now" discipline used everywhere else in this
-- project. SENT_DATA/RETURNED_DATA are selected and passed through
-- completely unmodified - no parsing, truncation, or reformatting -
-- since they are the actual historical SOAP request/response XML this
-- table exists to preserve. See
-- docs/architecture/SAP_AWARD_TRANSMISSION_ARCHIVE_DESIGN.md.

SELECT
    at.TRANSMISSION_ID,
    at.AWARD_ID,
    a.AWARD_NUMBER,
    a.SEQUENCE_NUMBER,

    at.INITIATOR_ID,
    at.TRANSMITTER_ID,
    at.SUCCESS_INDICATOR,
    at.TRANSMISSION_DATE,

    at.SENT_DATA,
    at.RETURNED_DATA,

    at.BASIS_OF_PAYMENT_CODE,
    at.ACCOUNT_TYPE_CODE,
    at.SPONSOR_CODE,
    at.METHOD_OF_PAYMENT_CODE,
    at.DOC_NBR AS DOCUMENT_NUMBER,

    at.UPDATE_TIMESTAMP,
    at.UPDATE_USER,
    at.VER_NBR

FROM AWARD_TRANSMISSION at
JOIN AWARD a ON a.AWARD_ID = at.AWARD_ID

ORDER BY at.AWARD_ID, at.TRANSMISSION_ID;
