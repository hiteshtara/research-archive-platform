-- Rollback for V089 (SEC-008 activity audit).
--
-- NOT a migration; run by hand. See V088's rollback for why these live
-- outside database/migrations/.
--
-- DESTRUCTIVE: dropping the table destroys every activity row in it. The
-- script refuses when rows exist, because an audit is the one thing a schema
-- rollback must not quietly delete. If the rows are genuinely unwanted, say so
-- explicitly by setting the guard variable below.
--
--   psql -v allow_audit_loss=yes -f ...   -- only with that decision recorded

BEGIN;

DO $$
DECLARE
    rows_present bigint;
    allowed text := current_setting('rollback.allow_audit_loss', true);
BEGIN
    IF to_regclass('authz.activity_audit') IS NULL THEN
        RAISE NOTICE 'authz.activity_audit absent - nothing to roll back';
        RETURN;
    END IF;

    SELECT count(*) INTO rows_present FROM authz.activity_audit;

    IF rows_present > 0 AND COALESCE(allowed, '') <> 'yes' THEN
        RAISE EXCEPTION
          'Refusing to roll back V089: % activity row(s) would be destroyed. '
          'Export them first, or re-run with '
          'SET rollback.allow_audit_loss = ''yes'' once that loss is an '
          'authorised decision.', rows_present;
    END IF;
END
$$;

-- Partitions go with the parent; CASCADE is not needed and is deliberately
-- not used, so an unexpected dependency fails loudly instead of being
-- silently dropped along with the table.
DROP TABLE IF EXISTS authz.activity_audit;
DROP FUNCTION IF EXISTS authz.activity_audit_append_only();

DELETE FROM public.schema_migration WHERE version = 89;

COMMIT;

-- The privilege is reversed separately and can be reversed independently:
--     REVOKE INSERT ON authz.activity_audit FROM archive_api;
--     REVOKE USAGE ON SEQUENCE authz.activity_audit_activity_id_seq FROM archive_api;
