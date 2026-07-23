CREATE TABLE IF NOT EXISTS archive.protocol_research_area (
    protocol_research_area_id BIGINT PRIMARY KEY,
    protocol_id               BIGINT NOT NULL
                                  REFERENCES archive.protocol_version(
                                      protocol_id
                                  ),
    source_protocol_id        BIGINT NOT NULL,
    protocol_number           VARCHAR(60) NOT NULL,
    sequence_number           INTEGER NOT NULL,
    research_area_code        VARCHAR(100),
    source_update_timestamp   TIMESTAMP,
    source_update_user        VARCHAR(100),
    source_version_number     BIGINT,
    source_object_id          VARCHAR(100),
    archived_at               TIMESTAMPTZ NOT NULL
                                  DEFAULT CURRENT_TIMESTAMP,
    load_run_id               BIGINT
                                  REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_research_area_protocol
    ON archive.protocol_research_area (
        protocol_id,
        protocol_research_area_id
    );

CREATE INDEX IF NOT EXISTS ix_protocol_research_area_family_sequence
    ON archive.protocol_research_area (
        protocol_number,
        sequence_number
    );

CREATE INDEX IF NOT EXISTS ix_protocol_research_area_code
    ON archive.protocol_research_area (research_area_code);

COMMENT ON COLUMN archive.protocol_research_area.protocol_id IS
    'Archive parent resolved from protocol_number + sequence_number';

COMMENT ON COLUMN archive.protocol_research_area.source_protocol_id IS
    'Original KCOEUS.PROTOCOL_RESEARCH_AREAS.PROTOCOL_ID';
