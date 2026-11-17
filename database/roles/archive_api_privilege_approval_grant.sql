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

-- Idle-in-transaction timeout, on archive_api ONLY.
--
-- Why: V090 judges expiry by the wall clock, so a long-open transaction no
-- longer extends an approval's life. But an API session left "idle in
-- transaction" still holds the row locks an approve/consume took, which
-- blocks every other approve/consume/revoke of that row and any bootstrap
-- waiting on the admin-membership rows. The server should end such a
-- session rather than let it hold locks indefinitely.
--
-- Why 5 minutes: scanned api/src/main on 2026-11-17. Every @Transactional
-- unit is either a readOnly repository/controller query sequence (Global
-- Search, Document Explorer, IRB, Dashboard, semantic search - the query
-- embedding is computed BEFORE the transaction) or an administrative write
-- of a handful of statements; the longest is the bulk department grant,
-- which issues its statements back to back. None waits on a client or an
-- external service between statements: the OpenAI call (timeout 60s +
-- connect 10s) and the Bedrock embedding call (2s) run outside any
-- transaction, and PDF report generation is not transactional. So
-- legitimate idle time inside a transaction is milliseconds. 5 minutes is
-- over four times the longest external timeout the API has, so even a
-- future change that wrongly wrapped an AI call in a transaction would not
-- be cut off, while a genuinely leaked session is still reaped quickly.
--
-- Scope: ALTER ROLE ... SET, with no IN DATABASE and no server-wide
-- setting, so it applies to archive_api sessions in every database and to
-- NO other role (archive_admin, the ETL and operators are unaffected). It
-- takes effect for NEW sessions; the API's pool picks it up as connections
-- recycle. Reverse with:
--     ALTER ROLE archive_api RESET idle_in_transaction_session_timeout;
ALTER ROLE archive_api SET idle_in_transaction_session_timeout = '5min';

COMMIT;

-- Verification (expect exactly this):
--
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','SELECT');   -- t
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','INSERT');   -- t
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','UPDATE');   -- t
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','DELETE');   -- f
--   SELECT has_table_privilege('archive_api','authz.privilege_approval','TRUNCATE'); -- f
--   SELECT setconfig FROM pg_db_role_setting s JOIN pg_roles r ON r.oid = s.setrole
--    WHERE r.rolname = 'archive_api';   -- {idle_in_transaction_session_timeout=5min}
