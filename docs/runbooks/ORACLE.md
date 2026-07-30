# Oracle Runbook

## Source

`KCOEUS` (BU's legacy Kuali Research Administration Oracle schema), reachable
only from a BU VPN-connected machine.

## Workflow (current: Oracle-direct, `SOURCE_MODE=oracle` default)

```text
Oracle (KCOEUS, BU VPN-only)
    │  Python ETL, run locally on a BU VPN-connected Mac
    ▼
PostgreSQL (archive schema, direct streaming load)
```

CSV/S3 upload (the previous default) is now an explicit, non-default
fallback — see "CSV fallback" below. It is not part of the normal workflow.

## Supported operator workflow

1. **Connect to the BU VPN.** Oracle is reachable only through the VPN;
   never connect AWS directly to Oracle.
2. **Refresh AWS credentials if needed:** run `buaws` (BU's AWS credential
   helper) whenever your AWS session has expired or you haven't
   authenticated yet this session.
3. **Establish the approved PostgreSQL connection.** Either a local Postgres
   (`./scripts/run-local.sh`) or the approved SSM tunnel to the BU dev RDS
   instance — see [`docs/runbooks/LOCAL_SETUP.md`](LOCAL_SETUP.md) for the
   exact tunnel command and target. Do not open a new, separately-documented
   tunnel path; reuse the one already approved there.
4. **Export Oracle connection variables** into your shell (never commit
   these — `.env` is gitignored, and `etl/.env.example` documents the full
   set with placeholder values only):
   ```bash
   export ORACLE_USER=...
   export ORACLE_PASSWORD=...
   export ORACLE_DSN=...      # e.g. host:1521/SERVICE_NAME
   ```
5. **Confirm Oracle is the source** (it's the default — this step is only
   needed if `SOURCE_MODE` was previously set to `csv` in your shell):
   ```bash
   export SOURCE_MODE=oracle
   ```
6. **Check connectivity before a real run** (validates both Oracle and
   Postgres, prints no secrets):
   ```bash
   uv run python -m archive_etl check
   ```
7. **Run a domain load:**
   ```bash
   uv run python -m archive_etl <domain> --source oracle
   # domain is one of: award, negotiation, subaward, proposal
   ```
8. **Use `--limit` only for read-only validation**, never as a partial
   load. It truncates every dataset to at most `N` rows after reading,
   skips cross-dataset validation, and returns before any database write —
   it never touches PostgreSQL:
   ```bash
   uv run python -m archive_etl <domain> --source oracle --limit 10
   ```
9. **Review reconciliation results** after a real run:
   ```bash
   uv run python scripts/reconcile_load.py --latest
   uv run python scripts/reconcile_load.py --domain AWARD --limit 5
   ```
   Investigate any load where `rows_read != rows_loaded + rows_rejected`
   before treating the load as complete.

See [`etl/README.md`](../../etl/README.md) for the full command reference
(the unified CLI, per-loader scripts, environment variable table, and
troubleshooting), and [`docs/DECISIONS.md`](../DECISIONS.md) /
[`CLAUDE.md`](../../CLAUDE.md) for the architectural rationale.

## CSV fallback (explicit, non-default)

CSV/S3 upload remains available only where a clean existing CSV path
already works (Award/Negotiation/Subaward/Proposal), and only when
explicitly requested — it is never the default and is not the supported
day-to-day workflow:

```bash
uv run python -m archive_etl <domain> --source csv --csv-dir ~/Downloads
```

Legacy IRB continues to use its own, separate Excel/Parquet-via-S3 export
pipeline (`load_from_s3.py`, `load_composite_from_s3.py`) regardless of
`SOURCE_MODE` — that pipeline was not part of the Oracle-direct change and
is unaffected by it.

## Never guess Oracle columns

Always verify against `information_schema`, the KC OJB descriptor, or a
`DESCRIBE` in the BU Oracle client before writing extraction SQL or ETL
code — never assume a column exists or a relationship holds.
