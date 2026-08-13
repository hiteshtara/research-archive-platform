SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 8 rows as of live verification against
-- BU's real Oracle instance. The FK target of
-- archive.award_report_term.frequency_base_code.

SELECT
    fb.FREQUENCY_BASE_CODE,
    fb.DESCRIPTION,
    fb.REGENERATION_TYPE_NAME,
    fb.ACTIVE_FLAG,

    fb.UPDATE_TIMESTAMP,
    fb.UPDATE_USER,
    fb.VER_NBR

FROM FREQUENCY_BASE fb

ORDER BY fb.FREQUENCY_BASE_CODE;
