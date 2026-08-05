# ETL command and configuration reference

Run all commands in this document from `etl/`.

## Unified CLI

```text
uv run python -m archive_etl <command> [options]
```

The unified CLI is a dispatcher. It imports the existing script, forwards
arguments, and calls that script's `main()`. Running a domain script directly
uses the same implementation.

| Command | Underlying script | Normal source | Normal target |
| --- | --- | --- | --- |
| `check [domain]` | `scripts/test_oracle_connection.py`, `scripts/test_postgres_connection.py`; optional domain loader | Oracle and PostgreSQL | None |
| `award` | `load_awards_from_csv.py` | Oracle | Award archive tables |
| `negotiation` | `load_negotiations_from_csv.py` | Oracle | Negotiation archive tables |
| `subaward` | `load_subawards_from_csv.py` | Oracle | Subaward archive tables |
| `proposal` | `load_proposals_from_csv.py` | Oracle | Proposal archive tables |
| `protocol` | `load_protocols.py` | Oracle | Protocol archive tables |
| `award-attachment` | `load_award_attachments.py` | Oracle and optional PostgreSQL metadata | PostgreSQL attachment metadata and optional S3 |
| `explore` | `archive_etl/explorer.py` | PostgreSQL | None |

The `_from_csv.py` suffixes are historical. The active structured domain
loaders read Oracle directly; they do not offer a CSV source mode.

## Common loader behavior

| Form | Effect |
| --- | --- |
| `<domain>` | Runs the domain's full load and can write PostgreSQL. |
| `<domain> --limit N` | Bounded, read-only extraction/transform diagnostic. It is not a partial load. |
| `check` | Tests Oracle and PostgreSQL connectivity without loading. |
| `check <domain>` | Runs connectivity checks, then that loader with `--limit 5`. |

Always read `--help` for domain-specific combinations:

```bash
uv run python -m archive_etl award --help
```

### Award-specific operations

| Option | Effect |
| --- | --- |
| `--load-award-id ID` | Upserts the selected Award's complete `award_number` version family and owned child records. |
| `--dry-run` | Rolls back an incremental Award or batch load after executing it. It does not make a full load dry-run. |
| `--create-batch N` | Stores exactly N selected Award IDs in the generic batch tables. |
| `--validation-overlap` | With `--create-batch`, allows repeat selection of earlier IDs for idempotency testing. Not for ongoing production loading. |
| `--load-batch ID` | Incrementally loads the persisted Award membership. |
| `--show-batch ID` | Reports batch and item status without writing. |
| `--load-unit-reference-data` | Loads shared unit, administrator, rolodex, and targeted person reference data. |
| `--load-comment-type-reference-data` | Loads the comment-type lookup. |
| `--diff-award-versions NUMBER` | Compares an Oracle Award family with archived versions. Read-only investigation command. |
| `--investigate-workflow-document-number NUMBER` | Inspects the proposed Oracle workflow-document join. Read-only and not an archive feature. |
| `--ecs` | Enables Secrets Manager credentials, startup validation, and structured logging. |
| `--migrate-only` | With `--ecs`, applies and validates migrations without Oracle access or a data load. |

## Other entry points

| Script | Purpose |
| --- | --- |
| `run_export.py` | Converts the approved legacy IRB Excel workbook into the S3 export flow. |
| `run_composite_export.py` | Produces the composite IRB history export. |
| `load_from_s3.py` | Loads the standard IRB Parquet export from S3. |
| `load_composite_from_s3.py` | Loads composite IRB history from S3. |
| `archive_attachments.py` | Selects an attachment plugin and archives Oracle BLOBs to S3. |
| `archive_subaward_attachments.py` | Convenience wrapper for the Subaward attachment plugin. |
| `export_proposal_attachments_csv.py` | Exports Proposal attachment metadata for the attachment workflow. |
| `scripts/reconcile_load.py` | Reports load audit and row reconciliation. |
| `scripts/resume_failed_load.py` | Lists failed loads and the command used to rerun the domain. |
| `scripts/test_oracle_connection.py` | Tests Oracle credentials and reachability. |
| `scripts/test_postgres_connection.py` | Tests PostgreSQL and reports migration status. |
| `scripts/build_award_ecs_overrides.py` | Builds ECS overrides for Award operations. |
| `scripts/build_award_attachment_ecs_overrides.py` | Builds ECS overrides for Award Attachment operations. |
| `scripts/transform_loader_task_definition.py` | Rewrites a loader task definition for deployment inputs. |

## Configuration

### Local structured loaders

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `ORACLE_USER` | Yes | None | Kuali Oracle username. |
| `ORACLE_PASSWORD` | Yes | None | Kuali Oracle password. |
| `ORACLE_DSN` | Yes | None | Easy Connect string or full Oracle connect descriptor. |
| `POSTGRES_HOST` | Yes | None | Archive PostgreSQL hostname. |
| `POSTGRES_PORT` | Yes | None | Archive PostgreSQL port. |
| `POSTGRES_DB` | Yes | None | Archive database name. |
| `POSTGRES_USER` | Yes | None | Archive database username. |
| `POSTGRES_PASSWORD` | Yes | None | Archive database password. |
| `POSTGRES_SSLMODE` | No | `prefer` | Psycopg/libpq TLS mode. Use the approved production value for RDS. |

### IRB S3 flow

| Variable | Required | Default | Meaning |
| --- | --- | --- | --- |
| `DATA_BUCKET_NAME` | Yes for IRB S3 commands | None | Bucket holding IRB export and validation artifacts. |
| `AWS_REGION` | No | `us-east-1` | AWS client region. |
| `IRB_S3_PREFIX` | No | `landing/irb/` | Prefix read by `load_from_s3.py`. |

### ECS mode

| Variable | Required | Meaning |
| --- | --- | --- |
| `POSTGRES_SECRET_ID` | Yes | ARN or name of a JSON secret containing `username`, `password`, and normally `host`, `port`, and `dbname`. |
| `ORACLE_SECRET_ID` | Yes except Award `--migrate-only` | ARN or name of a JSON secret containing `username`, `password`, and `dsn`. |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB` | Conditional | Non-secret routing fallback only when absent from the PostgreSQL secret. Credentials never fall back. |

ECS mode rejects `localhost`, loopback addresses, and port `15432` for
PostgreSQL. Secret values are not included in logs or error messages.

### Attachment archival

The generic attachment runner accepts:

| Option | Default | Meaning |
| --- | --- | --- |
| `--module` | Required | Attachment plugin. Availability and source mapping vary by module. |
| `--metadata-csv` | Plugin default | Approved attachment metadata input. |
| `--manifest` | Plugin default | Local SQLite progress and reconciliation manifest. |
| `--s3-bucket` | Module environment variable | Destination bucket. |
| `--s3-prefix` | Plugin default/environment variable | Destination key prefix. |
| `--aws-region` | `AWS_REGION` or `us-east-1` | S3 region. |
| `--limit` | None | Bounds records selected from metadata. |
| `--verify-only`, `--dry-run` | False | Verifies source, manifest, and S3 without uploading. |
| `--sync-postgres` | False | Writes archive location/status metadata to PostgreSQL. |
| `--max-retries` | `4` | Maximum retry count for retryable work. |
| `--blob-chunk-size` | `1048576` | Oracle BLOB streaming chunk size in bytes. |
| `--sse` | `AES256` | S3 server-side encryption: `AES256` or `aws:kms`. |
| `--kms-key-id` | None | KMS key when `--sse aws:kms` is used. |

Plugins add their own record identifiers and filters. Use the exact
module-specific runbook before executing an archival job.

## Shared package map

| Package | Responsibility |
| --- | --- |
| `archive_etl/config` | Local environment validation, ECS secret resolution, startup checks. |
| `archive_etl/pipeline` | Oracle/CSV data sources, loading primitives, validation, reporting, reconciliation. |
| `archive_etl/upload` | PostgreSQL engines, bulk copy, migration runner, S3 export upload. |
| `archive_etl/attachments` | Module plugins, BLOB readers, manifests, S3 archival runner. |
| `archive_etl/batch` | Persisted, domain-neutral batch membership and status transitions. |
| `archive_etl/extract` | Legacy IRB Excel extraction. |
| `archive_etl/transform` | IRB transformation logic. |
| `archive_etl/validate` | IRB validation logic. |
| `archive_etl/utils` | Structured logging and secret redaction. |

## Development commands

```bash
uv sync
uv run pytest
uv run ruff check .
uv run mypy .
```

