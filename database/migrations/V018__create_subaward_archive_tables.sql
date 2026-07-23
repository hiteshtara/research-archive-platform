CREATE TABLE IF NOT EXISTS archive.subaward (
    subaward_id                         BIGINT PRIMARY KEY,
    document_number                    VARCHAR(100),
    sequence_number                    INTEGER NOT NULL,
    subaward_code                      VARCHAR(100) NOT NULL,
    organization_id                    VARCHAR(100),
    start_date                         DATE,
    end_date                           DATE,
    subaward_type_code                 BIGINT,
    purchase_order_num                 VARCHAR(100),
    title                              TEXT,
    status_code                        BIGINT,
    status_description                 VARCHAR(500),
    account_number                     VARCHAR(100),
    vendor_number                      VARCHAR(100),
    requisitioner_id                   VARCHAR(100),
    requisitioner_unit                 VARCHAR(100),
    archive_location                   TEXT,
    closeout_date                      DATE,
    comments                           TEXT,
    site_investigator                  BIGINT,
    cost_type                          VARCHAR(100),
    date_of_fully_executed             DATE,
    requisition_number                 VARCHAR(100),
    fed_award_proj_desc                TEXT,
    f_and_a_rate                       NUMERIC(18, 2),
    de_minimus                         VARCHAR(10),
    subaward_sequence_status           VARCHAR(100),
    ffata_required                     VARCHAR(10),
    fsrs_subaward_number               VARCHAR(100),
    award_prime_sponsor_name           VARCHAR(500),
    award_sponsor_name                 VARCHAR(500),
    extension_date_received            DATE,
    source_update_timestamp            TIMESTAMP,
    source_update_user                 VARCHAR(100),
    source_version_number              BIGINT,
    source_object_id                   VARCHAR(100),
    document_source_update_timestamp   TIMESTAMP,
    document_source_update_user        VARCHAR(100),
    document_source_version_number     BIGINT,
    document_source_object_id          VARCHAR(100),
    loaded_at                          TIMESTAMPTZ NOT NULL
                                           DEFAULT CURRENT_TIMESTAMP,
    load_id                            BIGINT
                                           REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_code_sequence
    ON archive.subaward (subaward_code, sequence_number DESC);

CREATE INDEX IF NOT EXISTS ix_subaward_document_number
    ON archive.subaward (document_number);

CREATE INDEX IF NOT EXISTS ix_subaward_status
    ON archive.subaward (status_code);

CREATE INDEX IF NOT EXISTS ix_subaward_organization
    ON archive.subaward (organization_id);


CREATE TABLE IF NOT EXISTS archive.subaward_amount (
    subaward_amount_info_id             BIGINT PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    obligated_amount                    NUMERIC(18, 2),
    obligated_change                    NUMERIC(18, 2),
    obligated_change_direct             NUMERIC(18, 2),
    obligated_change_indirect           NUMERIC(18, 2),
    anticipated_amount                  NUMERIC(18, 2),
    anticipated_change                  NUMERIC(18, 2),
    anticipated_change_direct           NUMERIC(18, 2),
    anticipated_change_indirect         NUMERIC(18, 2),
    rate                                NUMERIC(18, 2),
    effective_date                      DATE,
    modification_effective_date         DATE,
    modification_number                 VARCHAR(100),
    modification_type_code              VARCHAR(100),
    modification_type_description       VARCHAR(500),
    performance_start_date              DATE,
    performance_end_date                DATE,
    purchase_order_num                  VARCHAR(100),
    comments                            TEXT,
    file_data_id                        VARCHAR(100),
    file_name                           TEXT,
    mime_type                           VARCHAR(255),
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_amount_parent
    ON archive.subaward_amount (subaward_id, subaward_amount_info_id);

CREATE INDEX IF NOT EXISTS ix_subaward_amount_family
    ON archive.subaward_amount (subaward_code, sequence_number);


CREATE TABLE IF NOT EXISTS archive.subaward_contact (
    subaward_contact_id                 BIGINT PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    contact_type_code                   VARCHAR(100),
    rolodex_id                          BIGINT,
    requisitioner_id                    VARCHAR(100),
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_contact_parent
    ON archive.subaward_contact (subaward_id, subaward_contact_id);

CREATE INDEX IF NOT EXISTS ix_subaward_contact_rolodex
    ON archive.subaward_contact (rolodex_id);


CREATE TABLE IF NOT EXISTS archive.subaward_custom_data (
    subaward_custom_data_id             BIGINT PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    custom_attribute_id                 BIGINT,
    value                               TEXT,
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_custom_data_parent
    ON archive.subaward_custom_data (subaward_id, subaward_custom_data_id);

CREATE INDEX IF NOT EXISTS ix_subaward_custom_attribute
    ON archive.subaward_custom_data (custom_attribute_id);


CREATE TABLE IF NOT EXISTS archive.subaward_funding (
    subaward_funding_source_id          BIGINT PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    award_id                            BIGINT,
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_funding_parent
    ON archive.subaward_funding (subaward_id, subaward_funding_source_id);

CREATE INDEX IF NOT EXISTS ix_subaward_funding_award
    ON archive.subaward_funding (award_id);


CREATE TABLE IF NOT EXISTS archive.subaward_attachment (
    attachment_id                       BIGINT PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    attachment_type_code                BIGINT,
    attachment_type_description         VARCHAR(500),
    document_id                         BIGINT,
    file_data_id                        VARCHAR(100),
    file_name                           TEXT,
    mime_type                           VARCHAR(255),
    document_status_code                VARCHAR(100),
    description                         TEXT,
    last_update_timestamp               TIMESTAMP,
    last_update_user                    VARCHAR(100),
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_attachment_parent
    ON archive.subaward_attachment (subaward_id, attachment_id);

CREATE INDEX IF NOT EXISTS ix_subaward_attachment_file_data
    ON archive.subaward_attachment (file_data_id);


CREATE TABLE IF NOT EXISTS archive.subaward_closeout (
    subaward_closeout_id                 BIGINT PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    closeout_number                     INTEGER,
    closeout_type_code                  BIGINT,
    date_requested                      DATE,
    date_followup                       DATE,
    date_received                       DATE,
    comments                            TEXT,
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_closeout_parent
    ON archive.subaward_closeout (subaward_id, subaward_closeout_id);


CREATE TABLE IF NOT EXISTS archive.subaward_report (
    subaward_report_id                  VARCHAR(100) PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    report_type_code                    VARCHAR(100),
    report_type_description             VARCHAR(500),
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_report_parent
    ON archive.subaward_report (subaward_id, subaward_report_id);


CREATE TABLE IF NOT EXISTS archive.subaward_notepad (
    subaward_notepad_id                 BIGINT PRIMARY KEY,
    subaward_id                         BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    entry_number                        INTEGER,
    note_topic                          TEXT,
    comments                            TEXT,
    restricted_view                    VARCHAR(10),
    create_timestamp                    TIMESTAMP,
    create_user                         VARCHAR(100),
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_notepad_parent
    ON archive.subaward_notepad (subaward_id, subaward_notepad_id);


CREATE TABLE IF NOT EXISTS archive.subaward_notification (
    notification_id                     BIGINT PRIMARY KEY,
    owning_document_id_fk               BIGINT NOT NULL
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    document_number                     VARCHAR(100),
    subaward_code                       VARCHAR(100),
    notification_type_id                BIGINT,
    recipients                          TEXT,
    subject                             TEXT,
    message                             TEXT,
    create_timestamp                    TIMESTAMP,
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    source_version_number               BIGINT,
    source_object_id                    VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_notification_parent
    ON archive.subaward_notification (owning_document_id_fk, notification_id);

CREATE INDEX IF NOT EXISTS ix_subaward_notification_document
    ON archive.subaward_notification (document_number);


CREATE TABLE IF NOT EXISTS archive.subaward_template_info (
    subaward_id                         BIGINT PRIMARY KEY
                                            REFERENCES archive.subaward(subaward_id)
                                            ON DELETE CASCADE,
    subaward_code                       VARCHAR(100) NOT NULL,
    sequence_number                     INTEGER NOT NULL,
    sow_or_sub_proposal_budget          VARCHAR(100),
    sub_proposal_date                   DATE,
    invoice_or_payment_contact          BIGINT,
    irb_iacuc_contact                   BIGINT,
    final_stmt_of_costs_contact         BIGINT,
    change_requests_contact             BIGINT,
    sub_change_requests_contact         BIGINT,
    termination_contact                 BIGINT,
    sub_termination_contact             BIGINT,
    no_cost_extension_contact           BIGINT,
    perf_site_diff_from_org_addr        VARCHAR(10),
    perf_site_same_as_sub_pi_addr       VARCHAR(10),
    sub_registered_in_ccr               VARCHAR(10),
    sub_exempt_from_reporting_comp      VARCHAR(10),
    parent_duns_number                  VARCHAR(100),
    parent_congressional_district       VARCHAR(100),
    exempt_from_rprtg_exec_comp         VARCHAR(10),
    copyright_type                      VARCHAR(100),
    automatic_carry_forward             VARCHAR(10),
    carry_forward_requests_sent_to      BIGINT,
    treatment_prgm_income_additive      VARCHAR(10),
    applicable_program_regulations      TEXT,
    applicable_program_regs_date        DATE,
    mpi_award                           VARCHAR(10),
    mpi_leadership_plan                 TEXT,
    r_and_d                             VARCHAR(10),
    includes_cost_sharing               VARCHAR(10),
    fcio                                VARCHAR(10),
    invoices_emailed                    VARCHAR(10),
    invoice_address_diff                VARCHAR(10),
    invoice_email_diff                  VARCHAR(10),
    fcio_subrec_policy_cd               VARCHAR(100),
    animal_flag                         VARCHAR(10),
    animal_pte_send_cd                  VARCHAR(100),
    animal_pte_nr_cd                    VARCHAR(100),
    human_flag                          VARCHAR(10),
    human_subjects                      VARCHAR(100),
    human_exempt_docs                   VARCHAR(100),
    human_pte_send_cd                   VARCHAR(100),
    human_pte_nr_cd                     VARCHAR(100),
    human_data_exchange_agree_cd        VARCHAR(100),
    human_data_exchange_terms_cd        VARCHAR(100),
    human_includes_clinical_trials      VARCHAR(100),
    additional_terms                    TEXT,
    treatment_of_income                 TEXT,
    data_sharing_attachment             VARCHAR(100),
    data_sharing_cd                     VARCHAR(100),
    final_statement_due_cd              VARCHAR(100),
    source_update_timestamp             TIMESTAMP,
    source_update_user                  VARCHAR(100),
    loaded_at                           TIMESTAMPTZ NOT NULL
                                            DEFAULT CURRENT_TIMESTAMP,
    load_id                             BIGINT
                                            REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_subaward_template_family
    ON archive.subaward_template_info (subaward_code, sequence_number);
