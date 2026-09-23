-- Award Workspace Summary: the Kuali-labelled fields BU users expect on
-- the legacy Award screen but which this archive has never captured.
-- Every column below was traced to a real Oracle source and verified
-- against live KCOEUS staging before being added here - no column is
-- added on the strength of a Java field name or a screen label alone.
--
-- WHY THIS MIGRATION EXISTS AT ALL (the Project Start Date trap):
-- Kuali's "Project Start Date" is NOT AWARD.BEGIN_DATE. BEGIN_DATE is
-- populated in 2 of 267,386 AWARD rows (0.0007%) - effectively a dead
-- column - while AWARD.AWARD_EFFECTIVE_DATE is populated in 267,260
-- (99.95%) and is what the legacy screen actually renders. That field
-- is ALREADY archived as award_version.award_effective_date, so
-- "Project Start Date" needs no new column: it is a label correction,
-- not an ETL gap. AWARD.CLOSEOUT_DATE is likewise near-empty (1,129
-- rows, 0.4%), which is why the Summary's "Closeout Date" card is being
-- removed rather than relabelled. Nothing here renames or drops
-- begin_date/closeout_date - both stay exactly as archived, because
-- they are real historical columns even where sparsely populated.
--
-- Obligation Start Date lives on AWARD_AMOUNT_INFO, not AWARD:
-- AWARD_AMOUNT_INFO.CURRENT_FUND_EFFECTIVE_DATE. Because one Award
-- version has MANY award_amount_info rows (884,201 rows against
-- 267,386 award versions), reading it requires Kuali's own current-row
-- rule - MAX(award_amount_info_id), full stop, never
-- source_version_number - per docs/kuali-business-rules/Time and
-- Money.md Rule 3 and etl/tests/
-- test_award_amount_info_current_row_selection.py. This migration
-- deliberately stores the date on the child row it belongs to and does
-- NOT denormalise a "current" copy onto award_version, so the existing
-- validated selection rule stays the single source of truth for which
-- row is current.
--
-- NSF Science Code is a resolved reference value, not a raw code:
-- AWARD.NSF_SEQUENCE_NUMBER is a surrogate FK into NSF_CODES, whose
-- NSF_CODE column holds the value the screen shows (e.g. 'J8').
-- nsf_sequence_number is retained alongside it as audit metadata so the
-- resolution can always be re-derived - the same parent-resolution
-- discipline V044 applied to AWARD_SCIENCE_KEYWORD. The join resolves
-- for 95,692 of 95,692 rows that carry an NSF_SEQUENCE_NUMBER at all.
--
-- FAIN_ID is stored verbatim. In real BU data the literal string
-- 'unknown' is a legitimately archived value (Award 105698-00001's
-- latest sequences carry exactly that), and it must never be
-- normalised to NULL or an em dash on the way through - it is what
-- Kuali shows.
--
-- Account/Activity/Award Type are code+description pairs resolved at
-- extraction time from ACCOUNT_TYPE/ACTIVITY_TYPE/AWARD_TYPE, all three
-- 100% populated (267,386/267,386) and all three resolving 100% against
-- their lookup. Note ACTIVITY_TYPE.ACTIVITY_TYPE_CODE is VARCHAR2 while
-- AWARD.ACTIVITY_TYPE_CODE is NUMBER - the extraction SQL joins via
-- TO_CHAR() and that join was verified to resolve for every row before
-- this column was added. This is NOT the same concept as
-- archive.award_transmission.account_type_code (V052), which is an
-- SAP-transmission-specific field; reusing that one as a general Award
-- attribute would misrepresent it.
--
-- AWARD.FED_AWARD_YEAR is deliberately NOT archived. It exists in
-- Oracle but is populated in 0 of 267,386 rows, so a column and a
-- Summary card for it would be permanently empty.

ALTER TABLE archive.award_version
    ADD COLUMN IF NOT EXISTS fain_id              VARCHAR(100),
    ADD COLUMN IF NOT EXISTS nsf_sequence_number  INTEGER,
    ADD COLUMN IF NOT EXISTS nsf_science_code     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS account_type_code    INTEGER,
    ADD COLUMN IF NOT EXISTS account_type         VARCHAR(300),
    ADD COLUMN IF NOT EXISTS activity_type_code   INTEGER,
    ADD COLUMN IF NOT EXISTS activity_type        VARCHAR(300),
    ADD COLUMN IF NOT EXISTS award_type_code      INTEGER,
    ADD COLUMN IF NOT EXISTS award_type           VARCHAR(300);

ALTER TABLE archive.award_amount_info
    ADD COLUMN IF NOT EXISTS current_fund_effective_date DATE;
