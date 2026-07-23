CREATE TABLE IF NOT EXISTS archive.protocol_submission (
    submission_id                      BIGINT PRIMARY KEY,
    protocol_id                        BIGINT NOT NULL
                                           REFERENCES archive.protocol_version(
                                               protocol_id
                                           ),
    source_protocol_id                 BIGINT NOT NULL,
    protocol_number                    VARCHAR(60) NOT NULL,
    sequence_number                    INTEGER NOT NULL,
    submission_number                  INTEGER,
    schedule_id                        VARCHAR(100),
    committee_id                       VARCHAR(100),
    submission_type_code               VARCHAR(100),
    submission_type_qual_code          VARCHAR(100),
    submission_status_code             VARCHAR(100),
    schedule_id_fk                     BIGINT,
    committee_id_fk                    BIGINT,
    protocol_review_type_code          VARCHAR(100),
    submission_date                    DATE,
    comments                           TEXT,
    comm_decision_motion_type_code     VARCHAR(100),
    yes_vote_count                     INTEGER,
    no_vote_count                      INTEGER,
    abstainer_count                    INTEGER,
    recused_count                      INTEGER,
    voting_comments                    TEXT,
    is_billable                        VARCHAR(1),
    source_update_timestamp            TIMESTAMP,
    source_update_user                 VARCHAR(100),
    source_version_number              BIGINT,
    source_object_id                   VARCHAR(100),
    archived_at                        TIMESTAMPTZ NOT NULL
                                           DEFAULT CURRENT_TIMESTAMP,
    load_run_id                        BIGINT
                                           REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_submission_protocol
    ON archive.protocol_submission (protocol_id, submission_id);

CREATE INDEX IF NOT EXISTS ix_protocol_submission_number
    ON archive.protocol_submission (submission_number);

CREATE INDEX IF NOT EXISTS ix_protocol_submission_date
    ON archive.protocol_submission (submission_date);

CREATE INDEX IF NOT EXISTS ix_protocol_submission_status
    ON archive.protocol_submission (submission_status_code);

CREATE INDEX IF NOT EXISTS ix_protocol_submission_type
    ON archive.protocol_submission (submission_type_code);

COMMENT ON COLUMN archive.protocol_submission.protocol_id IS
    'Exact archive.protocol_version parent resolved by direct PROTOCOL_ID';

COMMENT ON COLUMN archive.protocol_submission.source_protocol_id IS
    'Original KCOEUS.PROTOCOL_SUBMISSION.PROTOCOL_ID';
