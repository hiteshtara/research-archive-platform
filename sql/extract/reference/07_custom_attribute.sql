SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 105 rows as of live verification against
-- BU's real Oracle instance. CUSTOM_ATTRIBUTE has no ACTIVE flag or
-- sort order of its own - both are properties of CUSTOM_ATTRIBUTE_DOCUMENT
-- instead (see 08_custom_attribute_document.sql), since a given
-- attribute can be active/required/sorted differently per KEW document
-- type it appears on. DATA_TYPE_DESCRIPTION is denormalized from the
-- small CUSTOM_ATTRIBUTE_DATA_TYPE lookup (5 rows: String/Number/Date/
-- Boolean/Long String) at extraction time.

SELECT
    ca.ID AS CUSTOM_ATTRIBUTE_ID,
    ca.NAME,
    ca.LABEL,
    ca.DATA_TYPE_CODE,
    cadt.DESCRIPTION AS DATA_TYPE_DESCRIPTION,
    ca.GROUP_NAME,

    ca.UPDATE_TIMESTAMP,
    ca.UPDATE_USER,
    ca.VER_NBR

FROM CUSTOM_ATTRIBUTE ca
LEFT JOIN CUSTOM_ATTRIBUTE_DATA_TYPE cadt
    ON cadt.DATA_TYPE_CODE = ca.DATA_TYPE_CODE

ORDER BY ca.ID;
