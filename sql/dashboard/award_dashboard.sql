-- Award Dashboard: whole-archive row counts across every Award table.
-- Companion to award_explorer.sql and award_summary.sql (both scoped
-- to one award_number) in this same directory - this one has no
-- parameter, it reports totals across the entire archive.
--
-- Per CLAUDE.md's grain rule: a raw archive.award_version row count is
-- NOT a business-object (Award) count - multiple rows share one
-- award_number (one per historical sequence). Both grains are reported
-- explicitly and separately below, never conflated: "award (business
-- grain)" = COUNT(DISTINCT award_number); "award_version (historical
-- grain)" = COUNT(*) of every archived version row. Every other row
-- below is a plain archive.* table row count - none of them have a
-- different business/historical distinction of their own.
--
-- Usage:
--   psql -f sql/dashboard/award_dashboard.sql

SELECT 'award (business grain: distinct award_number)' AS table_name,
    COUNT(DISTINCT award_number) AS row_count
FROM archive.award_version
UNION ALL
SELECT 'award_version (historical grain: every archived version row)',
    COUNT(*)
FROM archive.award_version
UNION ALL
SELECT 'award_amount_info', COUNT(*) FROM archive.award_amount_info
UNION ALL
SELECT 'award_person', COUNT(*) FROM archive.award_person
UNION ALL
SELECT 'award_person_unit', COUNT(*) FROM archive.award_person_unit
UNION ALL
SELECT 'award_person_credit_split', COUNT(*)
FROM archive.award_person_credit_split
UNION ALL
SELECT 'award_person_unit_credit_split', COUNT(*)
FROM archive.award_person_unit_credit_split
UNION ALL
SELECT 'award_funding_proposal', COUNT(*) FROM archive.award_funding_proposal
UNION ALL
SELECT 'award_custom_data', COUNT(*) FROM archive.award_custom_data
UNION ALL
SELECT 'award_sponsor_term', COUNT(*) FROM archive.award_sponsor_term
UNION ALL
SELECT 'award_report_term', COUNT(*) FROM archive.award_report_term
UNION ALL
SELECT 'award_report_term_recipient', COUNT(*)
FROM archive.award_report_term_recipient
UNION ALL
SELECT 'award_unit_contact', COUNT(*) FROM archive.award_unit_contact
UNION ALL
SELECT 'award_sponsor_contact', COUNT(*) FROM archive.award_sponsor_contact
UNION ALL
SELECT 'award_closeout', COUNT(*) FROM archive.award_closeout
UNION ALL
SELECT 'award_payment_schedule', COUNT(*) FROM archive.award_payment_schedule
UNION ALL
SELECT 'award_approved_subaward', COUNT(*)
FROM archive.award_approved_subaward
UNION ALL
SELECT 'award_notepad', COUNT(*) FROM archive.award_notepad
UNION ALL
SELECT 'award_attachment', COUNT(*) FROM archive.award_attachment
ORDER BY table_name;
