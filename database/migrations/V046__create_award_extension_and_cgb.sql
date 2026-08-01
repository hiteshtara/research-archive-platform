-- Award Extension and Award CGB: the two real, confirmed persisted
-- 1:1-with-Award BU-specific extension tables (edu.bu.kuali.kra.award.
-- home.AwardExtension -> AWARD_EXTENSION, org.kuali.kra.award.cgb.
-- AwardCgb -> AWARD_CGB). See
-- docs/architecture/AWARD_EXTENSION_CGB_DESIGN.md.
--
-- Neither table is created by the generic Kuali Coeus bootstrap schema
-- - both are BU-specific customizations (AWARD_EXTENSION via
-- bu-db/BUKR-0002, AWARD_CGB via a later Kuali migration,
-- V600_047__KC_TBL_AWARD_CGB.sql). Both use award_id itself as the
-- primary key (not a surrogate sequence id) - the correct shape for a
-- true 1:1 extension row, matching the key each table's own OJB
-- mapping declares.
--
-- award_extension.award_number/sequence_number are JOIN-derived
-- (denormalized from AWARD at extraction time) - AWARD_EXTENSION has
-- neither column physically. No Oracle-level PK or FK constraint was
-- found for AWARD_EXTENSION in the available BU customization script,
-- despite confirmed real schema evolution (an added-then-dropped FAIN
-- column) proving its history extends beyond that one script - see
-- the design doc's Open Questions.
--
-- award_cgb.award_number/sequence_number are real, physically NOT
-- NULL columns - no join needed. award_cgb.bill_freq_cd is a real
-- column with no OJB mapping - the same risk shape as the
-- award_cost_share.fiscal_year column already found this session to
-- be fictional in real BU Oracle; see the design doc's Open Questions
-- before trusting it.

CREATE TABLE IF NOT EXISTS archive.award_extension (
    award_id BIGINT PRIMARY KEY
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    proposed_for_transmission_indicator VARCHAR(10),
    last_transmission_date DATE,
    child_type VARCHAR(50),
    child_description VARCHAR(50),
    major_project VARCHAR(50),
    arra_code VARCHAR(50),
    avc_indicator VARCHAR(50),
    a133_cluster VARCHAR(50),
    fringe_not_allowed_indicator VARCHAR(10),
    interest_earned VARCHAR(50),
    interest_earned_account_number VARCHAR(20),
    stepped_up_rate VARCHAR(50),
    bu_bmc_fa_split VARCHAR(20),
    conference_grant VARCHAR(50),
    program_income VARCHAR(50),
    stock_award VARCHAR(50),
    foreign_currency_award VARCHAR(50),
    nce_notification_date DATE,
    clinical_trial_initiated_by VARCHAR(50),
    ind_ide_responsibility VARCHAR(50),
    clinical_trial_registration_date DATE,
    spuds_record_number VARCHAR(50),
    walker_source_number VARCHAR(50),
    prime_sponsor_award_id VARCHAR(50),
    grant_number VARCHAR(20),
    federal_clinical_trial VARCHAR(10),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);


CREATE TABLE IF NOT EXISTS archive.award_cgb (
    award_id BIGINT PRIMARY KEY
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    additional_forms_required VARCHAR(10),
    auto_approve_invoice VARCHAR(10),
    stop_work VARCHAR(10),
    min_invoice_amount NUMERIC(19, 2),
    invoicing_option VARCHAR(150),
    dunning_campaign_id VARCHAR(10),
    last_billed_date DATE,
    previous_last_billed_date DATE,
    final_bill VARCHAR(10),
    amount_to_draw NUMERIC(19, 2),
    letter_of_credit_review_indicator VARCHAR(10),
    invoice_document_status VARCHAR(50),
    loc_creation_type VARCHAR(50),
    suspend_invoicing VARCHAR(10),
    bill_freq_cd VARCHAR(10),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);
