SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- PENDING_TRANSACTIONS_EXTENSION (BU-specific: bu-db/BUKR-0020,
-- "add_budget_period_to_tm.sql") has only TRANSACTION_ID and
-- BUDGET_PERIOD - no AWARD_NUMBER of its own, no UPDATE_TIMESTAMP/
-- UPDATE_USER/VER_NBR at all. Joined to PENDING_TRANSACTIONS here
-- purely to denormalize SOURCE_AWARD_NUMBER/DESTINATION_AWARD_NUMBER
-- for filtering (the same join-to-denormalize pattern already used for
-- AWARD_EXTENSION/AWARD_EXEMPT_NUMBER), so this table can be read via
-- read_filtered_any_column exactly like PENDING_TRANSACTIONS itself -
-- one Oracle read per batch, no chained/dependent second read.
-- BUDGET_PERIOD is VARCHAR2(30) here - a different physical type than
-- award_direct_fanda_distribution.budget_period's own NUMBER(3); do
-- not assume the two can be joined or compared without normalizing
-- type first.

SELECT
    pte.TRANSACTION_ID,
    pte.BUDGET_PERIOD,
    pt.SOURCE_AWARD_NUMBER,
    pt.DESTINATION_AWARD_NUMBER

FROM PENDING_TRANSACTIONS_EXTENSION pte
JOIN PENDING_TRANSACTIONS pt ON pt.TRANSACTION_ID = pte.TRANSACTION_ID

ORDER BY pte.TRANSACTION_ID;
