-- A-category control 3: the least-privilege grant that lets archive_api run
-- the privilege-approval workflow (authz.privilege_approval, V090).
--
-- DELIBERATELY NOT PART OF V090, AND NOT PART OF archive_api_role.sql - the
-- same separation as archive_api_activity_audit_grant.sql. Applying the
-- migration creates the table; applying THIS lets the API write to it. Each
-- is its own reviewed, dated act:
--
--     1. V090          - the table exists                    (not applied)
--     2. THIS SCRIPT   - the API may request/approve/consume  (not applied)
--     3. writes flag   - administrator writes enabled         (off, D6)
--
-- Run as the database owner (archive_admin), once per environment:
--
--     psql "$DATABASE_URL" -f database/roles/archive_api_privilege_approval_grant.sql
--
-- NOT APPLIED to shared DEV or Production.

BEGIN;

-- Fail loudly rather than half-granting: this is meaningless without V090.
DO $$
BEGIN
    IF to_regclass('authz.privilege_approval') IS NULL THEN
        RAISE EXCEPTION
          'authz.privilege_approval does not exist. Apply V090 before granting '
          'the privileges that use it.';
    END IF;
END
$$;

-- SELECT: the workflow reads an approval (requester, approver, binding)
--   before it approves or consumes it. archive_api_role.sql's blanket authz
--   SELECT also covers this when it is re-run after V090; granting it here
--   means this script alone is sufficient.
-- INSERT: a request is a new row.
-- UPDATE: approve, consume and revoke are status transitions. The
--   tr_privilege_approval_guard trigger confines every UPDATE to those
--   transitions and freezes the bound columns, so UPDATE cannot be used to
--   rewrite what was approved.
--
-- NO DELETE and NO TRUNCATE: the table is append-only. The trigger refuses a
-- DELETE as the second line of defence; this grant stops it first.
GRANT SELECT, INSERT, UPDATE ON authz.privilege_approval TO archive_api;

-- The sequence behind approval_id. Without it the first INSERT fails.
GRANT USAGE ON SEQUENCE authz.privilege_approval_approval_id_seq TO archive_api;

COMMIT;

-- Verification (expect exactly this):
--
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','SELECT');   -- t
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','INSERT');   -- t
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','UPDATE');   -- t
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','DELETE');   -- f
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','TRUNCATE'); -- f
