# SEC-009 — the archive is read-only, and now regression-tested

Status: **implemented, test-only change.** Nothing deployed, no migration, no
grant, no enforcement change. Branch `test/sec-009-read-only-regression`,
built from `origin/main` = `7442a8e`.

SEC-009 asks for one thing the project did not have: a test that **fails** if a
future grant widens the API's access to archived research data. The privilege
position was already correct and had been read by a person
(`database/roles/archive_api_role.sql:107` grants `SELECT` and nothing else on
schema `archive`). What was missing was a sensor.

## What already existed, and why it was not enough

`ArchiveApiRoleIntegrationTest.archiveWritesFail()` asserts that `archive_api`
cannot `INSERT`, `UPDATE` or `DELETE` **`archive.person`** — one sampled table.

That catches a role script which forgot the archive schema altogether. It does
not catch the realistic regression, which is one widening grant on one table:
`GRANT INSERT ON archive.award_version` added to unblock a load, or an
`ALTER DEFAULT PRIVILEGES` broadened to `ALL`. A sampled test passes straight
through either. Every assertion added here is driven from `pg_catalog` across
the whole schema instead of from a list written by hand.

## What was added

Three suites, 17 tests.

### 1. `ArchiveReadOnlyPrivilegeIntegrationTest` — 12 tests, Testcontainers

Applies every committed migration, runs the shipped role script verbatim, then
acts **as** `archive_api` on a second connection. Shares the provisioning with
the existing suite through a new `ArchiveApiRoleHarness`.

| Assertion | Closes |
|---|---|
| No `archive` table grants INSERT/UPDATE/DELETE/TRUNCATE | the SEC-009 requirement itself, over every table rather than one |
| No `archive` column carries a column-level INSERT or UPDATE grant | `has_table_privilege` is blind to `GRANT UPDATE (status) ON …`; `has_column_privilege` is not |
| No `archive` view is writable | the blanket `GRANT SELECT ON ALL TABLES` includes views, and a view can be auto-updatable |
| No `archive` sequence grants USAGE or UPDATE | sequence USAGE is the privilege an INSERT into a serial column actually needs |
| Default privileges in schema `archive` grant exactly `SELECT` | decides what a table created **tomorrow** inherits |
| A table created after the role script is readable and not writable | the same claim as behaviour rather than as catalog state |
| Every named historical research table is readable, and refuses INSERT / UPDATE / DELETE / TRUNCATE for real | `award_version`, `proposal_version`, `subaward`, `negotiation`, the three attachment tables, `archived_attachment`, `irb_protocol`, `person` |
| Archive DDL and `COPY … FROM` are refused | the other two ways to change data |
| `archive_api` belongs to no role | a grant to a group role would be invisible to every sweep above |
| `archive_api` can neither read nor write `public.schema_migration` | applying a migration is how archive data legitimately changes; the API cannot |

**Every count is asserted against a floor** (≥ 80 tables, ≥ 400 columns, ≥ 80
migrations applied). Each of these queries returns "no offending rows" just as
happily when it has inspected nothing, so a schema that failed to materialise
would otherwise read as a clean pass.

### 2. `ArchiveWriteSurfaceTest` — 3 tests, no container

The role script justifies its grants with a claim about the source tree:
"there is NO INSERT, UPDATE or DELETE against any archive table anywhere in
`api/src/main/java`". These tests keep that claim true on the commit that would
break it:

- every schema-qualified write statement in the API targets the `authz` schema;
- no write names an unqualified relation (which would resolve by `search_path`);
- every JdbcClient `.update()` call site — the call that executes DML whatever
  the SQL text says — is in `adapter/out/persistence/authorization`.

Measured on `7442a8e`: **16 schema-qualified write statements — 8 INSERT, 8
UPDATE — and all 16 target `authz.*`** (`identity_link`, `access_grant`,
`admin_role_member`, `relink_approval`, `person_status`, `access_audit`), zero
unqualified writes, and **11 `.update()` call sites, all in the authorization
package**.

### 3. `ArchiveHttpWriteSurfaceTest` — 2 tests, no container

Asserts the complete non-GET HTTP surface by equality, so a new route fails the
test on arrival rather than at the next audit. Eleven handlers, in two groups:

- **eight** on `AdminAuthorizationController` — grants, identity links,
  suspensions, administrators. These write `authz`. *Managing who may read the
  archive is not a change to the archive*, which is requirement 6 of this work;
- **three** POSTs that read: `AwardAiController#summarize`,
  `AwardAiQuestionController#answer`, `AwardEvidenceSearchController#evidenceSearch`.
  POST because their request bodies do not belong in a query string.

## One real defect found while writing these tests

The first version of the HTTP sweep used Spring's
`ClassPathScanningCandidateComponentProvider`. **It silently returned 16 of the
20 controllers.** The scanner evaluates `@Conditional` metadata, and four
controllers are gated by `@ConditionalOnProperty` — `AwardAiController`,
`AwardAiQuestionController`, `AwardEvidenceSearchController` and
`ExplorerController`. With no gating properties set they were skipped, and
nothing reported them missing.

A security sweep that skips feature-flagged code is worse than none: a flag is
exactly where an unreviewed route would sit. The test now walks
`target/classes` and reads the annotation directly, which evaluates no
condition and skips nothing. The controller-count floor is what turned this
from an invisible gap into a failing test.

(Related: a text search for `@RestController` finds 22 files, because
`@RestControllerAdvice` contains that string. There are 20 controllers and 2
exception handlers.)

## Test results

`mvn test` in `api/`, Docker 29.4.0 available so all Testcontainers suites ran:

```
Tests run: 1690, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Reconciles with the baseline: `origin/main` is 1673 tests, plus the 17 added
here. (PR #107 reports 1696 for 1673 + its own 23.)

`ArchiveReadOnlyPrivilegeIntegrationTest` is registered in **both** CI test
lists in `.github/workflows/ci.yml` — excluded from the unit job, included in
the database job — which is how every other Testcontainers suite in this
repository is wired.

## Gaps this work found and did NOT close

1. **`@Transactional(readOnly = true)` is applied inconsistently.** It is on
   eight repositories, `DashboardController` and `IrbQueryService`, and absent
   from the other nineteen controllers and most services. With `JdbcClient` and
   no JPA this is a connection hint, and the database role is the control that
   actually refuses a write, so the risk is low — but the annotation does not
   currently mean what a reader would assume it means. Changing it is a
   behaviour change and was deliberately left out of a test-only change.

2. ~~**The privilege sweep proves the role, not the deployment.**~~
   **CLOSED 2026-11-09 — verified against live dev RDS.** See the section
   below.

3. **The ETL role is out of scope.** SEC-009 is about the API being read-only.
   The ETL writes the archive by design; nothing here constrains it.

## Live verification against dev RDS — 2026-11-09

The gap above is closed. The same catalog sweep was lifted out of
`ArchiveReadOnlyPrivilegeIntegrationTest` and run **read-only** against the
real dev database, through `scripts/start-db-tunnel.sh` as `archive_analyst`
(credential read from SSM into an environment variable, never printed; tunnel
and its ephemeral Fargate task torn down afterwards and confirmed gone).

```
archive tables inspected                                     115
archive_api INSERT / UPDATE / DELETE / TRUNCATE privileges     0
```

**There is no drift.** The committed role script and the deployed reality agree:
`archive_api` holds no write privilege on any `archive.*` table in dev.

Two other facts recorded by the same session, both relevant to this PR's
context but not to its assertions:

- **V087 is applied in dev**, verified from `pg_indexes` rather than from the
  ledger: `ux_access_grant_active_scope` exists on `authz.access_grant` with
  `WHERE revoked_at IS NULL`, keyed on `(institutional_identifier, grant_type,
  COALESCE(unit_number,''), COALESCE(io_value,''))`. `include_descendants` is
  deliberately not in the key. The catalog is the better evidence here anyway:
  `archive_analyst` cannot read `public.schema_migration` at all (permission
  denied — the same withholding that applies to `archive_api`), so the ledger
  was not consulted.
- **Grant, identity-link and administrator counts remain UNVERIFIED as of
  2026-11-09.** `archive_analyst` has no `USAGE` on schema `authz`, so this
  path cannot read them. The last known figures — 1 administrator, 1 identity
  link, 0 grants — are the 2026-11-03 measurement and were not reconfirmed.
  Re-measuring needs a path with `authz` access, which was deliberately not
  taken.

Sanity check that this was dev and not the disposable local copy: 267,386
`archive.award_version` rows across 40,926 distinct `award_number` values.

## What this change does not do

No migration. No grant. No enforcement change. No deployment. No modification
to `database/roles/archive_api_role.sql` — the script was already correct, and
rewriting it would have invalidated the evidence that it was. SEC-003
(PR #109) is untouched and remains separate.
