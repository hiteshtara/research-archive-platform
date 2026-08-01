SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_EXEMPT_NUMBER (archived as
-- archive.award_special_review_exemption) has NO AWARD_ID column at
-- all - its only Oracle-level foreign key is AWARD_SPECIAL_REVIEW_ID,
-- referencing its true parent, AWARD_SPECIAL_REVIEW. This is the
-- clearest "grandchild, join required" case in this bundle: AWARD_ID/
-- AWARD_NUMBER/SEQUENCE_NUMBER are denormalized here by joining first
-- to AWARD_SPECIAL_REVIEW, then to AWARD - the same join-through-
-- parent-then-grandparent shape already used for
-- archive.award_person_unit_credit_split (via award_person_unit ->
-- award_persons). AWARD_EXEMPT_NUMBER_ID is aliased to its
-- authoritative Java field name (a deliberate historical business-
-- terminology rename, not a bug - same treatment as
-- 20_award_fanda_rate.sql). EXEMPTION_TYPE_CODE is a bare lookup code
-- - kept unjoined. award_special_review MUST be loaded before this
-- table - see docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.

SELECT
    aen.AWARD_EXEMPT_NUMBER_ID AS AWARD_SPECIAL_REVIEW_EXEMPTION_ID,
    aen.AWARD_SPECIAL_REVIEW_ID,
    asr.AWARD_ID,
    a.AWARD_NUMBER,
    a.SEQUENCE_NUMBER,

    aen.EXEMPTION_TYPE_CODE,

    aen.UPDATE_TIMESTAMP,
    aen.UPDATE_USER,
    aen.VER_NBR

FROM AWARD_EXEMPT_NUMBER aen
JOIN AWARD_SPECIAL_REVIEW asr ON asr.AWARD_SPECIAL_REVIEW_ID = aen.AWARD_SPECIAL_REVIEW_ID
JOIN AWARD a ON a.AWARD_ID = asr.AWARD_ID

ORDER BY
    asr.AWARD_ID,
    aen.AWARD_EXEMPT_NUMBER_ID;
