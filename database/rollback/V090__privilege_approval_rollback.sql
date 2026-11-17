-- Rollback for V090 (privilege approval, A-category control 3).
--
-- NOT a migration; run by hand. See V088's rollback for why these live
-- outside database/migrations/.
--
-- DESTRUCTIVE: dropping the table destroys the record of who requested and
-- who approved every privilege escalation. The script refuses when rows
-- exist. If the rows are genuinely unwanted, say so explicitly:
--
--   SET rollback.allow_approval_loss = 'yes';   -- only with that decision recorded

BEGIN;

DO $$
DECLARE
    rows_present bigint;
    allowed text := current_setting('rollback.allow_approval_loss', true);
BEGIN
    IF to_regclass('authz.privilege_approval') IS NULL THEN
        RAISE NOTICE 'authz.privilege_approval absent - nothing to roll back';
        RETURN;
    END IF;

    SELECT count(*) INTO rows_present FROM authz.privilege_approval;

    IF rows_present > 0 AND COALESCE(allowed, '') <> 'yes' THEN
        RAISE EXCEPTION
          'Refusing to roll back V090: % approval row(s) would be destroyed. '
          'Export them first, or re-run with '
          'SET rollback.allow_approval_loss = ''yes'' once that loss is an '
          'authorised decision.', rows_present;
    END IF;
END
$$;

-- No CASCADE, so an unexpected dependency fails loudly.
DROP TABLE IF EXISTS authz.privilege_approval;
DROP FUNCTION IF EXISTS authz.privilege_approval_guard();

DO $$
BEGIN
    IF to_regclass('public.schema_migration') IS NOT NULL THEN
        DELETE FROM public.schema_migration WHERE version = 90;
    END IF;
END
$$;

COMMIT;

-- The privilege is reversed separately:
--     REVOKE SELECT, INSERT, UPDATE ON authz.privilege_approval FROM archive_api;
--     REVOKE USAGE ON SEQUENCE authz.privilege_approval_approval_id_seq FROM archive_api;
