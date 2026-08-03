SET PAGESIZE 50000
SET LINESIZE 32767
SET FEEDBACK ON

-- Full reference-data load: ~12,481 rows as of live verification.
-- ROLODEX is Kuali's external-contact-card table, referenced by
-- AWARD_SPONSOR_CONTACTS.ROLODEX_ID (never duplicated per Award).
-- ORGANIZATION/PHONE_NUMBER/EMAIL_ADDRESS confirmed live directly on
-- this table - external Sponsor Contacts do not need any further join
-- to resolve contact info, unlike internal Person contacts which
-- require the Rice KIM chain (see 05_person.sql). Several columns are
-- wider on BU's real schema than the generic open-source reference
-- (ORGANIZATION VARCHAR2(200) not 80, OWNED_BY_UNIT/SPONSOR_CODE
-- VARCHAR2(20) not 8/CHAR(6)) - confirmed live, not assumed.

SELECT
    r.ROLODEX_ID,
    r.LAST_NAME,
    r.FIRST_NAME,
    r.MIDDLE_NAME,
    r.SUFFIX,
    r.PREFIX,
    r.TITLE,
    r.ORGANIZATION,
    r.PHONE_NUMBER,
    r.EMAIL_ADDRESS,
    r.ADDRESS_LINE_1,
    r.ADDRESS_LINE_2,
    r.ADDRESS_LINE_3,
    r.CITY,
    r.COUNTY,
    r.STATE,
    r.POSTAL_CODE,
    r.COUNTRY_CODE,
    r.OWNED_BY_UNIT,
    r.ACTV_IND,
    r.DELETE_FLAG,

    r.UPDATE_TIMESTAMP,
    r.UPDATE_USER,
    r.VER_NBR

FROM ROLODEX r

ORDER BY r.ROLODEX_ID;
