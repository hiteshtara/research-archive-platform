SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 3 rows as of live verification against
-- BU's real Oracle instance. The FK target of
-- archive.award_report_term_recipient.contact_type_code.

SELECT
    ct.CONTACT_TYPE_CODE,
    ct.DESCRIPTION,

    ct.UPDATE_TIMESTAMP,
    ct.UPDATE_USER,
    ct.VER_NBR

FROM CONTACT_TYPE ct

ORDER BY ct.CONTACT_TYPE_CODE;
