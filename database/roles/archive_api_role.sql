-- archive_api: the least-privilege database role for the Research Archive API.
--
-- NOT a migration. It lives outside database/migrations/ on purpose: the
-- migration runner applies every V*__*.sql in order against every
-- environment, and a role with a password is an environment-specific,
-- operator-run action, not a schema change. Keeping it here also means
-- `--migrate-only` can never create a login role as a side effect.
--
-- Run as the database owner (archive_admin), once per environment:
--
--   psql "<owner connection>" \
--     -v archive_api_password="$(read -s)" \
--     -f database/roles/archive_api_role.sql
--
-- The password is supplied as a psql variable so it is never written into
-- this file, never passed as a shell argument, and never committed.
--
-- Idempotent: safe to re-run. Re-running does NOT reset the password.

\set ON_ERROR_STOP on

BEGIN;

-- Bridge the psql variable into a run-time setting. psql does NOT expand
-- :'variables' inside a dollar-quoted block, so the DO below cannot read
-- one directly; set_config is evaluated out here, where it can.
--
-- Output is silenced because set_config RETURNS the value it sets, and
-- psql would print the password to the terminal - and therefore into
-- whatever is capturing that terminal. Learned the hard way on
-- 2026-10-28, when a DEV run echoed a freshly generated credential and
-- it had to be discarded and regenerated. Errors go to stderr and are
-- unaffected.
\o /dev/null
SELECT set_config('archive_api.password', :'archive_api_password', true);
\o

-- ─────────────────────────────────────────────────────────────────────
-- 1. The role
--
-- Owns nothing, so DROP and ALTER on any existing object are impossible
-- regardless of what is granted below: PostgreSQL requires ownership for
-- those, and there is no GRANT that confers it.
-- ─────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'archive_api') THEN
        EXECUTE format(
            'CREATE ROLE archive_api LOGIN PASSWORD %L '
            'NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT '
            'NOREPLICATION NOBYPASSRLS',
            current_setting('archive_api.password'));
    ELSE
        RAISE NOTICE 'archive_api already exists - attributes reasserted, password unchanged';
    END IF;
END
$$;

-- Assert, rather than reassert, the attributes.
--
-- ALTER ROLE cannot be used here. To turn an attribute OFF you must hold
-- it yourself: removing SUPERUSER needs SUPERUSER, removing REPLICATION
-- needs REPLICATION, and so on. The owner that runs this script
-- (archive_admin) holds CREATEDB and CREATEROLE but not SUPERUSER,
-- REPLICATION or BYPASSRLS, so a blanket
--   ALTER ROLE archive_api NOSUPERUSER ... NOBYPASSRLS;
-- fails outright - confirmed against DEV on 2026-10-28 and reproduced in
-- a throwaway container. CREATE ROLE with the same NO* list is fine,
-- because it changes nothing from the defaults.
--
-- So: verify, and fail loudly naming what a superuser must fix. That is
-- strictly better than an ALTER that silently could not have worked.
DO $$
DECLARE
    bad text;
BEGIN
    SELECT string_agg(attr, ', ') INTO bad
      FROM (
        SELECT 'SUPERUSER'   AS attr FROM pg_roles WHERE rolname='archive_api' AND rolsuper
        UNION ALL SELECT 'CREATEDB'    FROM pg_roles WHERE rolname='archive_api' AND rolcreatedb
        UNION ALL SELECT 'CREATEROLE'  FROM pg_roles WHERE rolname='archive_api' AND rolcreaterole
        UNION ALL SELECT 'REPLICATION' FROM pg_roles WHERE rolname='archive_api' AND rolreplication
        UNION ALL SELECT 'BYPASSRLS'   FROM pg_roles WHERE rolname='archive_api' AND rolbypassrls
        UNION ALL SELECT 'INHERIT'     FROM pg_roles WHERE rolname='archive_api' AND rolinherit
      ) t;

    IF bad IS NOT NULL THEN
        RAISE EXCEPTION
          'archive_api holds attributes it must not have: %. Removing them '
          'requires a role that holds each attribute (SUPERUSER for '
          'SUPERUSER, and so on). Fix with a superuser, then re-run.', bad;
    END IF;
END
$$;

GRANT CONNECT ON DATABASE research_archive TO archive_api;

-- ─────────────────────────────────────────────────────────────────────
-- 2. archive - SELECT only
--
-- Verified against the API source: there is NO INSERT, UPDATE or DELETE
-- against any archive table anywhere in api/src/main/java. Award,
-- Proposal, Subaward, Negotiation, Global Search, Dashboard, Explorer,
-- attachment metadata and AI evidence search are all reads.
-- ─────────────────────────────────────────────────────────────────────
GRANT USAGE ON SCHEMA archive TO archive_api;
GRANT SELECT ON ALL TABLES IN SCHEMA archive TO archive_api;   -- includes views

-- ─────────────────────────────────────────────────────────────────────
-- 3. authz - SELECT on all, write only where the API writes
-- ─────────────────────────────────────────────────────────────────────
GRANT USAGE ON SCHEMA authz TO archive_api;
GRANT SELECT ON ALL TABLES IN SCHEMA authz TO archive_api;

-- Written by the API. UPDATE is required on person_status and
-- identity_link even though no bare UPDATE statement appears in the
-- source: both are written with INSERT ... ON CONFLICT DO UPDATE.
GRANT INSERT, UPDATE ON authz.identity_link     TO archive_api;
GRANT INSERT, UPDATE ON authz.access_grant      TO archive_api;
GRANT INSERT, UPDATE ON authz.admin_role_member TO archive_api;
GRANT INSERT, UPDATE ON authz.relink_approval   TO archive_api;
GRANT INSERT, UPDATE ON authz.person_status     TO archive_api;

-- Append-only. INSERT and nothing else: no UPDATE, no DELETE. The
-- tr_access_audit_append_only trigger is a second line of defence, not
-- the first - this grant stops the statement before the trigger runs.
GRANT INSERT ON authz.access_audit TO archive_api;

-- authz.kim_principal and authz.principal_crosswalk are written by the
-- ETL only. They keep SELECT from the blanket grant above and get no DML.

-- ─────────────────────────────────────────────────────────────────────
-- 4. Sequences - exactly the five the API inserts into
--
-- INSERT into a BIGSERIAL column needs USAGE on its sequence; without it
-- the failure appears only at the first write.
-- principal_crosswalk_crosswalk_id_seq is deliberately NOT granted.
-- ─────────────────────────────────────────────────────────────────────
GRANT USAGE ON SEQUENCE authz.identity_link_identity_link_id_seq     TO archive_api;
GRANT USAGE ON SEQUENCE authz.access_grant_grant_id_seq              TO archive_api;
GRANT USAGE ON SEQUENCE authz.access_audit_audit_id_seq              TO archive_api;
GRANT USAGE ON SEQUENCE authz.admin_role_member_admin_member_id_seq  TO archive_api;
GRANT USAGE ON SEQUENCE authz.relink_approval_approval_id_seq        TO archive_api;

-- ─────────────────────────────────────────────────────────────────────
-- 5. Explicitly withheld
--
-- public.schema_migration: no grant, so the API cannot read or write
-- migration state. Nothing is revoked from PUBLIC here - doing so would
-- affect other roles and belongs in a reviewed migration, not a role
-- script.
-- ─────────────────────────────────────────────────────────────────────
-- The migration ledger is owned by the ETL, not by the API. The API must not
-- read it (it reveals nothing the API needs) and must never write it. The
-- table is created by the ETL's migration runner, not by any migration file,
-- so on a database that has only had migrations applied it does not exist
-- yet -- hence the guard. A bare REVOKE would abort this script there.
DO $$
BEGIN
    IF to_regclass('public.schema_migration') IS NOT NULL THEN
        REVOKE ALL ON public.schema_migration FROM archive_api;
    ELSE
        RAISE NOTICE 'public.schema_migration absent - no privileges to revoke; '
                     'archive_api gets none when the ETL later creates it';
    END IF;
END
$$;

-- ─────────────────────────────────────────────────────────────────────
-- 6. Future archive tables
--
-- ALTER DEFAULT PRIVILEGES applies ONLY to objects created by the role
-- named in FOR ROLE - it is not a property of the schema. So this covers
-- tables the ETL creates while connected as archive_admin, and covers
-- nothing if a future migration runs as a different role. If the
-- migration role ever changes, this statement must be repeated for it or
-- new archive tables will be invisible to the API.
--
-- Deliberately NOT done for authz: a new authorization table should
-- require a conscious grant rather than inherit one.
-- ─────────────────────────────────────────────────────────────────────
ALTER DEFAULT PRIVILEGES FOR ROLE archive_admin IN SCHEMA archive
    GRANT SELECT ON TABLES TO archive_api;

COMMIT;
