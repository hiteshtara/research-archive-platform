# DEV migration — V087 only

**Prepared 2026-11-02. The image is pushed and protected. Nothing is
registered and no migration has been applied.** This is the exact
configuration for review, and §6 stops for approval before step 4.

Source commit `e2a0d80` (Merge pull request #96) · migration
`V087__one_active_grant_per_scope.sql`, md5 `d3b5a822d7cd2d66980ff822ac1922f7`.

This is a companion to [`DEV_MIGRATION_RUNBOOK.md`](DEV_MIGRATION_RUNBOOK.md),
which covered V082–V086 and is now history: those five are applied. Every
mechanism there — the loader task family, the `--ecs --migrate-only` mode, the
Secrets Manager credential path, the `archive_analyst` read-only route, the
"never infer success from exit code 0" rule — applies unchanged. Only the
V087-specific facts are restated here.

---

## 1. What V087 does, and what it costs

One statement that matters:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS ux_access_grant_active_scope
    ON authz.access_grant (
        institutional_identifier, grant_type,
        COALESCE(unit_number, ''), COALESCE(io_value, ''))
    WHERE revoked_at IS NULL;
```

preceded by a `DO` block that refuses the index if the table already violates
it, naming the offending scopes.

It is additive: it creates one index, alters no column and touches no row.
`archive` is not referenced at all.

**The one behavioural cost, stated plainly.** The predicate is
`revoked_at IS NULL`, not "active right now", because a partial-index predicate
must be immutable and `now()` is not available. So a grant that has expired
naturally but was never revoked still occupies its scope: re-granting it
requires revoking the expired row first. That costs one audited revocation.
No grant in DEV sets `expires_at` and the provisioning CLI cannot set one, so
the case does not arise today. The migration's own header records the
alternative (an `EXCLUDE` constraint over `tstzrange` with `btree_gist`) and
why it was not chosen.

`include_descendants` is deliberately **not** in the key, so `modifyAccess`
can still toggle it. One visible consequence: the provisioning CLI can no
longer add a descendants-including grant *alongside* a non-descendants grant
for the same unit — it must modify the existing one. That is the intent (the
wider grant subsumes the narrower) and PR #95 makes the CLI say so instead of
throwing.

## 2. Target, verified 2026-11-02

| | |
|---|---|
| AWS account | `770203350335` — **confirm `AWS_PROFILE=bu-nprd` first; the default profile on this Mac resolves to a personal account** |
| RDS instance | `research-archive-platform-dev-postgres`, PostgreSQL **17.9**, available |
| Database | `research_archive` |
| Migration role | `archive_admin`, resolved at runtime from `POSTGRES_SECRET_ID` |
| Cluster | `research-archive-platform-dev-etl`, 0 tasks running |
| `authz` schema | **present, 8 tables** — V082–V086 applied |
| `ux_access_grant_active_scope` | **absent** — V087 not applied |
| API service | `research-archive-platform-dev-api:84`, unchanged by this work |
| Subaward nightly | pinned to `…loader:237`, `ENABLED`, `cron(0 2 * * ? *)` |

## 3. The image - built, pushed, protected and verified

| | |
|---|---|
| Source commit | **`e2a0d80075735f97da47c614efabbed3b58d752d`** (`e2a0d80`, the #96 merge) - built from a clean detached worktree, 0 uncommitted files |
| Repository | `770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader` |
| **Index digest - pin to this** | **`sha256:2497b4bb65bb0aa351685061ab115ac194b3984d596a7ed26c194e6a87d3f9f6`** |
| amd64 child manifest | `sha256:5907e048bc22fcf9dbbce6a7563e6291e7a713c3ecd98f65cbba71fb073e438e` |
| Tags | `20261102T171833Z-e2a0d80-amd64` and `protected-v087-e2a0d80` - both on the one digest |
| Size | ~121 MB |

```bash
docker buildx build --platform linux/amd64 --load \
  -f etl/Dockerfile.loader -t rap-v087:<tag> .
scripts/verify-image-platform.sh rap-v087:<tag>          # OK ... linux/amd64
```

`--platform linux/amd64` is not optional. On 2026-10-28 an arm64-only image was
pushed and a task-definition revision burned against it; the failure appeared
only when ECS tried to start the task (`CannotPullContainerError: ... does not
contain descriptor matching platform 'linux/amd64'`).

### Verified, each from the registry copy rather than the local build

1. **Platform, from the ECR manifest.** The pushed object is an
   `application/vnd.oci.image.index.v1+json` - the same shape that failed in
   October - so the index was parsed and confirmed to carry a real
   `linux/amd64` **image** descriptor, alongside an `unknown/unknown`
   attestation-manifest, which is normal and is not a platform.
2. **Round trip.** Pulled back **by digest**, re-checked with
   `verify-image-platform.sh` -> `linux/amd64`, and V087's md5 inside that
   pulled container is `d3b5a822d7cd2d66980ff822ac1922f7`, matching git; 85
   migration files in both.
3. **The runner can execute it** - not inferred, run. The ECR copy's own
   packaged runner applied V001 to V087 against a throwaway PostgreSQL 17
   container, recorded `(87, 'V087__one_active_grant_per_scope.sql')` and
   created the index with the expected definition. Rewound to V086, a second
   run applied **only V087**, logging the expected V081 gap warning.
4. **It refuses when it should.** Given two identical unrevoked UNIT grants,
   the same ECR copy refused - `Cannot add ux_access_grant_active_scope:
   duplicate unrevoked grants already exist: DUPU/UNIT/1250000000/- (2 rows)`
   - left the database at V086 with **no** index created, and **left both
   duplicate rows in place**. Nothing is deleted to make the migration fit.

(3) is the check that catches the V076 trap of 2026-08-14, where an image
lacking the migration files discovered nothing, applied nothing and exited 0.

### Protected against ECR lifecycle expiry

The repository has a two-rule lifecycle policy, and rule 1 is the protection
mechanism already in use:

| Priority | Selection | Action |
|---|---|---|
| 1 | tagged, prefix `protected-` | expire after 36,500 days - effectively never |
| 2 | any | expire when image count > 20 |

The image therefore carries `protected-v087-e2a0d80` as well as its build tag, which is why
**ECR's own lifecycle preview does not list it**:

```
status COMPLETE   would expire: 3
  20260921T201343Z-2bd4160-UNCOMMITTED-v078   (rule 2)
  <untagged>                                  (rule 2)
  <untagged>                                  (rule 2)
```

All three are a throwaway 2026-09-21 `UNCOMMITTED-v078` build. The V087 image
is absent from that list, and so is the nightly's image
(`protected-20260817T011034Z-4a82821`).

> **Two adjacent hazards, neither introduced here, both worth fixing separately.**
>
> - **Revision 254's image is not `protected-` tagged.** It sits inside the
>   newest-20 window today, but it is the image the already registered
>   migration task definition points at, and it will eventually be swept.
>   Tagging it `protected-` is a one-command fix.
> - **Nightly revision 237 references its image by mutable tag**
>   (`...-loader:20260817T011034Z-4a82821`), not by digest, and this repository
>   is `MUTABLE`. The image *object* is protected by its `protected-` tag, so
>   lifecycle cannot remove it - but a repointed tag would change what the
>   nightly pulls. Out of scope here: the nightly must not be touched.

### No credentials embedded

Checked before the push, not inferred from the scan:

- **Image config**: environment holds only `PATH`, `LANG`, `GPG_KEY`,
  `PYTHON_VERSION`, `PYTHON_SHA256`, `PYTHONDONTWRITEBYTECODE`,
  `PYTHONUNBUFFERED`. No credential, and nothing credential-shaped in the
  build history.
- **Filesystem**: no `.env`, `credentials`, `.pgpass`, `id_rsa`, `*.p12` or
  `*.keystore`. The only `*.pem` files are the Debian CA trust bundle under
  `/etc/ssl/certs` - public root certificates.
- **Our code**: no hardcoded AWS key and no `POSTGRES_PASSWORD=` or
  `ORACLE_PASSWORD=` literal. Only secret *identifiers* appear
  (`research-archive-platform/dev/postgres-4k6Ngz`,
  `research-archive-platform/dev/oracle`), never values - the loader resolves
  them at runtime, inside the container.
- **AWS-key-shaped strings** exist in exactly three vendored files
  (`boto3/examples/cloudfront.rst`, `botocore/data/iam/.../examples-1.json`,
  `botocore/data/sts/.../examples-1.json`) and every value ends in `EXAMPLE`
  (`AKIAIOSFODNN7EXAMPLE`, `AKIA111111111EXAMPLE`, ...). These are AWS's own
  documentation placeholders, re-confirmed here rather than inherited from the
  2026-10-28 review.

> **The ECR scan does not answer this question.** `scanOnPush` is enabled and
> the scan completed, but ECR basic scanning finds **CVEs, not secrets**. Its
> result is reported below on its own terms.

### CVE posture, stated plainly

The scan of the amd64 child manifest completed: **6 HIGH, 15 MEDIUM, 4 LOW**.
All six HIGH are base-image OS packages - `sqlite3` (CVE-2026-119373,
-119374, -119379), `glibc` (CVE-2026-19499, -5435) and `zlib`
(CVE-2026-85091). None is in our code or our Python dependencies.

That is **more** than the currently registered image (3 HIGH / 9 MEDIUM /
3 LOW), because `etl/Dockerfile.loader` starts `FROM python:3.12-slim`
unpinned, so a rebuild picks up a newer base carrying newly disclosed CVEs.
For this task - one DDL statement, about 30 seconds, private subnet, no
inbound path, no untrusted input - the practical exposure is minimal. The
durable fix is to pin the base image by digest and rebuild deliberately; that
is its own change, not a migration prerequisite.

## 4. Task definition

`ops/migration/dev-loader-migrate-only-v087.taskdef.json`

The image is pinned **by digest, not by tag** - this repository is `MUTABLE`,
so a tag can be repointed later and a digest cannot:

```
.../research-archive-platform-dev-loader@sha256:2497b4bb65bb0aa351685061ab115ac194b3984d596a7ed26c194e6a87d3f9f6
```

**It differs from live revision 254 in two fields, both deliberate.** Verified
by diffing against revision 254 read back from AWS: family, networkMode,
requiresCompatibilities, cpu, memory, both role ARNs, all six environment
variables and the log configuration are identical.

| Field | Why it differs |
|---|---|
| `image` | the V087 digest above, instead of the `cead763` image that predates the migration |
| `runtimePlatform` | **added**: `{"cpuArchitecture": "X86_64", "operatingSystemFamily": "LINUX"}` |

`runtimePlatform` was deliberately omitted from the earlier draft to keep a
one-field diff. It is added now because the brief asks for an image built
explicitly for linux/amd64, and leaving it out makes that requirement live only
in a build script. Three reasons it is the right call:

- Terraform's own `aws_ecs_task_definition.loader`
  (`terraform/modules/ecs/main.tf`) **already declares exactly this block**, so
  adding it moves the one-off definition *towards* the managed shape, not away.
- Live revision 254 reports `runtimePlatform: null`, i.e. it relies on the
  Fargate default. The default happens to be X86_64, so this changes no
  behaviour - it only stops the architecture being implicit.
- It makes ECS reject a wrong-architecture image at task start with a clear
  platform error, instead of the opaque pull failure seen on 2026-10-28.

```bash
aws ecs register-task-definition \
  --cli-input-json file://ops/migration/dev-loader-migrate-only-v087.taskdef.json
# -> note the new revision number (expected: 255)
```

> Registering a new revision in this family is safe. The nightly Subaward
> schedule is pinned to revision **237** by explicit ARN, the `run-*-loader.sh`
> scripts register their own revisions, and **nothing consumes "latest"**.
> Re-verify 237 afterwards anyway - it is one command.

## 5. Preflight

```bash
export AWS_PROFILE=bu-nprd
aws sts get-caller-identity --query Account --output text              # 770203350335
aws ecs list-tasks --cluster research-archive-platform-dev-etl \
  --desired-status RUNNING --query 'length(taskArns)' --output text    # 0
aws scheduler get-schedule --name research-archive-platform-dev-subaward-nightly \
  --query 'Target.EcsParameters.TaskDefinitionArn' --output text       # …loader:237
aws ecs describe-services --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' --output text                   # …api:84
aws ecr describe-images --repository-name research-archive-platform-dev-loader \
  --image-ids imageDigest=sha256:<pushed digest> \
  --query 'imageDetails[0].imageManifestMediaType' --output text
scripts/verify-image-platform.sh <ecr ref pulled back from ECR>
```

### The duplicate-grant preflight — RUN, as the migration role, and it PASSES

V087 refuses to index over data that already violates it. The check was run
against real DEV data on **2026-11-02** as `archive_admin` — the migration
role — from inside a one-off loader task, so the credential was resolved by
the loader from `POSTGRES_SECRET_ID` and never left the container:

```sql
SELECT institutional_identifier, grant_type,
       COALESCE(unit_number,'-'), COALESCE(io_value,'-'), count(*)
  FROM authz.access_grant
 WHERE revoked_at IS NULL
 GROUP BY institutional_identifier, grant_type,
          COALESCE(unit_number,''), COALESCE(io_value,''),
          unit_number, io_value          -- see the note below
HAVING count(*) > 1;
-- ZERO rows
```

> The trailing `unit_number, io_value` in that `GROUP BY` look redundant and
> are not: without them PostgreSQL rejects the statement, because the select
> list projects `COALESCE(unit_number,'-')` while the grouping key is
> `COALESCE(unit_number,'')`. V087's own `DO` block groups by both for exactly
> this reason. Dropping them produces
> `column "access_grant.unit_number" must appear in the GROUP BY clause`.

**And the migration's own fail-closed guard was executed verbatim** — the
`DO $$ … $$` block lifted byte-for-byte out of V087 and run against live DEV
data. It **passed**: V087 would not refuse on current data. Nothing was
created; the block only `SELECT`s.

| Check, as `archive_admin` on DEV, 2026-11-02 | Result |
|---|---|
| `max(version)` in `public.schema_migration` | **86** (84 rows — gaps at 73 and 81) |
| `V086__relink_approval.sql` applied | 2026-10-28 16:56:24 UTC |
| version 87 present | **no** |
| `ux_access_grant_active_scope` | **absent**; only `access_grant_pkey` and `ix_access_grant_grantee` |
| duplicate unrevoked scopes | **zero rows** |
| V087's own `DO` block, run verbatim | **passed** |
| `authz.access_grant` | **0 rows**, 0 unrevoked |
| first administrator | `ARP-FK7QY195-QW5HV7G0`, member id 3, **ACTIVE**, `granted_by=BOOTSTRAP`, 2026-10-29 17:21:22 UTC, `revoked_at` NULL |
| identity links | **1** — the administrator's own, `ADMIN_VERIFIED`/`ACTIVE`, no Kuali person id |
| `authz.person_status` / `authz.relink_approval` | 0 / 0 |
| audit trail | exactly **2** rows: `ADMIN_ROLE_BOOTSTRAPPED` then `IDENTITY_LINKED`, both at the bootstrap timestamp |

No unexpected grant, identity, suspension or relink exists.

> **One honest caveat about that diagnostic.** The script set
> `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` and
> `default_transaction_read_only = on`, but a transaction was already open on
> the pooled connection, so `current_setting('transaction_read_only')` read
> `off` for the queries that followed — **the database-level guard did not
> engage.** Safety rested on the script containing only `SELECT`s plus V087's
> `SELECT`-only `DO` block, which was checked statically beforehand, and on
> the after-state: 0 grant rows, the same 2 audit rows from 2026-10-29, and
> archive counts unchanged. To make the guard actually bind next time, pass it
> at connect time (`connect_args={"options": "-c default_transaction_read_only=on"}`)
> rather than as a statement after the connection is in use.

### Archive baseline — verified as the migration role 2026-11-02, must be identical afterwards

| table | rows |
|---|---|
| `archive.award_version` | **267,386** |
| `archive.proposal_version` | **119,788** |
| `archive.subaward` | **93,735** |
| `archive.negotiation` | **10,775** |
| tables in `archive` | **115** |
| tables in `authz` | **8** |

V087 does not reference the `archive` schema, so any change here means
something else ran.

## 6. Execution procedure - ten steps, in order

**STOP HERE FOR REVIEW.** Steps 1-3 are read-only and may be run freely.
Step 4 is the migration and requires explicit approval.

### 1. Verify the AWS account and environment independently

```bash
export AWS_PROFILE=bu-nprd
aws sts get-caller-identity --query Account --output text      # 770203350335
```

> **Check the profile, not just the output.** With no `AWS_PROFILE` the default
> credentials on this Mac resolve to a **personal** account (`589744711110`).
> "Independently" also means: the account is read from STS, never from a flag
> or a variable the operator typed - the same principle the grant and bootstrap
> CLIs enforce with `RuntimeVerification`.

### 2. Confirm no concurrent migration or ETL operation

```bash
aws ecs list-tasks --cluster research-archive-platform-dev-etl \
  --desired-status RUNNING --query 'length(taskArns)' --output text   # 0
aws scheduler get-schedule --name research-archive-platform-dev-subaward-nightly \
  --query '{State:State,Schedule:ScheduleExpression,Task:Target.EcsParameters.TaskDefinitionArn}'
# ENABLED, cron(0 2 * * ? *), ...loader:237
```

The nightly runs at 02:00 America/New_York. **Do not start the migration inside
an hour of that.** It applies no migrations itself (its image predates V082),
but a concurrent loader writing Subaward data while DDL takes an
ACCESS EXCLUSIVE lock on `authz.access_grant` is needless risk - and the two
would contend for nothing, since the nightly never touches `authz`.

### 3. Capture before-state evidence

Run the read-only preflight of §5 and **save the output**. It is the baseline
step 8 compares against. Minimum to record: `max(version)` = 86, zero duplicate
unrevoked scopes, index absent, administrator ACTIVE, grant count 0, and the
four archive counts.

### 4. Execute exactly one migration task

```bash
aws ecs run-task \
  --cluster research-archive-platform-dev-etl \
  --launch-type FARGATE \
  --task-definition research-archive-platform-dev-loader:255 \
  --started-by "v087-migrate-only" \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed],securityGroups=[sg-0817befcc5b4affc9],assignPublicIp=DISABLED}' \
  --overrides '{"containerOverrides":[{"name":"loader","command":["python3","load_awards_from_csv.py","--ecs","--migrate-only"]}]}'
```

Use the revision registered in §4 - **not** 254, whose image predates V087,
and never 253, which is the dead arm64 revision. Subnets, security group and
`assignPublicIp=DISABLED` are copied from the live nightly schedule, the
known-working configuration for this cluster.

`--migrate-only` is valid only with `--ecs`. It resolves AWS identity and the
PostgreSQL secret, applies pending migrations, validates the schema and exits.
**Oracle is never touched**: the Oracle secret is skipped entirely in this mode.

Wait for `lastStatus=STOPPED` and record `containers[0].exitCode`.

Expected log output - CloudWatch `/ecs/research-archive-platform-dev-loader`,
stream prefix `loader`:

```
AWS identity resolved via ECS task role: account=770203350335
PostgreSQL reachable
Gap detected in migration sequence on disk - missing version(s): V081   <-- WARNING, expected
Applying migration V087: one active grant per scope
Migration V087 completed
Migrations applied
Migration and schema validation complete
```

Exactly **one** migration applies. Anything more means the database was not
where §5 said it was - stop and re-read `schema_migration`.

> **V081 is still reserved** by the unmerged `feat/award-amount-dates-v081`
> branch and touches `archive.award_*` only - zero `authz` references.
> `apply_migrations` logs the gap and continues; only V073 is whitelisted, so
> V081 reports on every run. **Do not abort on it.**

**Exactly one task.** If it fails, read step 5 before re-running - a re-run is
safe only because applied versions are skipped, and you need to know which
state it stopped in.

### 5. Verify migration V087 recorded

```sql
-- as the migration role, inside a loader task
SELECT version, description, file_name, installed_at
  FROM public.schema_migration ORDER BY version DESC LIMIT 3;
-- expect version 87, file_name V087__one_active_grant_per_scope.sql
```

> **Do not infer success from exit code 0.** An image lacking the migration
> files discovers nothing, applies nothing and exits 0 - the V076 incident of
> 2026-08-14. Read the version list back. Expect `max(version)` to go 86 -> 87
> and the row count 84 -> 85.

### 6. Verify `ux_access_grant_active_scope` exists

```sql
SELECT indexdef FROM pg_indexes
 WHERE schemaname='authz' AND indexname='ux_access_grant_active_scope';
```

Expect exactly:

```
CREATE UNIQUE INDEX ux_access_grant_active_scope ON authz.access_grant
  USING btree (institutional_identifier, grant_type,
               COALESCE(unit_number, ''::character varying),
               COALESCE(io_value, ''::text))
  WHERE (revoked_at IS NULL)
```

Check the whole definition, not just the name: the `COALESCE` wrappers are what
make the constraint bind for CENTRAL and CONTACT_DERIVATION grants, where both
scope columns are NULL and NULLs would otherwise be distinct.

`archive_analyst` can confirm the index through `pg_catalog` without any
privilege on `authz` - expect **three** `%access_grant%` indexes where there
were two.

### 7. Confirm administrator and grant counts unchanged

```sql
SELECT admin_member_id, institutional_identifier, status FROM authz.admin_role_member;
-- 3 | ARP-FK7QY195-QW5HV7G0 | ACTIVE
SELECT count(*) FROM authz.access_grant;                     -- 0
SELECT count(*) FROM authz.identity_link WHERE status='ACTIVE';  -- 1
SELECT audit_id, action FROM authz.access_audit ORDER BY audit_id;
-- exactly 2: ADMIN_ROLE_BOOTSTRAPPED, IDENTITY_LINKED
```

V087 creates an index. It must not add, remove or revoke a grant, an
administrator, an identity link or an audit row. **Any change here means
something other than the migration ran.**

### 8. Confirm archive data counts unchanged

Compare against §5's baseline: `award_version` 267,386 · `proposal_version`
119,788 · `subaward` 93,735 · `negotiation` 10,775 · 115 `archive` tables ·
8 `authz` tables. V087 does not reference the `archive` schema at all, so a
difference means the nightly ran concurrently or something else wrote.

### 9. Confirm API revision 84 remains healthy

```bash
aws ecs describe-services --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].{taskDef:taskDefinition,running:runningCount,desired:desiredCount}'
# ...api:84, running 1, desired 1
```

Then one request against the live API to confirm it still serves.

**Do not restart or redeploy the API.** It is not expected to change: revision
84 contains no code that reads the new index, record-level enforcement is off,
and an unused unique index is invisible to every query the API makes. If the
API *were* unhealthy after this, the migration is not the likely cause - check
it independently before touching anything.

### 10. Confirm Terraform has no NEW drift

```bash
cd terraform/environments/dev
terraform plan            # READ-ONLY. Never apply from this procedure.
```

**Expect drift, and do not "fix" it.** Two discrepancies pre-date this work and
an untargeted `apply` is actively dangerous:

- **`var.loader_image_tag` defaults to `"latest"`** and `terraform.tfvars`
  (untracked) does not set it - so Terraform's desired loader image is
  `<repo>:latest`, **a tag that does not exist in this repository**. The repo's
  own `db_tunnel.tf` comment records this. An apply would register a loader
  revision pointing at a nonexistent tag.
- **Three `cloudshell_analyst` resources exist in dev state but not in config.**
  An untargeted apply would **destroy the approved read-only database access
  path**.

What "no new drift" means here is narrow and checkable: V087 is a database
migration applied by the ETL runner, which Terraform does not manage, and
registering a task-definition **revision** out of band adds an immutable
revision that Terraform's state does not track and does not remove. So neither
step 4 nor §4's registration should change the plan output at all. **Compare
the plan to the same plan taken before step 4** - identical output is the pass
condition, not an empty plan.

## 7. The read-only route, for anyone without the migration role

`archive_analyst` holds **no** privilege on `authz` and none on
`public.schema_migration`. That is deliberate and **must not be broadened**.
It can still confirm the index through `pg_catalog`, which needs no table
privilege - enough to answer "did V087 land" without being able to read a
single grant.

```sql
SELECT current_database(), current_user;   -- research_archive | archive_analyst
SHOW transaction_read_only;                -- on

-- pg_catalog, NOT information_schema: information_schema lists only objects the
-- caller holds a privilege on, so it would report nothing and look like a
-- failed migration.
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='authz' AND c.relkind='i' AND c.relname LIKE '%access_grant%'
 ORDER BY 1;
-- expect THREE: access_grant_pkey, ix_access_grant_grantee,
--               ux_access_grant_active_scope

-- the archive schema is untouched - compare to the §5 baseline
SELECT 'award_version' t, count(*) FROM archive.award_version
UNION ALL SELECT 'proposal_version', count(*) FROM archive.proposal_version
UNION ALL SELECT 'subaward',         count(*) FROM archive.subaward
UNION ALL SELECT 'negotiation',      count(*) FROM archive.negotiation;
```

> If a read-only diagnostic needs the guard to actually bind, set it at
> **connect** time - `connect_args={"options": "-c default_transaction_read_only=on"}`
> - and assert `current_setting('transaction_read_only') = 'on'` as its first
> check. Issuing `SET default_transaction_read_only` as a statement after the
> connection is in use does nothing: a 2026-11-02 diagnostic did exactly that
> and read `off` while believing itself read-only.

### Nothing else moved

```bash
aws ecs describe-services --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' --output text   # still ...api:84
aws scheduler get-schedule --name research-archive-platform-dev-subaward-nightly \
  --query 'Target.EcsParameters.TaskDefinitionArn' --output text  # still ...loader:237
aws ecs list-tasks --cluster research-archive-platform-dev-dbtunnel \
  --desired-status RUNNING --query 'length(taskArns)' --output text   # 0
aws ecr describe-images --repository-name research-archive-platform-dev-loader \
  --query 'length(imageDetails)' --output text
```

## 8. Failure recovery and rollback

### The governing rule

**Grant history is never deleted to make a migration succeed.** Not a grant
row, not an audit row. `authz.access_audit` is append-only and enforced by a
trigger (`tr_access_audit_append_only`, V082) that raises on UPDATE and DELETE,
so the audit trail cannot be edited even deliberately. Grant rows carry the
only record of who held what access and when it was withdrawn; a duplicate is
resolved by **revoking** the redundant row, which is itself audited, never by
removing it.

This is not aspirational - V087 is built that way and it was tested. Given two
identical unrevoked grants, the migration refused, named them, created no
index, and **left both rows in place** (`duplicate rows still present: 2`).

### Failure table

| Failure | What it means | Response |
|---|---|---|
| Task fails to start (`TaskFailedToStart`) | image pull, subnet or role problem. Nothing ran; no database change | Check the ECR manifest platform first - that was the 2026-10-28 cause. The digest in §4 has been verified `linux/amd64` from the registry, and `runtimePlatform` now makes a mismatch an explicit error. Fix and retry |
| `Cannot add ux_access_grant_active_scope: duplicate unrevoked grants already exist: …` | V087's own preflight fired and named the offending scopes. **Nothing was created and nothing was deleted** | **Stop.** For each named scope, `revokeAccess` the redundant grant - an audited action - leaving one live. **Never** `DELETE` the row. Then re-run; the migration is idempotent and applied versions are skipped |
| Exit 0 but `schema_migration` has no version 87 | the image lacks the file - the V076 trap of 2026-08-14 | **The run did nothing.** Confirm the registered revision uses the §3 digest, not 254's. Do not proceed |
| More than V087 applied | the database was not at V086 | **Stop and reconcile** before anything else. Read `schema_migration` for the true state |
| `archive` counts differ from §5 | should be impossible - V087 does not reference `archive` | **Stop and investigate.** Most likely the nightly ran concurrently (step 2) |
| Administrator, grant, identity-link or audit count changed | something other than the migration ran | **Stop.** Do not attempt repair writes; capture evidence first |
| Task times out or is killed mid-statement | `CREATE UNIQUE INDEX` is a single transaction | It rolled back. `schema_migration` stays at 86, no index exists, no row changed. Re-run |

### Rollback

**V087 is cheaply reversible, unlike V082-V086.** It creates one index and no
table, so undoing it loses no row and no audit entry:

```sql
-- reviewed, deliberate, audited out of band
DROP INDEX authz.ux_access_grant_active_scope;
DELETE FROM public.schema_migration WHERE version = 87;   -- only if re-applying
```

Three things to understand before using it:

1. **It re-opens the race PR #96 closed.** Without the index, two concurrent
   identical grant requests can both commit - proven, four requests once
   produced four active grants - and a later revocation, which takes one
   `grant_id`, silently leaves the others in force while the audit trail reads
   as though access was removed. So this is a decision, not a routine step.
2. **The `schema_migration` row survives a bare `DROP INDEX`**, so a re-run
   would skip version 87 and silently do nothing. Delete that row only if you
   intend to re-apply.
3. **It is almost never needed.** While no grant exists and record-level
   enforcement is off, the index is invisible to every query the API makes: a
   successful V087 is a no-op for every user of the archive. Leaving it costs
   nothing.

Rolling the API back is independent and unaffected - revision **84** is already
what is running, contains no code that reads the index, and must not be
restarted as part of this work.

## 9. What has and has not happened

**Done:** the loader image is built from `e2a0d80`, pushed to ECR, tagged
`protected-v087-e2a0d80` so lifecycle cannot sweep it, and verified from the
registry (platform, content, that its runner applies V087, and that it refuses
duplicates without deleting them). The DEV preflight of §5 has been run as the
migration role and passes.

**Not done, and each needs approval:**

- registering the task definition in §4 (expected revision 255)
- step 4 of §6 - applying V087
- deploying the grant-provisioning CLI (#95) or the bootstrap fix (#97)

**Explicitly not touched:** the nightly Subaward schedule (still
`...loader:237`, `ENABLED`), API revision **84** (not restarted, not
redeployed), Cognito, Shibboleth, production, enforcement (still off), and
grants (still zero - none created, none revoked).

State changes made while preparing this document, all reversible or ephemeral:
three one-off read-only diagnostic tasks on the ETL cluster (`run-task` on
revision 254 with SELECT-only `containerOverrides`; the first exited 1 on a
malformed `GROUP BY` **in the diagnostic itself** and wrote nothing), one
ephemeral read-only SSM tunnel task, local Docker containers, and the ECR push
above. All tasks stopped and verified gone: 0 running on both the ETL and
dbtunnel clusters.
