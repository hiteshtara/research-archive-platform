-- Protocol Archive (new implementation). Oracle-direct only - see
-- etl/load_protocols.py. This is a clean rebuild, not a restoration of the
-- schema dropped by V032; it intentionally has a smaller shape than the old
-- protocol_version/protocol_person/protocol_unit tables (no derived views,
-- no personnel-unit-administrator table pending a verified Oracle source).
--
-- Scope for this migration: protocol_version (root), protocol_person, and
-- protocol_unit. All three preserve both the Oracle physical row identity
-- (protocol_id / protocol_person_id / protocol_units_id) and the Oracle
-- business key (protocol_number, sequence_number) so historical versions
-- are never silently collapsed.

CREATE TABLE IF NOT EXISTS archive.protocol_version (
    protocol_id                      BIGINT PRIMARY KEY,
    protocol_number                  VARCHAR(60) NOT NULL,
    sequence_number                  INTEGER NOT NULL,

    document_number                  VARCHAR(100),
    active                           VARCHAR(10),

    protocol_type_code               VARCHAR(30),
    protocol_type_description        VARCHAR(200),
    protocol_status_code             VARCHAR(30),
    protocol_status_description      VARCHAR(200),

    title                            TEXT,
    description                      TEXT,

    initial_submission_date          DATE,
    approval_date                    DATE,
    expiration_date                  DATE,
    last_approval_date               DATE,

    fda_application_number           VARCHAR(100),
    reference_number_1               VARCHAR(200),
    reference_number_2               VARCHAR(200),

    protocol_workflow_type           VARCHAR(100),
    rerouted_flag                    VARCHAR(10),

    source_create_timestamp          TIMESTAMP,
    source_create_user               VARCHAR(100),
    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),
    source_version_number            BIGINT,
    source_object_id                 VARCHAR(100),

    archived_at                      TIMESTAMPTZ NOT NULL
                                         DEFAULT CURRENT_TIMESTAMP,
    load_id                          BIGINT
                                         REFERENCES archive.load_run(load_id),

    CONSTRAINT ux_protocol_version_source_row
        UNIQUE (protocol_number, sequence_number, protocol_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_version_number
    ON archive.protocol_version (protocol_number);

CREATE INDEX IF NOT EXISTS ix_protocol_version_number_sequence
    ON archive.protocol_version (
        protocol_number,
        sequence_number DESC,
        source_update_timestamp DESC,
        protocol_id DESC
    );

CREATE INDEX IF NOT EXISTS ix_protocol_version_status
    ON archive.protocol_version (protocol_status_code);

CREATE INDEX IF NOT EXISTS ix_protocol_version_active
    ON archive.protocol_version (active);

CREATE TABLE IF NOT EXISTS archive.protocol_person (
    protocol_person_id               BIGINT PRIMARY KEY,

    -- Resolved archive parent (NUMBER_SEQUENCE: protocol_number +
    -- sequence_number, not the raw Oracle PROTOCOL_ID - see
    -- docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md for why direct PROTOCOL_ID
    -- parenting is unsafe for this table).
    protocol_id                      BIGINT NOT NULL
                                         REFERENCES archive.protocol_version(
                                             protocol_id
                                         ),
    -- Original KCOEUS.PROTOCOL_PERSONS.PROTOCOL_ID, retained for audit only.
    source_protocol_id               BIGINT NOT NULL,

    protocol_number                  VARCHAR(60) NOT NULL,
    sequence_number                  INTEGER NOT NULL,

    person_id                        VARCHAR(100),
    full_name                        VARCHAR(500),

    protocol_person_role_id          VARCHAR(100),
    protocol_person_role_description VARCHAR(200),
    is_pi                            BOOLEAN NOT NULL DEFAULT FALSE,

    email_address                    VARCHAR(500),
    email_source                     VARCHAR(20),
    rolodex_id                       BIGINT,

    affiliation_type_code            VARCHAR(100),
    comments                         TEXT,

    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),
    source_version_number            BIGINT,
    source_object_id                 VARCHAR(100),

    archived_at                      TIMESTAMPTZ NOT NULL
                                         DEFAULT CURRENT_TIMESTAMP,
    load_id                          BIGINT
                                         REFERENCES archive.load_run(load_id),

    CONSTRAINT ck_protocol_person_email_source
        CHECK (
            email_source IS NULL
            OR email_source IN ('PERSON', 'ROLODEX')
        )
);

CREATE INDEX IF NOT EXISTS ix_protocol_person_protocol
    ON archive.protocol_person (protocol_id, protocol_person_id);

CREATE INDEX IF NOT EXISTS ix_protocol_person_family_sequence
    ON archive.protocol_person (protocol_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_protocol_person_source_protocol
    ON archive.protocol_person (source_protocol_id);

CREATE INDEX IF NOT EXISTS ix_protocol_person_role
    ON archive.protocol_person (protocol_person_role_id);

COMMENT ON COLUMN archive.protocol_person.protocol_id IS
    'Resolved archive parent from protocol_number + sequence_number (NUMBER_SEQUENCE)';

COMMENT ON COLUMN archive.protocol_person.source_protocol_id IS
    'Original KCOEUS.PROTOCOL_PERSONS.PROTOCOL_ID retained for audit';

COMMENT ON COLUMN archive.protocol_person.is_pi IS
    'Derived from protocol_person_role_description matching Principal '
    'Investigator - the exact BU role code/description has not been '
    'verified against live Oracle data yet; verify during --limit testing '
    'before trusting this column in reconciliation.';

COMMENT ON COLUMN archive.protocol_person.email_source IS
    'PERSON when email_address came directly from PROTOCOL_PERSONS.EMAIL_ADDRESS, '
    'ROLODEX when it was a fallback from ROLODEX.EMAIL_ADDRESS via rolodex_id, '
    'NULL when neither source had an email.';

CREATE TABLE IF NOT EXISTS archive.protocol_unit (
    protocol_units_id                BIGINT PRIMARY KEY,

    -- Verified physical FK (OWNER_CHAIN): the unit's archive parent is
    -- inherited from its owning protocol_person's already-resolved
    -- protocol_id, not re-resolved independently from protocol_number +
    -- sequence_number (those are audit evidence only for this table - see
    -- docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md).
    protocol_person_id               BIGINT NOT NULL
                                         REFERENCES archive.protocol_person(
                                             protocol_person_id
                                         ),
    protocol_id                      BIGINT NOT NULL
                                         REFERENCES archive.protocol_version(
                                             protocol_id
                                         ),

    protocol_number                  VARCHAR(60) NOT NULL,
    sequence_number                  INTEGER NOT NULL,

    unit_number                      VARCHAR(100),
    unit_name                        VARCHAR(500),
    lead_unit_flag                   VARCHAR(10),
    person_id                        VARCHAR(100),

    source_update_timestamp          TIMESTAMP,
    source_update_user               VARCHAR(100),
    source_version_number            BIGINT,
    source_object_id                 VARCHAR(100),

    archived_at                      TIMESTAMPTZ NOT NULL
                                         DEFAULT CURRENT_TIMESTAMP,
    load_id                          BIGINT
                                         REFERENCES archive.load_run(load_id)
);

CREATE INDEX IF NOT EXISTS ix_protocol_unit_person
    ON archive.protocol_unit (protocol_person_id, protocol_units_id);

CREATE INDEX IF NOT EXISTS ix_protocol_unit_protocol
    ON archive.protocol_unit (protocol_id);

CREATE INDEX IF NOT EXISTS ix_protocol_unit_family_sequence
    ON archive.protocol_unit (protocol_number, sequence_number);

CREATE INDEX IF NOT EXISTS ix_protocol_unit_number
    ON archive.protocol_unit (unit_number);

COMMENT ON COLUMN archive.protocol_unit.protocol_person_id IS
    'Verified physical parent (OWNER_CHAIN) - the real Oracle FK for this table';

COMMENT ON COLUMN archive.protocol_unit.protocol_id IS
    'Denormalized from the owning protocol_person''s resolved protocol_id, '
    'for direct querying without a join';

COMMENT ON COLUMN archive.protocol_unit.protocol_number IS
    'Audit evidence only - not an independent parent key for this table';
