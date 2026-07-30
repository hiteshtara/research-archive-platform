# Research Archive Platform — ETL

Python pipeline that extracts approved data from BU's Kuali Oracle database
(and, for IRB, an Excel/S3 export pipeline), validates it, and loads it into
the Research Archive's PostgreSQL database. See the
[repository root README](../README.md) for the overall architecture.

## Prerequisites

- Python 3.12+
- [uv](https://github.com/astral-sh/uv) for dependency management
- Network access to BU's Kuali Oracle database (typically requires the BU
  VPN) for anything that reads from Oracle
- Network access to the target PostgreSQL instance (local Docker Postgres
  for dev, BU's RDS instance for BU environments)
- No Oracle Instant Client install is required — `python-oracledb` runs in
  thin mode by default.

## Setup

```
cd etl
uv sync
cp .env.example .env   # then fill in real values
```

Export the variables in `.env` into your shell before running anything
(`set -a; source .env; set +a`, direnv, or your process manager's env
handling). Nothing in this codebase reads `.env` directly — scripts read the
process environment.

### Environment variables

| Variable | Required for | Notes |
| --- | --- | --- |
| `ORACLE_USER`, `ORACLE_PASSWORD`, `ORACLE_DSN` | Any Oracle read (`--oracle`/`SOURCE_MODE=oracle` loaders, `archive_attachments.py`) | `ORACLE_DSN` is an Easy Connect string, e.g. `kuali-oracle.bu.edu:1521/KCPROD` |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Every `load_*.py` script, both reconciliation scripts | — |
| `POSTGRES_SSLMODE` | Optional | Defaults to `prefer`. Use `require` or `verify-full` against BU's RDS once TLS is configured |
| `SOURCE_MODE` | Optional | `oracle` (default) or `csv`. Sets the default source for Award/Negotiation/Subaward/Proposal when neither `--oracle` nor `--csv` is passed explicitly on the command line — an explicit flag always wins. See `archive_etl/config/settings.py`'s `use_oracle_source()`. |
| `DATA_BUCKET_NAME` | `load_from_s3.py`, `load_composite_from_s3.py`, `run_export.py`, `run_composite_export.py` | S3 bucket holding IRB Parquet/validation exports |
| `AWS_REGION` | Optional | Defaults to `us-east-1` |
| `IRB_S3_PREFIX` | Optional | Defaults to `landing/irb/`; only read by `load_from_s3.py` |

Every script fails fast with a clear message listing exactly which variables
are missing — see `archive_etl/config/settings.py`.

## Connectivity tests

Run these before attempting a real extraction or load, especially the first
time from a new machine or network:

```
uv run python scripts/test_oracle_connection.py
uv run python scripts/test_postgres_connection.py
# or both together:
uv run python -m archive_etl check
```

`test_postgres_connection.py` also reports how many migrations are on disk
vs. applied, and flags a gap in the migration version sequence if one
exists.

## Layout

- `load_awards_from_csv.py`, `load_negotiations_from_csv.py`,
  `load_subawards_from_csv.py`, `load_proposals_from_csv.py` — Award,
  Negotiation, Subaward, and Proposal loaders. Award, Negotiation, and
  Subaward read directly from Oracle by default (`--csv` to fall back to a
  CSV export set). Proposal reads versions/awards from Oracle by default but
  always reads people from CSV — `proposal_persons` in Oracle is missing
  several columns (`academic_year_effort`, `faculty_flag`, etc.) that the
  CSV export currently includes, and no verified extraction query exists yet
  for that shape.
- `load_from_s3.py`, `load_composite_from_s3.py` — IRB loaders that read a
  Parquet export from S3 (produced by `run_export.py` /
  `run_composite_export.py` from a manually exported Kuali Excel workbook).
- `archive_etl/` — shared library code: `pipeline/` (Oracle/CSV data
  sources, the shared load-and-reconcile framework), `upload/` (Postgres
  engine creation, migrations, S3 upload), `config/settings.py` (all
  environment-variable validation), `attachments/` (Oracle BLOB streaming
  for document archival), `utils/redaction.py` (secret redaction for error
  messages).
- `scripts/` — operational scripts: connectivity tests, load reconciliation,
  failed-load listing (see below).
- `archive_attachments.py`, `archive_subaward_attachments.py` — document/
  attachment archival from Oracle BLOB columns.
- `tests/` — pytest test suite; none of it requires live Oracle/Postgres
  credentials (Oracle/Postgres/S3 clients are mocked).

## Unified CLI

`archive_etl/__main__.py` is a thin dispatcher over the same per-domain
scripts described below — it exists for a single consistent command shape,
not a replacement for running a script directly (both work identically):

```
uv run python -m archive_etl check                          # Oracle + Postgres connectivity, no secrets printed
uv run python -m archive_etl award --source oracle           # same as: load_awards_from_csv.py --oracle
uv run python -m archive_etl award --source csv --csv-dir ~/Downloads
uv run python -m archive_etl subaward --limit 10              # bounded dry run - reads + validates 10 rows per dataset, skips the database write
```

Covers `award`, `negotiation`, `subaward`, and `proposal` (the four domains
with a `--source oracle`/`--source csv` choice). `--limit N` truncates every
dataset to at most `N` rows after reading, skips cross-dataset referential
validation (which would otherwise spuriously fail against independently
truncated datasets), and returns before any database write — use it to
exercise Oracle/CSV connectivity and the transform/prepare logic without
touching PostgreSQL. It is not a partial-load mechanism.

## Running a loader

Each loader is also a standalone script, exactly as before:

```
uv run python load_awards_from_csv.py            # Oracle (default; or SOURCE_MODE=csv)
uv run python load_awards_from_csv.py --csv       # CSV export set fallback (explicit, always overrides SOURCE_MODE)
uv run python load_from_s3.py
```

Every active loader is idempotent via `TRUNCATE`-then-reload inside a
transaction. Rerunning a loader after a failure is safe — no manual cleanup
is required first. (`archive_etl/pipeline/postgres.py` also defines a
generic `INSERT ... ON CONFLICT DO UPDATE` loading framework, but as of the
Protocol Archive removal no active loader uses it — see "Known issues"
below.)

Every loader writes a row to `archive.load_run` before it does any risky
work, so a failure is always visible in the audit trail even if the load
itself never completes (see `archive_etl/pipeline/postgres.py` and each
loader's `create_load_run`/`mark_load_failed` functions).

## Reconciliation and recovery

```
# See what failed and the exact command to rerun it
uv run python scripts/resume_failed_load.py
uv run python scripts/resume_failed_load.py --domain AWARD

# Inspect row counts for a specific load, a domain's recent history, or the
# most recent load overall
uv run python scripts/reconcile_load.py --load-id 42
uv run python scripts/reconcile_load.py --domain AWARD --limit 5
uv run python scripts/reconcile_load.py --latest
```

There is no destructive "rollback" command by design — a loader failure
leaves the previous successful data in place (every active loader reloads
inside a single transaction). Recovery is: fix the underlying problem
(credentials, source data, network), then rerun the same loader command.

## Troubleshooting

- **"Missing required environment variable(s): ..."** — set the listed
  variables; see the table above.
- **Oracle connection hangs or times out** — confirm you're on the BU VPN
  and that `ORACLE_DSN` is reachable (`test_oracle_connection.py` will fail
  with a clear driver error rather than hanging indefinitely, since
  `oracledb.connect` uses the driver's normal connect timeout).
- **Postgres `sslmode` errors against BU's RDS** — set `POSTGRES_SSLMODE`
  explicitly (`require` or `verify-full`); the default `prefer` is meant for
  local dev.
- **A load's row counts don't add up** — run
  `scripts/reconcile_load.py --load-id <id>`; it flags any load where
  `rows_read != rows_loaded + rows_rejected`.
- **Migration gap warning on startup** — a migration file is missing from
  `database/migrations/` relative to the version sequence on disk; check
  version control history for a renamed/deleted file before proceeding.

## Known issues

- `archive_etl/pipeline/postgres.py`'s `PostgreSQLLoader`/
  `PostgreSQLLoadContext` (`INSERT ... ON CONFLICT DO UPDATE` framework) was
  used only by the Protocol Archive loaders, which have been removed. It is
  currently referenced only by its own module and `tests/test_pipeline_framework.py`
  — no active loader uses it. It was intentionally left in place rather than
  deleted during Protocol Archive removal, since removing shared-looking
  framework code was out of scope for that change; a future change may want
  to either adopt it for a real use case or remove it as dead code.

## Development

```
uv sync
uv run pytest
uv run ruff check .
uv run mypy .
```
