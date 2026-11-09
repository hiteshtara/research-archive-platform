-- SEC-008: the least-privilege grant that lets archive_api write activity
-- audit rows, and nothing else.
--
-- DELIBERATELY NOT PART OF V089, AND NOT PART OF archive_api_role.sql.
--
-- Applying the migration creates the table. Applying THIS makes the API able
-- to write to it. They are separated so that granting the privilege is its
-- own reviewed, dated act rather than something that arrives with a schema
-- change - and so that, until it is run, collection cannot happen even if the
-- feature flag is turned on by mistake.
--
--   Order of operations, each approved separately:
--     1. V089          - the table exists                 (not applied)
--     2. THIS SCRIPT   - the API may INSERT into it        (not applied)
--     3. the flag      - the API actually writes rows      (off)
--
-- Run as the database owner (archive_admin), once per environment:
--
--     psql "$DATABASE_URL" -f database/roles/archive_api_activity_audit_grant.sql
--
-- NOT APPLIED to shared DEV or Production. Requires BU approval of the
-- privacy defaults and the retention period first - see
-- docs/security/SEC_008_ACTIVITY_AUDIT.md.

BEGIN;

-- Fail loudly rather than half-granting: this script is meaningless without
-- V089, and a silent no-op would look like it had worked.
DO $$
BEGIN
    IF to_regclass('authz.activity_audit') IS NULL THEN
        RAISE EXCEPTION
          'authz.activity_audit does not exist. Apply V089 before granting '
          'the privilege that writes to it.';
    END IF;
END
$$;

-- INSERT and nothing else.
--
-- No UPDATE or DELETE: the table is append-only. The trigger in V089 is the
-- second line of defence; this grant is the first, and it is the one that
-- stops the statement before the trigger ever runs.
GRANT INSERT ON authz.activity_audit TO archive_api;

-- And an explicit REVOKE of SELECT, which is NOT redundant.
--
-- archive_api_role.sql contains:
--     GRANT SELECT ON ALL TABLES IN SCHEMA authz TO archive_api;
--
-- That is a one-shot grant over the tables existing when it runs - so if the
-- role script is run AFTER V089, archive_api silently acquires read access to
-- the activity audit. Verified: without this revoke,
-- has_table_privilege('archive_api','authz.activity_audit','SELECT') is TRUE.
--
-- The API writes the audit; it does not read it. Who may read an audit of who
-- read what is a separate access-control question, and inheriting SELECT from
-- a blanket grant would answer it by accident.
--
-- OPERATIONAL CONSEQUENCE, and it is easy to get wrong: re-running
-- archive_api_role.sql after this script RE-GRANTS the SELECT. This script
-- must be re-applied after any run of the role script. The integration test
-- ActivityAuditGrantIntegrationTest applies them in that order and asserts
-- SELECT is absent, so the ordering is checked rather than remembered.
REVOKE SELECT ON authz.activity_audit FROM archive_api;

-- The sequence behind activity_id. An INSERT into a BIGSERIAL column needs
-- USAGE on its sequence; without this the failure appears only at the first
-- write, which for an audit table means at the worst possible moment.
GRANT USAGE ON SEQUENCE authz.activity_audit_activity_id_seq TO archive_api;

-- Partitions inherit the parent's privileges in PostgreSQL, so no per-partition
-- grant is needed and none should be added: a per-partition grant would have to
-- be repeated on every monthly rollover and would be forgotten.

COMMIT;

-- Verification (run as archive_api, expect exactly this):
--
--   SELECT has_table_privilege('archive_api','authz.activity_audit','INSERT');  -- t
--   SELECT has_table_privilege('archive_api','authz.activity_audit','SELECT');  -- f
--   SELECT has_table_privilege('archive_api','authz.activity_audit','UPDATE');  -- f
--   SELECT has_table_privilege('archive_api','authz.activity_audit','DELETE');  -- f
--
-- And unchanged by this script:
--
--   archive_api holds no INSERT/UPDATE/DELETE on any archive.* table
--   archive_api holds no UPDATE/DELETE on authz.access_audit
