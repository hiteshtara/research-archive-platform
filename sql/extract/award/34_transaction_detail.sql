SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- TRANSACTION_DETAILS is the durable, permanent history ledger (as
-- opposed to PENDING_TRANSACTIONS' working state) - one or more rows
-- written per approved PendingTransaction, classified PRIMARY/
-- INTERMEDIATE/DATE via TRANSACTION_DETAIL_TYPE (plain text, no lookup
-- table). AWARD_NUMBER/SEQUENCE_NUMBER here are the CURRENT/root
-- Award's own version at approval time, not necessarily the version of
-- SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER specifically. TRANSACTION_ID
-- is a soft reference to the originating PendingTransaction (confirmed
-- only via Java - ActivePendingTransactionsServiceImpl - no Oracle
-- constraint) - NOT the same concept as AWARD_AMOUNT_TRANSACTION's own
-- confusingly-named VARCHAR2 "TRANSACTION_ID" column (see
-- 35_award_amount_transaction.sql).

SELECT
    td.TRANSACTION_DETAIL_ID,
    td.AWARD_NUMBER,
    td.SEQUENCE_NUMBER,
    td.TRANSACTION_ID,
    td.TNM_DOCUMENT_NUMBER AS TIME_AND_MONEY_DOCUMENT_NUMBER,
    td.SOURCE_AWARD_NUMBER,
    td.DESTINATION_AWARD_NUMBER,

    td.OBLIGATED_AMOUNT,
    td.OBLIGATED_DIRECT_AMOUNT,
    td.OBLIGATED_INDIRECT_AMOUNT,
    td.ANTICIPATED_AMOUNT,
    td.ANTICIPATED_DIRECT_AMOUNT,
    td.ANTICIPATED_INDIRECT_AMOUNT,

    td.COMMENTS,
    td.TRANSACTION_DETAIL_TYPE,

    td.UPDATE_TIMESTAMP,
    td.UPDATE_USER,
    td.VER_NBR

FROM TRANSACTION_DETAILS td

ORDER BY td.AWARD_NUMBER, td.SEQUENCE_NUMBER, td.TRANSACTION_DETAIL_ID;
