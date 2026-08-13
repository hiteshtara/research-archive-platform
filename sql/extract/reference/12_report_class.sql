SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 11 rows as of live verification against
-- BU's real Oracle instance. The FK target of
-- archive.award_report_term.report_class_code.

SELECT
    rc.REPORT_CLASS_CODE,
    rc.DESCRIPTION,
    rc.GENERATE_REPORT_REQUIREMENTS,
    rc.ACTIVE_FLAG,

    rc.UPDATE_TIMESTAMP,
    rc.UPDATE_USER,
    rc.VER_NBR

FROM REPORT_CLASS rc

ORDER BY rc.REPORT_CLASS_CODE;
