SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load - 28 rows as of live verification against
-- BU's real Oracle instance. The FK target of
-- archive.award_report_term.frequency_code. ADVANCE_NUMBER_OF_DAYS/
-- ADVANCE_NUMBER_OF_MONTHS live here (on the frequency lookup itself),
-- not on AWARD_REPORT_TERMS - live-verified null for FREQUENCY_CODE 5
-- ("As required"), a real absence of a fixed advance-notice window,
-- not a load gap.

SELECT
    f.FREQUENCY_CODE,
    f.DESCRIPTION,
    f.NUMBER_OF_DAYS,
    f.NUMBER_OF_MONTHS,
    f.REPEAT_FLAG,
    f.ADVANCE_NUMBER_OF_DAYS,
    f.ADVANCE_NUMBER_OF_MONTHS,
    f.ACTIVE_FLAG,

    f.UPDATE_TIMESTAMP,
    f.UPDATE_USER,
    f.VER_NBR

FROM FREQUENCY f

ORDER BY f.FREQUENCY_CODE;
