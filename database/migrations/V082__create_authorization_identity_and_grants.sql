-- Record-level authorization store (design rev 3.6a, private; requirements
-- IDs 1-7 of the Security Requirements tab). DESIGN-STAGE SCHEMA: nothing in
-- the API enforces record authorization yet, and nothing populates these
-- tables. Applied by the ETL migration runner like every migration; it adds
-- empty tables only.
--
-- A separate schema, "authz", because none of this is archived Kuali data:
-- it is the archive's own identity mapping, grants and audit, and must never
-- be exported, indexed or returned by record endpoints.
--
-- Identity is separate from permissions:
--   identity_link  - a validated Cognito identity (issuer + sub) linked to a
--                    verified institutional identifier and, when known, a
--                    Kuali employee PERSON_ID. Created only by a trusted
--                    enrollment process. login_name is display/lookup only.
--   person_status  - suspension of an institutional identity (overrides all).
--   access_grant   - CENTRAL / UNIT / IO / CONTACT_DERIVATION grants, keyed
--                    on the institutional identifier, never on a login name.
--   access_audit   - append-only record of identity and grant changes.
--
-- Which BU attribute supplies the institutional identifier is NOT decided
-- (awaiting BU IAM); no column here encodes that choice. Migration number
-- V082: V081 is reserved for the Award amount-dates work (separate branch).

CREATE SCHEMA IF NOT EXISTS authz;

CREATE TABLE IF NOT EXISTS authz.identity_link (
    identity_link_id         BIGSERIAL PRIMARY KEY,
    cognito_issuer           TEXT NOT NULL,
    cognito_subject          TEXT NOT NULL,
    institutional_identifier TEXT NOT NULL,
    kuali_person_id          TEXT,
    login_name               TEXT,
    method                   VARCHAR(30) NOT NULL,
    status                   VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    verified_by              TEXT NOT NULL,
    verified_at              TIMESTAMPTZ NOT NULL,
    revoked_by               TEXT,
    revoked_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_identity_link_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_identity_link_method CHECK (method IN ('AUTO_VERIFIED', 'ADMIN_VERIFIED')),
    CONSTRAINT ck_identity_link_revoked CHECK (
        (status = 'REVOKED') = (revoked_at IS NOT NULL)
    )
);

-- At most one ACTIVE link per Cognito identity. More than one would be
-- ambiguous; the resolver denies it anyway, the index stops it being written.
CREATE UNIQUE INDEX IF NOT EXISTS ux_identity_link_active_cognito
    ON authz.identity_link (cognito_issuer, cognito_subject)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS ix_identity_link_institutional
    ON authz.identity_link (institutional_identifier);

CREATE TABLE IF NOT EXISTS authz.person_status (
    institutional_identifier TEXT PRIMARY KEY,
    suspended                BOOLEAN NOT NULL,
    changed_by               TEXT NOT NULL,
    changed_at               TIMESTAMPTZ NOT NULL,
    reason                   TEXT
);

CREATE TABLE IF NOT EXISTS authz.access_grant (
    grant_id                 BIGSERIAL PRIMARY KEY,
    institutional_identifier TEXT NOT NULL,
    grant_type               VARCHAR(20) NOT NULL,
    unit_number              VARCHAR(20),
    include_descendants      BOOLEAN NOT NULL DEFAULT FALSE,
    io_value                 TEXT,
    granted_by               TEXT NOT NULL,
    approved_by              TEXT,
    reason                   TEXT,
    valid_from               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at               TIMESTAMPTZ,
    revoked_by               TEXT,
    revoked_at               TIMESTAMPTZ,

    CONSTRAINT ck_access_grant_type CHECK (
        grant_type IN ('CENTRAL', 'UNIT', 'IO', 'CONTACT_DERIVATION')
    ),
    CONSTRAINT ck_access_grant_shape CHECK (
        (grant_type = 'UNIT' AND unit_number IS NOT NULL AND io_value IS NULL)
        OR (grant_type = 'IO' AND io_value IS NOT NULL AND unit_number IS NULL
            AND include_descendants = FALSE)
        OR (grant_type IN ('CENTRAL', 'CONTACT_DERIVATION')
            AND unit_number IS NULL AND io_value IS NULL
            AND include_descendants = FALSE)
    ),
    CONSTRAINT ck_access_grant_revocation CHECK (
        (revoked_at IS NULL) = (revoked_by IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_access_grant_grantee
    ON authz.access_grant (institutional_identifier);

CREATE TABLE IF NOT EXISTS authz.access_audit (
    audit_id                 BIGSERIAL PRIMARY KEY,
    occurred_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor                    TEXT NOT NULL,
    action                   VARCHAR(40) NOT NULL,
    institutional_identifier TEXT,
    detail                   JSONB
);

-- Append-only: an audit row can be added, never changed or removed.
CREATE OR REPLACE FUNCTION authz.reject_audit_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'authz.access_audit is append-only';
END;
$$;

DROP TRIGGER IF EXISTS tr_access_audit_append_only ON authz.access_audit;
CREATE TRIGGER tr_access_audit_append_only
    BEFORE UPDATE OR DELETE ON authz.access_audit
    FOR EACH ROW EXECUTE FUNCTION authz.reject_audit_change();
