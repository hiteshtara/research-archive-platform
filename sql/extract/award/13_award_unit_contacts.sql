SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- AWARD_UNIT_CONTACTS carries AWARD_ID/AWARD_NUMBER/SEQUENCE_NUMBER
-- directly - no join needed, same flat shape as
-- 05_award_custom_data.sql/09_award_sponsor_terms.sql. AWARD_UNIT_CONTACT_ID
-- is already singular ("CONTACT", not "CONTACTS") matching the archive
-- column name exactly - no alias needed (see 10_award_report_terms.sql's
-- AWARD_REPORT_TERMS_ID for the class of bug this was double-checked
-- against). PERSON_ID/UNIT_CONTACT_TYPE/UNIT_ADMINISTRATOR_TYPE_CODE
-- are preserved without lookup joins, matching the same bare-code/value
-- convention already established elsewhere in this domain.
--
-- Schema verified against BOTH the Kuali OJB mapping AND the real
-- Oracle bootstrap DDL (coeus-db V300_107__schema.sql +
-- V510_060__KC_TBL_AWARD_UNIT_CONTACTS.sql for DEFAULT_UNIT_CONTACT) -
-- see docs/architecture/AWARD_CONTACTS_DESIGN.md for why this table was
-- previously dropped (V033, unverified) and why this re-creation is
-- narrower and verified, not a restoration of the old guessed schema.

SELECT
    auc.AWARD_UNIT_CONTACT_ID,
    auc.AWARD_ID,
    auc.AWARD_NUMBER,
    auc.SEQUENCE_NUMBER,

    auc.PERSON_ID,
    auc.FULL_NAME,
    auc.UNIT_CONTACT_TYPE,
    auc.UNIT_ADMINISTRATOR_TYPE_CODE,
    auc.UNIT_ADMINISTRATOR_UNIT_NUMBER,
    auc.DEFAULT_UNIT_CONTACT,

    auc.UPDATE_TIMESTAMP,
    auc.UPDATE_USER,
    auc.VER_NBR

FROM AWARD_UNIT_CONTACTS auc

ORDER BY
    auc.AWARD_ID,
    auc.AWARD_UNIT_CONTACT_ID;
