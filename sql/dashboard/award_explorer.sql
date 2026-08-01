-- Award Explorer: the full archived object graph for one Award, by
-- award_number. Read-only validation tool - part of the standard
-- Award archive toolkit alongside award_summary.sql and
-- award_dashboard.sql in this same directory.
--
-- Returns every historical version and every child row for the whole
-- award_number family (not just the current version) - that is the
-- point of an archive. Every section below filters on the
-- denormalized award_number column each table already carries for
-- exactly this purpose, except award_funding_proposal (the one table
-- in this graph with no denormalized award_number/sequence_number of
-- its own), which joins back to archive.award_version instead.
--
-- Usage (pass the raw, unquoted award_number - :'award_number' below
-- is psql's quoted-variable substitution and quotes it for you; if you
-- also quote it in -v, the literal quote characters end up embedded in
-- the value):
--   psql -v award_number=A-0001 -f sql/dashboard/award_explorer.sql
--
-- Only tables archived as of this writing are included. See
-- docs/architecture/KUALI_ARCHIVE_COVERAGE.md for what remains
-- unarchived.

-- === Award Version (every historical sequence) =========================

SELECT
    award_id AS "Award ID",
    award_number AS "Award Number",
    sequence_number AS "Sequence",
    award_sequence_status AS "Sequence Status",
    status_description AS "Status",
    title AS "Title",
    sponsor_name AS "Sponsor",
    prime_sponsor_name AS "Prime Sponsor",
    lead_unit_name AS "Lead Unit",
    proposal_number AS "Proposal Number",
    account_number AS "Account Number",
    sponsor_award_number AS "Sponsor Award Number",
    award_effective_date AS "Effective Date",
    award_execution_date AS "Execution Date",
    begin_date AS "Begin Date",
    closeout_date AS "Closeout Date",
    transaction_type AS "Transaction Type",
    modification_number AS "Modification Number",
    is_current_version AS "Is Current Version",
    is_primary_current AS "Is Primary Current"
FROM archive.award_version
WHERE award_number = :'award_number'
ORDER BY sequence_number;

-- === Amount Info =========================================================

SELECT
    award_amount_info_id AS "Amount Info ID",
    sequence_number AS "Sequence",
    anticipated_total_amount AS "Anticipated Total",
    obligated_total_amount AS "Obligated Total",
    anticipated_total_direct AS "Anticipated Direct",
    anticipated_total_indirect AS "Anticipated Indirect",
    obligated_total_direct AS "Obligated Direct",
    obligated_total_indirect AS "Obligated Indirect",
    anticipated_change_direct AS "Anticipated Change (Direct)",
    anticipated_change_indirect AS "Anticipated Change (Indirect)",
    tnm_document_number AS "Time & Money Document"
FROM archive.award_amount_info
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_amount_info_id;

-- === People ==============================================================

SELECT
    award_person_id AS "Person ID",
    sequence_number AS "Sequence",
    full_name AS "Name",
    person_id AS "Person Kuali ID",
    rolodex_id AS "Rolodex ID",
    key_person_project_role AS "Project Role",
    contact_role_code AS "Contact Role",
    faculty_flag AS "Faculty?",
    academic_year_effort AS "Academic Yr Effort",
    calendar_year_effort AS "Calendar Yr Effort",
    summer_effort AS "Summer Effort",
    total_effort AS "Total Effort"
FROM archive.award_person
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_person_id;

-- === Person Units =========================================================

SELECT
    award_person_unit_id AS "Person Unit ID",
    award_person_id AS "Person ID",
    sequence_number AS "Sequence",
    unit_number AS "Unit Number",
    lead_unit_flag AS "Lead Unit?"
FROM archive.award_person_unit
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_person_id, award_person_unit_id;

-- === Person Credit Splits =================================================

SELECT
    award_person_credit_split_id AS "Credit Split ID",
    award_person_id AS "Person ID",
    sequence_number AS "Sequence",
    inv_credit_type_code AS "Credit Type",
    credit AS "Credit %"
FROM archive.award_person_credit_split
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_person_id, award_person_credit_split_id;

-- === Person Unit Credit Splits =============================================

SELECT
    award_person_unit_credit_split_id AS "Unit Credit Split ID",
    award_person_unit_id AS "Person Unit ID",
    sequence_number AS "Sequence",
    inv_credit_type_code AS "Credit Type",
    credit AS "Credit %"
FROM archive.award_person_unit_credit_split
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_person_unit_id, award_person_unit_credit_split_id;

-- === Funding Proposals =====================================================
-- No denormalized award_number/sequence_number of its own - the one
-- exception in this graph - so this section joins back to
-- archive.award_version instead of filtering directly.

SELECT
    fp.award_funding_proposal_id AS "Funding Proposal ID",
    v.sequence_number AS "Sequence",
    fp.proposal_id AS "Proposal ID",
    fp.active_flag AS "Active?"
FROM archive.award_funding_proposal fp
JOIN archive.award_version v ON v.award_id = fp.award_id
WHERE v.award_number = :'award_number'
ORDER BY v.sequence_number, fp.award_funding_proposal_id;

-- === Custom Data ============================================================

SELECT
    award_custom_data_id AS "Custom Data ID",
    sequence_number AS "Sequence",
    custom_attribute_id AS "Attribute ID",
    value AS "Value"
FROM archive.award_custom_data
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_custom_data_id;

-- === Sponsor Terms ===========================================================

SELECT
    award_sponsor_term_id AS "Sponsor Term ID",
    sequence_number AS "Sequence",
    sponsor_term_id AS "Sponsor Term Code ID"
FROM archive.award_sponsor_term
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_sponsor_term_id;

-- === Report Terms =============================================================

SELECT
    award_report_term_id AS "Report Term ID",
    sequence_number AS "Sequence",
    report_class_code AS "Report Class",
    report_code AS "Report Code",
    frequency_code AS "Frequency",
    frequency_base_code AS "Frequency Base",
    osp_distribution_code AS "OSP Distribution",
    due_date AS "Due Date"
FROM archive.award_report_term
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_report_term_id;

-- === Report Term Recipients =====================================================

SELECT
    award_report_term_recipient_id AS "Recipient ID",
    award_report_term_id AS "Report Term ID",
    sequence_number AS "Sequence",
    contact_id AS "Contact ID",
    contact_type_code AS "Contact Type",
    rolodex_id AS "Rolodex ID",
    number_of_copies AS "Copies"
FROM archive.award_report_term_recipient
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_report_term_id, award_report_term_recipient_id;

-- === Unit Contacts =================================================================

SELECT
    award_unit_contact_id AS "Unit Contact ID",
    sequence_number AS "Sequence",
    full_name AS "Name",
    person_id AS "Person ID",
    unit_contact_type AS "Contact Type",
    unit_administrator_type_code AS "Admin Type",
    unit_administrator_unit_number AS "Admin Unit",
    default_unit_contact AS "Default Contact?"
FROM archive.award_unit_contact
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_unit_contact_id;

-- === Sponsor Contacts ================================================================

SELECT
    award_sponsor_contact_id AS "Sponsor Contact ID",
    sequence_number AS "Sequence",
    full_name AS "Name",
    rolodex_id AS "Rolodex ID",
    contact_role_code AS "Contact Role"
FROM archive.award_sponsor_contact
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_sponsor_contact_id;

-- === Closeout ==========================================================================

SELECT
    award_closeout_id AS "Closeout ID",
    sequence_number AS "Sequence",
    closeout_report_code AS "Report Code",
    closeout_report_name AS "Report Name",
    due_date AS "Due Date",
    final_submission_date AS "Final Submission Date",
    multiple_flag AS "Multiple Award Numbers?"
FROM archive.award_closeout
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_closeout_id;

-- === Payment Schedule ===================================================================

SELECT
    award_payment_schedule_id AS "Payment Schedule ID",
    sequence_number AS "Sequence",
    due_date AS "Due Date",
    amount AS "Amount",
    status AS "Status",
    status_description AS "Status Notes",
    submit_date AS "Submitted",
    submitted_by AS "Submitted By",
    invoice_number AS "Invoice Number",
    award_report_term_id AS "Linked Report Term ID",
    award_report_term_description AS "Linked Report Term"
FROM archive.award_payment_schedule
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_payment_schedule_id;

-- === Approved Subawards ===================================================================

SELECT
    award_approved_subaward_id AS "Approved Subaward ID",
    sequence_number AS "Sequence",
    organization_name AS "Organization",
    organization_id AS "Organization ID",
    amount AS "Amount"
FROM archive.award_approved_subaward
WHERE award_number = :'award_number'
ORDER BY sequence_number, award_approved_subaward_id;

-- === Notepad ================================================================================
-- Scoped to the whole award_number family, not a version - see
-- docs/architecture/AWARD_NOTEPAD_DESIGN.md.

SELECT
    award_notepad_id AS "Notepad ID",
    entry_number AS "Entry #",
    note_topic AS "Topic",
    comments AS "Comment",
    restricted_view AS "Restricted?",
    source_create_user AS "Created By",
    source_create_timestamp AS "Created"
FROM archive.award_notepad
WHERE award_number = :'award_number'
ORDER BY entry_number;
