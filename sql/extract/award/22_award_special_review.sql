SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_SPECIAL_REVIEW has no AWARD_NUMBER/SEQUENCE_NUMBER columns of
-- its own - same shape as AWARD_SCIENCE_KEYWORD. Joined back to AWARD
-- here to denormalize AWARD_NUMBER/SEQUENCE_NUMBER for consistency
-- with every other archived table. AWARD_SPECIAL_REVIEW_ID is already
-- singular matching the archive column name exactly - no alias needed.
-- SPECIAL_REVIEW_NUMBER is the review's OWN per-award ordinal
-- (assigned by the application), a distinct concept from the Award
-- version's own SEQUENCE_NUMBER pulled in via the join below - never
-- conflated. SPECIAL_REVIEW_CODE/APPROVAL_TYPE_CODE are bare lookup
-- codes (to SpecialReviewType/SpecialReviewApprovalType); PROTOCOL_NUMBER
-- is a soft, non-enforced cross-reference into Kuali's separate
-- Protocol/IRB world, kept as bare text, not joined to archive.irb_*.
-- Has a real child table, AWARD_EXEMPT_NUMBER (see
-- 23_award_special_review_exemption.sql) - this table must be loaded
-- first. See docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.

SELECT
    asr.AWARD_SPECIAL_REVIEW_ID,
    asr.AWARD_ID,
    a.AWARD_NUMBER,
    a.SEQUENCE_NUMBER,

    asr.SPECIAL_REVIEW_NUMBER,
    asr.SPECIAL_REVIEW_CODE AS SPECIAL_REVIEW_TYPE_CODE,
    asr.APPROVAL_TYPE_CODE,
    asr.PROTOCOL_NUMBER,
    asr.APPLICATION_DATE,
    asr.APPROVAL_DATE,
    asr.EXPIRATION_DATE,
    asr.COMMENTS,

    asr.UPDATE_TIMESTAMP,
    asr.UPDATE_USER,
    asr.VER_NBR

FROM AWARD_SPECIAL_REVIEW asr
JOIN AWARD a ON a.AWARD_ID = asr.AWARD_ID

ORDER BY
    asr.AWARD_ID,
    asr.AWARD_SPECIAL_REVIEW_ID;
