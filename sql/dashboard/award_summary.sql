-- Award Summary: one row of archive row-counts for a single Award, by
-- award_number. Companion to award_explorer.sql (full detail) and
-- award_dashboard.sql (whole-archive totals) in this same directory.
--
-- "Contacts" combines award_unit_contact + award_sponsor_contact - the
-- two are archived separately (see
-- docs/architecture/AWARD_CONTACTS_DESIGN.md), but this summary treats
-- them as one figure since the caller is asking "how much contact data
-- exists for this Award," not which contact subtype.
--
-- Usage (pass the raw, unquoted award_number - :'award_number' below
-- is psql's quoted-variable substitution and quotes it for you; if you
-- also quote it in -v, the literal quote characters end up embedded in
-- the value):
--   psql -v award_number=A-0001 -f sql/dashboard/award_summary.sql

SELECT
    :'award_number' AS "Award Number",
    (SELECT COUNT(*) FROM archive.award_version
        WHERE award_number = :'award_number') AS "Versions",
    (SELECT COUNT(*) FROM archive.award_person
        WHERE award_number = :'award_number') AS "People",
    (SELECT COUNT(*) FROM archive.award_person_unit
        WHERE award_number = :'award_number') AS "Units",
    (SELECT COUNT(*) FROM archive.award_custom_data
        WHERE award_number = :'award_number') AS "Custom Data",
    (SELECT COUNT(*) FROM archive.award_sponsor_term
        WHERE award_number = :'award_number') AS "Sponsor Terms",
    (SELECT COUNT(*) FROM archive.award_report_term
        WHERE award_number = :'award_number') AS "Report Terms",
    (
        (SELECT COUNT(*) FROM archive.award_unit_contact
            WHERE award_number = :'award_number')
        +
        (SELECT COUNT(*) FROM archive.award_sponsor_contact
            WHERE award_number = :'award_number')
    ) AS "Contacts",
    (SELECT COUNT(*) FROM archive.award_payment_schedule
        WHERE award_number = :'award_number') AS "Payment Schedules",
    (SELECT COUNT(*) FROM archive.award_closeout
        WHERE award_number = :'award_number') AS "Closeouts",
    (SELECT COUNT(*) FROM archive.award_notepad
        WHERE award_number = :'award_number') AS "Notepad";
