-- At most one live grant per grantee and scope.
--
-- authz.access_grant had only ix_access_grant_grantee, a plain index, so
-- nothing stopped two identical grants existing at once. Proven, not
-- theorised: GrantProvisioningIntegrationTest ran four concurrent
-- identical requests and got FOUR active grants. The application's
-- duplicate check is a read followed by a write with no lock, which is
-- the same shape of race that once produced four administrators before
-- pg_advisory_xact_lock was added to the bootstrap path.
--
-- Why it matters beyond tidiness: revocation takes a grant_id. With two
-- identical rows, revoking one silently leaves the other in force, and
-- the audit trail reads as though access was removed when it was not.
--
-- WHAT THE KEY IS, AND WHY
--
--   institutional_identifier, grant_type,
--   COALESCE(unit_number, ''), COALESCE(io_value, '')
--
-- COALESCE is load-bearing. unit_number and io_value are NULL for CENTRAL
-- and CONTACT_DERIVATION grants, and in a unique index NULLs are DISTINCT
-- from each other - so without COALESCE two CENTRAL grants for the same
-- person would not collide and the constraint would guard nothing for
-- exactly the grant type that confers the most access.
--
-- include_descendants is deliberately NOT in the key. modifyAccess can
-- toggle it, so a key containing it would let a modification collide with
-- an existing row, or slip a duplicate past by flipping the flag. Two
-- grants differing only in that flag are not meaningfully distinct either:
-- the one including descendants subsumes the one that does not.
--
-- WHAT THE PREDICATE IS, AND WHY NOT MORE
--
--   WHERE revoked_at IS NULL
--
-- A partial index predicate must be immutable, so it cannot express
-- "active right now" - AccessGrant.isActiveAt(now) also consults
-- valid_from and expires_at against the current time, and now() is not
-- available here. Two honest choices follow:
--
--   this one      - forbids a second unrevoked grant for the same scope,
--                   whatever its validity window. A naturally expired
--                   grant must be revoked before the scope is granted
--                   again. That costs one audited revocation and makes
--                   the history read correctly.
--
--   the alternative - an EXCLUDE constraint over tstzrange(valid_from,
--                   expires_at) with btree_gist, which permits sequential
--                   non-overlapping grants. Fully correct across time,
--                   but it adds an extension dependency to RDS for a
--                   case that does not arise today: no grant in DEV sets
--                   expires_at, and the provisioning CLI cannot set one.
--
-- The simpler one is chosen. If expiring grants ever become routine, the
-- exclusion constraint is the upgrade, and it can replace this index
-- without data loss.
--
-- Historical and revoked rows are untouched: they fall outside the
-- predicate and are never compared.

-- No psql meta-commands here. The ETL migration runner executes each file
-- with a raw cursor.execute(), so a \set line is a syntax error - and no
-- other migration in this repository uses one. The DO block below raises,
-- which aborts the statement batch on its own.

-- Refuse to create the constraint over data that already violates it,
-- rather than failing with a bare duplicate-key error that says nothing
-- about which rows are at fault.
DO $$
DECLARE
    offending text;
BEGIN
    SELECT string_agg(
               format('%s/%s/%s/%s (%s rows)',
                      institutional_identifier, grant_type,
                      COALESCE(unit_number, '-'), COALESCE(io_value, '-'), n),
               '; ')
      INTO offending
      FROM (
          SELECT institutional_identifier, grant_type, unit_number, io_value,
                 count(*) AS n
            FROM authz.access_grant
           WHERE revoked_at IS NULL
           GROUP BY institutional_identifier, grant_type,
                    COALESCE(unit_number, ''), COALESCE(io_value, ''),
                    unit_number, io_value
          HAVING count(*) > 1
      ) duplicates;

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION
          'Cannot add ux_access_grant_active_scope: duplicate unrevoked grants '
          'already exist: %. Revoke the redundant ones first - do not delete '
          'them, the audit trail depends on the rows surviving.', offending;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_access_grant_active_scope
    ON authz.access_grant (
        institutional_identifier,
        grant_type,
        COALESCE(unit_number, ''),
        COALESCE(io_value, '')
    )
    WHERE revoked_at IS NULL;

COMMENT ON INDEX authz.ux_access_grant_active_scope IS
    'At most one unrevoked grant per grantee and scope. COALESCE because '
    'NULLs are distinct in a unique index and would leave CENTRAL grants '
    'unguarded. include_descendants is excluded so modifyAccess can toggle '
    'it without colliding.';
