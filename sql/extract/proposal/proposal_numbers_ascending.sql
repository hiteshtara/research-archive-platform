SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Minimal, narrowly-scoped candidate-enumeration query for
-- --create-batch's production selection mode only - deliberately NOT
-- part of the 8-dataset Proposal extraction/load sequence (hence no
-- numeric prefix; it never populates any archive.* table). Mirrors
-- sql/extract/award/award_ids_ascending.sql exactly, except the
-- selected/ordered value is PROPOSAL_NUMBER (Proposal's natural batch
-- key - a string, not a numeric surrogate; see
-- database/migrations/V065__create_etl_batch_proposal_item.sql) rather
-- than a BIGINT id. DISTINCT is required here (unlike Award's own
-- award_ids_ascending.sql, where AWARD_ID is already unique per row):
-- PROPOSAL has one row per version, so the same PROPOSAL_NUMBER repeats
-- across every version of a family.

SELECT DISTINCT
    p.PROPOSAL_NUMBER

FROM PROPOSAL p

ORDER BY p.PROPOSAL_NUMBER;
