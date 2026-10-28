-- Two-person rule for re-linking an archive identity that holds active
-- research-data grants.
--
-- Ordinary grants and revocations need one administrator (decision D-ADM-2).
-- Re-linking is different in kind: the grants hang off the archive identity,
-- so pointing it at a new Cognito identity hands that person everything the
-- identity holds. One administrator should not be able to do that alone.
--
-- Identities with NO active grants keep the existing single-administrator
-- procedure - there is nothing to transfer, so the extra ceremony would buy
-- nothing and would discourage the legitimate pool-replacement case.
--
-- This is NOT a second role-management system. Approvers are ARCHIVE_ADMINs
-- from authz.admin_role_member; this table holds approvals, not roles.
--
-- Does not alter V082-V085.
CREATE TABLE IF NOT EXISTS authz.relink_approval (
    approval_id              BIGSERIAL PRIMARY KEY,

    -- Bound to the exact identity AND the exact proposed target, so an
    -- approval cannot be carried to another identity or another Cognito
    -- account. All three are compared when it is consumed.
    institutional_identifier TEXT NOT NULL,
    proposed_cognito_issuer  TEXT NOT NULL,
    proposed_cognito_subject TEXT NOT NULL,

    requested_by             TEXT NOT NULL,
    requested_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- How many active grants the request was made against. Advisory: the
    -- count is RE-CHECKED when the relink executes, because grants can change
    -- in between and the decision must reflect what is actually being moved.
    grants_at_request        INTEGER NOT NULL,
    reason                   TEXT NOT NULL,

    approved_by              TEXT,
    approved_at              TIMESTAMPTZ,
    consumed_at              TIMESTAMPTZ,
    revoked_by               TEXT,
    revoked_at               TIMESTAMPTZ,

    status                   VARCHAR(10) NOT NULL DEFAULT 'PENDING',

    CONSTRAINT ck_relink_approval_status
        CHECK (status IN ('PENDING', 'APPROVED', 'CONSUMED', 'REVOKED')),
    CONSTRAINT ck_relink_approval_approved
        CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_relink_approval_needs_approver
        CHECK (status NOT IN ('APPROVED', 'CONSUMED') OR approved_by IS NOT NULL),
    CONSTRAINT ck_relink_approval_consumed
        CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CONSTRAINT ck_relink_approval_revoked
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)
               AND (revoked_at IS NULL) = (revoked_by IS NULL)),

    -- The two-person rule itself, in the database rather than only in Java:
    -- the approver is never the requester.
    CONSTRAINT ck_relink_approval_two_people
        CHECK (approved_by IS NULL OR approved_by <> requested_by)
);

-- At most one live approval per (identity, proposed target). A second would
-- make "which approval authorised this" ambiguous, and would let a refused
-- request be quietly replaced by a fresh one while the first still stood.
CREATE UNIQUE INDEX IF NOT EXISTS ux_relink_approval_live
    ON authz.relink_approval
       (institutional_identifier, proposed_cognito_issuer, proposed_cognito_subject)
    WHERE status IN ('PENDING', 'APPROVED');

CREATE INDEX IF NOT EXISTS ix_relink_approval_identity
    ON authz.relink_approval (institutional_identifier);
