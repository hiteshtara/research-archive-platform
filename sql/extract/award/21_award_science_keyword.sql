SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_SCIENCE_KEYWORD has no AWARD_NUMBER/SEQUENCE_NUMBER columns
-- of its own - the only table in the whole archived Award domain
-- (besides AWARD_SPECIAL_REVIEW below) with just AWARD_ID and nothing
-- else identifying. Joined back to AWARD here to denormalize
-- AWARD_NUMBER/SEQUENCE_NUMBER for consistency with every other
-- archived table - the same join-to-denormalize pattern already used
-- for archive.award_person_unit_credit_split and
-- archive.award_report_term_recipient. AWARD_SCIENCE_KEYWORD_ID is
-- already singular matching the archive column name exactly - no
-- alias needed. SCIENCE_KEYWORD_CODE is a genuine many-to-many bridge
-- to the shared SCIENCE_KEYWORD lookup table - kept unjoined, and
-- SCIENCE_KEYWORD itself is not archived. See
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.

SELECT
    ask.AWARD_SCIENCE_KEYWORD_ID,
    ask.AWARD_ID,
    a.AWARD_NUMBER,
    a.SEQUENCE_NUMBER,

    ask.SCIENCE_KEYWORD_CODE,

    ask.UPDATE_TIMESTAMP,
    ask.UPDATE_USER,
    ask.VER_NBR

FROM AWARD_SCIENCE_KEYWORD ask
JOIN AWARD a ON a.AWARD_ID = ask.AWARD_ID

ORDER BY
    ask.AWARD_ID,
    ask.AWARD_SCIENCE_KEYWORD_ID;
