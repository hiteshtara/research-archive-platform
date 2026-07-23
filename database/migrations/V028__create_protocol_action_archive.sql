CREATE TABLE IF NOT EXISTS archive.protocol_action (
    protocol_action_id              BIGINT PRIMARY KEY,
    action_id                       INTEGER,
    protocol_id                     BIGINT NOT NULL
                                        REFERENCES archive.protocol_version(
                                            protocol_id
                                        ),
    source_protocol_id              BIGINT NOT NULL,
    protocol_number                 VARCHAR(60) NOT NULL,
    sequence_number                 INTEGER NOT NULL,
    submission_number               INTEGER,
    submission_id_fk                BIGINT,
    protocol_action_type_code       VARCHAR(100),
    comments                        TEXT,
    prev_submission_status_code     VARCHAR(100),
    submission_type_code            VARCHAR(100),
    prev_protocol_status_code       VARCHAR(100),
    source_create_timestamp         TIMESTAMP,
    source_create_user              VARCHAR(100),
    source_update_timestamp         TIMESTAMP,
    source_update_user              VARCHAR(100),
    action_date                     TIMESTAMP,
    actual_action_date              TIMESTAMP,
    source_version_number           BIGINT,
    source_object_id                VARCHAR(100),
    followup_action_code            VARCHAR(100),
    archived_at                     TIMESTAMPTZ NOT NULL
                                        DEFAULT CURRENT_TIMESTAMP,
    load_run_id                     BIGINT
                                        REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_action_protocol
    ON archive.protocol_action (protocol_id, protocol_action_id);

CREATE INDEX IF NOT EXISTS ix_protocol_action_submission
    ON archive.protocol_action (submission_id_fk);

CREATE INDEX IF NOT EXISTS ix_protocol_action_type
    ON archive.protocol_action (protocol_action_type_code);

CREATE INDEX IF NOT EXISTS ix_protocol_action_date
    ON archive.protocol_action (action_date);

COMMENT ON COLUMN archive.protocol_action.protocol_id IS
    'Exact archive parent resolved only by protocol_number + sequence_number';

COMMENT ON COLUMN archive.protocol_action.source_protocol_id IS
    'Original KCOEUS.PROTOCOL_ACTIONS.PROTOCOL_ID; audit only';

COMMENT ON COLUMN archive.protocol_action.submission_id_fk IS
    'Original submission owner reference; metadata only for this slice';
