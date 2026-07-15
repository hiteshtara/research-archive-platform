CREATE TABLE IF NOT EXISTS archive.person (
    person_id           BIGSERIAL PRIMARY KEY,
    source_person_id    VARCHAR(100),
    buid                VARCHAR(30),
    first_name          VARCHAR(150),
    middle_name         VARCHAR(150),
    last_name           VARCHAR(150),
    full_name           VARCHAR(400),
    email_address       VARCHAR(320),
    active_flag         BOOLEAN,
    source_system       VARCHAR(100) NOT NULL DEFAULT 'KUALI',
    source_updated_at   TIMESTAMPTZ,
    loaded_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id             BIGINT REFERENCES archive.load_run(load_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_person_buid
    ON archive.person (buid)
    WHERE buid IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_person_full_name_trgm
    ON archive.person
    USING GIN (full_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS ix_person_email
    ON archive.person (LOWER(email_address));

CREATE TABLE IF NOT EXISTS archive.organization (
    organization_id        BIGSERIAL PRIMARY KEY,
    source_org_id          VARCHAR(100),
    organization_code      VARCHAR(100),
    organization_name      VARCHAR(500),
    organization_type      VARCHAR(100),
    parent_org_code        VARCHAR(100),
    active_flag            BOOLEAN,
    source_system          VARCHAR(100) NOT NULL DEFAULT 'KUALI',
    loaded_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                BIGINT REFERENCES archive.load_run(load_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_organization_code
    ON archive.organization (organization_code)
    WHERE organization_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_organization_name_trgm
    ON archive.organization
    USING GIN (organization_name gin_trgm_ops);

CREATE TABLE IF NOT EXISTS archive.sponsor (
    sponsor_id             BIGSERIAL PRIMARY KEY,
    source_sponsor_id      VARCHAR(100),
    sponsor_code           VARCHAR(100),
    sponsor_name           VARCHAR(500),
    sponsor_type_code      VARCHAR(50),
    sponsor_type           VARCHAR(150),
    active_flag            BOOLEAN,
    source_system          VARCHAR(100) NOT NULL DEFAULT 'KUALI',
    loaded_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    load_id                BIGINT REFERENCES archive.load_run(load_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_sponsor_code
    ON archive.sponsor (sponsor_code)
    WHERE sponsor_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_sponsor_name_trgm
    ON archive.sponsor
    USING GIN (sponsor_name gin_trgm_ops);
