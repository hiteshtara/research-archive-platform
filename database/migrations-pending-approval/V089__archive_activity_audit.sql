-- V089: archive activity audit (SEC-008).
--
-- NOT the same table as authz.access_audit, and deliberately so.
-- access_audit records ADMINISTRATION - who was granted what, by whom. This
-- records ACTIVITY - who read what. They have different volumes (one is a
-- handful of rows a year, the other is every page view), different retention
-- questions, and different privacy questions. Extending access_audit would
-- have made it undeletable-by-design AND high-volume, which is a combination
-- nobody wants to operate.
--
-- COLLECTION IS OFF until app.authorization.activity-audit.enabled is set.
-- Applying this migration enables nothing; it only makes the table exist.
--
-- PRIVACY DEFAULTS, pending approval (docs/security/SEC_008_ACTIVITY_AUDIT.md):
--   * raw search TERMS are never stored - only that a search happened, and
--     how many results it returned;
--   * no document content, no attachment bytes, no credentials;
--   * the actor is the institutional identifier already used for grants,
--     never a Cognito token, never a raw login name.
--
-- RETENTION is configurable and PROPOSED at 90 days. That is a number for
-- review, NOT an approved BU retention policy. Monthly partitions make the
-- eventual decision a DROP PARTITION rather than a DELETE over a hot table.

BEGIN;

CREATE TABLE IF NOT EXISTS authz.activity_audit (
    activity_id       BIGSERIAL    NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- WHO. The institutional identifier, the same key grants are held on, so
    -- a reassigned login name cannot be mistaken for the same person. NULL
    -- only when a request was refused before an identity was resolved.
    actor             TEXT,

    -- WHAT. Constrained, because a free-text action column becomes six
    -- spellings of the same event within a year.
    action            VARCHAR(40)  NOT NULL,

    -- WHICH. Resource type and key kept separate so a query can count by
    -- type without parsing. resource_key is a business key (award_number,
    -- proposal_number, subaward_id...), never a row id that means nothing
    -- outside one database.
    resource_type     VARCHAR(20),
    resource_key      TEXT,

    -- HOW IT ENDED. PERMITTED / DENIED / ERROR.
    outcome           VARCHAR(20)  NOT NULL,

    -- WHY, in the engine's own vocabulary: an AccessReason for a permit, a
    -- denial code for a deny. Never prose.
    reason            VARCHAR(60),

    -- Ties one user action to the API request that served it, and to the
    -- application logs for the same request.
    correlation_id    TEXT,

    -- Deliberately NOT a free-text sink. Bounded, structured extras only -
    -- a result count, a module, a version key. A JSONB column is where search
    -- terms and document contents end up if nobody says they must not.
    detail            JSONB,

    CONSTRAINT ck_activity_audit_outcome CHECK (
        outcome IN ('PERMITTED', 'DENIED', 'ERROR')
    ),
    CONSTRAINT ck_activity_audit_action CHECK (
        action IN (
            'RECORD_VIEWED',
            'RECORD_ACCESS_DENIED',
            'ATTACHMENT_DOWNLOADED',
            'EXPORT_GENERATED',
            'SEARCH_PERFORMED'
        )
    ),
    PRIMARY KEY (activity_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE authz.activity_audit IS
    'SEC-008 archive activity: who read what, and what was refused. Separate '
    'from authz.access_audit, which records grant administration. Collection '
    'is off until app.authorization.activity-audit.enabled is true. Stores no '
    'search terms, no document content and no credentials.';

COMMENT ON COLUMN authz.activity_audit.detail IS
    'Bounded structured extras only (result counts, module, version key). '
    'Never search terms, never document content, never credentials.';

-- Partitions. Created ahead rather than on demand: an INSERT with no matching
-- partition fails, and an audit write that fails must never be the thing that
-- breaks a read (see the failure policy in the design note).
CREATE TABLE IF NOT EXISTS authz.activity_audit_2026_11
    PARTITION OF authz.activity_audit
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS authz.activity_audit_2026_12
    PARTITION OF authz.activity_audit
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
CREATE TABLE IF NOT EXISTS authz.activity_audit_2027_01
    PARTITION OF authz.activity_audit
    FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');

-- A catch-all so an INSERT can never fail for want of a partition, including
-- a clock skew or a missed monthly rollover. Rows landing here are a signal
-- that partition maintenance has stopped, not a failure to write.
CREATE TABLE IF NOT EXISTS authz.activity_audit_overflow
    PARTITION OF authz.activity_audit DEFAULT;

CREATE INDEX IF NOT EXISTS ix_activity_audit_actor
    ON authz.activity_audit (actor, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_activity_audit_resource
    ON authz.activity_audit (resource_type, resource_key, occurred_at DESC);
CREATE INDEX IF NOT EXISTS ix_activity_audit_denied
    ON authz.activity_audit (occurred_at DESC)
    WHERE outcome = 'DENIED';

-- Append-only, same defence as access_audit. The API role will hold INSERT
-- and nothing else; this trigger is the second line, for anyone who holds
-- more. Retention is implemented by DROPPING a partition, which does not fire
-- row triggers - so the policy below does not fight the retention job.
CREATE OR REPLACE FUNCTION authz.activity_audit_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'authz.activity_audit is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tr_activity_audit_append_only ON authz.activity_audit;
CREATE TRIGGER tr_activity_audit_append_only
    BEFORE UPDATE OR DELETE ON authz.activity_audit
    FOR EACH ROW EXECUTE FUNCTION authz.activity_audit_append_only();

COMMIT;

-- DELIBERATELY NOT HERE: GRANT INSERT ON authz.activity_audit TO archive_api.
--
-- Granting it is a separate, reviewed step, because it is the point at which
-- the API can write activity rows at all. It belongs with the role script
-- (database/roles/archive_api_role.sql) under the same review as every other
-- privilege, not buried in a migration. Until it is granted, collection
-- cannot happen even if the feature flag is turned on by accident - which is
-- the intended order: schema, then privilege, then flag, each approved.
