# DEV migration — V087 only

**Prepared 2026-11-02. Nothing pushed, nothing registered, nothing executed.**
This is the exact configuration for review.

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

## 3. The image — built and verified, NOT pushed

Built from a **clean detached worktree at `e2a0d80`**, never the live working
directory:

```bash
docker buildx build --platform linux/amd64 --load \
  -f etl/Dockerfile.loader -t research-archive-loader-local:<tag> .
scripts/verify-image-platform.sh research-archive-loader-local:<tag>
# OK  …  linux/amd64
```

`--platform linux/amd64` is not optional. On 2026-10-28 an arm64-only image
was pushed and a task definition burned against it; the failure appeared only
when ECS tried to start the task (`CannotPullContainerError`). The gate script
exists because of that, and the ECR manifest must be re-checked after the push,
not only the local image.

Three things were verified about the built image, not assumed:

1. **Platform** — `linux/amd64` per `docker image inspect`.
2. **Content** — `database/migrations/V087__one_active_grant_per_scope.sql`
   is present inside the container, 85 migration files total, and the file's
   md5 inside the image equals the md5 of the merged file
   (`d3b5a822d7cd2d66980ff822ac1922f7`).
3. **It actually applies.** The image's *own* packaged runner was pointed at a
   throwaway PostgreSQL 17 container: it applied V001→V087, recorded
   `(87, 'V087__one_active_grant_per_scope.sql')` in `public.schema_migration`
   and created `ux_access_grant_active_scope`. Then, modelling DEV exactly —
   the same database rewound to V086 — a second run applied **only V087**.

   This is the check that catches the V076 trap of 2026-08-14, where an image
   lacking the migration files discovered nothing, applied nothing and exited 0.

> **Not done, and gated on approval: the ECR push.** The task definition
> below therefore carries a placeholder digest. Push, then read the digest
> **back from ECR** and substitute it — both repositories are MUTABLE, so a
> tag can be repointed and a digest cannot.

## 4. Task definition

`ops/migration/dev-loader-migrate-only-v087.taskdef.json`

**It differs from live revision 254 in exactly one field: the image.** Verified
by diffing the template against revision 254 read back from AWS — family,
networkMode, requiresCompatibilities, cpu, memory, both role ARNs, all six
environment variables and the log configuration are identical.

```bash
aws ecs register-task-definition \
  --cli-input-json file://ops/migration/dev-loader-migrate-only-v087.taskdef.json
# -> note the new revision number (expected: 255)
```

> Registering a new revision in this family is safe: the nightly Subaward
> schedule is pinned to revision **237** by explicit ARN, and the
> `run-*-loader.sh` scripts register their own. **Nothing consumes "latest".**
> Re-verify 237 afterwards anyway — it is one command.

> Optional hardening, deliberately **not** applied here so that the
> one-field-differs property holds: the definition has no `runtimePlatform`,
> so it relies on the Fargate default (`LINUX`/`X86_64`). Declaring it
> explicitly would make the amd64 requirement visible in the definition
> rather than only in the gate script. Propose it as its own change.

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

### The duplicate-grant preflight

V087 refuses to index over data that already violates it. Run the check first
anyway, so a refusal is expected rather than discovered:

```sql
SELECT institutional_identifier, grant_type,
       COALESCE(unit_number,'-') AS unit, COALESCE(io_value,'-') AS io, count(*)
  FROM authz.access_grant
 WHERE revoked_at IS NULL
 GROUP BY institutional_identifier, grant_type,
          COALESCE(unit_number,''), COALESCE(io_value,'')
HAVING count(*) > 1;
-- expect ZERO rows
```

**This query needs the migration role, and that is a real gap in the
read-only route.** `archive_analyst` is granted nothing on `authz` by design,
so through the SSM tunnel it returns `permission denied for schema authz` —
and `public.schema_migration` is likewise denied. Two things *are* readable
from `pg_catalog`, which needs no table privilege, and both were checked on
2026-11-02:

- `authz.access_grant` has **0 live tuples** (`pg_stat_all_tables`:
  `n_tup_ins=1, n_tup_del=0, n_live_tup=0`, `relpages=0` — one insert that did
  not survive, consistent with a rolled-back DEV experiment), and its only
  indexes are `access_grant_pkey` and `ix_access_grant_grantee`.
- `ux_access_grant_active_scope` does not exist.

So a collision is not possible on today's data. Close the gap properly in one
of two ways before the run:

1. run the query inside a one-off loader task as the migration role — the
   authoritative answer; or
2. accept V087's embedded `DO` block as the guard. It was exercised against a
   seeded violation: it raised, named all three offending scopes, ignored a
   revoked duplicate pair, and left `schema_migration` at 86 with no index
   created. It fails closed.

### Archive baseline — recorded 2026-11-02, must be identical afterwards

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

## 6. The run

```bash
aws ecs run-task \
  --cluster research-archive-platform-dev-etl \
  --launch-type FARGATE \
  --task-definition research-archive-platform-dev-loader:255 \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed],securityGroups=[sg-0817befcc5b4affc9],assignPublicIp=DISABLED}' \
  --overrides '{"containerOverrides":[{"name":"loader","command":["python3","load_awards_from_csv.py","--ecs","--migrate-only"]}]}'
```

Subnets, security group and `assignPublicIp=DISABLED` come from the live
nightly schedule — the known-working configuration for this cluster.
`--migrate-only` is valid only with `--ecs`; it resolves AWS identity and the
PostgreSQL secret, applies pending migrations, validates the schema and exits.
**Oracle is never touched**: the Oracle secret is skipped entirely for
`--migrate-only`.

### Expected log output

```
PostgreSQL reachable
Gap detected in migration sequence on disk - missing version(s): V081   <-- WARNING, expected
Applying migration V087: one active grant per scope
Migration V087 completed
Migrations applied
Migration and schema validation complete
```

Exactly **one** migration applies. Anything more means the database was not
where this runbook says it was — stop and re-read `schema_migration`.

> **V081 is still reserved** by the unmerged `feat/award-amount-dates-v081`
> branch and touches `archive.award_*` only. `apply_migrations` logs the gap
> and continues; only V073 is whitelisted. **Do not abort on it.**

Logs: CloudWatch `/ecs/research-archive-platform-dev-loader`, stream prefix
`loader`.

## 7. Verification

### As the migration role, inside a loader task — authoritative

```sql
SELECT version, description, file_name, installed_at
  FROM public.schema_migration ORDER BY version DESC LIMIT 3;
-- expect version 87, file_name V087__one_active_grant_per_scope.sql

SELECT indexdef FROM pg_indexes
 WHERE schemaname='authz' AND indexname='ux_access_grant_active_scope';
-- expect the COALESCE expressions and WHERE (revoked_at IS NULL)
```

**Do not infer success from exit code 0.** Read the version list back.

### Through the SSM tunnel as `archive_analyst` — read-only, no authz privilege needed

```sql
SELECT current_database(), current_user;   -- research_archive | archive_analyst
SHOW transaction_read_only;                -- on

-- pg_catalog, NOT information_schema: archive_analyst holds no privilege on
-- authz, so information_schema would report nothing and look like a failure.
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='authz' AND c.relkind='i' AND c.relname LIKE '%access_grant%'
 ORDER BY 1;
-- expect THREE: access_grant_pkey, ix_access_grant_grantee,
--               ux_access_grant_active_scope

-- and the archive schema is untouched - compare to the §5 baseline
SELECT 'award_version' t, count(*) FROM archive.award_version
UNION ALL SELECT 'proposal_version', count(*) FROM archive.proposal_version
UNION ALL SELECT 'subaward',         count(*) FROM archive.subaward
UNION ALL SELECT 'negotiation',      count(*) FROM archive.negotiation;
```

### Nothing else moved

```bash
aws ecs describe-services --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' --output text   # still …api:84
aws scheduler get-schedule --name research-archive-platform-dev-subaward-nightly \
  --query 'Target.EcsParameters.TaskDefinitionArn' --output text  # still …loader:237
aws ecs list-tasks --cluster research-archive-platform-dev-dbtunnel \
  --desired-status RUNNING --query 'length(taskArns)' --output text   # 0
```

## 8. Failure and rollback

| Failure | What it means | Response |
|---|---|---|
| Task fails to start | image pull, subnet or role problem | nothing ran, no database change. Check the ECR manifest platform first — that was the 2026-10-28 cause. Fix and retry |
| `Cannot add ux_access_grant_active_scope: duplicate unrevoked grants already exist: …` | V087's own preflight fired and named the scopes | **stop.** **Revoke** the redundant grants — do not delete them; the audit trail depends on the rows surviving. Then re-run |
| Exit 0 but `schema_migration` has no version 87 | the image lacks the file — the V076 trap | **the run did nothing.** Check the digest actually registered; do not proceed |
| More than V087 applied | the database was not at V086 | **stop and reconcile** before anything else |
| `archive` counts differ from §5 | should be impossible | **stop and investigate.** V087 does not reference `archive` |

### Rollback

**There is no down migration, and none is needed as a forward fix.** Unlike
V082–V086, this one *is* cheaply reversible, because it creates exactly one
index and no table:

```sql
DROP INDEX authz.ux_access_grant_active_scope;   -- reviewed, deliberate, audited
```

That is safe at any time: dropping it loses no row and no audit entry, and
returns the table to exactly its pre-V087 state. It also re-opens the race
that PR #96 closed, so it is a deliberate decision and not a routine step —
and `public.schema_migration` would still record version 87, so a re-run would
skip it. Re-applying means deleting that row as well, which is a reviewed
change in its own right.

What rollback is *not* needed for: the index is invisible to every user while
no grant exists, and the running API (revision 84) has record-level
authorization **off**. A successful V087 is a no-op for every user of the
archive.

## 9. Out of scope

Not included and not done: the ECR push, registering the revision, applying
the migration, deploying the API, bootstrapping an administrator, creating a
grant, enabling enforcement, or touching the nightly schedule. The only
state changes made while preparing this document were an ephemeral read-only
SSM tunnel task (stopped and verified gone) and local Docker containers
(removed).
