# DEV migration runbook — V088 (SEC-005 Parent Award)

**Nothing in this runbook has been executed.** V088 is not applied to DEV. No
task definition is registered. No ECS change has been made. This is the
prepared plan, with its preflight evidence.

Prepared 2026-11-09. Follows the shape of
[`DEV_MIGRATION_RUNBOOK_V087.md`](DEV_MIGRATION_RUNBOOK_V087.md).

---

## 1. What V088 does

| | |
|---|---|
| Adds | `authz.access_grant.award_number VARCHAR(50)` |
| Alters | `ck_access_grant_type` (+`PARENT_AWARD`), `ck_access_grant_shape` (+ the PARENT_AWARD arm, and `award_number IS NULL` on every other arm) |
| Rebuilds | `ux_access_grant_active_scope` — **V087's index, widened** with `COALESCE(award_number,'')` |
| Adds | `ix_access_grant_award_number` (partial, for the grant → hierarchy lookup) |
| Touches archive data | **no** — `authz` only |
| Writes/updates/deletes any row | **no** |

The index rebuild is the only destructive step, and it recreates the same
invariant on a wider key. Without widening, a grantee's **second**
`PARENT_AWARD` grant for a **different** Award would collide with the first.

---

## 2. The image

| | |
|---|---|
| Source commit | **`c1d25e6010cb8b0c773c5748e2983dc52033deb5`** — the #114 merge, built from a clean detached worktree, 0 uncommitted files |
| Repository | `770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-loader` |
| **Index digest — pin to this** | **`sha256:8b236cad38d4f57181f0c6c1d839d1d4214d26925236d49e441bac328b11f893`** |
| amd64 child manifest | `sha256:902e0e7278751c64c845ccf1914b37824d82b9cac6f2c586561f954bf8eb0789` |
| Tags | `20261109T223633Z-c1d25e6-amd64` and `protected-v088-c1d25e6` — both on the one digest |

Built with `docker buildx build --platform linux/amd64 --load -f
etl/Dockerfile.loader .`. `--platform linux/amd64` is not optional: an
arm64-only image fails only when ECS tries to start the task.

### Verified from the registry copy, not the local build

1. **Platform, from the ECR index.** `application/vnd.oci.image.index.v1+json`
   carrying a real `linux/amd64` image descriptor plus an `unknown/unknown`
   attestation manifest (normal; not a platform).
2. **Round trip.** Pulled back **by digest**; inside that copy:
   **86 migration files, highest V088, `V089` count 0**, and
   `database/migrations-pending-approval/` **not present at all** —
   `etl/Dockerfile.loader` copies only `database/migrations`.
   `V088` md5 `ed8e0b1a90465fe1312f238b0a7af52d`, matching git exactly.
3. **The runner can execute it** — run, not inferred. The ECR copy's own
   packaged runner applied V001→V088 against a throwaway PostgreSQL 17,
   recorded `max_version=88` over 86 files, created the widened index, added
   `award_number`, and created **no** `activity_audit` table. This is the
   check that catches the V076 trap, where an image lacking the migration
   files discovered nothing, applied nothing and exited 0.
4. **Only V088 on a second run.** Rewound to V087; the same copy logged
   exactly `Applying migration V088: parent award grants` and nothing else.
5. **It refuses when it should.** Given two identical unrevoked UNIT grants it
   refused — `Cannot rebuild ux_access_grant_active_scope: duplicate unrevoked
   grants already exist: DUPU/UNIT/1250000000/-/- (2 rows)` — left the database
   at **V087**, created **no** index, added **no** column, and **left both
   duplicate rows in place**.

The expected `Gap detected … missing version(s): V081` warning appears on every
run. V081 is a documented intentional gap, not an error.

---

## 3. DEV preflight — measured 2026-11-09, read-only

Through `scripts/start-db-tunnel.sh` as `archive_analyst`; tunnel and its
ephemeral Fargate task torn down afterwards and confirmed gone.

| Check | Result |
|---|---|
| `ux_access_grant_active_scope` | **present**, `indisvalid = t`, `indisready = t` |
| Its definition | exactly V087's: `(institutional_identifier, grant_type, COALESCE(unit_number,''), COALESCE(io_value,'')) WHERE revoked_at IS NULL` |
| `authz.access_grant` columns | the 13 V082 columns; **`award_number` ABSENT** → V088 not applied |
| Indexes on the table | `access_grant_pkey`, `ix_access_grant_grantee`, `ux_access_grant_active_scope` — all valid |

### Data conflicts that would block V088: none possible

`archive_analyst` has no `USAGE` on schema `authz`, so the grant **rows**
cannot be counted on this path. The conflict is nonetheless ruled out by
construction:

- V087's unique index exists and is **valid**, so no two unrevoked rows share
  `(identifier, grant_type, COALESCE(unit_number,''), COALESCE(io_value,''))`.
- V088's duplicate check groups by that same key **plus**
  `COALESCE(award_number,'')`. The column does not exist yet, so every row
  contributes the same `''` to it — the grouping is **identical**.
- A wider key can only split groups, never merge them.

**Therefore V088's refusal branch cannot fire.** A valid V087 index is itself
the proof. (Last known counts, 2026-11-03, unverified since: 1 administrator,
1 identity link, **0 active grants** — which would make it trivially true too.)

---

## 4. Execution

**Both steps need explicit approval. Neither has been performed.**

### 4.1 Register the task definition

```bash
AWS_PROFILE=bu-nprd aws ecs register-task-definition \
  --region us-east-1 \
  --cli-input-json file://ops/migration/dev-loader-migrate-only-v088.taskdef.json
```

Image pinned **by digest**, `runtimePlatform` `X86_64`/`LINUX` so a platform
mismatch is an explicit error rather than a pull failure. Note the revision it
returns; use it below.

### 4.2 Run it

```bash
AWS_PROFILE=bu-nprd aws ecs run-task \
  --region us-east-1 \
  --cluster research-archive-platform-dev-etl \
  --launch-type FARGATE \
  --task-definition research-archive-platform-dev-loader:<NEW_REVISION> \
  --started-by "v088-migrate-only" \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-00fba12ee73ff0e3b,subnet-0c5b92d15314b93ed],securityGroups=[sg-0817befcc5b4affc9],assignPublicIp=DISABLED}' \
  --overrides '{"containerOverrides":[{"name":"loader","command":["python3","load_awards_from_csv.py","--ecs","--migrate-only"]}]}'
```

`--migrate-only` is valid only with `--ecs`. It resolves the PostgreSQL
credential from `POSTGRES_SECRET_ID` inside the running container — there is
no plaintext password anywhere — skips Oracle entirely, applies migrations,
validates `award_version` and `award_transmission_child` exist, and exits.

**Expected log line:** `Applying migration V088: parent award grants`, and no
other `Applying migration` line. Anything else means the preflight assumption
was wrong — stop and re-measure.

### 4.3 Deregister afterwards

Per `GRANT_LIFECYCLE_ACCEPTANCE_TESTS.md`: the one-off definition is
registered and deregistered per run.

---

## 5. Post-migration verification SQL

Read-only, through the tunnel as `archive_analyst`. Every query here is
catalog-only and works without `authz` schema privileges.

```sql
-- 1. The column exists.
SELECT CASE WHEN EXISTS (
    SELECT 1 FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname='authz' AND c.relname='access_grant'
       AND a.attname='award_number' AND NOT a.attisdropped)
  THEN 'PASS award_number present' ELSE 'FAIL award_number missing' END;

-- 2. The index was rebuilt on the WIDER key, and is valid.
SELECT i.indisvalid, i.indisready, pg_get_indexdef(i.indexrelid)
  FROM pg_index i
  JOIN pg_class c ON c.oid = i.indexrelid
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname='authz' AND c.relname='ux_access_grant_active_scope';
-- expect: t | t | ... COALESCE(award_number, ''::character varying) ... WHERE (revoked_at IS NULL)

-- 3. The lookup index exists.
SELECT count(*) AS expect_1
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname='authz' AND c.relname='ix_access_grant_award_number';

-- 4. V089 did NOT arrive.
SELECT count(*) AS expect_0
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname='authz' AND c.relname LIKE 'activity_audit%';

-- 5. The archive is still read-only to the API role.
SELECT count(*) AS expect_0
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 CROSS JOIN (VALUES ('INSERT'),('UPDATE'),('DELETE'),('TRUNCATE')) AS p(priv)
 WHERE n.nspname='archive' AND c.relkind IN ('r','p')
   AND has_table_privilege('archive_api', c.oid, p.priv);
```

The migration ledger (`public.schema_migration`) is deliberately unreadable by
`archive_analyst`, which is why every check above is a catalog check. The
loader's own log line is the ledger evidence.

---

## 6. Recovery

| Situation | What happened | Action |
|---|---|---|
| Task fails to start (`TaskFailedToStart`) | image pull, subnet or role problem. **Nothing ran; no database change** | Check the ECR manifest platform first — that was the 2026-10-28 cause. The digest in §2 is verified `linux/amd64`, and `runtimePlatform` makes a mismatch explicit. Fix and retry |
| Migration refuses with `Cannot rebuild …` | duplicate unrevoked grants exist. **Nothing was changed** — still V087, no column, no index change, duplicate rows intact | Revoke the redundant grant (never delete — the row is audit evidence), then retry |
| Task exits non-zero mid-migration | V088 runs inside `BEGIN … COMMIT`, so it is all-or-nothing | Re-run §5 query 1. Absent column ⇒ nothing applied; retry. Present ⇒ it committed; treat as applied |
| Applied, then needs reversing | — | `database/rollback/V088__parent_award_grants_rollback.sql`, run as the owner. Restores V087's index **exactly**, drops the column, removes the ledger row. **Refuses** while any `PARENT_AWARD` grant exists |
| API behaving unexpectedly after | — | Roll the API service back to task definition **84**. Image 84 never reads `award_number`, so it is safe against a V088 database |

**The API does not need to be redeployed for V088.** The current image simply
never selects the new column. Deploying the new API image is a separate,
later, independently approvable step.

---

## 7. The nightly Subaward sync — verified unaffected

| | |
|---|---|
| Schedule | `research-archive-platform-dev-subaward-nightly`, **ENABLED**, `cron(0 2 * * ? *)` America/New_York |
| Target | task definition **`research-archive-platform-dev-loader:237`** — pinned to that revision, so it cannot drift |
| Its image | `20260817T011034Z-4a82821`, source commit `4a82821` (2026-08-16) |
| Highest migration in that image | **V077** — it contains **neither V088 nor V089** |

Every loader run applies whatever migrations its own image carries. The
nightly's image tops out at V077, and DEV is already at V087, so **every
migration it can find is already recorded and skipped**. It will not apply an
unexpected migration before, during or after this rollout, and it will not run
an incompatible image.

Applying V088 does not change this: the nightly still finds only ≤ V077.

> **Residual risk, named rather than hidden.** If the nightly's task definition
> is later re-registered against the new loader image, the nightly **would**
> apply V088 automatically on its next run. That is safe for V088 specifically
> — it is this release — but it is the mechanism to be aware of, and it is
> exactly why V089 is not in any image built from `main`.
