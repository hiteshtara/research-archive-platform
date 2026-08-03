SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load: ~5,115 rows as of live verification. UNIT
-- is Kuali's shared organizational-unit master, referenced (never
-- duplicated) by Award.LEAD_UNIT_NUMBER - already archived on
-- archive.award_version.lead_unit_number/lead_unit_name, this table
-- adds PARENT_UNIT_NUMBER/ORGANIZATION_ID/ACTIVE_FLAG for Unit Details
-- and for resolving Central Administration Contacts (see
-- docs/architecture/AWARD_CONTACTS_DESIGN.md). ACTIVE_FLAG confirmed
-- live as CHAR(1) 'Y'/'N' - not present in the generic open-source
-- Kuali Coeus reference schema, a real BU-specific column.

SELECT
    u.UNIT_NUMBER,
    u.UNIT_NAME,
    u.PARENT_UNIT_NUMBER,
    u.ORGANIZATION_ID,
    u.ACTIVE_FLAG,

    u.UPDATE_TIMESTAMP,
    u.UPDATE_USER,
    u.VER_NBR

FROM UNIT u

ORDER BY u.UNIT_NUMBER;
