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
-- archive_api_role.sql grants SELECT ON ALL TABLES IN SCHEMA authz. Running it
-- after V089 hands archive_api read access to the audit AND to every partition
-- of it. The role script now revokes that itself on every run, so the two
-- scripts are order-independent - this block repeats it so that applying the
-- grant alone is also sufficient.
--
-- The API writes the audit; it does not read it. Who may read an audit of who
-- read what is a separate access-control question.
-- The blanket GRANT SELECT above is a one-shot over the tables that exist when
-- it runs. If V089 has already been applied, it hands archive_api read access
-- to the activity audit - and to every PARTITION of it, because partitions are
-- separate relations with their own ACLs and "ALL TABLES IN SCHEMA" includes
-- them. Measured: without this block, all four partitions were readable.
--
-- So the revoke is part of the role script itself rather than a follow-up,
-- and it walks the partitions. Idempotent: revoking a privilege that is not
-- held is a no-op, and the guard makes it safe before V089 exists.
--
DO $$
DECLARE
    target record;
BEGIN
    IF to_regclass('authz.activity_audit') IS NULL THEN
        RAISE NOTICE 'authz.activity_audit absent (V089 not applied) - '
                     'nothing to revoke; archive_api gets no read access when '
                     'it is later created, because this script revokes on '
                     'every run';
        RETURN;
    END IF;

    FOR target IN
        SELECT c.oid::regclass AS relation
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'authz'
           AND (c.oid = 'authz.activity_audit'::regclass
                OR c.oid IN (SELECT inhrelid
                               FROM pg_inherits
                              WHERE inhparent = 'authz.activity_audit'::regclass))
    LOOP
        EXECUTE format('REVOKE SELECT ON %s FROM archive_api', target.relation);
    END LOOP;
END
$$;


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
