# ETL architecture

The ETL is the only component allowed to move approved historical data into
the archive. Oracle remains read-only, PostgreSQL and S3 are archive-owned,
and the Spring Boot API never receives Oracle connectivity or credentials.

## The three data flows

```text
Structured research data
  Kuali Oracle
      -> domain Oracle queries
      -> pandas preparation and validation
      -> PostgreSQL migrations
      -> atomic archive-table reload or scoped UPSERT

Legacy IRB export
  approved Excel workbook
      -> extract/transform/validate
      -> Parquet + validation artifacts in S3
      -> IRB PostgreSQL loader

Attachment binaries
  approved attachment metadata + Oracle BLOB
      -> chunked temporary file + SHA-256
      -> private S3 object
      -> S3 HEAD verification
      -> optional PostgreSQL archive-location status
```

These flows share configuration and utility code, but they are not
interchangeable. In particular, structured loaders do not have a CSV source
fallback, the IRB S3 workflow does not replace the Protocol loader, and
attachment metadata loading does not itself copy BLOB content.

## Structured loader lifecycle

```text
validate configuration
        |
        v
apply unapplied migrations
        |
        v
read Oracle datasets ---- --limit N ---> validate/report/exit
        |
        v
prepare and validate complete dataset relationships
        |
        v
commit STARTED row in archive.load_run
        |
        v
begin PostgreSQL transaction
        |
        +--> truncate domain-owned tables
        +--> bulk copy prepared rows
        +--> reconcile counts and relationships
        +--> mark load LOADED
        |
        v
commit transaction

on failure:
  rollback load transaction
  keep prior successful archive snapshot
  mark the separately committed load_run row FAILED
```

The separate audit transaction matters. If the `STARTED` row were created
inside the destructive reload transaction, a failure would roll it back and
leave no record of the attempt.

## Data grain and validation

The pipeline distinguishes source rows, archive rows, and business objects.
Those counts often differ legitimately.

For example, one Award business identifier can have many historical version
rows. The loader must preserve those rows rather than deduplicating until a
dashboard count happens to match. Reconciliation therefore records mechanical
load counts, while domain validation applies the correct business key and
relationship rules.

`--limit` deliberately skips complete-dataset referential validation for
most domains because independently truncating related datasets creates false
orphans. Protocol instead builds a coherent sample around selected version
keys.

## Migration ownership

Migration files live in `database/migrations/`, but Spring Boot has Flyway
disabled. The Python migration runner:

1. discovers files matching `V<number>__<description>.sql`;
2. warns about gaps in the on-disk version sequence;
3. creates `public.schema_migration` if needed;
4. skips versions already recorded there;
5. applies each remaining file and its tracking row in one database
   transaction.

This makes migration execution part of the controlled load path. It also
means an API deployment alone cannot bring a database schema up to date.

## Full reloads, incremental loads, and batches

Full domain loaders favor a truncate-and-reload snapshot. This is simple and
idempotent, but requires enough time and capacity to re-extract the domain.
The truncate and copy occur inside one transaction so readers do not see a
half-loaded snapshot.

Award also has scoped UPSERT operations. `--load-award-id` widens one Oracle
row to the complete `award_number` family before updating owned tables. This
preserves the business relationship between versions.

A batch is a durable selection, not another form of `--limit`:

```text
archive.etl_batch
   1
   +--- many archive.etl_batch_item rows
                    |
                    +--> exact numeric entity keys in stable order
```

Once created, membership does not change. Later load/upload commands can
resume against the same entities. Generic batch-item status tracks only the
batch step; domain-specific state, such as attachment upload status, stays in
the domain table.

## Attachment architecture

Each attachment plugin owns the source metadata interpretation, business
record identifier, BLOB reader, destination naming, and PostgreSQL sync
behavior. This prevents a verified join for one Kuali module from being
incorrectly reused for another.

The runner streams each BLOB rather than loading it wholly into memory:

```text
Oracle BLOB
   -> fixed-size chunks
   -> temporary file + running SHA-256
   -> S3 upload with checksum metadata
   -> S3 HEAD: compare size and checksum
   -> manifest/PostgreSQL status
   -> remove temporary file
```

The local SQLite manifest makes long archival jobs resumable and exposes
missing content, failed uploads, checksum mismatches, and metadata rows no
longer present in the current source selection.

## Local and ECS execution

Local mode reads credentials from process environment variables. It is meant
for a trusted developer shell with `.env` excluded from version control.

ECS mode changes the security and operational contract:

- database usernames and passwords come from Secrets Manager;
- AWS identity is checked before work begins;
- PostgreSQL, Oracle, S3, and schema prerequisites fail closed;
- local tunnel endpoints are rejected;
- structured logs use an allowlist and error messages pass through secret
  redaction.

The API task and the loader task are different trust boundaries. Loader IAM
can write approved archive objects and database tables. API IAM should remain
read-only against archived documents and must never gain Oracle access.

## Key trade-offs

### pandas preparation

DataFrames make column normalization, joins, and validation direct and
testable. Full-domain loads can consume substantial memory, so bounded Oracle
reads and domain batching exist where operationally necessary.

### truncate and reload

Snapshot replacement is easy to reason about and safe to rerun. It is more
expensive than change-data capture and cannot provide small continuous
updates. The archive currently values deterministic preservation over
near-real-time synchronization.

### migrations in ETL

Keeping migrations next to the load ensures schema and data advance together.
The cost is that API-only deployment tooling cannot assume migrations ran;
operators must include an ETL migration step.

### module-specific attachment plugins

Plugins repeat some mapping code, but they keep unverified Oracle assumptions
from becoming a generic abstraction. In this archive, explicit source lineage
is more valuable than removing a small amount of duplication.

## Legacy surfaces

`CsvDataSource` and the generic `PostgreSQLLoader` UPSERT framework remain in
the shared package and have tests, but the active structured loaders do not
use them. Treat them as retained legacy code, not as the preferred template
for a new loader.

## Related documentation

- [Operations guide](operations.md)
- [Reference](reference.md)
- [Database schema](../architecture/DATABASE_SCHEMA.md)
- [End-to-end architecture](../architecture/END_TO_END_OVERVIEW.md)
- [Protocol parent resolution](../PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md)
- [ETL batch framework](../architecture/ETL_BATCH_FRAMEWORK.md)

