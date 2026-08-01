-- Generic ETL batch framework: a deterministic, resumable manifest for
-- "select exactly N entities, then load/process exactly that membership"
-- workflows, shared across domains rather than one bespoke batch-table
-- pair per domain.
--
-- Originally written (as V037__create_award_attachment_batches.sql) as an
-- Award-attachment-only pair (attachment_load_batch/
-- attachment_load_batch_file). Generalized before ever being applied
-- anywhere (no schema_migration row for V037 exists in any environment),
-- once a second candidate consumer (Award parent-batching) made clear a
-- second bespoke batch-table pair per domain would create two
-- incompatible operational models instead of one. See
-- docs/ETL_BATCH_FRAMEWORK.md for the full design rationale.
--
-- Fixes the same real gap the original version fixed: neither --limit
-- (metadata load) nor --limit (upload/processing candidate selection) is
-- a persisted selection - both are live queries re-evaluated on every
-- invocation, over data sources with no relationship to each other. There
-- is no guarantee the same N entities are used across separate
-- invocations. A batch is a durable manifest: once created, its
-- membership never changes.
--
-- Domain design:
--   - archive.etl_batch is the parent manifest, tagged with `domain`
--     (e.g. 'AWARD_ATTACHMENT') and `entity_type` (e.g. 'PHYSICAL_FILE') -
--     plain VARCHAR discriminators, matching this schema's existing
--     convention (archive.load_run.domain is the same shape, not a
--     foreign-keyed lookup table).
--   - archive.etl_batch_item.entity_key is a plain BIGINT, not a FK to any
--     domain table: batch creation persists membership *before* any load
--     has upserted the corresponding domain row (a FK here would make
--     batch creation itself impossible), and every domain in scope today
--     (Award via award_id, Award-attachment physical files via file_id)
--     already has a real numeric surrogate primary key, so no composite-
--     key representation is needed yet. A future domain with no numeric
--     surrogate key is the trigger for a domain-specific membership table
--     alongside this one, not for weakening entity_key into a fragile
--     free-form string now.
--   - etl_batch_item.status tracks exactly one thing: whether this
--     batch's own load/process step has run for this entity_key. It is
--     deliberately never used to duplicate domain-specific downstream
--     state (e.g. S3 upload progress) - that remains solely owned by the
--     domain's own table (e.g. archive.attachment_object.upload_status),
--     exactly as the original attachment-only design already established.
--     Domain code scopes further processing to batch membership by
--     joining etl_batch_item to its own table on entity_key, filtered by
--     batch_id/domain/entity_type - it never mirrors that table's status
--     into etl_batch_item.

CREATE TABLE IF NOT EXISTS archive.etl_batch (
    batch_id              BIGSERIAL PRIMARY KEY,
    domain                VARCHAR(50) NOT NULL,
    entity_type           VARCHAR(50) NOT NULL,
    requested_size        INTEGER NOT NULL,
    status                VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    selection_strategy    VARCHAR(50) NOT NULL,
    selection_parameters  JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by_run_id     VARCHAR(100),
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    notes                 TEXT,

    CONSTRAINT ck_etl_batch_requested_size_positive
        CHECK (requested_size > 0),
    CONSTRAINT ck_etl_batch_status
        CHECK (
            status IN (
                'CREATED',
                'METADATA_LOADING',
                'READY',
                'PROCESSING',
                'PARTIAL',
                'COMPLETED',
                'FAILED',
                'ABANDONED'
            )
        )
);

CREATE INDEX IF NOT EXISTS ix_etl_batch_domain
    ON archive.etl_batch (domain, entity_type, status);

CREATE TABLE IF NOT EXISTS archive.etl_batch_item (
    batch_id       BIGINT NOT NULL
                       REFERENCES archive.etl_batch(batch_id),
    entity_key     BIGINT NOT NULL,
    ordinal        INTEGER NOT NULL,
    status         VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,

    PRIMARY KEY (batch_id, entity_key),

    CONSTRAINT ck_etl_batch_item_status
        CHECK (
            status IN (
                'PENDING',
                'PROCESSING',
                'COMPLETED',
                'FAILED',
                'MISSING_SOURCE',
                'SKIPPED'
            )
        ),
    CONSTRAINT uq_etl_batch_item_ordinal
        UNIQUE (batch_id, ordinal)
);

-- batch_id is already the leading column of the composite primary key
-- above (and so already indexed for batch_id-only lookups) - entity_key
-- and status each get their own index since neither is a leading column
-- of any existing index.
CREATE INDEX IF NOT EXISTS ix_etl_batch_item_entity_key
    ON archive.etl_batch_item (entity_key);

CREATE INDEX IF NOT EXISTS ix_etl_batch_item_status
    ON archive.etl_batch_item (status);

COMMENT ON TABLE archive.etl_batch IS
    'Generic, domain-tagged deterministic batch manifest for '
    'select-N-then-load/process workflows (e.g. Award attachment physical '
    'files). Membership (etl_batch_item) never changes after creation.';

COMMENT ON TABLE archive.etl_batch_item IS
    'Batch membership: exactly the entity_key values selected at batch '
    'creation time, in stable ascending order (ordinal). status tracks '
    'only whether this batch''s own load/process step has run for this '
    'entity_key - domain-specific downstream state (e.g. S3 upload '
    'progress) is never duplicated here, only in the domain''s own table.';
