-- Award Special Approvals and Compliance: the nine real, currently-
-- unarchived tables confirmed against the upstream Kuali Coeus source
-- (org.kuali.kra.award.home.AwardCfda -> AWARD_CFDA,
-- org.kuali.kra.award.commitments.AwardCostShare -> AWARD_COST_SHARE,
-- org.kuali.kra.award.commitments.AwardFandaRate -> AWARD_IDC_RATE,
-- org.kuali.kra.award.home.keywords.AwardScienceKeyword ->
-- AWARD_SCIENCE_KEYWORD, org.kuali.kra.award.specialreview.AwardSpecialReview
-- -> AWARD_SPECIAL_REVIEW, org.kuali.kra.award.specialreview.
-- AwardSpecialReviewExemption -> AWARD_EXEMPT_NUMBER,
-- org.kuali.kra.award.paymentreports.specialapproval.approvedequipment.
-- AwardApprovedEquipment -> AWARD_APPROVED_EQUIPMENT,
-- org.kuali.kra.award.paymentreports.specialapproval.foreigntravel.
-- AwardApprovedForeignTravel -> AWARD_APPROVED_FOREIGN_TRAVEL,
-- org.kuali.kra.award.subcontracting.goalsAndExpenditures.
-- AwardSubcontractingBudgetedGoals -> SUBCONTRACTING_BUD). See
-- docs/architecture/AWARD_SPECIAL_APPROVALS_COMPLIANCE_DESIGN.md.
--
-- AWARD_CFDA confirmed a REAL child table (not an enrichment/reference
-- view) via its own creating migration (V1807_003__multi_cfda.sql)'s
-- backfill logic, not inferred from the class name.
--
-- award_fanda_rate_id/applicable_fanda_rate/fanda_rate_type_code and
-- award_special_review_exemption_id are deliberately renamed from
-- their literal Oracle column names (AWARD_IDC_RATE_ID/
-- APPLICABLE_IDC_RATE/IDC_RATE_TYPE_CODE, AWARD_EXEMPT_NUMBER_ID) to
-- match their authoritative Java field names - a historical business-
-- terminology rename on Kuali's side, not a bug, same precedent as
-- AWARD_REPORT_TERMS_ID in V040.
--
-- AWARD_SCIENCE_KEYWORD and AWARD_SPECIAL_REVIEW have no AWARD_NUMBER/
-- SEQUENCE_NUMBER columns in Oracle at all; both are denormalized here
-- via an Oracle-side JOIN back to AWARD at extraction time so every
-- table in this schema keeps the same shape.
--
-- AWARD_EXEMPT_NUMBER has no AWARD_ID column at all - its only FK is
-- to its true parent, AWARD_SPECIAL_REVIEW. award_special_review_id
-- below is therefore a real containment FK (ON DELETE CASCADE), and
-- award_special_review must be loaded before
-- award_special_review_exemption.
--
-- SUBCONTRACTING_BUD is the one genuine structural exception: its own
-- Oracle primary key IS award_number itself - no surrogate ID, no
-- AWARD_ID, no SEQUENCE_NUMBER exist for this table at all. Its
-- archive table therefore has no award_id/sequence_number columns and
-- no FK to archive.award_version - there is no specific version to tie
-- it to.

CREATE TABLE IF NOT EXISTS archive.award_cfda (
    award_cfda_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    cfda_number VARCHAR(20),
    cfda_description VARCHAR(300),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_cfda_award
    ON archive.award_cfda (award_id, award_cfda_id);


CREATE TABLE IF NOT EXISTS archive.award_cost_share (
    award_cost_share_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    project_period VARCHAR(50),
    cost_share_percentage NUMERIC(5, 2),
    cost_share_type_code VARCHAR(10),
    unit_number VARCHAR(20),
    source VARCHAR(50),
    destination VARCHAR(50),
    commitment_amount NUMERIC(12, 2),
    cost_share_met NUMERIC(12, 2),
    verification_date DATE,
    fiscal_year VARCHAR(10),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_cost_share_award
    ON archive.award_cost_share (award_id, award_cost_share_id);


CREATE TABLE IF NOT EXISTS archive.award_fanda_rate (
    award_fanda_rate_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    applicable_fanda_rate NUMERIC(5, 2),
    fanda_rate_type_code VARCHAR(10),
    fiscal_year VARCHAR(10),
    on_campus_flag VARCHAR(10),
    underrecovery_of_indirect_cost NUMERIC(12, 2),
    source_account VARCHAR(50),
    destination_account VARCHAR(50),
    start_date DATE,
    end_date DATE,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_fanda_rate_award
    ON archive.award_fanda_rate (award_id, award_fanda_rate_id);


-- award_number/sequence_number are JOIN-derived (denormalized from
-- AWARD at extraction time) - Oracle's own AWARD_SCIENCE_KEYWORD table
-- has no such columns.
CREATE TABLE IF NOT EXISTS archive.award_science_keyword (
    award_science_keyword_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    science_keyword_code VARCHAR(20),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_science_keyword_award
    ON archive.award_science_keyword (award_id, award_science_keyword_id);


-- award_number/sequence_number are JOIN-derived, same as
-- award_science_keyword above. special_review_number is the review's
-- OWN per-award ordinal, distinct from the Award version's
-- sequence_number.
CREATE TABLE IF NOT EXISTS archive.award_special_review (
    award_special_review_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    special_review_number INTEGER,
    special_review_type_code VARCHAR(10),
    approval_type_code VARCHAR(10),
    protocol_number VARCHAR(50),
    application_date DATE,
    approval_date DATE,
    expiration_date DATE,
    comments TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_special_review_award
    ON archive.award_special_review (award_id, award_special_review_id);


-- award_special_review_id is a REAL containment FK (this table's only
-- Oracle-level FK is to AWARD_SPECIAL_REVIEW, not to AWARD at all) -
-- award_special_review must be loaded first. award_id/award_number/
-- sequence_number here are JOIN-derived, through
-- award_special_review's own AWARD join.
CREATE TABLE IF NOT EXISTS archive.award_special_review_exemption (
    award_special_review_exemption_id BIGINT PRIMARY KEY,
    award_special_review_id BIGINT NOT NULL
        REFERENCES archive.award_special_review(award_special_review_id)
        ON DELETE CASCADE,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    exemption_type_code VARCHAR(10),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_special_review_exemption_review
    ON archive.award_special_review_exemption (award_special_review_id);

CREATE INDEX IF NOT EXISTS ix_award_special_review_exemption_award
    ON archive.award_special_review_exemption (award_id, award_special_review_exemption_id);


CREATE TABLE IF NOT EXISTS archive.award_approved_equipment (
    award_approved_equipment_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    item VARCHAR(200),
    model VARCHAR(100),
    vendor VARCHAR(100),
    amount NUMERIC(12, 2),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_approved_equipment_award
    ON archive.award_approved_equipment (award_id, award_approved_equipment_id);


-- No Oracle-level FK to AWARD exists for this table (Java/OJB-layer
-- relationship only) - same "no physical FK" precedent as
-- award_notepad and award_approved_subaward.
CREATE TABLE IF NOT EXISTS archive.award_approved_foreign_travel (
    award_approved_foreign_travel_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL
        REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    award_number VARCHAR(50),
    sequence_number INTEGER,

    person_id VARCHAR(50),
    rolodex_id BIGINT,
    traveler_name VARCHAR(200),
    destination VARCHAR(100),
    start_date DATE,
    end_date DATE,
    amount NUMERIC(12, 2),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_approved_foreign_travel_award
    ON archive.award_approved_foreign_travel (award_id, award_approved_foreign_travel_id);


-- The one structural exception in this bundle: Oracle's own
-- SUBCONTRACTING_BUD table is keyed by AWARD_NUMBER itself - no
-- surrogate ID, no AWARD_ID, no SEQUENCE_NUMBER exist for it at all,
-- and there is no Oracle-level FK to AWARD. This table is therefore
-- NOT tied to archive.award_version by a foreign key - it is a single
-- row per award_number, independent of any specific version.
CREATE TABLE IF NOT EXISTS archive.award_subcontracting_budgeted_goals (
    award_number VARCHAR(50) PRIMARY KEY,

    large_business_goal_amount NUMERIC(12, 2),
    small_business_goal_amount NUMERIC(12, 2),
    woman_owned_goal_amount NUMERIC(12, 2),
    eight_a_disadvantage_goal_amount NUMERIC(12, 2),
    hub_zone_goal_amount NUMERIC(12, 2),
    veteran_owned_goal_amount NUMERIC(12, 2),
    service_disabled_veteran_owned_goal_amount NUMERIC(12, 2),
    historical_black_college_goal_amount NUMERIC(12, 2),
    comments TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);
