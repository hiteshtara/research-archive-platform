# Protocol Oracle-Direct Loader

Documents the Protocol Archive rebuilt on `feature/protocol-oracle-loader`:
`archive.protocol_version` / `archive.protocol_person` / `archive.protocol_unit`,
loaded directly from Oracle via `etl/load_protocols.py`. This is a clean
rebuild, not a restoration of the Protocol Archive removed by
`V032__drop_protocol_archive.sql` — see
[`docs/DECISIONS.md`](DECISIONS.md) for that history. As of this branch:
**no API or UI work exists for Protocol** (ETL only), and **legacy IRB is
completely unchanged** — this is an additive, independent domain, not a
replacement for IRB.

## Architecture: Oracle → Python ETL → PostgreSQL

```text
KCOEUS.PROTOCOL / PROTOCOL_PERSONS / PROTOCOL_UNITS  (BU Oracle, VPN-only)
    │
    │  etl/load_protocols.py, run from a BU VPN-connected machine
    │  python-oracledb, fetchmany() batches — no CSV step, no CSV fallback
    ▼
archive.protocol_version / archive.protocol_person / archive.protocol_unit
    (PostgreSQL, one TRUNCATE-then-reload transaction per full load)
```

Same shape as every other Oracle-direct loader in this repo (Award,
Negotiation, Subaward, Proposal): the API and UI never talk to Oracle
directly, and the ETL never writes back to Oracle. **There is no CSV source
for Protocol** — no `SOURCE_MODE`, no `--csv`/`--csv-dir` flag, and no CSV
export step in the development order. Oracle is the only supported source.

Three Oracle extraction queries, one per table, each ordered ascending by
`(protocol_number, sequence_number, ...)`:

| Table | Oracle source | File |
| --- | --- | --- |
| `archive.protocol_version` | `KCOEUS.PROTOCOL` (+ `PROTOCOL_DOCUMENT`, `PROTOCOL_STATUS`, `PROTOCOL_TYPE` lookups) | `oracle/protocol/export_protocol_versions.sql` |
| `archive.protocol_person` | `KCOEUS.PROTOCOL_PERSONS` (+ `PROTOCOL_PERSON_ROLES`, `ROLODEX` lookups) | `oracle/protocol/export_protocol_persons.sql` |
| `archive.protocol_unit` | `KCOEUS.PROTOCOL_UNITS` (+ `UNIT` lookup) | `oracle/protocol/export_protocol_units.sql` |

**`protocol_unit_administrator` is explicitly out of scope.** No table in
this repo's reference material (`reference/kc/ojb/ProtocolOJB.xml`, the
other OJB descriptors, or git history) documents a "unit administrator"
concept for Protocol. Rather than guess at an unverified Oracle
table/column, it was dropped from this branch's scope entirely — do not
add it without first finding and verifying a real Oracle source.

### Parent resolution

A child's own Oracle `PROTOCOL_ID` does not reliably identify its archive
parent (~14.83% mismatch rate measured for Personnel — see
[`docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md`](PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md)).
Two resolution strategies, both in
`etl/archive_etl/pipeline/protocol_parent_resolution.py`:

- **`protocol_person`** — `NUMBER_SEQUENCE`: the parent is resolved from
  `(protocol_number, sequence_number)`, not the row's own `PROTOCOL_ID`
  (kept only as `source_protocol_id`, for audit).
- **`protocol_unit`** — `OWNER_CHAIN`: `PROTOCOL_UNITS` has no direct
  `PROTOCOL_ID` column at all — only the physical FK `PROTOCOL_PERSON_ID`.
  A unit's parent is inherited from its owning person's already-resolved
  `protocol_id`. The unit's own `protocol_number`/`sequence_number` are
  audit evidence only, never an independent parent key.

`load_protocols.py`'s `resolve_person_parents`/`resolve_unit_parents` are
**tolerant, not fail-fast**: a row with a missing or ambiguous parent gets
`protocol_id = None` and is counted, rather than raising on the first bad
row. This lets both `--limit` and the full load report the *complete*
extent of a resolution problem. The full load then explicitly aborts
**before writing anything** if any count is nonzero — see "Full-load
behavior" below. (The database schema also backstops this at the
constraint level: `protocol_id` is `NOT NULL` with a foreign key on both
child tables, so an unresolved row could never be written even if the
application-level check were bypassed — but the explicit check is what
turns that into a clear, actionable count instead of a raw constraint
violation.)

### Known-unverified fields (verify against real Oracle data, don't block on them)

- **`is_pi`** — derived by a case-insensitive substring match
  (`"principal investigator"`) against `protocol_person_role_description`.
  The exact BU role code/description for PI has not been confirmed against
  live `PROTOCOL_PERSON_ROLES` data. Verify this the first time real
  `--limit` output is available (see `COMMENT ON COLUMN
  archive.protocol_person.is_pi` in the migration).
- **`email_address` / `email_source`** — `PROTOCOL_PERSONS.EMAIL_ADDRESS`
  is primary; `ROLODEX.EMAIL_ADDRESS` (via `ROLODEX_ID`) is only used when
  the primary is null. `email_source` records which one was used (`PERSON`
  / `ROLODEX`), or `NULL` if neither had a value — so a fallback is always
  visible in reconciliation, never silently hidden.

## Required environment variables

Same variables as every other Oracle-direct loader in this repo (see
[`etl/README.md`](../etl/README.md#environment-variables) for the full
table):

| Variable | Purpose |
| --- | --- |
| `ORACLE_USER`, `ORACLE_PASSWORD`, `ORACLE_DSN` | Oracle connection (`ORACLE_DSN` is an Easy Connect string, e.g. `host:1521/SERVICE_NAME`) |
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | PostgreSQL connection |
| `POSTGRES_SSLMODE` | Optional, defaults to `prefer`; use `require`/`verify-full` against BU's RDS |

Every script fails fast and clearly ("Missing required environment
variable(s): ...") if any are unset — no partial/garbled connection
attempts, and no secrets are ever printed or logged.

## BU VPN requirement

Oracle (`KCOEUS`) is reachable **only from a BU VPN-connected machine** —
the API, UI, and ETL run outside the VPN except for the Oracle read itself.
Connect to the BU VPN before exporting `ORACLE_*` or running anything that
touches Oracle (`protocol`, `protocol --limit N`, or `check protocol`). See
[`docs/runbooks/ORACLE.md`](runbooks/ORACLE.md) for the full operator
workflow shared by every Oracle-direct domain.

## `protocol --limit N` behavior

```bash
uv run python -m archive_etl protocol --limit 10
```

A genuinely bounded, coherent, **read-only** dry run:

1. Stops the Oracle fetch for protocol versions as soon as `N` rows are
   collected (`OracleDataSource.read_batches()` + `fetchmany()`), never
   reading the full table just to satisfy a small `--limit`.
2. Bounds the personnel/units reads by the sampled versions'
   `protocol_number` range (all three extraction queries are `ORDER BY
   protocol_number, ...`), then exact-matches each personnel/unit row's
   `(protocol_number, sequence_number)` against the sampled versions —
   never an independent `head(N)` per dataset, which could pull in
   personnel/units that don't actually belong to the sampled versions.
3. Units are further filtered to the `protocol_person_id` values present
   among the retained personnel.
4. Runs the same tolerant parent resolution as the full load on this
   sample and reports:
   - sampled protocol versions
   - matching personnel
   - matching units
   - unresolved parent relationships
   - ambiguous parent relationships
   - personnel `source_protocol_id` mismatches
   - missing email
   - missing role description
   - missing unit name
5. **Never opens a PostgreSQL connection, applies a migration, writes, or
   truncates anything.** `--limit` mode returns before
   `create_postgres_engine()` is ever called.

This was validated against live Oracle with `--limit 10` and reported
`sampled protocol versions: 10`, `matching personnel: 10`, `matching
units: 10`, and zero for every unresolved/ambiguous/missing metric.

## Full-load behavior

```bash
uv run python -m archive_etl protocol
```

1. Reads all three datasets fully from Oracle (`OracleDataSource.read()`),
   validates required columns/values, and rejects duplicate primary keys
   (`protocol_id`, `protocol_person_id`, `protocol_units_id`) outright.
2. Creates the PostgreSQL engine and **applies any pending migrations**
   (including `V034`, if not already applied — see "Migration V034"
   below; there is no separate `migrate` command, this is the only place
   migrations run for Protocol).
3. Writes a `STARTED` row to `archive.load_run` (`domain='PROTOCOL'`) in
   its own committed transaction, before any risky work — so a failure is
   always visible in the audit trail even if the load never completes.
4. Runs the same tolerant `resolve_person_parents`/`resolve_unit_parents`
   used by `--limit`, computing full unresolved/ambiguous/mismatch counts
   across every row (not just the first bad one found).
5. **If any unresolved or ambiguous parent relationship exists, the load
   aborts before truncating or writing anything** — the full counts are
   already recorded in `validation_report` for `mark_load_failed` to
   persist (see "Reconciliation metrics" below). No row is ever silently
   dropped; the load either succeeds completely with fully resolved
   parents, or fails completely with nothing written.
6. Otherwise, truncates and reloads `protocol_unit`, `protocol_person`,
   `protocol_version` (children before parents) and copies all three
   datasets, `verify_loaded_data`'s row-count and orphan-row checks, and
   `mark_load_complete` — **all inside one `engine.begin()` transaction**,
   so any failure at any point (a copy error, a row-count mismatch, an
   orphan row) rolls the entire write back and nothing partial is left
   committed.
7. On any failure after the `STARTED` row exists, `mark_load_failed`
   records the (redacted) error message and, when available, the
   reconciliation counts gathered so far.
8. Rerunning after a failure is safe with no manual cleanup — the same
   `TRUNCATE`-then-reload-in-one-transaction pattern used by every other
   active loader in this repo.

No names, emails, or credentials are ever logged or stored in
`error_message`/`validation_report` — only integer counts, and
`redact_error_message()` is applied to every error message as a
defense-in-depth backstop regardless.

## Local PostgreSQL testing

- **Unit/integration tests** (`etl/tests/test_protocol_loader.py`,
  `test_protocol_parent_resolution.py`, `test_protocol_loader_framework.py`)
  never require live Oracle or Postgres credentials — every collaborator
  (`OracleDataSource`, `create_postgres_engine`, the write-path helpers) is
  mocked. Run with:
  ```bash
  cd etl
  uv run pytest
  ```
- **Local Postgres against real data** (not yet exercised for Protocol on
  this branch — no migration or full load has been run anywhere): use
  `scripts/run-local.sh` from the repo root to start a local Homebrew
  Postgres and set `SPRING_PROFILES_ACTIVE=local`. There is no supported
  direct Mac-to-dev-RDS connection (`scripts/start-db-tunnel.sh` +
  `api/scripts/dev.sh` were removed 2026-08-13 — no EC2 bastion exists);
  for dev RDS, use an ECS Fargate one-off task instead (see `CLAUDE.md`'s
  "Authoritative data location" section). Protocol itself only needs a
  reachable Postgres and Oracle (over the BU VPN) — it does not depend on
  the API or UI being up at all.

## Migration V034

`database/migrations/V034__create_protocol_archive.sql` — the next
sequential migration after `V033`. Forward-only, like every migration in
this repo: historical migrations are never edited. Creates three tables,
scoped intentionally smaller than the old (removed) Protocol Archive
schema — no derived views, no unit-administrator table:

- **`archive.protocol_version`** — root. `protocol_id BIGINT PRIMARY KEY`
  (Oracle physical row identity) plus `UNIQUE (protocol_number,
  sequence_number, protocol_id)` (the business key, allowing multiple
  physical rows to share a business key only when `protocol_id` genuinely
  differs — no historical version is ever silently collapsed).
- **`archive.protocol_person`** — `protocol_id` is the *resolved*
  NUMBER_SEQUENCE parent FK (`NOT NULL REFERENCES
  archive.protocol_version`); `source_protocol_id` is the raw Oracle value,
  retained for audit only. `email_source` has a `CHECK` constraint limiting
  it to `PERSON` / `ROLODEX` / `NULL`.
- **`archive.protocol_unit`** — `protocol_person_id` is the verified
  physical OWNER_CHAIN parent FK; `protocol_id` is denormalized from the
  owning person for direct querying; `protocol_number`/`sequence_number`
  are audit evidence only (see the column comments in the migration for
  why they are not independent parent keys here).

Like every other domain, migrations are applied by the **Python ETL**, not
Spring Boot (`spring.flyway.enabled: false` — see the root `CLAUDE.md`).
Running `uv run python -m archive_etl protocol` is what applies `V034` (and
any other pending migration) — there is no separate migration command.

**No migration has been run against any database (local or BU RDS) as
part of this branch's work** — everything above was verified by static
review, unit tests with mocked Postgres/Oracle, and one live, read-only
`--limit 10` Oracle validation performed by the operator directly (not by
running a migration or a write).

## Reconciliation metrics

Every full load's outcome is recorded in `archive.load_run` — `domain =
'PROTOCOL'`, `source_system = 'KUALI'`, `source_file_name = 'Oracle KCOEUS
export'`. On success (`status = 'LOADED'`), `validation_report` (JSONB)
contains:

| Key | Meaning |
| --- | --- |
| `versions_read` / `versions_loaded` | Rows read from Oracle vs. copied into `protocol_version` |
| `personnel_read` / `personnel_loaded` | Same, for `protocol_person` |
| `units_read` / `units_loaded` | Same, for `protocol_unit` |
| `unresolved_parents` | Personnel + units whose parent could not be resolved at all (would have been `0` for the load to succeed) |
| `ambiguous_parents` | Personnel whose `(protocol_number, sequence_number)` matched more than one version (would have been `0` for the load to succeed) |
| `personnel_source_protocol_id_mismatches` | Personnel rows where Oracle's own `PROTOCOL_ID` differed from the resolved parent — expected to be nonzero given the ~14.83% measured mismatch rate; informational, not a failure condition |
| `missing_email` | Personnel with no email from either `PROTOCOL_PERSONS` or `ROLODEX` |
| `missing_role_description` | Personnel with no `protocol_person_role_description` (also means `is_pi` could not be evaluated for that row) |
| `missing_unit_name` | Units with no `UNIT.UNIT_NAME` match |

On failure (`status = 'FAILED'`), `error_message` holds the (redacted)
exception text, and `validation_report` holds whatever reconciliation
counts were computed before the failure — e.g. a resolution failure
records the full `unresolved_parents`/`ambiguous_parents` counts even
though nothing was ever written, so an operator can see *why* from
`load_run` alone, without grepping logs.

Inspect a load the same way as every other domain:

```bash
uv run python scripts/reconcile_load.py --domain PROTOCOL --limit 5
uv run python scripts/reconcile_load.py --latest
```

## Safe BU dev deployment procedure

Not performed as part of this branch — this is the procedure for someone
to follow later, on a BU VPN-connected machine, once this branch (or its
merge to `main`) is ready to run against a real target:

1. Connect to the BU VPN; refresh AWS credentials (`buaws`) if needed.
2. Establish the approved PostgreSQL connection — local Postgres
   (`./scripts/run-local.sh`) for a first dry run, or an ECS Fargate
   one-off task for a real dev RDS target (no local Mac-to-RDS tunnel is
   supported). See [`docs/runbooks/LOCAL_SETUP.md`](runbooks/LOCAL_SETUP.md).
3. Export `ORACLE_USER` / `ORACLE_PASSWORD` / `ORACLE_DSN` and the
   `POSTGRES_*` variables into the shell (never commit them).
4. `uv run python -m archive_etl check protocol` — confirms Oracle and
   Postgres connectivity, then runs an internal `--limit 5` smoke test.
   Prints no secrets.
5. `uv run python -m archive_etl protocol --limit 10` — bounded,
   read-only. Review the reported counts (see "`protocol --limit N`
   behavior" above) before proceeding. Expect `unresolved_parents`,
   `ambiguous_parents`, and `missing_*` to be `0` or a small, explainable
   number; investigate before continuing if not.
6. `uv run python -m archive_etl protocol` — applies `V034` (if not
   already applied) and runs the full load in one command. Review the
   resulting `archive.load_run` row (`reconcile_load.py --domain PROTOCOL
   --latest`) before considering the load complete.

## Rollback and troubleshooting

There is no destructive "rollback" command, by design, matching every
other domain in this repo — a failed load leaves the previous successful
data in place (the full load's write is one transaction; a failure rolls
it back automatically, and `archive.protocol_*` is simply never touched
until parent resolution has already succeeded). Recovery is: diagnose from
`archive.load_run` (`error_message` + `validation_report`), fix the
underlying problem, then rerun `uv run python -m archive_etl protocol`.

- **"Missing required environment variable(s): ..."** — set the listed
  variables; see "Required environment variables" above.
- **Oracle connection hangs or times out** — confirm the BU VPN is
  connected and `ORACLE_DSN` is reachable; `test_oracle_connection.py`
  fails with a clear driver error rather than hanging.
- **Load fails with "Protocol parent resolution failed: N unresolved and M
  ambiguous parent relationships"** — nothing was written. Check
  `archive.load_run.validation_report` for the exact counts, then
  cross-reference `docs/PROTOCOL_PARENT_RESOLUTION_ANALYSIS.md` for the
  known failure modes (a child's own `PROTOCOL_ID` disagreeing with its
  `(protocol_number, sequence_number)` parent) before assuming the
  extraction SQL itself is wrong.
- **Load fails with a row-count or orphan-row mismatch from
  `verify_loaded_data`** — the write transaction rolled back automatically;
  rerun after investigating (this should not happen if parent resolution
  reported zero unresolved/ambiguous rows beforehand).
- **A load's row counts don't add up** —
  `scripts/reconcile_load.py --load-id <id>` flags any load where
  `rows_read != rows_loaded + rows_rejected`.
- **`is_pi` looks wrong for a known PI** — see "Known-unverified fields"
  above; the role-description substring match may need correcting against
  real `PROTOCOL_PERSON_ROLES` data.

## Scope confirmation

- **No CSV source is supported.** Oracle is the only data source for
  Protocol — no `SOURCE_MODE`, no `--csv`/`--csv-dir` flag, no CSV export
  step, consistent with every other structured-data domain in this repo.
- **API and UI are not part of this branch.** `etl/load_protocols.py` and
  its supporting migration/Oracle SQL/tests are the entire scope so far —
  no `api/` controller/service/repository code and no `ui/` pages, client
  functions, or types exist for Protocol yet.
