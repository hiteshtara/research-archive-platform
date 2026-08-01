SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_EXTENSION is a BU-specific 1:1 extension table with no
-- AWARD_NUMBER/SEQUENCE_NUMBER columns of its own - joined back to
-- AWARD here to denormalize them for consistency with every other
-- archived table, the same join-to-denormalize pattern already used
-- for AWARD_SCIENCE_KEYWORD/AWARD_SPECIAL_REVIEW. AWARD_ID is this
-- table's own key (no surrogate id) - no alias needed.
-- PROPOSED_INDICATOR/LAST_TRANS_DATE/CLINICAL_TRIAL_REG_DATE are
-- aliased to their authoritative Java field names; FEDERAL_RATE_DATE
-- is aliased to STEPPED_UP_RATE (the real Java field name for this
-- column, despite the Oracle name suggesting a date - it is a
-- VARCHAR2, not a date). See
-- docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md.

SELECT
    ae.AWARD_ID,
    a.AWARD_NUMBER,
    a.SEQUENCE_NUMBER,

    ae.PROPOSED_INDICATOR AS PROPOSED_FOR_TRANSMISSION_INDICATOR,
    ae.LAST_TRANS_DATE AS LAST_TRANSMISSION_DATE,
    ae.CHILD_TYPE,
    ae.CHILD_DESCRIPTION,
    ae.MAJOR_PROJECT,
    ae.ARRA_CODE,
    ae.AVC_INDICATOR,
    ae.A133_CLUSTER,
    ae.FRINGE_NOT_ALLOWED_INDICATOR,
    ae.INTEREST_EARNED,
    ae.INTEREST_EARNED_ACCOUNT_NUMBER,
    ae.FEDERAL_RATE_DATE AS STEPPED_UP_RATE,
    ae.BU_BMC_FA_SPLIT,
    ae.CONFERENCE_GRANT,
    ae.PROGRAM_INCOME,
    ae.STOCK_AWARD,
    ae.FOREIGN_CURRENCY_AWARD,
    ae.NCE_NOTIFICATION_DATE,
    ae.CLINICAL_TRIAL_INITIATED_BY,
    ae.IND_IDE_RESPONSIBILITY,
    ae.CLINICAL_TRIAL_REG_DATE AS CLINICAL_TRIAL_REGISTRATION_DATE,
    ae.SPUDS_RECORD_NUMBER,
    ae.WALKER_SOURCE_NUMBER,
    ae.PRIME_SPONSOR_AWARD_ID,
    ae.GRANT_NUMBER,
    ae.FEDERAL_CLINICAL_TRIAL,

    ae.UPDATE_TIMESTAMP,
    ae.UPDATE_USER,
    ae.VER_NBR

FROM AWARD_EXTENSION ae
JOIN AWARD a ON a.AWARD_ID = ae.AWARD_ID

ORDER BY ae.AWARD_ID;
