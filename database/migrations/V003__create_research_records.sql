CREATE TABLE IF NOT EXISTS archive.research_record (
    record_id             BIGSERIAL PRIMARY KEY,

    record_type           VARCHAR(50) NOT NULL,
    source_system         VARCHAR(100) NOT NULL DEFAULT 'KUALI',
    source_identifier     VARCHAR(150) NOT NULL,
    business_identifier   VARCHAR(150),

    title                 TEXT,
    short_title           VARCHAR(1000),
    description           TEXT,

    status_code           VARCHAR(100),
    status_description    VARCHAR(500),

    lead_person_buid      VARCHAR(30),
    lead_person_name      VARCHAR(400),

    responsible_unit      VARCHAR(100),

    start_date            DATE,
    end_date              DATE,

    source_created_at     TIMESTAMPTZ,
    source_updated_at     TIMESTAMPTZ,

    active_flag           BOOLEAN,
    archived_flag         BOOLEAN NOT NULL DEFAULT TRUE,

    source_payload        JSONB,

    loaded_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id               BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ck_research_record_type
        CHECK (
            record_type IN (
                'IRB',
                'AWARD',
                'PROPOSAL',
                'NEGOTIATION',
                'SUBAWARD'
            )
        ),

    CONSTRAINT ux_research_record_source
        UNIQUE (
            record_type,
            source_system,
            source_identifier
        )
);

CREATE INDEX IF NOT EXISTS ix_research_record_business_id
    ON archive.research_record (business_identifier);

CREATE INDEX IF NOT EXISTS ix_research_record_type_status
    ON archive.research_record (
        record_type,
        status_description
    );

CREATE INDEX IF NOT EXISTS ix_research_record_lead_person
    ON archive.research_record (lead_person_buid);

CREATE INDEX IF NOT EXISTS ix_research_record_title_trgm
    ON archive.research_record
    USING GIN (title gin_trgm_ops);
