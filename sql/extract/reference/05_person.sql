SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Targeted read only (via OracleDataSource.read_filtered(column="PERSON_ID", ...)),
-- never a full-table scan - KRIM_PRNCPL_T covers every BU affiliate and
-- would be enormous. Confirmed live: no PERSON/KC_PERSON table exists
-- in BU's real schema; person identity/name/email/phone all live in
-- Rice KIM (KRIM_PRNCPL_T/KRIM_ENTITY_NM_T/KRIM_ENTITY_EMAIL_T/
-- KRIM_ENTITY_PHONE_T), reachable from the same Oracle connection as
-- KCOEUS. PRNCPL_ID = the PERSON_ID already used everywhere else in
-- this schema (UNIT_ADMINISTRATOR.PERSON_ID, AWARD_UNIT_CONTACTS.PERSON_ID) -
-- confirmed by a live join, not assumed. DFLT_IND='Y' picks each
-- person's single default name/email/phone row - a person can have
-- multiple of each (e.g. home vs work phone), and only the default is
-- archived here, matching what Kuali itself displays for a person's
-- name.

SELECT
    p.PRNCPL_ID AS PERSON_ID,
    n.FIRST_NM,
    n.MIDDLE_NM,
    n.LAST_NM,
    e.EMAIL_ADDR,
    ph.PHONE_NBR

FROM KRIM_PRNCPL_T p
LEFT JOIN KRIM_ENTITY_NM_T n
       ON n.ENTITY_ID = p.ENTITY_ID
      AND n.DFLT_IND = 'Y'
LEFT JOIN KRIM_ENTITY_EMAIL_T e
       ON e.ENTITY_ID = p.ENTITY_ID
      AND e.DFLT_IND = 'Y'
LEFT JOIN KRIM_ENTITY_PHONE_T ph
       ON ph.ENTITY_ID = p.ENTITY_ID
      AND ph.DFLT_IND = 'Y';
