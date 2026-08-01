SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Minimal, narrowly-scoped candidate-enumeration query for
-- --create-batch's production selection mode only - deliberately NOT
-- part of the 48-table Award extraction/load sequence (hence no NN_
-- prefix; it never populates any archive.* table). ORDER BY AWARD_ID
-- (unlike 01_award_versions.sql, which is ORDER BY AWARD_NUMBER,
-- SEQUENCE_NUMBER) is what makes
-- batch_framework.select_distinct_ascending_from_oracle_batches's
-- early-stop optimization correct here: since Oracle already returns
-- rows in ascending AWARD_ID order, collecting the first N
-- non-excluded distinct values really does give the N globally-
-- smallest eligible award_ids, without ever scanning the rest of the
-- table - the "avoid loading the entire population into memory"
-- requirement a production --create-batch call needs. See
-- docs/architecture/AWARD_BATCH_PRODUCTION_SELECTION_DESIGN.md.

SELECT
    a.AWARD_ID

FROM AWARD a

ORDER BY a.AWARD_ID;
