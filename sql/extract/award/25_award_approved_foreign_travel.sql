SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_APPROVED_FOREIGN_TRAVEL carries AWARD_ID/AWARD_NUMBER/
-- SEQUENCE_NUMBER directly - no join needed. AWARD_APPR_FORN_TRAVEL_ID
-- is aliased to a full, readable archive column name (an Oracle-
-- identifier-length abbreviation, not a business-terminology
-- divergence). No Oracle-level FK to AWARD exists for this table
-- (Java/OJB-layer relationship only). PERSON_ID/ROLODEX_ID/
-- TRAVELER_NAME are bare, unjoined person references; DESTINATION is
-- free text, not a lookup. See
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.

SELECT
    aaft.AWARD_APPR_FORN_TRAVEL_ID AS AWARD_APPROVED_FOREIGN_TRAVEL_ID,
    aaft.AWARD_ID,
    aaft.AWARD_NUMBER,
    aaft.SEQUENCE_NUMBER,

    aaft.PERSON_ID,
    aaft.ROLODEX_ID,
    aaft.TRAVELER_NAME,
    aaft.DESTINATION,
    aaft.START_DATE,
    aaft.END_DATE,
    aaft.AMOUNT,

    aaft.UPDATE_TIMESTAMP,
    aaft.UPDATE_USER,
    aaft.VER_NBR

FROM AWARD_APPROVED_FOREIGN_TRAVEL aaft

ORDER BY
    aaft.AWARD_ID,
    aaft.AWARD_APPR_FORN_TRAVEL_ID;
