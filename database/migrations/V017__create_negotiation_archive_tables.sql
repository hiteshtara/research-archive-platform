CREATE TABLE IF NOT EXISTS archive.negotiation (
    negotiation_id                           BIGINT PRIMARY KEY,
    document_number                         VARCHAR(100),

    negotiation_status_id                   BIGINT,
    negotiation_status_code                 VARCHAR(100),
    negotiation_status_description          VARCHAR(500),

    negotiation_agreement_type_id           BIGINT,
    negotiation_agreement_type_code         VARCHAR(100),
    negotiation_agreement_type_description  VARCHAR(500),

    negotiation_association_type_id         BIGINT,
    negotiation_association_type_code       VARCHAR(100),
    negotiation_association_type_description VARCHAR(500),

    negotiator_person_id                    VARCHAR(100),
    negotiator_full_name                    VARCHAR(500),

    negotiation_start_date                  DATE,
    negotiation_end_date                    DATE,
    anticipated_award_date                  DATE,
    document_folder                         TEXT,
    associated_document_id                  TEXT,

    source_update_timestamp                 TIMESTAMP,
    source_update_user                      VARCHAR(100),
    source_version_number                   BIGINT,
    source_object_id                        VARCHAR(100),

    document_source_update_timestamp        TIMESTAMP,
    document_source_update_user             VARCHAR(100),
    document_source_version_number          BIGINT,
    document_source_object_id               VARCHAR(100),

    loaded_at                               TIMESTAMPTZ NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP,
    load_id                                 BIGINT
                                                REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_negotiation_document_number
    ON archive.negotiation (document_number);

CREATE INDEX IF NOT EXISTS ix_negotiation_status
    ON archive.negotiation (negotiation_status_id);

CREATE INDEX IF NOT EXISTS ix_negotiation_agreement_type
    ON archive.negotiation (negotiation_agreement_type_id);

CREATE INDEX IF NOT EXISTS ix_negotiation_association
    ON archive.negotiation (
        negotiation_association_type_id,
        associated_document_id
    );

CREATE INDEX IF NOT EXISTS ix_negotiation_negotiator
    ON archive.negotiation (negotiator_person_id);


CREATE TABLE IF NOT EXISTS archive.negotiation_activity (
    negotiation_activity_id                 BIGINT PRIMARY KEY,
    negotiation_id                          BIGINT NOT NULL
                                                REFERENCES archive.negotiation(negotiation_id)
                                                ON DELETE CASCADE,

    activity_type_id                        BIGINT,
    activity_type_code                      VARCHAR(100),
    activity_type_description               VARCHAR(500),
    location_id                             BIGINT,
    location_code                           VARCHAR(100),
    location_description                    VARCHAR(500),

    start_date                              DATE,
    end_date                                DATE,
    create_date                             DATE,
    followup_date                           DATE,
    last_modified_user                      VARCHAR(100),
    last_modified_date                      DATE,
    description                             TEXT,
    restricted                              VARCHAR(10),

    source_update_timestamp                 TIMESTAMP,
    source_update_user                      VARCHAR(100),
    source_version_number                   BIGINT,
    source_object_id                        VARCHAR(100),

    loaded_at                               TIMESTAMPTZ NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP,
    load_id                                 BIGINT
                                                REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_negotiation_activity_parent
    ON archive.negotiation_activity (
        negotiation_id,
        negotiation_activity_id
    );

CREATE INDEX IF NOT EXISTS ix_negotiation_activity_type
    ON archive.negotiation_activity (activity_type_id);

CREATE INDEX IF NOT EXISTS ix_negotiation_activity_location
    ON archive.negotiation_activity (location_id);


CREATE TABLE IF NOT EXISTS archive.negotiation_custom_data (
    negotiation_custom_data_id              BIGINT PRIMARY KEY,
    negotiation_id                          BIGINT NOT NULL
                                                REFERENCES archive.negotiation(negotiation_id)
                                                ON DELETE CASCADE,
    negotiation_number                      VARCHAR(100),
    custom_attribute_id                     BIGINT,
    value                                   TEXT,

    source_update_timestamp                 TIMESTAMP,
    source_update_user                      VARCHAR(100),
    source_version_number                   BIGINT,
    source_object_id                        VARCHAR(100),

    loaded_at                               TIMESTAMPTZ NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP,
    load_id                                 BIGINT
                                                REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_negotiation_custom_data_parent
    ON archive.negotiation_custom_data (
        negotiation_id,
        negotiation_custom_data_id
    );

CREATE INDEX IF NOT EXISTS ix_negotiation_custom_attribute
    ON archive.negotiation_custom_data (custom_attribute_id);


CREATE TABLE IF NOT EXISTS archive.negotiation_notification (
    notification_id                         BIGINT PRIMARY KEY,
    notification_type_id                    BIGINT,
    document_number                         VARCHAR(100),
    owning_document_id_fk                   BIGINT NOT NULL
                                                REFERENCES archive.negotiation(negotiation_id)
                                                ON DELETE CASCADE,
    recipients                              TEXT,
    subject                                 TEXT,
    message                                 TEXT,

    source_update_timestamp                 TIMESTAMP,
    source_update_user                      VARCHAR(100),
    source_version_number                   BIGINT,
    source_object_id                        VARCHAR(100),

    loaded_at                               TIMESTAMPTZ NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP,
    load_id                                 BIGINT
                                                REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_negotiation_notification_parent
    ON archive.negotiation_notification (
        owning_document_id_fk,
        notification_id
    );

CREATE INDEX IF NOT EXISTS ix_negotiation_notification_document
    ON archive.negotiation_notification (document_number);

CREATE INDEX IF NOT EXISTS ix_negotiation_notification_type
    ON archive.negotiation_notification (notification_type_id);


CREATE TABLE IF NOT EXISTS archive.negotiation_unassociated_detail (
    negotiation_unassoc_detail_id           BIGINT PRIMARY KEY,
    negotiation_id                          BIGINT NOT NULL
                                                REFERENCES archive.negotiation(negotiation_id)
                                                ON DELETE CASCADE,
    title                                   TEXT,
    pi_person_id                            VARCHAR(100),
    pi_rolodex_id                           VARCHAR(100),
    lead_unit                               VARCHAR(100),
    sponsor_code                            VARCHAR(100),
    pi_name                                 VARCHAR(500),
    prime_sponsor_code                      VARCHAR(100),
    sponsor_award_number                    VARCHAR(200),
    contact_admin_person_id                 VARCHAR(100),
    subaward_org                            VARCHAR(100),

    source_update_timestamp                 TIMESTAMP,
    source_update_user                      VARCHAR(100),
    source_version_number                   BIGINT,
    source_object_id                        VARCHAR(100),

    loaded_at                               TIMESTAMPTZ NOT NULL
                                                DEFAULT CURRENT_TIMESTAMP,
    load_id                                 BIGINT
                                                REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_negotiation_unassociated_parent
    ON archive.negotiation_unassociated_detail (
        negotiation_id,
        negotiation_unassoc_detail_id
    );

CREATE INDEX IF NOT EXISTS ix_negotiation_unassociated_unit
    ON archive.negotiation_unassociated_detail (lead_unit);

CREATE INDEX IF NOT EXISTS ix_negotiation_unassociated_sponsor
    ON archive.negotiation_unassociated_detail (sponsor_code);
