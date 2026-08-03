SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load: ~998 rows / ~128 distinct person_ids as of
-- live verification. This is the exact table
-- Award.initCentralAdminContacts() queries
-- (UnitService.retrieveUnitAdministratorsByUnitNumber, an equality
-- filter on UNIT_NUMBER only - see docs/architecture/AWARD_CONTACTS_DESIGN.md
-- for the full Java trace). No ACTIVE/status column exists on this
-- table in BU's real schema either - confirmed live, matching the
-- generic Kuali Coeus schema.

SELECT
    ua.UNIT_NUMBER,
    ua.PERSON_ID,
    ua.UNIT_ADMINISTRATOR_TYPE_CODE,

    ua.UPDATE_TIMESTAMP,
    ua.UPDATE_USER,
    ua.VER_NBR

FROM UNIT_ADMINISTRATOR ua

ORDER BY ua.UNIT_NUMBER, ua.PERSON_ID, ua.UNIT_ADMINISTRATOR_TYPE_CODE;
