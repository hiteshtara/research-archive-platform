CREATE TABLE IF NOT EXISTS archive.protocol_location (
    protocol_location_id        BIGINT PRIMARY KEY,
    protocol_id                 BIGINT NOT NULL
                                    REFERENCES archive.protocol_version(
                                        protocol_id
                                    ),
    source_protocol_id          BIGINT NOT NULL,
    protocol_number             VARCHAR(60) NOT NULL,
    sequence_number             INTEGER NOT NULL,
    parent_resolution_method    VARCHAR(30) NOT NULL,
    protocol_org_type_code      VARCHAR(100),
    organization_id             VARCHAR(100),
    rolodex_id                  BIGINT,
    source_update_timestamp     TIMESTAMP,
    source_update_user          VARCHAR(100),
    source_version_number       BIGINT,
    source_object_id            VARCHAR(100),
    archived_at                 TIMESTAMPTZ NOT NULL
                                    DEFAULT CURRENT_TIMESTAMP,
    load_run_id                 BIGINT
                                    REFERENCES archive.load_run(load_id),
    CONSTRAINT ck_protocol_location_parent_resolution
        CHECK (
            parent_resolution_method IN (
                'NUMBER_SEQUENCE',
                'DIRECT_ID_PLACEHOLDER'
            )
        )
);

CREATE INDEX IF NOT EXISTS ix_protocol_location_protocol
    ON archive.protocol_location (protocol_id, protocol_location_id);

CREATE INDEX IF NOT EXISTS ix_protocol_location_family_sequence
    ON archive.protocol_location (protocol_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_protocol_location_organization
    ON archive.protocol_location (organization_id);

COMMENT ON COLUMN archive.protocol_location.protocol_id IS
    'Archive parent resolved by the controlled parent_resolution_method';

COMMENT ON COLUMN archive.protocol_location.source_protocol_id IS
    'Original KCOEUS.PROTOCOL_LOCATION.PROTOCOL_ID';

COMMENT ON COLUMN archive.protocol_location.parent_resolution_method IS
    'NUMBER_SEQUENCE or controlled DIRECT_ID_PLACEHOLDER for source (0,0)';
