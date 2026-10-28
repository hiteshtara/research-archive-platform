# DEV authorization migration — V082–V086

**Prepared 2026-10-28. Nothing registered, nothing executed.** This is the
exact configuration for review.

Source commit `cead763` · loader image pushed to ECR 2026-10-28.

---

## 0. ⚠️ Revision 253 is DEAD — use 254

The first attempt (`task …/db8bfb8f65084fe98b09578cefb87aa0`, 2026-10-28)
**failed before the container started**:

```
stopCode: TaskFailedToStart
CannotPullContainerError: image Manifest does not contain descriptor
                          matching platform 'linux/amd64'
```

The images had been built on an Apple Silicon Mac without `--platform`, so
they were **arm64-only**; Fargate here is **amd64**. Nothing ran: no exit
code, no CloudWatch stream, no database change — `authz` still absent and
every `archive` count identical to the baseline.

Everything verified before that push was **content** — migrations present,
classes present, enforcement default, no secrets. Content was never the
problem. **The one property that determines whether the image can run at all
was not checked.** It is now: `scripts/verify-image-platform.sh`, which must
pass before any push.

| | Dead (arm64) | Correct (amd64) |
|---|---|---|
| Loader task definition | `:253` | **`:254`** |
| Loader digest | `sha256:1dcb39ed5dde…` | **`sha256:983bab23dc84…`** |
| API image | `20261028T142100Z-cead763` | **`20261028T163514Z-cead763-amd64`** |

**Revision 253 must never be run.** It is left ACTIVE rather than deregistered
so the failure stays visible in the task-definition history.

## 1. Target, verified

| | |
|---|---|
| AWS account | `770203350335` |
| RDS instance | `research-archive-platform-dev-postgres`, PostgreSQL **17.9**, available |
| Database | `research_archive` |
| Migration role | **`archive_admin`** — the RDS master user, resolved at runtime from `POSTGRES_SECRET_ID` |
| Cluster | `research-archive-platform-dev-etl` |
| Current `authz` schema | **absent** — verified read-only through the SSM tunnel, 2026-10-28 |

> The password is never read, logged or passed as an argument. `--ecs` makes
> the loader resolve it from Secrets Manager inside the running container.
> The task role's `…-loader-task-secrets` policy grants
> `secretsmanager:GetSecretValue` on exactly the two secret ARNs and nothing
> else.

---

## 2. Task definition — register this, nothing else

`ops/migration/dev-loader-migrate-only.taskdef.json`

**It differs from the live revision 252 in exactly one field: the image.**
cpu, memory, network mode, both IAM roles, all six environment variables and
the log configuration are byte-identical.

The image is pinned **by digest, not by tag**:

```
…/research-archive-platform-dev-loader@sha256:983bab23dc84…717974
```

Both ECR repositories are **MUTABLE**, so a tag could be repointed later and
a digest cannot. This is the digest that was pushed, pulled back and
re-verified to contain all five migrations.

```bash
aws ecs register-task-definition \
  --cli-input-json file://ops/migration/dev-loader-migrate-only.taskdef.json
# -> note the new revision number
```

> ⚠️ This registers a new revision in the same family. That is safe:
> the nightly Subaward schedule is pinned to **revision 237** and the
> `run-*-loader.sh` scripts register their own revisions. **Nothing consumes
> "latest".** Re-verify 237 afterwards anyway — it is one command.

---

## 3. Preflight — run every one before the task

```bash
export AWS_PROFILE=bu-nprd
# 1. right account
aws sts get-caller-identity --query Account --output text        # 770203350335
# 2. nothing already running on the ETL cluster
aws ecs list-tasks --cluster research-archive-platform-dev-etl \
  --desired-status RUNNING --query 'length(taskArns)' --output text   # 0
# 3. the nightly is pinned, and not about to fire (it runs 02:00 UTC)
aws scheduler get-schedule --name research-archive-platform-dev-subaward-nightly \
  --query 'Target.EcsParameters.TaskDefinitionArn' --output text      # …loader:237
# 4. the API is untouched and stays that way
aws ecs describe-services --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' --output text                  # …api:81
# 5. the digest is really in ECR
aws ecr describe-images --repository-name research-archive-platform-dev-loader \
  --image-ids imageDigest=sha256:983bab23dc84000af49ac972d3eb6e6704f5998c18ea58c48786077a7f717974 \
  --query 'imageDetails[0].imageTags' --output text   # 20261028T141920Z-cead763
```

### Through the SSM tunnel, as `archive_analyst` (read-only)

```sql
-- 6. V085 precheck. Expect ZERO rows - V085 creates a unique index and will
--    FAIL if the data already violates it. Vacuously safe here: identity_link
--    does not exist yet, so this errors with "relation does not exist", which
--    is the expected answer before V082 runs.
SELECT institutional_identifier, COUNT(*)
  FROM authz.identity_link WHERE status = 'ACTIVE'
 GROUP BY 1 HAVING COUNT(*) > 1;

-- 7. Baseline the archive schema. Record these numbers; they MUST be
--    identical afterwards.
SELECT 'award_version' t, count(*) FROM archive.award_version
UNION ALL SELECT 'proposal_version', count(*) FROM archive.proposal_version
UNION ALL SELECT 'subaward',         count(*) FROM archive.subaward
UNION ALL SELECT 'negotiation',      count(*) FROM archive.negotiation
UNION ALL SELECT 'archive tables',
       (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
         WHERE n.nspname='archive' AND c.relkind='r');
```

---

## 4. The run

```bash
aws ecs run-task \
  --cluster research-archive-platform-dev-etl \
  --launch-type FARGATE \
  --task-definition research-archive-platform-dev-loader:254 \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed],securityGroups=[sg-0817befcc5b4affc9],assignPublicIp=DISABLED}' \
  --overrides '{"containerOverrides":[{"name":"loader","command":["python3","load_awards_from_csv.py","--ecs","--migrate-only"]}]}'
```

Subnets, security group and `assignPublicIp=DISABLED` are copied from the
**live nightly schedule**, which is the known-working configuration for this
cluster. `python3 … --ecs` matches the command shape the nightly uses.

### Why this command is correct

- **`--migrate-only`** calls `apply_migrations` **unconditionally** — it is
  not gated on `--ecs`, which was checked in source, because `--ecs` *does*
  suppress migrations on some other branches.
- **`--ecs`** means credentials come from Secrets Manager only, never a
  plaintext environment variable.
- **Oracle is not touched.** The Oracle secret is skipped entirely for
  `--migrate-only`, so the task needs no Oracle connectivity.
- After applying, it validates `award_version` and `award_transmission_child`
  exist, then exits.

### Expected log output

```
PostgreSQL reachable
Gap detected in migration sequence on disk - missing version(s): V081   <-- WARNING, expected
Migration V082 ... Applying
Migration V083 ... Applying
Migration V084 ... Applying
Migration V085 ... Applying
Migration V086 ... Applying
Migrations applied
Migration and schema validation complete
```

> **V081 is reserved** by the unmerged `feat/award-amount-dates-v081` branch
> and touches `archive.award_*` only — zero `authz` references, so it is
> irrelevant to V082–V086. `apply_migrations` logs and continues; only V073
> is whitelisted, so V081 reports every run. **Do not abort on it.**

Order is guaranteed: `discover_migrations` sorts by integer version, so
V082→V083→V084→V085→V086 regardless of filesystem order.

Logs: CloudWatch `/ecs/research-archive-platform-dev-loader`, stream prefix
`loader`.

---

## 5. Post-migration verification

### As the migration role (inside a loader task) — the authoritative check

```sql
SELECT version, description, file_name, applied_at
  FROM public.schema_migration ORDER BY version;
-- expect a run ending at 86, with 73 and 81 absent
```

> **Do not infer success from exit code 0.** A loader image that lacks the
> migration files discovers nothing, applies nothing and exits 0 — that is
> the V076 incident of 2026-08-14. **Read the version list back.**

### Through the tunnel as `archive_analyst` (read-only)

```sql
SELECT current_database(), current_user;   -- research_archive | archive_analyst
SHOW transaction_read_only;                 -- on

-- pg_catalog, NOT information_schema: that view lists only tables the current
-- user holds a privilege on, and archive_analyst is granted nothing on authz,
-- so it would report 0 and look like a failed migration.
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='authz' AND c.relkind='r' ORDER BY 1;
-- access_audit, access_grant, admin_role_member, identity_link,
-- kim_principal, person_status, principal_crosswalk, relink_approval   (8)

SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='authz' AND c.relkind='i' ORDER BY 1;
-- must include ux_identity_link_active_cognito, ux_identity_link_active_identity,
--              ux_admin_role_member_active, ux_relink_approval_live

-- V086 validation: the two-person rule must be a DATABASE constraint
SELECT conname FROM pg_constraint
 WHERE conrelid = 'authz.relink_approval'::regclass ORDER BY 1;
-- must include ck_relink_approval_two_people

-- and the archive schema is untouched - compare to the §3 baseline
SELECT 'award_version' t, count(*) FROM archive.award_version
UNION ALL SELECT 'proposal_version', count(*) FROM archive.proposal_version
UNION ALL SELECT 'subaward',         count(*) FROM archive.subaward
UNION ALL SELECT 'negotiation',      count(*) FROM archive.negotiation;
```

### Nothing else moved

```bash
aws ecs describe-services --cluster research-archive-platform-dev-api \
  --services research-archive-platform-dev-api \
  --query 'services[0].taskDefinition' --output text     # still …api:81
aws scheduler get-schedule --name research-archive-platform-dev-subaward-nightly \
  --query 'Target.EcsParameters.TaskDefinitionArn' --output text  # still …loader:237
```

---

## 6. Failure and rollback

| Failure | What it means | Response |
|---|---|---|
| Task fails to start | image pull, subnet, or role problem | nothing ran; no database change. Fix and retry |
| `relation "authz.identity_link" does not exist` in the **preflight** | expected before V082 | proceed |
| A migration raises | that one migration rolled back; earlier ones stayed committed | **stop.** Read `schema_migration` for the true stopping point, fix the cause, re-run `--migrate-only` — applied versions are skipped |
| **V085 fails on a duplicate index key** | two active links already share an archive identity | **stop.** Resolve the duplicates deliberately; **never** force the index |
| Exit 0 but `schema_migration` unchanged | the image lacks the files — the V076 trap | **the run did nothing.** Check the image digest; do not proceed |
| `archive` counts differ from the baseline | should be impossible | **stop and investigate.** V082–V086 do not reference the `archive` schema at all |

### Rollback

**There is no down migration, and none should be written.**

The eight tables are created **empty**, and nothing reads them while
enforcement is off — the running API (revision 81) does not even contain the
code that would. So a successful migration is, by itself, a no-op for every
user. Leaving it costs nothing.

If the schema must truly go, that is a **separate reviewed forward
migration**, never an ad-hoc `DROP SCHEMA authz CASCADE` — that would
discard the append-only audit trail along with it.

Nothing about this changes the API. Rolling the API back is independent and
unaffected: revision 81 is already what is running.

---

## 7. Explicitly out of scope for this task

Registering revision 253 is the **only** state change proposed here, and even
that is not done. Not included: applying the migration, deploying the API,
bootstrapping an administrator, creating a grant, enabling enforcement, or
touching the nightly schedule.
