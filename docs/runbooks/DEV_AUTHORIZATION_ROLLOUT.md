# DEV authorization rollout — prepared plan

Prepared 2026-10-28 against `origin/main` = **`cead763`**.
**Nothing has been applied, pushed, deployed, bootstrapped or enabled.**
Both images were built **locally only**; neither has been pushed to ECR.

Companion to [`AUTHORIZATION_ACTIVATION.md`](AUTHORIZATION_ACTIVATION.md),
which holds the model and the stage gates. This holds the concrete artefacts.

---

## 0. What is actually running right now

| | |
|---|---|
| API service | `research-archive-platform-dev-api:81`, 1/1 running, deployed 2026-10-22 |
| API image | `…-dev-api:20261006T182647Z-b10e4e3` — built **2026-10-06** |
| Contains | **none** of #56, #62, #64, #66, #71, #74, #76, #77, #80, #82 |
| Enforcement | **OFF** — the task definition carries no `APP_AUTHORIZATION_*` variable |
| Loader task definition | revision **252**, image `…-dev-loader:20260923T163946Z-443e507` — built **2026-09-23** |
| `authz` schema on dev RDS | **does not exist** — verified read-only through the SSM tunnel, 2026-10-28 |

---

## 1. Two images must be rebuilt, and in this order

```
rebuild LOADER  ──►  apply V082–V086  ──►  rebuild API  ──►  deploy  ──►  bootstrap
```

The loader comes first because **the current loader image physically does not
contain the migrations**, so `--migrate-only` against it would find nothing to
apply and report success. That is the shape of the V076 incident of
2026-08-14.

### Built and verified locally

| | Loader | API |
|---|---|---|
| Tag | `20261028T141920Z-cead763` | `20261028T142100Z-cead763` |
| Local image id | `sha256:1dcb39ed5ddefcdda63c3c8087e57ca6a6dc1d59e0dd7be5e08c9bb1bac93011` | `sha256:5c9bc0d4636464c29a77954ecb3f4fbcb1f4312ac979da030730f1313baf0895` |
| Source commit | `cead763957b4b5ef929ffa7849d0af40ffe301c2` | same |
| Dockerfile | `etl/Dockerfile.loader` | `api/Dockerfile` |

### ✅ Pushed to ECR 2026-10-28, with owner approval

| | Loader | API |
|---|---|---|
| ECR digest | `sha256:1dcb39ed5ddefcdda63c3c8087e57ca6a6dc1d59e0dd7be5e08c9bb1bac93011` | `sha256:5c9bc0d4636464c29a77954ecb3f4fbcb1f4312ac979da030730f1313baf0895` |
| Pushed | 11:15:12 EDT | 11:15:47 EDT |
| Size | 115 MiB | 180 MiB |

The ECR manifest digest equals the local image id in both cases. Neither tag
existed beforehand, so **nothing was overwritten** — both repositories are
MUTABLE, so that was checked rather than assumed. The loader was pulled back
**by digest** and re-verified to still contain all five migrations after the
round trip.

**No task definition was registered and no service was updated.** Publishing
an image changes nothing that runs.

#### Loader image — verified contents

- All **five** migrations present: `V082`, `V083`, `V084`, `V085`, `V086`.
- **84** migration files in the image, **84** in source — nothing dropped.
- `--migrate-only` is present on the loader entry point.

#### API image — verified contents

Every merged class present: `AdminAuthorizationController`,
`ArchiveAdminBootstrapCli`, `ArchivePrincipalId`,
`ArchiveAdminBootstrapCommand`, `JdbcRelinkApprovalRepository`,
`RelinkApprovalRequiredException`, `DashboardService`.

**Enforcement defaults OFF in the artefact**, not merely in source: neither
`application.yml` nor `application-local.yml` inside the jar contains any
`authorization` or `enforcement` key, so `AuthorizationProperties.enforcementEnabled`
falls to the Java primitive default `false`.

### Nightly Subaward sync is unaffected

Its EventBridge schedule `research-archive-platform-dev-subaward-nightly`
(`cron(0 2 * * ? *)`, ENABLED) is pinned to task definition **revision 237**,
not to `:latest` and not to 252. Registering a new revision cannot move it.
**Do not repoint it.**

---

## 2. Migration plan for dev RDS

### Current version

`archive_analyst` **cannot read `public.schema_migration`** (permission denied
— least privilege, working as intended), so the exact version cannot be read
over the analyst tunnel. What is established:

- `authz` does not exist ⇒ the database is **below V082**.
- The loader will report the true version when it runs; `apply_migrations`
  logs every version it skips and applies.

**Read the actual version from the loader's first `--migrate-only` output**,
not from an assumption.

### Dependency order, and what each does

| | Creates | Touches existing data |
|---|---|---|
| `V082` | schema `authz`; `identity_link`, `person_status`, `access_grant`, `access_audit` + append-only trigger | **none** |
| `V083` | `kim_principal`, `principal_crosswalk` | **none** |
| `V084` | `admin_role_member` + 2 indexes | **none** |
| `V085` | unique index `ux_identity_link_active_identity` | **none** |
| `V086` | `relink_approval` + 2 indexes | **none** |

They must run in order: `V083`/`V084` reference `authz` from `V082`; `V085`
indexes `identity_link` from `V082`; `V086` is standalone but belongs after.

### ✅ No `archive` data is modified

Verified by inspection of all five files: **no `ALTER` of an existing table,
no `UPDATE`, `DELETE`, `INSERT`, `TRUNCATE` or backfill.** Every table and
index is `CREATE … IF NOT EXISTS`. The only `DROP` in the set is
`DROP TRIGGER IF EXISTS tr_access_audit_append_only` immediately before the
same migration recreates it, on a table that same migration creates — re-run
safety, not a change to anything existing. **Nothing in the `archive` schema
is referenced at all.**

### ⚠️ V085 precheck — run this BEFORE applying

V085 creates a unique index and **will fail if the data already violates it**.
That is the correct outcome, but it stops the apply, so check first:

```sql
-- Expect zero rows. Any row means two Cognito identities already share one
-- archive identity, and both inherit its grants - resolve deliberately.
SELECT institutional_identifier, COUNT(*)
  FROM authz.identity_link WHERE status = 'ACTIVE'
 GROUP BY 1 HAVING COUNT(*) > 1;
```

On dev this is vacuously safe: `authz.identity_link` will not exist until
V082 runs in the same pass, and it is created empty.

### V086 validation — after applying

```sql
-- the two-person rule must be a DATABASE constraint, not only Java
SELECT conname FROM pg_constraint
 WHERE conrelid = 'authz.relink_approval'::regclass ORDER BY 1;
-- expect, among others: ck_relink_approval_two_people
```

### Expected end state

```sql
SELECT table_name FROM information_schema.tables
 WHERE table_schema='authz' ORDER BY 1;
-- access_audit, access_grant, admin_role_member, identity_link,
-- kim_principal, person_status, principal_crosswalk, relink_approval   (8)

SELECT indexname FROM pg_indexes WHERE schemaname='authz' ORDER BY 1;
-- includes ux_identity_link_active_cognito, ux_identity_link_active_identity,
--          ux_admin_role_member_active, ux_principal_crosswalk_active_value,
--          ux_principal_crosswalk_active_principal, ux_relink_approval_live
```

### Privileges required

The migration runs as the **master/owner credential** the loader resolves from
`POSTGRES_SECRET_ID` — it must `CREATE SCHEMA`, `CREATE TABLE`, `CREATE INDEX`,
`CREATE FUNCTION` and `CREATE TRIGGER`. `archive_analyst` **cannot and must
not** do this; it is read-only by design and could not even read
`schema_migration`.

### Execution method

ECS Fargate one-off `run-task` on cluster `research-archive-platform-dev-etl`,
using the **new** loader task definition with `--migrate-only`. Not from a Mac,
not over the analyst tunnel.

> **Expect one warning, and do not mistake it for an error:**
> `Gap detected in migration sequence on disk - missing version(s): V081`.
> V081 is reserved for separate Award amount-dates work on another branch.
> `apply_migrations` logs and continues; only V073 is whitelisted as an
> intentional gap, so V081 reports. Harmless.

### The V081 gap — investigated 2026-10-28

| Question | Answer |
|---|---|
| **Why absent?** | Reserved by `feat/award-amount-dates-v081` (commit `6ae107d`, WIP, **unmerged**), which adds `V081__add_award_amount_dates_and_tnm_status.sql` |
| **Does it interact with V082–V086?** | **No.** It touches `archive.award_*` only — **zero** references to `authz`, `identity_link` or `access_grant` |
| **Warning or error?** | **Warning.** `apply_migrations` calls `logger.warning` and continues; there is no raise. Only V073 is whitelisted in `INTENTIONALLY_SUPERSEDED_MIGRATION_VERSIONS`, so V081 reports on every run |
| **Correct order?** | **Yes.** `discover_migrations` sorts by integer version (`migrations.sort(key=lambda item: item[0])`), so V082→V083→V084→V085→V086 regardless of filesystem order |
| **Recorded transactionally?** | **Yes.** The DDL and the `INSERT INTO public.schema_migration` run on one connection and commit together; any exception rolls both back and re-raises. PostgreSQL has transactional DDL, so a migration cannot be applied without being recorded, or recorded without being applied |

#### ⚠️ Can a partial failure be reported as success?

**A failure, no.** Each migration is its own transaction; an exception rolls
it back and propagates, and the task exits non-zero. If V082–V084 succeed and
V085 fails, the first three stay committed, the run fails loudly, and
re-running skips what is already applied.

**A no-op, yes — and that is the real risk.** If the image does not contain
the migration files, `discover_migrations` finds nothing, the loop does
nothing, and the run reports success having applied nothing. That is exactly
the V076 incident of 2026-08-14, and exactly why the loader image had to be
rebuilt first. **Always read the applied-version list back; never infer
success from a zero exit code.**

#### Verifying applied versions with the execution role

`public.schema_migration` is readable only by the **master credential the
loader resolves from `POSTGRES_SECRET_ID`** — `archive_analyst` gets
*permission denied*, confirmed on 2026-10-28.

```sql
-- as the migration role, inside the loader task
SELECT version, description, file_name, applied_at
  FROM public.schema_migration ORDER BY version;
-- expect a contiguous run ending at 86, with 73 and 81 absent
```

From the analyst tunnel the *existence* of the objects can still be confirmed
via `pg_catalog`, which is privilege-independent — see below.

### Post-migration read-only verification

Through the analyst tunnel, as `archive_analyst`:

```sql
SELECT current_database(), current_user;        -- research_archive | archive_analyst
SHOW transaction_read_only;                      -- on

-- pg_catalog, NOT information_schema. information_schema.tables lists only
-- tables the CURRENT user holds a privilege on, and archive_analyst is
-- granted nothing on authz - so it would report 0 and look like a failed
-- migration. pg_class is visible regardless of privilege.
SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'authz' AND c.relkind = 'r';  -- expect 8

SELECT count(*) FROM archive.award_version;      -- unchanged from before
```

Record the `archive` row counts **before** the migration and compare. They
must be identical; nothing in V082–V086 touches that schema.

### Failure handling and rollback

| Failure | Response |
|---|---|
| V085 precheck finds duplicates | **stop.** Resolve the duplicate links first; do not force the index |
| A migration fails mid-run | each migration runs in its own transaction and is recorded only on success, so the database stops at the last good version. Fix and re-run `--migrate-only`; applied versions are skipped |
| Everything applied, then regret | **there is no down migration, and none should be written.** The tables are empty and read by nothing while enforcement is off. Leaving them costs nothing; removing them is a separate reviewed forward migration, never an ad-hoc `DROP SCHEMA` — that would discard the append-only audit trail |

---

## 3. API deployment and rollback

### Deploy

`ops/deploy-api.sh`, run against a **clean detached worktree at `cead763`**,
never the live working directory. It builds, pushes to ECR, registers a new
task definition revision and updates the service.

### Rollback — one command, no data involved

```bash
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:81 --force-new-deployment
```

Revision **81** is the current, known-good definition —
image `20261006T182647Z-b10e4e3`, cpu 512, memory 1024, execution role
`…-dev-api-execution-role`, task role `…-dev-api-task-role`. Rolling back the
API does **not** require rolling back the migrations: the old image simply
never reads the `authz` tables.

### Expected behaviour after deploying, before enabling enforcement

| Surface | Expected |
|---|---|
| Every existing endpoint | **unchanged** — three independent mechanisms keep authorization inert: the property default, a service short-circuit, and `PERMIT_ALL` port defaults |
| `/api/v1/admin/**` | **404** on the whole tree |
| Dashboard | IRB figures **present** (D9 only excludes them when enforcement is on) |
| Users | see no difference whatsoever |

Proven by `AuthorizationInertWhenDisabledTest`, which starts the full
application context with all of this code present and asserts every
administrative path 404s and an ordinary request is not blocked. That test is
green on `cead763`.

---

## 4. Administrator readiness

Approved initial administrator: **`mukadder@bu.edu`**, DEV only, no
research-data grants. Verified Cognito identity is recorded in
[`AUTHORIZATION_ACTIVATION.md`](AUTHORIZATION_ACTIVATION.md) §2.

### 4.1 Dry run — writes nothing, reserves nothing

```
SPRING_PROFILES_ACTIVE=bootstrap-admin \
java -jar app.jar --spring.main.web-application-type=none \
  --aws-account-id=770203350335 \
  --environment=dev \
  --cognito-user-pool-id=us-east-1_VJ4ekQ27c \
  --cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c \
  --cognito-subject=b438a418-d0a1-7071-2169-023a374a4038 \
  --mint-archive-identifier \
  --login-name=BUShibboleth_mukadder@bu.edu \
  --operator='<the person running this>' \
  --reason='Approved by the project owner 2026-10-28, dev only'
```

No `--confirm`, so it validates and stops. **No identifier is minted or
reserved**, and none is displayed — a dry-run value could otherwise be
mistaken for the real one. Exit status 0 means every check passed.

### 4.2 Confirmed run

The same command plus `--confirm`. It mints the `ARP-…`, writes the
membership and the identity link, and prints the identifier prefixed
**`RECORD THIS VALUE`** — it is the key every future grant uses.

### 4.3 Audit verification

```sql
SELECT actor, action, occurred_at, detail FROM authz.access_audit ORDER BY audit_id;
-- ADMIN_ROLE_BOOTSTRAPPED (actor BOOTSTRAP, detail names the operator)
-- IDENTITY_LINKED
```

`authz.access_audit` is append-only by database trigger, so these rows are
permanent.

### 4.4 Membership verification

```sql
SELECT institutional_identifier, status, granted_by, granted_at, reason
  FROM authz.admin_role_member;     -- exactly 1 ACTIVE, granted_by='BOOTSTRAP'
SELECT method, status FROM authz.identity_link;  -- ADMIN_VERIFIED | ACTIVE
SELECT COUNT(*) FROM authz.access_grant;         -- 0
```

**Zero grants is correct, not a fault.** Holding ARCHIVE_ADMIN confers no
archive data access; the administrator will see no records until a grant is
issued, and the UI says so.

### 4.5 Grant/revoke acceptance tests

Run in dev **with enforcement temporarily on**, against this administrator.
The full matrix is in `AUTHORIZATION_ACTIVATION.md` §4 — A1 to A12. The two
that matter most: an administrator with no grant opens a record (**404**), and
a revoked grant denies on the **very next request** with the JWT still valid.

### 4.6 Second administrator

Appointed **through the API by the first**, so it is audited normally —
never a second bootstrap, which refuses once an administrator exists.

> ⚠️ **Strongly recommended before relying on the first.** With one
> administrator, nobody can appoint a replacement if that account is lost: the
> role cannot be self-granted and the bootstrap refuses to replay. Recovery
> would mean direct database intervention.
>
> ⛔ **Blocked in practice for one case:** the relink approval API and UI are
> **not built** (D-ID-6). A two-person relink cannot be performed through the
> product today. It is only reachable once an identity holds grants *and*
> needs re-linking, so it does not block activation — but it is not done.

---

## 5. Approvals still required

| Owner | Approval |
|---|---|
| **Project owner** | push the two built images to ECR |
| **Project owner** | apply V082–V086 to dev |
| **Project owner** | deploy the new API revision |
| **Project owner** | run the bootstrap with `--confirm` |
| **Project owner** | enable enforcement in dev |
| **Project owner** | **SEC-008** — audit scope: administrative actions only, or record views too. **The one open policy question** |
| Optional | D-ID-4(a) KRIM backfill; D-ID-6 relink approval API/UI |
