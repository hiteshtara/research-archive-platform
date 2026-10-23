-- ARCHIVE_ADMIN administrative role (Phase 1). DESIGN-STAGE SCHEMA: nothing
-- enforces it yet and nothing populates it. Applied by the ETL migration
-- runner like every migration; it adds one empty table only.
--
-- Deliberately a SEPARATE table from authz.access_grant rather than another
-- grant_type value. Making ARCHIVE_ADMIN a grant type would put it in the same
-- set as CENTRAL/UNIT/IO/CONTACT_DERIVATION, so anything asking "does this
-- user hold a grant" would treat administration as data access - which breaks
-- the decision that holding ARCHIVE_ADMIN grants NO archive data access.
-- Keeping the two stores apart makes that property structural instead of
-- something every future query has to remember.
--
-- The "cannot remove the last active administrator" rule is NOT expressed here.
-- It is a condition over the set of rows, not over one row, so no CHECK
-- constraint can enforce it: two concurrent revokes would each see two active
-- administrators and both succeed, leaving zero. It is enforced in
-- JdbcAdminRoleRepository by locking the active rows with SELECT ... FOR UPDATE
-- inside the revoking transaction. See AdminRoleRepository's contract.
CREATE TABLE IF NOT EXISTS authz.admin_role_member (
    admin_member_id          BIGSERIAL PRIMARY KEY,
    institutional_identifier TEXT NOT NULL,
    status                   VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',

    -- The acting administrator's institutional identifier, or the literal
    -- 'BOOTSTRAP' for the single controlled first grant. Never a login name:
    -- login names change (KRIM_PRNCPL_T.PRNCPL_NM is mutable), institutional
    -- identifiers are the stable key everywhere else in this schema.
    granted_by               TEXT NOT NULL,
    granted_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_by               TEXT,
    revoked_at               TIMESTAMPTZ,
    reason                   TEXT,

    CONSTRAINT ck_admin_role_member_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_admin_role_member_revoked
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT ck_admin_role_member_revoked_by
        CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

-- At most one ACTIVE membership per identity. A second active row would make
-- "is this user an administrator" ambiguous and would let one revoke appear to
-- succeed while the identity stayed an administrator through the other row.
CREATE UNIQUE INDEX IF NOT EXISTS ux_admin_role_member_active
    ON authz.admin_role_member (institutional_identifier)
    WHERE status = 'ACTIVE';

-- Supports the locking count in the revoke path.
CREATE INDEX IF NOT EXISTS ix_admin_role_member_status
    ON authz.admin_role_member (status);
