CREATE TABLE IF NOT EXISTS archive.protocol_funding (
    protocol_funding_source_id BIGINT PRIMARY KEY,
    protocol_id                BIGINT NOT NULL
                                   REFERENCES archive.protocol_version(
                                       protocol_id
                                   ),
    source_protocol_id         BIGINT NOT NULL,
    protocol_number            VARCHAR(60) NOT NULL,
    sequence_number            INTEGER NOT NULL,
    funding_source_type_code   VARCHAR(100),
    funding_source_number      TEXT,
    funding_source_name        TEXT,
    source_update_timestamp    TIMESTAMP,
    source_update_user         VARCHAR(100),
    source_version_number      BIGINT,
    source_object_id           VARCHAR(100),
    archived_at                TIMESTAMPTZ NOT NULL
                                   DEFAULT CURRENT_TIMESTAMP,
    load_run_id                BIGINT
                                   REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_funding_protocol
    ON archive.protocol_funding (
        protocol_id,
        protocol_funding_source_id
    );

CREATE INDEX IF NOT EXISTS ix_protocol_funding_family_sequence
    ON archive.protocol_funding (
        protocol_number,
        sequence_number
    );

CREATE INDEX IF NOT EXISTS ix_protocol_funding_source_protocol
    ON archive.protocol_funding (source_protocol_id);

COMMENT ON COLUMN archive.protocol_funding.protocol_id IS
    'Archive parent resolved from protocol_number + sequence_number';

COMMENT ON COLUMN archive.protocol_funding.source_protocol_id IS
    'Original KCOEUS.PROTOCOL_FUNDING_SOURCE.PROTOCOL_ID';
