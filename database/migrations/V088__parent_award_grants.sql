-- V088: PARENT_AWARD grants (SEC-005).
--
-- A PARENT_AWARD grant names ONE Award number and reaches that Award plus its
-- hierarchy descendants, down to a bounded depth the application configures
-- (app.authorization.parent-award-depth, default 1 = the Award and its direct
-- children only). The traversal itself lives in the application, against
-- archive.award_hierarchy; this migration only makes the grant storable.
--
-- Three things have to change together, and leaving any one out is a silent
-- failure:
--
--   1. ck_access_grant_type is a CHECK over a fixed list. A new grant type
--      cannot be inserted at all until the list includes it - the INSERT
--      fails with a constraint violation, not a type error.
--
--   2. The grant needs a qualifier saying WHICH Award. unit_number and
--      io_value are the wrong columns: overloading unit_number to hold an
--      Award number would make ck_access_grant_shape unenforceable and every
--      later reader guess which meaning applied. A new, explicitly named
--      column is cheaper than that ambiguity.
--
--   3. ux_access_grant_active_scope (V087) keys on
--      (identifier, grant_type, COALESCE(unit_number,''), COALESCE(io_value,'')).
--      Without award_number in the key, a grantee's SECOND PARENT_AWARD grant
--      for a DIFFERENT Award collides with the first and is rejected as a
--      duplicate - the invariant would silently mean "one parent-award grant
--      per person", which is not what V087 decided. The index is therefore
--      rebuilt, not added to.
--
-- Fund Center is deliberately NOT a grant type here. Verified in BU's Kuali
-- source (SapIntegrationServiceImpl: ZZFUNDCENTER is the Award lead unit
-- number right-padded to 10 characters), so a FUND_CENTER type would
-- duplicate UNIT. See docs/security/SEC_005_PARENT_AWARD.md.
--
-- Forward-only. No row is written, updated or deleted by this migration.

BEGIN;

ALTER TABLE authz.access_grant
    ADD COLUMN IF NOT EXISTS award_number VARCHAR(50);

COMMENT ON COLUMN authz.access_grant.award_number IS
    'PARENT_AWARD grants only: the Award number the grant is rooted at. '
    'Reaches that Award and its archive.award_hierarchy descendants, bounded '
    'by app.authorization.parent-award-depth. NULL for every other type.';

-- 1. Permit the new type.
ALTER TABLE authz.access_grant DROP CONSTRAINT IF EXISTS ck_access_grant_type;
ALTER TABLE authz.access_grant
    ADD CONSTRAINT ck_access_grant_type CHECK (
        grant_type IN ('CENTRAL', 'UNIT', 'IO', 'CONTACT_DERIVATION', 'PARENT_AWARD')
    );

-- 2. Shape: exactly one qualifier per type, and award_number only on
--    PARENT_AWARD. The existing arms are reproduced with "award_number IS
--    NULL" added, so an Award number cannot be smuggled onto a UNIT or IO
--    grant and silently ignored by the application.
ALTER TABLE authz.access_grant DROP CONSTRAINT IF EXISTS ck_access_grant_shape;
ALTER TABLE authz.access_grant
    ADD CONSTRAINT ck_access_grant_shape CHECK (
        (grant_type = 'UNIT' AND unit_number IS NOT NULL AND io_value IS NULL
            AND award_number IS NULL)
        OR (grant_type = 'IO' AND io_value IS NOT NULL AND unit_number IS NULL
            AND award_number IS NULL AND include_descendants = FALSE)
        OR (grant_type = 'PARENT_AWARD' AND award_number IS NOT NULL
            AND unit_number IS NULL AND io_value IS NULL
            AND include_descendants = FALSE)
        OR (grant_type IN ('CENTRAL', 'CONTACT_DERIVATION')
            AND unit_number IS NULL AND io_value IS NULL AND award_number IS NULL
            AND include_descendants = FALSE)
    );

-- 3. Rebuild the V087 uniqueness invariant with the new qualifier in the key.
--
-- Same refusal-first discipline as V087: if unrevoked duplicates already
-- exist under the WIDER key, say so and stop rather than failing on an opaque
-- "could not create unique index". Revoking a redundant grant is the fix;
-- deleting one is not, because the audit trail depends on the row surviving.
DO $$
DECLARE
    offending text;
BEGIN
    SELECT string_agg(
               format('%s/%s/%s/%s/%s (%s rows)',
                      institutional_identifier, grant_type,
                      COALESCE(unit_number, '-'), COALESCE(io_value, '-'),
                      COALESCE(award_number, '-'), n),
               '; ')
      INTO offending
      FROM (
          SELECT institutional_identifier, grant_type, unit_number, io_value,
                 award_number, count(*) AS n
            FROM authz.access_grant
           WHERE revoked_at IS NULL
           GROUP BY institutional_identifier, grant_type,
                    COALESCE(unit_number, ''), COALESCE(io_value, ''),
                    COALESCE(award_number, ''),
                    unit_number, io_value, award_number
          HAVING count(*) > 1
      ) duplicates;
    IF offending IS NOT NULL THEN
        RAISE EXCEPTION
          'Cannot rebuild ux_access_grant_active_scope: duplicate unrevoked '
          'grants already exist: %. Revoke the redundant ones first - do not '
          'delete them, the audit trail depends on the rows surviving.', offending;
    END IF;
END
$$;

DROP INDEX IF EXISTS authz.ux_access_grant_active_scope;

CREATE UNIQUE INDEX IF NOT EXISTS ux_access_grant_active_scope
    ON authz.access_grant (
        institutional_identifier,
        grant_type,
        COALESCE(unit_number, ''),
        COALESCE(io_value, ''),
        COALESCE(award_number, '')
    )
    WHERE revoked_at IS NULL;

COMMENT ON INDEX authz.ux_access_grant_active_scope IS
    'At most one unrevoked grant per grantee and scope (V087), widened by '
    'V088 to include award_number so PARENT_AWARD grants for different Awards '
    'do not collide. include_descendants is deliberately NOT in the key: '
    'toggling it is a modification of the same grant, handled by '
    'ArchiveAdminService.modifyAccess, not a second grant.';

-- Lookup support for the grant -> hierarchy expansion.
CREATE INDEX IF NOT EXISTS ix_access_grant_award_number
    ON authz.access_grant (award_number)
    WHERE award_number IS NOT NULL AND revoked_at IS NULL;

COMMIT;
