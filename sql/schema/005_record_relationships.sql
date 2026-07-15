CREATE TABLE IF NOT EXISTS archive.record_person (
    record_person_id     BIGSERIAL PRIMARY KEY,
    record_id            BIGINT NOT NULL
                             REFERENCES archive.research_record(record_id)
                             ON DELETE CASCADE,
    person_id            BIGINT
                             REFERENCES archive.person(person_id),
    source_person_id     VARCHAR(100),
    buid                 VARCHAR(30),
    full_name            VARCHAR(400),
    role_code            VARCHAR(100),
    role_description     VARCHAR(300),
    lead_flag            BOOLEAN NOT NULL DEFAULT FALSE,
    loaded_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id              BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_record_person_record
    ON archive.record_person (record_id);

CREATE INDEX IF NOT EXISTS ix_record_person_buid
    ON archive.record_person (buid);

CREATE TABLE IF NOT EXISTS archive.record_organization (
    record_organization_id BIGSERIAL PRIMARY KEY,
    record_id               BIGINT NOT NULL
                                REFERENCES archive.research_record(record_id)
                                ON DELETE CASCADE,
    organization_id         BIGINT
                                REFERENCES archive.organization(organization_id),
    organization_code       VARCHAR(100),
    organization_name       VARCHAR(500),
    relationship_type       VARCHAR(100),
    lead_flag               BOOLEAN NOT NULL DEFAULT FALSE,
    loaded_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                 BIGINT REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_record_organization_record
    ON archive.record_organization (record_id);

CREATE TABLE IF NOT EXISTS archive.record_relationship (
    relationship_id       BIGSERIAL PRIMARY KEY,
    parent_record_id      BIGINT NOT NULL
                              REFERENCES archive.research_record(record_id),
    child_record_id       BIGINT NOT NULL
                              REFERENCES archive.research_record(record_id),
    relationship_type     VARCHAR(100) NOT NULL,
    loaded_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id               BIGINT REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_record_relationship
        UNIQUE (
            parent_record_id,
            child_record_id,
            relationship_type
        )
);

CREATE INDEX IF NOT EXISTS ix_record_relationship_parent
    ON archive.record_relationship (parent_record_id);

CREATE INDEX IF NOT EXISTS ix_record_relationship_child
    ON archive.record_relationship (child_record_id);
