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
SELECT set_config('archive_api.password', :'archive_api_password', true);

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

-- Reassert the attributes even on an existing role, so a hand-edited role
-- cannot quietly keep a privilege it should not have.
ALTER ROLE archive_api
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

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
