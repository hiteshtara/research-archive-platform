SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- PENDING_TRANSACTIONS has no bare AWARD_NUMBER column - only
-- SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER. A transaction belongs
-- to a loaded Award if it appears on EITHER side - read via
-- OracleDataSource.read_filtered_any_column (WHERE SOURCE_AWARD_NUMBER
-- IN (...) OR DESTINATION_AWARD_NUMBER IN (...)), still exactly one
-- Oracle read for this table per batch. This is in-flight/working
-- state for an unapproved-or-just-approved Time and Money document -
-- whether Oracle retains rows indefinitely after PROCESSED_FLAG='Y' is
-- an open question, not resolved here; see
-- docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md. TRANSACTION_ID here
-- is a real numeric surrogate key - NOT the same concept as
-- AWARD_AMOUNT_TRANSACTION's own confusingly-named VARCHAR2
-- "TRANSACTION_ID" column (see 35_award_amount_transaction.sql).

SELECT
    pt.TRANSACTION_ID,
    pt.DOCUMENT_NUMBER,
    pt.SOURCE_AWARD_NUMBER,
    pt.DESTINATION_AWARD_NUMBER,

    pt.OBLIGATED_AMOUNT,
    pt.OBLIGATED_DIRECT_AMOUNT,
    pt.OBLIGATED_INDIRECT_AMOUNT,
    pt.ANTICIPATED_AMOUNT,
    pt.ANTICIPATED_DIRECT_AMOUNT,
    pt.ANTICIPATED_INDIRECT_AMOUNT,

    pt.COMMENTS,
    pt.PROCESSED_FLAG,
    pt.SINGLE_NODE_TRANS AS SINGLE_NODE_TRANSACTION,

    pt.UPDATE_TIMESTAMP,
    pt.UPDATE_USER,
    pt.VER_NBR

FROM PENDING_TRANSACTIONS pt

ORDER BY pt.TRANSACTION_ID;
