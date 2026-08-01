SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- TIME_AND_MONEY_DOCUMENT is a real KEW workflow document, the same
-- shape as AWARD_DOCUMENT - DOCUMENT_NUMBER is a KEW-assigned string,
-- not a surrogate sequence. AWARD_NUMBER is the Award family this
-- document was raised against - kept unaliased here (renamed to
-- root_award_number, the Java field name, inside
-- prepare_time_and_money_document instead) so this table can still be
-- read via the shared read_award_children_matching_award_numbers
-- bounded reader, which filters on a literal AWARD_NUMBER column.
-- CREATION_DATE is a genuine BU customization (bu-db-equivalent
-- migration V1608_096, "Add Creation Date to T&M Document")
-- backfilled from UPDATE_TIMESTAMP when it was added - included here
-- as a real column, not a computed value.

SELECT
    tmd.DOCUMENT_NUMBER,
    tmd.AWARD_NUMBER,
    tmd.TIME_AND_MONEY_DOC_STATUS AS DOCUMENT_STATUS,
    tmd.CREATION_DATE,

    tmd.UPDATE_TIMESTAMP,
    tmd.UPDATE_USER,
    tmd.VER_NBR

FROM TIME_AND_MONEY_DOCUMENT tmd

ORDER BY tmd.AWARD_NUMBER, tmd.DOCUMENT_NUMBER;
