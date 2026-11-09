-- Rollback for V088 (SEC-005 Parent Award).
--
-- NOT a migration. Forward-only numbering is this project's convention, so
-- this lives outside database/migrations/ and is run by hand, deliberately.
--
-- Safe to run when: no PARENT_AWARD grant exists. The script REFUSES
-- otherwise, rather than deleting rows - a grant row is audit evidence, and
-- reversing a schema change must never be a way to lose it.
--
-- Restores V087's unique index exactly, so the "at most one unrevoked grant
-- per grantee and scope" invariant is preserved across the rollback rather
-- than dropped and forgotten.

BEGIN;

DO $$
DECLARE
    offending bigint;
BEGIN
    SELECT count(*) INTO offending
      FROM authz.access_grant
     WHERE grant_type = 'PARENT_AWARD';

    IF offending > 0 THEN
        RAISE EXCEPTION
          'Refusing to roll back V088: % PARENT_AWARD grant(s) exist. Revoke '
          'them first and decide deliberately what happens to the rows - this '
          'script will not delete audit evidence.', offending;
    END IF;
END
$$;

-- Rebuild V087's index, on V087's key.
DROP INDEX IF EXISTS authz.ux_access_grant_active_scope;

CREATE UNIQUE INDEX IF NOT EXISTS ux_access_grant_active_scope
    ON authz.access_grant (
        institutional_identifier,
        grant_type,
        COALESCE(unit_number, ''),
        COALESCE(io_value, '')
    )
    WHERE revoked_at IS NULL;

DROP INDEX IF EXISTS authz.ix_access_grant_award_number;

-- Restore V082's constraints.
ALTER TABLE authz.access_grant DROP CONSTRAINT IF EXISTS ck_access_grant_type;
ALTER TABLE authz.access_grant
    ADD CONSTRAINT ck_access_grant_type CHECK (
        grant_type IN ('CENTRAL', 'UNIT', 'IO', 'CONTACT_DERIVATION')
    );

ALTER TABLE authz.access_grant DROP CONSTRAINT IF EXISTS ck_access_grant_shape;
ALTER TABLE authz.access_grant
    ADD CONSTRAINT ck_access_grant_shape CHECK (
        (grant_type = 'UNIT' AND unit_number IS NOT NULL AND io_value IS NULL)
        OR (grant_type = 'IO' AND io_value IS NOT NULL AND unit_number IS NULL
            AND include_descendants = FALSE)
        OR (grant_type IN ('CENTRAL', 'CONTACT_DERIVATION')
            AND unit_number IS NULL AND io_value IS NULL
            AND include_descendants = FALSE)
    );

-- Last, because the constraints above reference it.
ALTER TABLE authz.access_grant DROP COLUMN IF EXISTS award_number;

-- The ledger row, so a re-run of the migration is not skipped as applied.
DELETE FROM public.schema_migration WHERE version = 88;

COMMIT;
