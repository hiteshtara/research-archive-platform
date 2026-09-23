SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load: 7,246 rows as of live verification
-- (2026-09-22). SPONSOR is Kuali's shared sponsor master, referenced -
-- never duplicated - by Award (already denormalized onto
-- archive.award_version.sponsor_name/prime_sponsor_name at extract time)
-- and, from V079, by Negotiation, which previously had no way to resolve
-- NEGOTIATION_UNASSOC_DETAIL.SPONSOR_CODE to a readable name.
--
-- ACTV_IND is VARCHAR2(1) in BU's real schema ('Y'/'N'), unlike UNIT's
-- ACTIVE_FLAG which is CHAR(1); both are mapped to a BOOLEAN `active`
-- on the archive side by the loader.
--
-- External registry identifiers (DUNS/CAGE/DODAC/UEI/SAM), address
-- fields and ROLODEX_ID are intentionally not selected - see V079's
-- header for why.

SELECT
    s.SPONSOR_CODE,
    s.SPONSOR_NAME,
    s.ACRONYM,
    s.SPONSOR_TYPE_CODE,
    s.ACTV_IND,

    s.UPDATE_TIMESTAMP,
    s.UPDATE_USER,
    s.VER_NBR

FROM SPONSOR s

ORDER BY s.SPONSOR_CODE;
