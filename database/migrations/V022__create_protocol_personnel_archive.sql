CREATE TABLE IF NOT EXISTS archive.protocol_person (
    protocol_person_id          BIGINT PRIMARY KEY,
    protocol_id                 BIGINT NOT NULL
                                    REFERENCES archive.protocol_version(
                                        protocol_id
                                    ),
    protocol_number             VARCHAR(60) NOT NULL,
    sequence_number             INTEGER NOT NULL,
    person_id                   VARCHAR(100),
    person_name                 VARCHAR(500),
    protocol_person_role_id     VARCHAR(100),
    rolodex_id                  BIGINT,
    affiliation_type_code       VARCHAR(100),
    comments                    TEXT,
    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    source_version_number       BIGINT,
    source_object_id            VARCHAR(100),
    archived_at                 TIMESTAMPTZ NOT NULL
                                    DEFAULT CURRENT_TIMESTAMP,
    load_run_id                 BIGINT
                                    REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_person_protocol
    ON archive.protocol_person (
        protocol_id,
        protocol_person_id
    );

CREATE INDEX IF NOT EXISTS ix_protocol_person_family_sequence
    ON archive.protocol_person (
        protocol_number,
        sequence_number
    );

CREATE TABLE IF NOT EXISTS archive.protocol_unit (
    protocol_units_id           BIGINT PRIMARY KEY,
    protocol_person_id          BIGINT NOT NULL
                                    REFERENCES archive.protocol_person(
                                        protocol_person_id
                                    ),
    protocol_number             VARCHAR(60) NOT NULL,
    sequence_number             INTEGER NOT NULL,
    unit_number                 VARCHAR(100),
    lead_unit_flag              VARCHAR(10),
    person_id                   VARCHAR(100),
    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    source_version_number       BIGINT,
    source_object_id            VARCHAR(100),
    archived_at                 TIMESTAMPTZ NOT NULL
                                    DEFAULT CURRENT_TIMESTAMP,
    load_run_id                 BIGINT
                                    REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_unit_person
    ON archive.protocol_unit (
        protocol_person_id,
        protocol_units_id
    );

CREATE INDEX IF NOT EXISTS ix_protocol_unit_family_sequence
    ON archive.protocol_unit (
        protocol_number,
        sequence_number
    );
