# `archive_api` — least-privilege database role

Design only, 2026-10-28. **No role created, no grant executed, no secret
created, no task definition registered.**

---

## 0. Why

The API connects as **`archive_admin`**, the RDS master user — the same
credential the loader uses to run migrations. It holds `CREATEDB` and
`CREATEROLE` and owns every object in the database.

That was tolerable while `authz` did not exist. It is not now: the API
process can `DROP` the append-only trigger on `authz.access_audit`, alter
`authz` tables, or delete audit rows — and the audit trail is the only record
of who changed access. A web-facing process should not be able to erase its
own oversight.

---

## 1. What the API actually does — inventoried, not assumed

Source scan of `api/src/main/java`: **90** distinct `archive.*` objects and
**8** `authz.*` objects referenced.

### `archive` — read-only, confirmed

```
grep -rhoiE "(INSERT INTO|UPDATE|DELETE FROM)\s+archive\.[a-z_]+" api/src/main/java
→ no matches
```

**Zero DML against `archive` anywhere in the API.** That covers Award,
Proposal, Subaward, Negotiation, Global Search, Dashboard, Explorer,
attachment metadata and AI evidence search — all reads. Views are included:
`archive.v_global_search`, `archive.search`, `archive.search_embedding`,
`archive.search_embedding_poc`, `archive.negotiation_search_attribute`.

### `authz` — reads on all eight, writes on six

| Table | SELECT | INSERT | UPDATE | DELETE |
|---|:--:|:--:|:--:|:--:|
| `identity_link` | ✅ | ✅ | ✅ | — |
| `access_grant` | ✅ | ✅ | ✅ | — |
| `admin_role_member` | ✅ | ✅ | ✅ | — |
| `relink_approval` | ✅ | ✅ | ✅ | — |
| `person_status` | ✅ | ✅ | ✅ ⚠️ | — |
| `access_audit` | ✅ | ✅ | **never** | **never** |
| `kim_principal` | ✅ | — | — | — |
| `principal_crosswalk` | ✅ | — | — | — |

> ⚠️ **`person_status` needs UPDATE even though no `UPDATE authz.person_status`
> appears in the source.** It is written with
> `INSERT … ON CONFLICT (institutional_identifier) DO UPDATE`. A grant derived
> from a naive grep would omit UPDATE and suspension would fail at runtime.
> `JdbcEnrollmentStore` has the same shape against `identity_link`.

**No `DELETE` is issued against any `authz` table.** Revocation is a status
change; the row is the evidence. So the role needs **no DELETE at all**.

### Sequences

Six `BIGSERIAL` columns, of which the API inserts into five:
`identity_link_id`, `access_grant_id`, `audit_id`, `admin_member_id`,
`approval_id`. `crosswalk_id` is written only by the ETL, not the API.

`INSERT` on a `BIGSERIAL` column requires `USAGE` on its sequence — a common
omission that fails only at the first write.

### Triggers, functions, temp tables

- `authz.reject_audit_change()` fires on `UPDATE`/`DELETE` of `access_audit`
  and raises. It needs no privilege from the caller; it exists to stop one.
- **No temporary tables, no `CREATE` of any kind** in API SQL — verified.
- No stored procedures are called.

---

## 2. Role design

```sql
-- ── Role ──────────────────────────────────────────────────────────────
-- LOGIN only. No SUPERUSER, no CREATEDB, no CREATEROLE, no REPLICATION,
-- no BYPASSRLS. Owns nothing, so it can never DROP or ALTER by ownership.
CREATE ROLE archive_api LOGIN PASSWORD :'archive_api_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

-- ── Connect ───────────────────────────────────────────────────────────
GRANT CONNECT ON DATABASE research_archive TO archive_api;

-- ── archive: read-only, and nothing more ──────────────────────────────
GRANT USAGE ON SCHEMA archive TO archive_api;
GRANT SELECT ON ALL TABLES IN SCHEMA archive TO archive_api;   -- includes views
ALTER DEFAULT PRIVILEGES FOR ROLE archive_admin IN SCHEMA archive
  GRANT SELECT ON TABLES TO archive_api;   -- future archive tables stay readable

-- ── authz: read all, write only what is used ──────────────────────────
GRANT USAGE ON SCHEMA authz TO archive_api;
GRANT SELECT ON ALL TABLES IN SCHEMA authz TO archive_api;

GRANT INSERT, UPDATE ON authz.identity_link      TO archive_api;
GRANT INSERT, UPDATE ON authz.access_grant       TO archive_api;
GRANT INSERT, UPDATE ON authz.admin_role_member  TO archive_api;
GRANT INSERT, UPDATE ON authz.relink_approval    TO archive_api;
GRANT INSERT, UPDATE ON authz.person_status      TO archive_api;  -- ON CONFLICT upsert

-- append-only: INSERT and nothing else. No UPDATE, no DELETE.
GRANT INSERT ON authz.access_audit TO archive_api;

-- read-only: written by the ETL, never the API
-- (authz.kim_principal, authz.principal_crosswalk: SELECT only, from above)

-- ── Sequences: only the five the API inserts into ─────────────────────
GRANT USAGE ON SEQUENCE authz.identity_link_identity_link_id_seq   TO archive_api;
GRANT USAGE ON SEQUENCE authz.access_grant_grant_id_seq            TO archive_api;
GRANT USAGE ON SEQUENCE authz.access_audit_audit_id_seq            TO archive_api;
GRANT USAGE ON SEQUENCE authz.admin_role_member_admin_member_id_seq TO archive_api;
GRANT USAGE ON SEQUENCE authz.relink_approval_approval_id_seq      TO archive_api;
-- deliberately NOT granted: authz.principal_crosswalk_crosswalk_id_seq

-- ── Explicitly withheld ───────────────────────────────────────────────
-- no CREATE on any schema; no DELETE anywhere; no TRUNCATE; no ownership;
-- no USAGE on schema public beyond the default; no access to
-- public.schema_migration, so the API cannot even see migration state.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;  -- if not already revoked
```

### Verifying the sequence names before running any of this

Sequence names are derived, not declared. **Confirm them** rather than trust
the convention:

```sql
SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='authz' AND c.relkind='S' ORDER BY 1;
```

### Requirements 1–10, point by point

| # | Requirement | How |
|---|---|---|
| 1 | No ownership | role created fresh; owns nothing. Objects stay owned by `archive_admin` |
| 2 | No CREATEDB/CREATEROLE | `NOCREATEDB NOCREATEROLE` |
| 3 | No schema CREATE/ALTER | `USAGE` only; `ALTER` requires ownership, which it lacks |
| 4 | Cannot drop tables/indexes/triggers | `DROP` requires ownership |
| 5 | Read only what is needed in `archive` | `SELECT` on `archive` only; no DML |
| 6 | Minimal `authz` DML | INSERT+UPDATE on five, INSERT on audit, SELECT on two |
| 7 | Sequences only where needed | five granted, `crosswalk_id` withheld |
| 8 | No modification of audit history | **no UPDATE, no DELETE** on `access_audit`; the trigger is a second line, not the first |
| 9 | Existing read-only archive behaviour preserved | unchanged — the API never wrote to `archive` |
| 10 | Migrations stay privileged | the loader keeps `archive_admin`; `archive_api` cannot create or alter anything |

---

## 3. Should sensitive `authz` writes go through functions instead?

**Investigated. Recommendation: not now — table-level DML plus the constraints
already in the schema is the better trade here.** The reasoning, so it can be
revisited:

**What `SECURITY DEFINER` functions would add.** Table-level `UPDATE` on
`admin_role_member` lets a compromised API set any row to any value — it could
appoint an administrator without writing the paired audit row, because the
pairing is enforced in Java (`@Transactional` over a `MANDATORY` sink), not in
the database. A function could make the pair atomic *in the database* and
refuse everything else.

**What it would cost.** The rules are already implemented and tested in
`ArchiveAdminService` — 89 tests. Reimplementing them in PL/pgSQL means two
authorities that can disagree, and the SQL copy has no test suite of its own.
`SECURITY DEFINER` functions also run as their owner, so a flaw in one is a
privilege-escalation path that the current design simply does not have.

**What already constrains these writes without functions:**

- `access_audit` is **append-only by trigger and by grant** — the API cannot
  rewrite history even if it writes a wrong row;
- `ck_relink_approval_two_people` makes the two-person rule a **database**
  constraint;
- `ux_admin_role_member_active`, `ux_identity_link_active_cognito`,
  `ux_identity_link_active_identity`, `ux_relink_approval_live` make the
  ambiguity and sharing failures **impossible rows**, not merely refused ones;
- `ck_access_grant_shape` constrains grant shape;
- the last-administrator guard is serialised by `pg_advisory_xact_lock`.

**Revisit if** the audit pairing is ever required to hold against a
compromised application rather than a buggy one. That is a different threat
model from the one this project has stated, and it should be adopted
deliberately. A narrower first step would be a single
`authz.record_admin_change()` function covering only `admin_role_member`
writes, leaving the rest as DML.

---

## 4. Secrets, IAM and deployment

### Secret

A **new** secret, not a second key in the existing one — the existing
`research-archive-platform/dev/postgres` is the loader's owner credential and
must not become readable by anything that only needs the API role.

```
name: research-archive-platform/dev/postgres-api
keys: username=archive_api, password=<generated>, host, port, dbname
```

### IAM

The API **execution** role (`…-dev-api-execution-role`) resolves ECS
`secrets`, so it needs `secretsmanager:GetSecretValue` on the **new ARN**, and
should **lose** it on the old one once the switch is verified.

```json
{"Effect":"Allow","Action":"secretsmanager:GetSecretValue",
 "Resource":"arn:aws:secretsmanager:us-east-1:770203350335:secret:research-archive-platform/dev/postgres-api-*"}
```

### Task definition

Only the five `secrets` entries change — `valueFrom` repointed from
`…/postgres-4k6Ngz:<key>::` to the new secret. **No application
configuration changes at all**: the API reads `POSTGRES_USER`/`PASSWORD`/
`HOST`/`PORT`/`DB`, and does not care which role it is.

### Startup tests, in order

1. `GET /actuator/health` → `UP`.
2. An Award search, a Proposal search, a Dashboard load — plain reads.
3. With enforcement still **OFF**, `GET /api/v1/admin/administrators` → **404**
   (the tree is absent when enforcement is off; this proves nothing broke).
4. Only once enforcement is enabled in a later stage: a grant, a revoke, and
   confirmation that `access_audit` gained exactly one row each time.

### Rollback

```bash
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-api:81 --force-new-deployment
```

Revision 81 keeps the `archive_admin` credential and stays ACTIVE. The
`archive_api` role can be left in place during a rollback — an unused role
grants nothing to nobody.

---

## 5. Verification path that does not touch `archive_analyst`

`archive_analyst` must **not** be broadened. Two approved routes:

**Object existence, ownership and grants** — `pg_catalog` is
privilege-independent, so `archive_analyst` can already read it:

```sql
SELECT c.relname, pg_get_userbyid(c.relowner) AS owner,
       array_to_string(c.relacl, E'\n') AS grants
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'authz' AND c.relkind IN ('r','S') ORDER BY 1;
```

**Row counts and migration history** — need the privileged role, via one
read-only loader task (SELECT only; prepared in
`ops/deploy/README.md`, offered for approval, not run).

---

## 6. Tests — disposable PostgreSQL, Testcontainers

New suite `ArchiveApiRoleIntegrationTest`, registered in **both** `ci.yml`
lists. It applies every migration, creates `archive_api` with the grants
above, opens a **second connection as that role**, and asserts:

| # | Test | Expect |
|---|---|---|
| 1 | `archiveReadsSucceed` | SELECT on `archive.award_version`, `proposal_version`, `subaward`, `negotiation` and `v_global_search` all succeed |
| 2 | `archiveWritesFail` | INSERT/UPDATE/DELETE on any `archive` table → *permission denied* |
| 3 | `authorizedAdministrativeWritesSucceed` | INSERT+UPDATE on `identity_link`, `access_grant`, `admin_role_member`, `relink_approval`; upsert on `person_status`; INSERT on `access_audit` |
| 4 | `auditUpdateAndDeleteFail` | UPDATE and DELETE on `access_audit` → denied **by grant**, before the trigger is reached |
| 5 | `droppingTheAuditTriggerFails` | `DROP TRIGGER tr_access_audit_append_only` → *must be owner* |
| 6 | `ddlFails` | `CREATE TABLE`, `ALTER TABLE`, `DROP INDEX`, `CREATE SCHEMA`, `TRUNCATE` → all denied |
| 7 | `migrationCommandsFail` | writing `public.schema_migration`, and `CREATE SCHEMA authz2` → denied |
| 8 | `deleteOnAuthzTablesFails` | DELETE on every `authz` table → denied (no DELETE is granted anywhere) |
| 9 | `sequencesWorkForGrantedTablesOnly` | the five INSERTs get ids; `principal_crosswalk` INSERT → denied |
| 10 | `behaviourUnchangedWithEnforcementOff` | existing suites re-run against a connection using `archive_api` |

Test 4 is the one that matters most: it proves the audit trail is protected by
**two independent mechanisms**, grant and trigger, and that the grant catches
it first.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| A missed privilege fails only at the first write in production | Tests 1, 3 and 9 exercise every write path the inventory found. The `person_status` upsert is the known trap and is covered explicitly |
| Future `archive` tables unreadable by the API | `ALTER DEFAULT PRIVILEGES` covers tables created by `archive_admin`. **It does not cover tables created by any other role** — if the ETL ever runs as something else, the grant must be repeated |
| Future `authz` tables not granted | **Deliberate.** A new authz table should require a conscious grant |
| Two credentials in flight during the switch | The old secret stays until the new one is verified; rollback is one command |
| `NOINHERIT` surprises | `archive_api` is granted no roles, so nothing to inherit. Listed for completeness |
| Sequence names guessed wrong | §2 includes the query to confirm them first |

---

## 8. Recommended order

1. **Confirm the sequence names** (§2 query) — everything else depends on it.
2. Write and land the Testcontainers suite **first**, with the grants as a
   test fixture. It proves the grant set before any real role exists.
3. Create the secret; generate the password; grant the execution role access.
4. Create `archive_api` in dev and apply the grants.
5. Register a task definition repointing the five `secrets`; deploy.
6. Run the startup tests.
7. After a soak, remove the execution role's access to the owner secret.
8. Revisit the `SECURITY DEFINER` question only if the threat model changes.

**Steps 1–2 need no AWS or database change and can start immediately.**
Everything from step 3 needs approval.
