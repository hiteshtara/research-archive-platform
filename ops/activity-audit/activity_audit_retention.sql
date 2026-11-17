-- SEC-008 / US-SEC-004 - activity-audit retention procedure.
--
-- NOT SCHEDULED. NOT EXECUTED ANYWHERE. Nothing calls this file: no cron, no
-- EventBridge rule, no pg_cron, no loader. It exists so that the day BU
-- approves a retention period the operation is reviewed, tested code rather
-- than an improvised DROP TABLE. See docs/runbooks/ACTIVITY_AUDIT_PARTITIONS.md.
--
-- THE RETENTION PERIOD IS A BUSINESS DECISION AND IS NOT APPROVED.
-- 90 days is a PROPOSAL for review (docs/security/SEC_008_ACTIVITY_AUDIT.md).
-- The period is a required parameter here precisely so that no default can
-- silently become the policy.
--
-- WHAT IT DOES
--   For every monthly partition of authz.activity_audit whose whole range
--   ends on or before (as_of - retention_days), report it - and, ONLY when
--   execute=true, DETACH and DROP it. Whole months only: a partition that is
--   partly inside the retention window is kept in full. The DEFAULT
--   (overflow) partition is never touched - rows there mean partition
--   maintenance has stopped, and they need a person, not a job.
--
--   A DROP does not fire row triggers, so this does not fight the
--   append-only trigger, and the trigger is never disabled.
--
-- WHAT IT DOES NOT DO
--   - export anything first (a decision still open - see the runbook);
--   - keep denials longer than views (also open);
--   - create next month's partition.
--
-- USAGE (dry run is the default; nothing is dropped without execute=true):
--
--   psql "$URL" -v retention_days=90 -f ops/activity-audit/activity_audit_retention.sql
--   psql "$URL" -v retention_days=90 -v execute=true \
--        -f ops/activity-audit/activity_audit_retention.sql
--
-- Run as the table OWNER (the migration role), never as archive_api: the API
-- role holds INSERT on authz.activity_audit and nothing else.
--
-- The function is created in pg_temp, so it lives only for this session and
-- leaves nothing behind in the schema.

\set ON_ERROR_STOP on

CREATE OR REPLACE FUNCTION pg_temp.activity_audit_retention(
    p_retention_days INTEGER,
    p_as_of          TIMESTAMPTZ,
    p_execute        BOOLEAN
)
RETURNS TABLE (partition_name TEXT, range_end TIMESTAMPTZ, action TEXT)
LANGUAGE plpgsql
AS $$
DECLARE
    v_cutoff    TIMESTAMPTZ;
    v_partition RECORD;
BEGIN
    IF p_retention_days IS NULL OR p_retention_days < 1 THEN
        RAISE EXCEPTION 'retention_days must be a positive number of days (got %)',
            p_retention_days;
    END IF;
    IF p_as_of IS NULL THEN
        RAISE EXCEPTION 'as_of is required';
    END IF;
    v_cutoff := p_as_of - make_interval(days => p_retention_days);

    FOR v_partition IN
        SELECT c.oid::regclass::text AS name,
               pg_get_expr(c.relpartbound, c.oid) AS bound
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
         WHERE i.inhparent = 'authz.activity_audit'::regclass
         ORDER BY 1
    LOOP
        partition_name := v_partition.name;
        IF v_partition.bound = 'DEFAULT' THEN
            range_end := NULL;
            action := 'SKIPPED_DEFAULT_PARTITION';
            RETURN NEXT;
            CONTINUE;
        END IF;

        range_end := (regexp_match(v_partition.bound, 'TO \(''([^'']+)''\)'))[1]::timestamptz;
        IF range_end IS NULL THEN
            RAISE EXCEPTION 'cannot read the range of partition % (bound: %)',
                v_partition.name, v_partition.bound;
        END IF;

        IF range_end <= v_cutoff THEN
            IF p_execute THEN
                EXECUTE format('ALTER TABLE authz.activity_audit DETACH PARTITION %s',
                               v_partition.name);
                EXECUTE format('DROP TABLE %s', v_partition.name);
                action := 'DROPPED';
            ELSE
                action := 'WOULD_DROP';
            END IF;
        ELSE
            action := 'RETAINED';
        END IF;
        RETURN NEXT;
    END LOOP;
END;
$$;

-- END OF FUNCTION DEFINITION --
-- Everything below is psql-only and is not executed by the integration test.
\if :{?retention_days}
\else
    \echo 'retention_days is required: there is no approved default. Pass -v retention_days=N'
    DO $$ BEGIN RAISE EXCEPTION 'retention_days not supplied'; END $$;
\endif
\if :{?execute}
\else
    \set execute false
\endif

BEGIN;
SELECT * FROM pg_temp.activity_audit_retention(:retention_days, now(), :execute);
COMMIT;
