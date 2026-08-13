SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 54 rows as of live verification against
-- BU's real Oracle instance. The FK target of
-- archive.award_report_term.report_code (Oracle's own
-- AWARD_REPORT_TERMS.REPORT_CODE -> REPORT join).

SELECT
    r.REPORT_CODE,
    r.DESCRIPTION,
    r.FINAL_REPORT_FLAG,
    r.ACTIVE_FLAG,

    r.UPDATE_TIMESTAMP,
    r.UPDATE_USER,
    r.VER_NBR

FROM REPORT r

ORDER BY r.REPORT_CODE;
