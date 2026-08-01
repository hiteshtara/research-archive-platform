SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- TRANSACTION_ID/ORIGINATING_AWARD_VERSION added for the Award Time
-- and Money bundle - see V048 and
-- docs/architecture/AWARD_TIME_AND_MONEY_DESIGN.md. TRANSACTION_ID
-- here is a real numeric surrogate key tracing back to a specific
-- PENDING_TRANSACTIONS/TRANSACTION_DETAILS row - NOT the same concept
-- as AWARD_AMOUNT_TRANSACTION's own confusingly-named VARCHAR2
-- "TRANSACTION_ID" column (archived separately as
-- archive.award_amount_transaction.document_number). Multiple
-- AWARD_AMOUNT_INFO rows can legitimately share one TRANSACTION_ID -
-- one PendingTransaction can fan out into several AwardAmountInfo rows
-- (one per Award hop along the hierarchy path, and potentially once
-- each for a pending and an active Award version) - never assume or
-- enforce a 1:1 mapping here.

SELECT
    aai.AWARD_AMOUNT_INFO_ID,
    aai.AWARD_ID,
    aai.AWARD_NUMBER,
    aai.SEQUENCE_NUMBER,

    aai.ANTICIPATED_CHANGE_DIRECT,
    aai.ANTICIPATED_CHANGE_INDIRECT,

    aai.ANTICIPATED_TOTAL_DIRECT,
    aai.ANTICIPATED_TOTAL_INDIRECT,

    aai.OBLIGATED_TOTAL_DIRECT,
    aai.OBLIGATED_TOTAL_INDIRECT,

    NVL(aai.ANTICIPATED_TOTAL_DIRECT, 0)
      + NVL(aai.ANTICIPATED_TOTAL_INDIRECT, 0)
        AS ANTICIPATED_TOTAL_AMOUNT,

    NVL(aai.OBLIGATED_TOTAL_DIRECT, 0)
      + NVL(aai.OBLIGATED_TOTAL_INDIRECT, 0)
        AS OBLIGATED_TOTAL_AMOUNT,

    aai.TNM_DOCUMENT_NUMBER,
    aai.TRANSACTION_ID,
    aai.ORIGINATING_AWARD_VERSION,
    aai.VER_NBR

FROM AWARD_AMOUNT_INFO aai

ORDER BY
    aai.AWARD_NUMBER,
    aai.SEQUENCE_NUMBER,
    aai.AWARD_AMOUNT_INFO_ID;
