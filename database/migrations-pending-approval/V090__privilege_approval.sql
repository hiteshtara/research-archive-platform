-- V090: independent approval for privilege escalation (A-category control 3).
--
-- HELD BACK. This file lives in database/migrations-pending-approval/, not in
-- database/migrations/, so no loader run (including the nightly Subaward
-- sync) can apply it. Applying it to any shared database is a separate,
-- explicitly approved step - see database/migrations-pending-approval/README.md
-- and docs/security/A_CATEGORY_CONTROLS_DESIGN.md sections 3 and 9.
--
-- WHAT IT IS. A sibling of V086's authz.relink_approval, generalised to the
-- six escalations the design places behind a second administrator:
--
--   ADMIN_ASSIGN     appointing an ARCHIVE_ADMIN
--   ADMIN_RESTORE    un-suspending an identity whose ARCHIVE_ADMIN is active (D3)
--   SELF_GRANT       an administrator granting archive access to themselves
--   SELF_WIDEN       an administrator widening their own grant
--   LINK_PRIVILEGED  linking a Cognito identity to an archive identity that
--                    holds grants or ARCHIVE_ADMIN (closes finding N2)
--   CENTRAL_GRANT    any CENTRAL grant, to anyone (D1)
--
-- V086's authz.relink_approval is left untouched.
--
-- THE PROPERTIES, each enforced HERE rather than only in Java:
--   * two people:    the approver is never the requester   (ck_pa_two_people)
--   * not the subject: the beneficiary never approves        (ck_pa_not_subject)
--   * exact binding: the approved specification cannot change after the row
--                    exists (tr_privilege_approval_guard, section 3.2a)
--   * legal transitions only: PENDING->APPROVED, APPROVED->CONSUMED,
--                    PENDING/APPROVED->REVOKED; nothing else (same trigger)
--   * 72h expiry:    valid_until = requested_at + 72 hours, set by the
--                    trigger and pinned by a CHECK (section 3.2b, D2)
--   * one live request per target (ux_privilege_approval_live)
--   * append-only:   rows are never deleted (same trigger; archive_api also
--                    holds no DELETE privilege - see
--                    database/roles/archive_api_privilege_approval_grant.sql)
--
-- Additive only. No existing table, constraint or row is changed. In
-- particular access_grant is NOT altered: the grant-revocation reason
-- (finding N3) is handled without a migration.

BEGIN;

CREATE TABLE IF NOT EXISTS authz.privilege_approval (
    approval_id          BIGSERIAL PRIMARY KEY,

    action               VARCHAR(30) NOT NULL,
    -- Who GAINS the privilege. Never the approver (ck_pa_not_subject).
    subject_identifier   TEXT NOT NULL,

    -- Exact binding: what was approved is exactly what may execute. Every
    -- one of these is compared, NULL-safely, when the approval is consumed.
    grant_type           VARCHAR(20),
    unit_number          VARCHAR(20),
    io_value             TEXT,
    award_number         VARCHAR(50),
    include_descendants  BOOLEAN,
    expires_at           TIMESTAMPTZ,
    target_grant_id      BIGINT REFERENCES authz.access_grant (grant_id),
    -- LINK_PRIVILEGED only: the Cognito identity being given the archive
    -- identity. Without these an approval to link identity X could be spent
    -- linking X to ANY sign-in, which is the escalation the action exists to
    -- stop. (An addition to the design's column list, for that reason.)
    cognito_issuer       TEXT,
    cognito_subject      TEXT,
    -- LINK_PRIVILEGED only: the Kuali principal the new link will carry
    -- (authz.identity_link.kuali_person_id). Bound explicitly, owner
    -- requirement: the principal decides which records contact-derived
    -- access reaches, so an approval for a link with principal P (or with
    -- none) must not be spendable on a link with principal Q. NULL is a
    -- bound value too - "no principal" approved is "no principal" executed,
    -- compared NULL-safely, so a null<->value change also invalidates it.
    proposed_kuali_person_id TEXT,

    requested_by        TEXT NOT NULL,
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason               TEXT NOT NULL,

    approved_by          TEXT,
    approved_at          TIMESTAMPTZ,
    approval_reason      TEXT,

    -- Set by the trigger from requested_at; whatever an INSERT supplies is
    -- overwritten, so no caller can choose a longer window.
    valid_until          TIMESTAMPTZ NOT NULL,

    consumed_at          TIMESTAMPTZ,
    consumed_by          TEXT,

    revoked_at           TIMESTAMPTZ,
    -- An administrator's identifier, or the literal 'EXPIRED' when a stale
    -- live row is cleared so the request can be resubmitted (section 3.2b).
    revoked_by           TEXT,
    revocation_reason    TEXT,

    status               VARCHAR(10) NOT NULL DEFAULT 'PENDING',

    CONSTRAINT ck_pa_status
        CHECK (status IN ('PENDING', 'APPROVED', 'CONSUMED', 'REVOKED')),
    CONSTRAINT ck_pa_action
        CHECK (action IN ('ADMIN_ASSIGN', 'ADMIN_RESTORE', 'SELF_GRANT', 'SELF_WIDEN',
                          'LINK_PRIVILEGED', 'CENTRAL_GRANT')),

    -- Reasons. The service applies the full rule (decision D4); the database
    -- holds the floor so a direct insert cannot leave an unexplained row.
    CONSTRAINT ck_pa_reason
        CHECK (length(btrim(reason)) >= 10),
    CONSTRAINT ck_pa_approval_reason
        CHECK ((approved_by IS NULL) = (approval_reason IS NULL)
               AND (approval_reason IS NULL OR length(btrim(approval_reason)) >= 10)),
    CONSTRAINT ck_pa_revocation_reason
        CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL)
               AND (revocation_reason IS NULL OR length(btrim(revocation_reason)) >= 10)),

    -- 72 hours from the REQUEST (D2).
    CONSTRAINT ck_pa_valid_until
        CHECK (valid_until = requested_at + INTERVAL '72 hours'),

    -- V086's status/timestamp consistency, reproduced.
    CONSTRAINT ck_pa_approved
        CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_pa_needs_approver
        CHECK (status NOT IN ('APPROVED', 'CONSUMED') OR approved_by IS NOT NULL),
    CONSTRAINT ck_pa_consumed
        CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)
               AND (consumed_at IS NULL) = (consumed_by IS NULL)),
    CONSTRAINT ck_pa_revoked
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)
               AND (revoked_at IS NULL) = (revoked_by IS NULL)),

    -- An approval or a consumption can only happen inside the window.
    CONSTRAINT ck_pa_approved_in_window
        CHECK (approved_at IS NULL OR approved_at < valid_until),
    CONSTRAINT ck_pa_consumed_in_window
        CHECK (consumed_at IS NULL OR consumed_at < valid_until),

    -- The two-person rule, and its extension: the beneficiary never approves.
    CONSTRAINT ck_pa_two_people
        CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CONSTRAINT ck_pa_not_subject
        CHECK (approved_by IS NULL OR approved_by <> subject_identifier),

    -- Each action binds exactly the columns that describe it, so an approval
    -- cannot be created that is vaguer than the act it authorises.
    CONSTRAINT ck_pa_binding_shape CHECK (
        (action IN ('ADMIN_ASSIGN', 'ADMIN_RESTORE')
            AND grant_type IS NULL AND unit_number IS NULL AND io_value IS NULL
            AND award_number IS NULL AND include_descendants IS NULL
            AND expires_at IS NULL AND target_grant_id IS NULL
            AND cognito_issuer IS NULL AND cognito_subject IS NULL
            AND proposed_kuali_person_id IS NULL)
        OR (action = 'SELF_GRANT'
            AND grant_type IS NOT NULL AND include_descendants IS NOT NULL
            AND target_grant_id IS NULL
            AND cognito_issuer IS NULL AND cognito_subject IS NULL
            AND proposed_kuali_person_id IS NULL)
        OR (action = 'CENTRAL_GRANT'
            AND grant_type IS NOT DISTINCT FROM 'CENTRAL' AND unit_number IS NULL AND io_value IS NULL
            AND award_number IS NULL AND include_descendants IS FALSE
            AND target_grant_id IS NULL
            AND cognito_issuer IS NULL AND cognito_subject IS NULL
            AND proposed_kuali_person_id IS NULL)
        OR (action = 'SELF_WIDEN'
            AND target_grant_id IS NOT NULL AND include_descendants IS NOT NULL
            AND grant_type IS NULL AND unit_number IS NULL AND io_value IS NULL
            AND award_number IS NULL
            AND cognito_issuer IS NULL AND cognito_subject IS NULL
            AND proposed_kuali_person_id IS NULL)
        -- proposed_kuali_person_id may be NULL here: "link with no principal"
        -- is itself the bound value.
        OR (action = 'LINK_PRIVILEGED'
            AND cognito_issuer IS NOT NULL AND cognito_subject IS NOT NULL
            AND grant_type IS NULL AND unit_number IS NULL AND io_value IS NULL
            AND award_number IS NULL AND include_descendants IS NULL
            AND expires_at IS NULL AND target_grant_id IS NULL)
    )
);

-- At most one live approval per target. A second would make "which approval
-- authorised this" ambiguous, and would let a refused request be quietly
-- replaced while the first still stood. include_descendants and expires_at
-- are deliberately NOT in the key: one live request per grant or scope, and
-- a different expiry means revoking the request and submitting a new one.
CREATE UNIQUE INDEX IF NOT EXISTS ux_privilege_approval_live
    ON authz.privilege_approval
       (action, subject_identifier,
        COALESCE(grant_type, ''), COALESCE(unit_number, ''),
        COALESCE(io_value, ''), COALESCE(award_number, ''),
        COALESCE(target_grant_id, 0),
        COALESCE(cognito_issuer, ''), COALESCE(cognito_subject, ''),
        COALESCE(proposed_kuali_person_id, ''))
    WHERE status IN ('PENDING', 'APPROVED');

CREATE INDEX IF NOT EXISTS ix_privilege_approval_subject
    ON authz.privilege_approval (subject_identifier);
CREATE INDEX IF NOT EXISTS ix_privilege_approval_live_status
    ON authz.privilege_approval (status)
    WHERE status IN ('PENDING', 'APPROVED');

-- ─────────────────────────────────────────────────────────────────────
-- Immutability, legal transitions, expiry and append-only (3.2a, 3.2b).
--
-- Java cannot be trusted alone for this: the risk being closed is a request
-- edited between approval and execution, and whoever could do that could
-- also skip a Java check. A change means revoking and resubmitting.
-- ─────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION authz.privilege_approval_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'authz.privilege_approval is append-only: approval % cannot be deleted',
            OLD.approval_id;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'PENDING'
           OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL
           OR NEW.approval_reason IS NOT NULL
           OR NEW.consumed_at IS NOT NULL OR NEW.consumed_by IS NOT NULL
           OR NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL
           OR NEW.revocation_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a privilege approval must be created PENDING, with no approval, '
                            'consumption or revocation recorded';
        END IF;
        IF NEW.requested_at IS NULL THEN
            NEW.requested_at := CURRENT_TIMESTAMP;
        ELSIF NEW.requested_at > CURRENT_TIMESTAMP THEN
            -- A future-dated request would stretch its own 72-hour window.
            RAISE EXCEPTION 'requested_at cannot be in the future';
        END IF;
        NEW.valid_until := NEW.requested_at + INTERVAL '72 hours';
        RETURN NEW;
    END IF;

    -- UPDATE: every bound column is frozen.
    IF NEW.approval_id         IS DISTINCT FROM OLD.approval_id
       OR NEW.action              IS DISTINCT FROM OLD.action
       OR NEW.subject_identifier  IS DISTINCT FROM OLD.subject_identifier
       OR NEW.grant_type          IS DISTINCT FROM OLD.grant_type
       OR NEW.unit_number         IS DISTINCT FROM OLD.unit_number
       OR NEW.io_value            IS DISTINCT FROM OLD.io_value
       OR NEW.award_number        IS DISTINCT FROM OLD.award_number
       OR NEW.include_descendants IS DISTINCT FROM OLD.include_descendants
       OR NEW.expires_at          IS DISTINCT FROM OLD.expires_at
       OR NEW.target_grant_id     IS DISTINCT FROM OLD.target_grant_id
       OR NEW.cognito_issuer      IS DISTINCT FROM OLD.cognito_issuer
       OR NEW.cognito_subject     IS DISTINCT FROM OLD.cognito_subject
       OR NEW.proposed_kuali_person_id IS DISTINCT FROM OLD.proposed_kuali_person_id
       OR NEW.requested_by        IS DISTINCT FROM OLD.requested_by
       OR NEW.requested_at        IS DISTINCT FROM OLD.requested_at
       OR NEW.reason              IS DISTINCT FROM OLD.reason
       OR NEW.valid_until         IS DISTINCT FROM OLD.valid_until THEN
        RAISE EXCEPTION 'privilege approval % is immutable: a bound column cannot change '
                        '(revoke it and submit a new request)', OLD.approval_id;
    END IF;

    IF OLD.status = 'PENDING' AND NEW.status = 'APPROVED' THEN
        IF NEW.consumed_at IS NOT NULL OR NEW.consumed_by IS NOT NULL
           OR NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL
           OR NEW.revocation_reason IS NOT NULL THEN
            RAISE EXCEPTION 'approving privilege approval % may set only the approval columns',
                OLD.approval_id;
        END IF;
    ELSIF OLD.status = 'APPROVED' AND NEW.status = 'CONSUMED' THEN
        IF NEW.approved_by     IS DISTINCT FROM OLD.approved_by
           OR NEW.approved_at     IS DISTINCT FROM OLD.approved_at
           OR NEW.approval_reason IS DISTINCT FROM OLD.approval_reason
           OR NEW.revoked_at IS NOT NULL OR NEW.revoked_by IS NOT NULL
           OR NEW.revocation_reason IS NOT NULL THEN
            RAISE EXCEPTION 'consuming privilege approval % may set only the consumption columns',
                OLD.approval_id;
        END IF;
    ELSIF OLD.status IN ('PENDING', 'APPROVED') AND NEW.status = 'REVOKED' THEN
        IF NEW.approved_by     IS DISTINCT FROM OLD.approved_by
           OR NEW.approved_at     IS DISTINCT FROM OLD.approved_at
           OR NEW.approval_reason IS DISTINCT FROM OLD.approval_reason
           OR NEW.consumed_at IS NOT NULL OR NEW.consumed_by IS NOT NULL THEN
            RAISE EXCEPTION 'revoking privilege approval % may set only the revocation columns',
                OLD.approval_id;
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal privilege approval transition % -> % (approval %)',
            OLD.status, NEW.status, OLD.approval_id;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS tr_privilege_approval_guard ON authz.privilege_approval;
CREATE TRIGGER tr_privilege_approval_guard
    BEFORE INSERT OR UPDATE OR DELETE ON authz.privilege_approval
    FOR EACH ROW EXECUTE FUNCTION authz.privilege_approval_guard();

COMMIT;

-- DELIBERATELY NOT HERE: the archive_api privileges on this table. They are
-- granted by database/roles/archive_api_privilege_approval_grant.sql, a
-- separate, reviewed step that refuses to run before this migration exists.
