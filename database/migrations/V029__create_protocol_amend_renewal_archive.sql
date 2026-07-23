CREATE TABLE IF NOT EXISTS archive.protocol_amend_renewal (
    proto_amend_renewal_id       BIGINT PRIMARY KEY,
    protocol_id                  BIGINT NOT NULL
                                     REFERENCES archive.protocol_version(
                                         protocol_id
                                     ),
    source_protocol_id           BIGINT NOT NULL,
    protocol_number              VARCHAR(60) NOT NULL,
    sequence_number              INTEGER NOT NULL,
    proto_amend_ren_number       VARCHAR(100),
    date_created                 DATE,
    summary                      TEXT,
    source_update_timestamp      TIMESTAMP,
    source_update_user           VARCHAR(100),
    source_version_number        BIGINT,
    source_object_id             VARCHAR(100),
    archived_at                  TIMESTAMPTZ NOT NULL
                                     DEFAULT CURRENT_TIMESTAMP,
    load_run_id                  BIGINT
                                     REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_amend_renewal_protocol
    ON archive.protocol_amend_renewal (
        protocol_id,
        proto_amend_renewal_id
    );

CREATE INDEX IF NOT EXISTS ix_protocol_amend_renewal_number
    ON archive.protocol_amend_renewal (proto_amend_ren_number);

CREATE INDEX IF NOT EXISTS ix_protocol_amend_renewal_created
    ON archive.protocol_amend_renewal (date_created);

COMMENT ON COLUMN archive.protocol_amend_renewal.protocol_id IS
    'Exact archive parent resolved only by protocol_number + sequence_number';

COMMENT ON COLUMN archive.protocol_amend_renewal.source_protocol_id IS
    'Original KCOEUS.PROTO_AMEND_RENEWAL.PROTOCOL_ID; audit only';
