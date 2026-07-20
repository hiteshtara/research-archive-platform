CREATE TABLE IF NOT EXISTS archive.award_unit_contact (
    award_unit_contact_id            BIGINT PRIMARY KEY,

    award_id                         BIGINT NOT NULL
                                         REFERENCES archive.award_version(award_id)
                                         ON DELETE CASCADE,

    award_number                     VARCHAR(50) NOT NULL,
    sequence_number                  INTEGER NOT NULL,

    person_id                        VARCHAR(50),
    full_name                        VARCHAR(500),

    unit_number                      VARCHAR(50),
    unit_name                        VARCHAR(500),

    parent_unit_number               VARCHAR(50),
    parent_unit_name                 VARCHAR(500),

    unit_administrator_type_code     VARCHAR(50),
    project_role                     VARCHAR(500),

    unit_contact_type                VARCHAR(50),
    default_unit_contact             VARCHAR(10),

    primary_title                    VARCHAR(500),
    directory_title                  VARCHAR(500),
    office_location                  VARCHAR(300),

    email_address                    VARCHAR(500),
    office_phone                     VARCHAR(100),
    phone_extension                  VARCHAR(50),

    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),
    source_version_number            BIGINT,
    source_object_id                 VARCHAR(100),

    loaded_at                        TIMESTAMPTZ NOT NULL
                                         DEFAULT CURRENT_TIMESTAMP,

    load_id                          BIGINT
                                         REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_award
    ON archive.award_unit_contact (
        award_id,
        award_unit_contact_id
    );

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_number
    ON archive.award_unit_contact (
        award_number,
        sequence_number
    );

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_person
    ON archive.award_unit_contact (person_id);

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_unit
    ON archive.award_unit_contact (unit_number);

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_role
    ON archive.award_unit_contact (
        unit_administrator_type_code
    );

CREATE INDEX IF NOT EXISTS ix_award_unit_contact_email
    ON archive.award_unit_contact (email_address);
