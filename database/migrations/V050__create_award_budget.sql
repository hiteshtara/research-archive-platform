-- Award Budget: the deepest, most interconnected Award bundle in this
-- project - five levels of parent/child nesting, and every level is a
-- shared, table-per-subclass Oracle table also used by Proposal
-- Development budgets, not an Award-only table. See
-- docs/architecture/AWARD_BUDGET_DESIGN.md.
--
-- Every table below merges a generic budget table (BUDGET/
-- BUDGET_PERIODS/BUDGET_DETAILS/BUDGET_DETAILS_CAL_AMTS/
-- BUDGET_PERSONNEL_DETAILS/BUDGET_PERSONNEL_CAL_AMTS - all shared with
-- Proposal Development) with its Award-specific "_EXT" extension table
-- (AWARD_BUDGET_EXT/AWARD_BUDGET_PERIOD_EXT/AWARD_BUDGET_DETAILS_EXT/
-- AWD_BGT_DET_CAL_AMTS_EXT/AWD_BUDGET_PER_DET_EXT/
-- AWD_BUDGET_PER_CAL_AMTS_EXT) into one flattened archive table, keyed
-- by the shared PK value - the same 1:1-extension reasoning already
-- used for archive.award_extension/archive.award_cgb, applied six
-- times in a nested chain. This is the first confirmed case in the
-- whole Award domain of a real, Oracle-enforced FK between two tables
-- archived in this project (V300_258__schema-constraints.sql declares
-- one real FK per generic/Ext pair) - every other Award relationship
-- found so far has been Java/OJB-layer only.
--
-- AWD_BGT_PER_SUM_CALC_AMT and AWARD_BUDGET_LIMIT have no generic
-- counterpart - both are standalone, Award-specific tables.
--
-- previous_obligated_total (a real OJB field with no DDL evidence
-- found anywhere in the checkout) and BUDGET.final_version_flag (a
-- real DDL column with no OJB field-descriptor at all) are
-- deliberately excluded - see the design doc's Traps section.

-- archive.award_budget: merges AWARD_BUDGET_EXT + BUDGET.
-- award_budget_status_code/award_budget_type_code are denormalized via
-- LEFT JOIN to their own tiny lookup tables (AWARD_BUDGET_STATUS/
-- AWARD_BUDGET_TYPE), the same convention already used for
-- status_code/transaction_type_code in archive.award_version.
-- award_id was added to AWARD_BUDGET_EXT by a later migration
-- (V600_046) and is NOT NULL there today after a one-time backfill.
CREATE TABLE IF NOT EXISTS archive.award_budget (
    budget_id BIGINT PRIMARY KEY,
    award_id BIGINT NOT NULL REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    document_number VARCHAR(50),

    award_budget_status_code VARCHAR(10),
    award_budget_status_description VARCHAR(300),
    award_budget_type_code VARCHAR(10),
    award_budget_type_description VARCHAR(300),

    budget_version_number INTEGER,
    name VARCHAR(300),
    description VARCHAR(300),
    budget_initiator VARCHAR(100),

    start_date DATE,
    end_date DATE,

    total_cost NUMERIC(14, 2),
    total_direct_cost NUMERIC(14, 2),
    total_indirect_cost NUMERIC(14, 2),
    total_cost_limit NUMERIC(14, 2),
    cost_sharing_amount NUMERIC(14, 2),
    underrecovery_amount NUMERIC(14, 2),
    residual_funds NUMERIC(14, 2),
    obligated_amount NUMERIC(14, 2),
    obligated_total NUMERIC(14, 2),

    oh_rate_class_code VARCHAR(10),
    oh_rate_type_code VARCHAR(10),
    ur_rate_class_code VARCHAR(10),
    modular_budget_flag VARCHAR(10),
    on_off_campus_flag VARCHAR(10),
    submit_cost_sharing_flag VARCHAR(10),
    parent_document_type_code VARCHAR(20),
    budget_adjustment_document_number VARCHAR(30),

    comments TEXT,
    budget_justification TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_award
    ON archive.award_budget (award_id, budget_id);


-- archive.award_budget_period: merges AWARD_BUDGET_PERIOD_EXT +
-- BUDGET_PERIODS. budget_period is the numeric period number (1, 2,
-- 3...) - not to be confused with budget_period_id, the surrogate PK.
CREATE TABLE IF NOT EXISTS archive.award_budget_period (
    budget_period_id BIGINT PRIMARY KEY,
    budget_id BIGINT REFERENCES archive.award_budget(budget_id) ON DELETE CASCADE,
    budget_period INTEGER,

    start_date DATE,
    end_date DATE,

    total_cost NUMERIC(14, 2),
    total_direct_cost NUMERIC(14, 2),
    total_indirect_cost NUMERIC(14, 2),
    total_cost_limit NUMERIC(14, 2),
    cost_sharing_amount NUMERIC(14, 2),
    underrecovery_amount NUMERIC(14, 2),
    number_of_participants INTEGER,
    obligated_amount NUMERIC(14, 2),
    total_fringe_amount NUMERIC(14, 2),
    fringe_overridden VARCHAR(10),
    f_and_a_overridden VARCHAR(10),

    comments TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_period_budget
    ON archive.award_budget_period (budget_id);


-- archive.award_budget_line_item: merges AWARD_BUDGET_DETAILS_EXT +
-- BUDGET_DETAILS.
CREATE TABLE IF NOT EXISTS archive.award_budget_line_item (
    budget_line_item_id BIGINT PRIMARY KEY,
    budget_period_id BIGINT REFERENCES archive.award_budget_period(budget_period_id) ON DELETE CASCADE,
    budget_id BIGINT,
    budget_period INTEGER,
    line_item_number INTEGER,

    budget_category_code VARCHAR(20),
    cost_element VARCHAR(20),
    line_item_description VARCHAR(200),
    group_name VARCHAR(50),
    based_on_line_item INTEGER,
    line_item_sequence INTEGER,

    start_date DATE,
    end_date DATE,

    line_item_cost NUMERIC(14, 2),
    cost_sharing_amount NUMERIC(14, 2),
    underrecovery_amount NUMERIC(14, 2),
    obligated_amount NUMERIC(14, 2),
    quantity INTEGER,

    on_off_campus_flag VARCHAR(10),
    apply_in_rate_flag VARCHAR(10),
    submit_cost_sharing_flag VARCHAR(10),
    formulated_cost_element_flag VARCHAR(10),

    subaward_number INTEGER,
    hierarchy_proposal_number VARCHAR(20),
    hidden_in_hierarchy VARCHAR(10),

    budget_justification TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_line_item_period
    ON archive.award_budget_line_item (budget_period_id);


-- archive.award_budget_line_item_calculated_amount: merges
-- AWD_BGT_DET_CAL_AMTS_EXT + BUDGET_DETAILS_CAL_AMTS.
-- budget_period_id here is a real physical column
-- (BUDGET_DETAILS_CAL_AMTS.BUDGET_PERIOD_NUMBER) whose OJB
-- field-descriptor is commented out in repository-budget.xml - kept
-- anyway since it costs nothing and may aid reconciliation.
-- rate_type_description is denormalized by Oracle itself, not
-- computed here.
CREATE TABLE IF NOT EXISTS archive.award_budget_line_item_calculated_amount (
    budget_line_item_calculated_amount_id BIGINT PRIMARY KEY,
    budget_line_item_id BIGINT REFERENCES archive.award_budget_line_item(budget_line_item_id) ON DELETE CASCADE,
    budget_period_id BIGINT,
    budget_id BIGINT,
    budget_period INTEGER,
    line_item_number INTEGER,

    rate_class_code VARCHAR(10),
    rate_type_code VARCHAR(10),
    rate_type_description VARCHAR(300),

    apply_rate_flag VARCHAR(10),
    calculated_cost NUMERIC(14, 2),
    calculated_cost_sharing NUMERIC(14, 2),
    obligated_amount NUMERIC(14, 2),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_line_item_cal_amt_line_item
    ON archive.award_budget_line_item_calculated_amount (budget_line_item_id);


-- archive.award_budget_personnel_detail: merges AWD_BUDGET_PER_DET_EXT
-- + BUDGET_PERSONNEL_DETAILS. person_sequence_number is a bare,
-- unenforced reference to BUDGET_PERSONS (the budget-level personnel
-- roster) - not archived in this bundle, see the design doc's Open
-- Questions.
CREATE TABLE IF NOT EXISTS archive.award_budget_personnel_detail (
    budget_personnel_line_item_id BIGINT PRIMARY KEY,
    budget_line_item_id BIGINT REFERENCES archive.award_budget_line_item(budget_line_item_id) ON DELETE CASCADE,
    budget_period_id BIGINT,
    budget_id BIGINT,
    budget_period INTEGER,
    line_item_number INTEGER,
    person_number INTEGER,
    person_sequence_number INTEGER,

    person_id VARCHAR(50),
    job_code VARCHAR(20),
    period_type_code VARCHAR(10),
    line_item_description VARCHAR(200),
    sequence_number INTEGER,

    start_date DATE,
    end_date DATE,

    salary_requested NUMERIC(14, 2),
    percent_charged NUMERIC(7, 2),
    percent_effort NUMERIC(7, 2),
    cost_sharing_percent NUMERIC(7, 2),
    cost_sharing_amount NUMERIC(14, 2),
    underrecovery_amount NUMERIC(14, 2),
    obligated_amount NUMERIC(14, 2),

    on_off_campus_flag VARCHAR(10),
    apply_in_rate_flag VARCHAR(10),

    budget_justification TEXT,

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_personnel_detail_line_item
    ON archive.award_budget_personnel_detail (budget_line_item_id);


-- archive.award_budget_personnel_calculated_amount: merges
-- AWD_BUDGET_PER_CAL_AMTS_EXT + BUDGET_PERSONNEL_CAL_AMTS.
CREATE TABLE IF NOT EXISTS archive.award_budget_personnel_calculated_amount (
    budget_personnel_calculated_amount_id BIGINT PRIMARY KEY,
    budget_personnel_line_item_id BIGINT REFERENCES archive.award_budget_personnel_detail(budget_personnel_line_item_id) ON DELETE CASCADE,
    budget_period_id BIGINT,
    budget_id BIGINT,
    budget_period INTEGER,
    line_item_number INTEGER,
    person_number INTEGER,

    rate_class_code VARCHAR(10),
    rate_type_code VARCHAR(10),
    rate_type_description VARCHAR(300),

    apply_rate_flag VARCHAR(10),
    calculated_cost NUMERIC(14, 2),
    calculated_cost_sharing NUMERIC(14, 2),
    obligated_amount NUMERIC(14, 2),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_personnel_cal_amt_detail
    ON archive.award_budget_personnel_calculated_amount (budget_personnel_line_item_id);


-- archive.award_budget_period_summary_calculated_amount: standalone,
-- no generic counterpart. Serves TWO logical roles depending on
-- rate_class_type ('E' = fringe/employee-benefit amounts, 'O' = F&A/
-- overhead amounts) - kept as one table with rate_class_type intact,
-- matching Kuali's own single-table, query-filtered design (see the
-- design doc's Findings).
CREATE TABLE IF NOT EXISTS archive.award_budget_period_summary_calculated_amount (
    award_budget_period_summary_calculated_amount_id BIGINT PRIMARY KEY,
    budget_period_id BIGINT REFERENCES archive.award_budget_period(budget_period_id) ON DELETE CASCADE,

    cost_element VARCHAR(20),
    on_off_campus_flag VARCHAR(10),
    rate_class_type VARCHAR(10),
    calculated_cost NUMERIC(14, 2),
    calculated_cost_sharing NUMERIC(14, 2),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_period_summary_cal_amt_period
    ON archive.award_budget_period_summary_calculated_amount (budget_period_id);


-- archive.award_budget_limit: standalone, no generic counterpart.
-- Both award_id and budget_id have REAL Oracle-enforced FK constraints
-- (V310_3_066), mirrored here as real Postgres FKs - both nullable in
-- real Oracle DDL despite being constrained.
CREATE TABLE IF NOT EXISTS archive.award_budget_limit (
    budget_limit_id BIGINT PRIMARY KEY,
    award_id BIGINT REFERENCES archive.award_version(award_id) ON DELETE CASCADE,
    budget_id BIGINT REFERENCES archive.award_budget(budget_id) ON DELETE CASCADE,

    limit_type_code VARCHAR(150),
    limit_amount NUMERIC(14, 2),

    source_update_timestamp TIMESTAMP,
    source_update_user VARCHAR(100),
    source_version_number BIGINT,

    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_budget_limit_award
    ON archive.award_budget_limit (award_id);

CREATE INDEX IF NOT EXISTS ix_award_budget_limit_budget
    ON archive.award_budget_limit (budget_id);
