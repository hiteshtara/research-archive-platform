SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_APPROVED_EQUIPMENT carries AWARD_ID/AWARD_NUMBER/
-- SEQUENCE_NUMBER directly - no join needed.
-- AWARD_APPROVED_EQUIPMENT_ID is already singular matching the
-- archive column name exactly - no alias needed. No lookups, no
-- hierarchical child rows - a plain, flat data table. See
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.

SELECT
    aae.AWARD_APPROVED_EQUIPMENT_ID,
    aae.AWARD_ID,
    aae.AWARD_NUMBER,
    aae.SEQUENCE_NUMBER,

    aae.ITEM,
    aae.MODEL,
    aae.VENDOR,
    aae.AMOUNT,

    aae.UPDATE_TIMESTAMP,
    aae.UPDATE_USER,
    aae.VER_NBR

FROM AWARD_APPROVED_EQUIPMENT aae

ORDER BY
    aae.AWARD_ID,
    aae.AWARD_APPROVED_EQUIPMENT_ID;
