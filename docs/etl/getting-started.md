# Run your first ETL smoke test

This tutorial sets up the Python environment, checks both databases, and runs
a bounded Oracle extraction without changing PostgreSQL. By the end, you will
know that your credentials, network path, Oracle queries, and transform logic
work.

## What you need

- Python 3.12 or newer
- `uv`
- BU VPN or equivalent access to the Kuali Oracle database
- Network access to the target PostgreSQL database
- Oracle and PostgreSQL credentials

`python-oracledb` uses thin mode, so Oracle Instant Client is not required.

## Step 1: Install the ETL environment

From the repository root:

```bash
cd etl
uv sync
```

Verify that the CLI is available:

```bash
uv run python -m archive_etl --help
```

You should see subcommands for `award`, `negotiation`, `subaward`,
`proposal`, `award-attachment`, `protocol`, and `check`.

## Step 2: Configure database access

Create a local environment file:

```bash
cp .env.example .env
```

Replace every `changeme` value in `.env`, then export it into the current
shell:

```bash
set -a
source .env
set +a
```

The code does not load `.env` itself. It reads the process environment.
Keep `.env` local; it is gitignored and must never be committed.

## Step 3: Check connectivity

```bash
uv run python -m archive_etl check
```

This performs read-only Oracle and PostgreSQL checks. The PostgreSQL check
also compares migrations present on disk with migrations recorded in
`public.schema_migration`.

For a deeper but still read-only check, add a domain:

```bash
uv run python -m archive_etl check award
```

After checking connectivity, this runs the Award loader with a five-row
limit. It exercises Oracle extraction and transformation but does not write
to PostgreSQL.

## Step 4: Run a larger bounded sample

```bash
uv run python -m archive_etl award --limit 10
```

For Award, Proposal, Negotiation, and Subaward, `--limit N` caps each dataset
after extraction, skips cross-dataset referential checks that would be
misleading on independently truncated data, and returns before the database
write. It is a diagnostic mode, not a partial load.

Protocol builds a coherent sample instead: it selects up to `N` protocol
versions, then retains only the personnel and units belonging to those
versions.

## Step 5: Run the development checks

```bash
uv run pytest
uv run ruff check .
uv run mypy .
```

The test suite mocks Oracle, PostgreSQL, S3, and AWS identity calls. It does
not require live credentials.

## What you built

You now have a working ETL development environment and have exercised the
real connectivity and transformation paths without loading archive data.
Continue with the [operations guide](operations.md) before running a full
load, or use the [reference](reference.md) to choose a command.

## Troubleshooting

### `Missing required environment variable(s)`

Export the variables named in the error. See the complete
[configuration reference](reference.md#configuration).

### Oracle times out

Confirm the BU VPN is active and that `ORACLE_DSN` resolves from your current
network. Run the narrower check for a clearer error:

```bash
uv run python scripts/test_oracle_connection.py
```

### PostgreSQL fails on TLS

Set `POSTGRES_SSLMODE=require` or `verify-full` for RDS. The default,
`prefer`, supports local development but should not be treated as the
production policy.

