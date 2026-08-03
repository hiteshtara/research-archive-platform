SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load: 11 rows as of live verification against
-- BU's real Oracle instance. DEFAULT_GROUP_FLAG='C' is the exact filter
-- Award.initCentralAdminContacts() applies in Java - see
-- docs/architecture/AWARD_CONTACTS_DESIGN.md. Real BU codes confirmed
-- live (differ from generic Kuali Coeus demo seed data): 3=OSP
-- Administrator, 4=PAFO Administrator, both group 'C'.

SELECT
    uat.UNIT_ADMINISTRATOR_TYPE_CODE,
    uat.DESCRIPTION,
    uat.DEFAULT_GROUP_FLAG,
    uat.MULTIPLES_FLAG,

    uat.UPDATE_TIMESTAMP,
    uat.UPDATE_USER,
    uat.VER_NBR

FROM UNIT_ADMINISTRATOR_TYPE uat

ORDER BY uat.UNIT_ADMINISTRATOR_TYPE_CODE;
