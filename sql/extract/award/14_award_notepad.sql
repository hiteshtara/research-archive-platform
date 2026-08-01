SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_NOTEPAD carries AWARD_ID/AWARD_NUMBER directly - no join
-- needed, same flat shape as 05_award_custom_data.sql/
-- 09_award_sponsor_terms.sql. AWARD_NOTEPAD_ID is already singular
-- matching the archive column name exactly - no alias needed (see
-- 10_award_report_terms.sql's AWARD_REPORT_TERMS_ID for the class of
-- bug this was double-checked against). There is deliberately no
-- SEQUENCE_NUMBER column selected here - AWARD_NOTEPAD has none; notes
-- are scoped to the whole award_number family, not a version - see
-- docs/architecture/AWARD_NOTEPAD_DESIGN.md.

SELECT
    an.AWARD_NOTEPAD_ID,
    an.AWARD_ID,
    an.AWARD_NUMBER,
    an.ENTRY_NUMBER,

    an.NOTE_TOPIC,
    an.COMMENTS,
    an.RESTRICTED_VIEW,

    an.CREATE_TIMESTAMP,
    an.CREATE_USER,
    an.UPDATE_TIMESTAMP,
    an.UPDATE_USER,
    an.VER_NBR

FROM AWARD_NOTEPAD an

ORDER BY
    an.AWARD_ID,
    an.AWARD_NOTEPAD_ID;
