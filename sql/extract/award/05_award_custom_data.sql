SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- CUSTOM_ATTRIBUTE_ID is preserved without a lookup join, matching the
-- same convention already established for Negotiation's and Subaward's
-- own custom-data extraction queries - the common custom-attribute
-- lookup object has not been independently verified for Award either.

SELECT
    acd.AWARD_CUSTOM_DATA_ID,
    acd.AWARD_ID,
    acd.AWARD_NUMBER,
    acd.SEQUENCE_NUMBER,
    acd.CUSTOM_ATTRIBUTE_ID,
    acd.VALUE,
    acd.UPDATE_TIMESTAMP,
    acd.UPDATE_USER,
    acd.VER_NBR

FROM AWARD_CUSTOM_DATA acd

ORDER BY
    acd.AWARD_ID,
    acd.AWARD_CUSTOM_DATA_ID;
