SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_CGB carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER directly (a
-- real 1:1 extension table, AWARD_ID is its own key - no surrogate id,
-- no alias needed). ADDITIONAL_FORMS_REQ/MIN_INVOICE_AMT/
-- PREV_LAST_BILLED_DATE/AMT_TO_DRAW/LETTER_OF_CREDIT_REVIEW are
-- aliased to their authoritative Java field names for readability.
-- BILL_FREQ_CD is a real column (added by a later Kuali migration,
-- V601_007) with NO corresponding OJB field-descriptor - the same
-- risk shape as AWARD_COST_SHARE.FISCAL_YEAR, which real BU Oracle
-- already proved does not exist despite appearing in the generic
-- Kuali source tree's DDL. Included here as the best available
-- evidence, but flagged prominently as unverified against real BU
-- Oracle - see docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md's Open
-- Questions before trusting it.

SELECT
    acg.AWARD_ID,
    acg.AWARD_NUMBER,
    acg.SEQUENCE_NUMBER,

    acg.ADDITIONAL_FORMS_REQ AS ADDITIONAL_FORMS_REQUIRED,
    acg.AUTO_APPROVE_INVOICE,
    acg.STOP_WORK,
    acg.MIN_INVOICE_AMT AS MIN_INVOICE_AMOUNT,
    acg.INVOICING_OPTION,
    acg.DUNNING_CAMPAIGN_ID,
    acg.LAST_BILLED_DATE,
    acg.PREV_LAST_BILLED_DATE AS PREVIOUS_LAST_BILLED_DATE,
    acg.FINAL_BILL,
    acg.AMT_TO_DRAW AS AMOUNT_TO_DRAW,
    acg.LETTER_OF_CREDIT_REVIEW AS LETTER_OF_CREDIT_REVIEW_INDICATOR,
    acg.INVOICE_DOCUMENT_STATUS,
    acg.LOC_CREATION_TYPE,
    acg.SUSPEND_INVOICING,
    acg.BILL_FREQ_CD,

    acg.UPDATE_TIMESTAMP,
    acg.UPDATE_USER,
    acg.VER_NBR

FROM AWARD_CGB acg

ORDER BY acg.AWARD_ID;
