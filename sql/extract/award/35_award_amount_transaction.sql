SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_AMOUNT_TRANSACTION: one row per (Time and Money document,
-- affected Award) pair. Oracle's own TRANSACTION_ID column here is
-- VARCHAR2(10), NOT the numeric transaction_id used everywhere else in
-- this bundle (PENDING_TRANSACTIONS/TRANSACTION_DETAILS/
-- AWARD_AMOUNT_INFO) - it actually stores the Time and Money DOCUMENT
-- NUMBER (confirmed by both the OJB field name, documentNumber, and
-- its matching VARCHAR2(10) width against
-- TIME_AND_MONEY_DOCUMENT.DOCUMENT_NUMBER). Aliased to DOCUMENT_NUMBER
-- here so the numeric and character "TRANSACTION_ID" concepts are
-- never exposed under the same archive field name - see
-- docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md. TRANSACTION_TYPE_CODE
-- reuses the exact same AWARD_TRANSACTION_TYPE lookup table already
-- denormalized for Award.transaction_type_code in
-- 01_award_versions.sql.

SELECT
    aat.AWARD_AMOUNT_TRANSACTION_ID,
    aat.AWARD_NUMBER,
    aat.TRANSACTION_ID AS DOCUMENT_NUMBER,
    aat.TRANSACTION_TYPE_CODE,
    att.DESCRIPTION AS TRANSACTION_TYPE_DESCRIPTION,
    aat.NOTICE_DATE,
    aat.COMMENTS,

    aat.UPDATE_TIMESTAMP,
    aat.UPDATE_USER,
    aat.VER_NBR

FROM AWARD_AMOUNT_TRANSACTION aat

LEFT JOIN AWARD_TRANSACTION_TYPE att
       ON att.AWARD_TRANSACTION_TYPE_CODE = aat.TRANSACTION_TYPE_CODE

ORDER BY aat.AWARD_NUMBER, aat.AWARD_AMOUNT_TRANSACTION_ID;
