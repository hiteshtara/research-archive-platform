# Proposal: validate migration identity, not just version number

**Status:** proposal, not implemented. No code change accompanies this
document.
**Scope:** `etl/archive_etl/upload/migrations.py` only. No schema change to
`archive.*`, no change to any migration file, no change to the API or UI.

## The problem, demonstrated

`apply_migrations` decides what to run by integer version alone:

```python
applied_versions = get_applied_versions(engine)   # set[int]
for version, description, migration_file in migrations:
    if version in applied_versions:
        logger.info("Migration V{:03d} already applied: {}", version, description)
        continue
```

So a version recorded once is never looked at again, whatever file now carries
that number. Run against a throwaway database with two *different* files at
version 2:

```
applied V002 from: V002__add_column_a.sql
columns now: ['col_a', 'id']
col_b applied: False
schema_migration rows: 2
recorded file_name: V002__add_column_a.sql
on-disk file_name:  V002__add_column_b.sql
```

The second migration was skipped silently — no error, no warning, and its
column never created. An API deployed against that database expecting `col_b`
fails at runtime, exactly as the V076 incident did.

This is not hypothetical here. Two branches independently numbered a migration
V082, and V081 currently exists only on an unpushed local branch, so the next
number is genuinely ambiguous.

## What already exists

`public.schema_migration` is created with four columns and **already records
the filename**:

```sql
CREATE TABLE IF NOT EXISTS public.schema_migration (
    version       INTEGER PRIMARY KEY,
    description   VARCHAR(500) NOT NULL,
    file_name     VARCHAR(500) NOT NULL,
    installed_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
)
```

It is written on every apply and never read back. So the cheap half of this
proposal needs **no schema change at all**.

## Proposed change

### Stage 1 — filename check (no schema change)

On each run, for every version already applied, compare the recorded
`file_name` with the file now on disk at that version. On a mismatch, **fail
before applying anything**:

```
Migration version 82 was applied from V082__create_authorization_identity_and_grants.sql
but the file on disk at that version is V082__add_award_amount_dates.sql.
Two different migrations share version 82. Renumber one and re-run; do not
rename the applied file to match.
```

Catches the whole demonstrated failure. Costs one query and no migration.

### Stage 2 — content checksum (one additive column)

Filenames can match while content has changed — an edit to an already-applied
file. Add a nullable column and record a hash:

```sql
ALTER TABLE public.schema_migration
    ADD COLUMN IF NOT EXISTS checksum CHAR(64);
```

- Nullable, so every existing row stays valid; rows applied before this change
  simply have no checksum.
- Enforce **only where present**: NULL means "unknown, not verified", never a
  failure. This is what keeps the change safe to roll out against dev, test
  and production without a backfill.
- Backfill is optional and separate: a `--record-checksums` mode could fill
  NULLs from the current files, which is only meaningful if the files are known
  to be unmodified.

### Stage 3 — make the V081-style gap a decision, not a warning

The gap check already exists but only logs. Rather than promote it to an error
(it would fail every run while V081 is outstanding), require gaps to be
*declared*. `INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS` already models this
for version 73 with a documented justification. A second set — reserved but
not yet merged — would let V081 be declared reserved, keep the log quiet, and
still fail on a genuinely unexplained gap.

## Deliberately not proposed

- **Adopting Flyway.** `spring.flyway.enabled: false` is intentional and
  migrations run from the ETL. Flyway would also refuse out-of-order
  migrations by default, which would break the V081-after-V084 case that
  currently works.
- **Refusing out-of-order application.** Verified against the real runner and
  real files: applying V001–V080 and V082–V084, then introducing V081, applies
  exactly `[81]`, creates all four columns and the new table, and leaves the
  seven `authz` tables intact (install order newest-first `[81, 84, 83, 82]`).
  That behaviour is a feature of parallel branches here and should be kept.
- **Rewriting history or renaming applied files** to resolve a clash. The
  error message says so explicitly, because renaming the file to match the
  record would hide the clash rather than fix it.

## Rollout

1. Stage 1 behind no flag — it can only fail runs that were already applying
   the wrong thing.
2. Verify against a throwaway database with the real migration set, plus the
   two-files-one-version case above as a regression test.
3. Stage 2's column in its own migration, enforced only where non-NULL.
4. Stage 3 last, once V081's numbering is settled.

Stages 1 and 3 need no database change. Stage 2 is the only one that touches
`public.schema_migration`, and it is additive and nullable.
