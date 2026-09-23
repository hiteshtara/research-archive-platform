-- Shared Sponsor reference data (KCOEUS.SPONSOR).
--
-- Kuali's sponsor master. Like archive.unit (V056), this is referenced,
-- never duplicated: Award already denormalizes sponsor_name onto
-- archive.award_version at extract time, but Negotiation had no way to
-- resolve a sponsor code to a human-readable name at all, so the
-- Negotiations UI could only ever show "303630" where BU staff expect
-- "Addgene".
--
-- Full reference load, small and bounded - verified live against KCOEUS
-- staging 2026-09-22: 7,246 rows, 7,246 with SPONSOR_NAME populated,
-- 7,246 distinct SPONSOR_CODE values (so SPONSOR_CODE is a genuine key,
-- not merely a not-null column). Every sponsor code carried by
-- Negotiation resolves against it: 8,554 / 8,554 = 100%.
--
-- Column widths follow BU's real deployed schema as read from
-- all_tab_columns, not the open-source Kuali reference schema:
-- SPONSOR_CODE VARCHAR2(20), SPONSOR_NAME VARCHAR2(200),
-- ACRONYM VARCHAR2(10), SPONSOR_TYPE_CODE VARCHAR2(3).
--
-- Deliberately NOT archived here: DUN_AND_BRADSTREET_NUMBER,
-- DUNS_PLUS_FOUR_NUMBER, DODAC_NUMBER, CAGE_NUMBER, UEI, CUSTOMER_NUMBER,
-- SAM_NAME/MATCHES_SAM, address fields and ROLODEX_ID. They are external
-- registry identifiers and contact data that no archive surface needs;
-- omitting them keeps this reference table to what the UI actually
-- resolves. Add them only with a verified consumer.

CREATE TABLE IF NOT EXISTS archive.sponsor (
    sponsor_code            VARCHAR(20) PRIMARY KEY,
    sponsor_name            VARCHAR(200) NOT NULL,
    acronym                 VARCHAR(10),
    sponsor_type_code       VARCHAR(3),
    active                  BOOLEAN,

    source_update_timestamp TIMESTAMP,
    source_update_user      VARCHAR(60),
    source_version_number   BIGINT,

    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id)
);

-- Sponsor NAME is what users type into a Sponsor filter; the code is
-- already the primary key. This supports case-insensitive name lookup
-- without committing to a trigram strategy before it is measured.
CREATE INDEX IF NOT EXISTS ix_sponsor_name_lower
    ON archive.sponsor (LOWER(sponsor_name));
