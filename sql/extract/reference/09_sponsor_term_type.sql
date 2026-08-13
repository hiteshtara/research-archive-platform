SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 10 rows as of live verification against
-- BU's real Oracle instance. The real-world Kuali Award Terms business
-- category names (Referenced Document Terms, Invention Terms, etc.).

SELECT
    stt.SPONSOR_TERM_TYPE_CODE,
    stt.DESCRIPTION,

    stt.UPDATE_TIMESTAMP,
    stt.UPDATE_USER,
    stt.VER_NBR

FROM SPONSOR_TERM_TYPE stt

ORDER BY stt.SPONSOR_TERM_TYPE_CODE;
