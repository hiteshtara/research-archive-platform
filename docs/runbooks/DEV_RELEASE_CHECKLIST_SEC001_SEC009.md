# DEV release checklist — SEC-001 … SEC-009

**Nothing in this checklist has been executed.** No merge, no image build, no
ECS change, no migration, no grant, no enforcement. This is the plan and the
evidence behind it.

Prepared 2026-11-09 against integration branch
`integration/security-sec001-sec009`.

---

## 1. Release contents

| | |
|---|---|
| Source branch | `integration/security-sec001-sec009` |
| Base | `origin/main` = `7442a8e` (verified ancestor) |
| Image digest | **not built** — fill in after `ops/deploy-api.sh` |
| Carries | PR #109 (SEC-003) `9e0cad7`, PR #112 (SEC-009) `ec55b22`, both by merge |

**Verified present:**

- **V087 duplicate-grant handling** — `8bc1e52` (#100, answer 409 not 500) is
  an ancestor of the branch head. The running DEV image `66f3cfe` does **not**
  carry it; this release is what fixes that.
- **No simulator, no impersonation** — `git ls-files | grep -ic viewaccessas`
  returns **0**. The View Access As harness is not on this branch at all, and
  `grep -ri viewaccessas ui/dist` after a production build returns 0.

**Migrations added to the release:** `V088` (Parent Award) only. `V089`
(activity audit) is held in `database/migrations-pending-approval/` — see §2.
**Role scripts:** `archive_api_role.sql` (modified), new
`archive_api_activity_audit_grant.sql`.
**Rollback scripts:** `database/rollback/V088…`, `database/rollback/V089…`.

---

## 2. Required database migrations

| | V088 | V089 |
|---|---|---|
| Purpose | `PARENT_AWARD` grant type, `award_number`, V087 index rebuild | `authz.activity_audit`, partitions, append-only trigger |
| Required for this release? | **Yes** — the code reads `award_number` | **No** — defer until SEC-008 is approved |
| Applied anywhere? | **No** | **No** |
| In the release artifact? | **Yes**, `database/migrations/` | **No** — held in `database/migrations-pending-approval/` |
| Rehearsed? | Yes, on top of V082–V087 **with existing grant rows** | Yes, both with and without V088 |

### Why V089 is not merely "deferred" but physically separated

The ETL migration runner has **no target-version parameter**. It globs
`database/migrations/` and applies **every** version not already recorded in
`public.schema_migration`:

- `--migrate-only` applies all of them, not a chosen one;
- `load_subawards_from_csv.py` — the **nightly Subaward sync at 02:00 ET** —
  calls `apply_migrations` unconditionally on every run, as does every other
  loader.

So a file left in `database/migrations/` is not a proposal: it is an
instruction to apply it the next time any loader runs from an image containing
it, including a scheduled one. There is no "apply V088 but not V089" in that
design.

V089 therefore lives in `database/migrations-pending-approval/`, outside that
glob. `MigrationReleaseScopeTest` fails the build if any version above V088
reappears in the applied directory, so the separation is enforced rather than
remembered.

Spring does not apply migrations either: `spring.flyway.enabled: false` in
`application.yml`, and no profile overrides it. CI applies migrations only to
throwaway Testcontainers databases.

**The V088 ordering trap.** The ETL loader applies migrations, and its image
must be a descendant of the migration-adding commit. Check before running:

```bash
git merge-base --is-ancestor <V088-commit> <loader-image-commit>
```

A registered loader task definition does **not** rebuild itself because a
migration was merged.

---

## 3. ECS task-definition changes

Register a new revision of `research-archive-platform-dev-api` (current: **84**;
revision 85 does not exist yet) with:

| Change | Value |
|---|---|
| Image | the new digest from the build |
| `APP_AUTHORIZATION_*` | **none — do not add** |
| `app.authorization.activity-audit.enabled` | **do not set** (defaults false) |
| All eight existing variables | unchanged |

Any `APP_AUTHORIZATION_*` variable at this stage turns enforcement on with
**zero grants issued**, which makes every record invisible to every
non-administrator.

---

## 4. Database privilege changes

**None required for this release.**

`archive_api_role.sql` has changed — it now revokes `SELECT` on
`authz.activity_audit` and every partition of it — but that revoke is a no-op
until V089 exists. Re-running the role script is safe and idempotent either
way, and rehearsed in both orders.

`archive_api_activity_audit_grant.sql` (`GRANT INSERT`) is **only** for when
SEC-008 is approved. Not part of this release.

---

## 5. Smoke tests after deployment

Read-only, in this order. Stop at the first failure.

| # | Check | Expected |
|---|---|---|
| 1 | `GET /actuator/health` | `200 UP` |
| 2 | ECS service | 1/1 running, deployment `COMPLETED` |
| 3 | Image digest on the running task | matches the digest built in §1 |
| 4 | `GET /api/dashboard` (authenticated) | Awards 40,926 · Proposals 33,960 · Historical Award 267,386 · Negotiations 10,775 · Subawards 3,511 — **unchanged** |
| 5 | Global Search, a known term | cross-module results, as before |
| 6 | Award workspace + version history | opens; historical versions navigable |
| 7 | Proposal workspace + history | opens |
| 8 | Subaward workspace | opens, version navigation works |
| 9 | Negotiation workspace | opens |
| 10 | Document Explorer | lists, Disposition labels render |
| 11 | Attachment tab without `ArchiveAttachmentViewer` | still refuses with the group message |
| 12 | `/qa-status` | Security Readiness renders; enforcement still reads **off** |
| 13 | Any record as a signed-in user | still visible — enforcement is off |

**Check 13 is the one that matters.** If records start disappearing,
enforcement has been enabled by accident. Roll back immediately (§6).

---

## 6. Rollback

**Application only (no migration applied yet):** update the ECS service to task
definition **84**. The previous image is retained in ECR under a
`protected-` tag. One command, no database involvement — this is why §2 defers
V089.

**After V088:** `database/rollback/V088__parent_award_grants_rollback.sql`.
Restores V087's index **exactly**, drops `award_number`, removes the ledger
row. **Refuses** if any `PARENT_AWARD` grant exists rather than deleting it —
a grant row is audit evidence.

**After V089 (not in this release):**
`database/rollback/V089__archive_activity_audit_rollback.sql`. **Refuses** when
the table holds rows unless `rollback.allow_audit_loss` is explicitly set to
`yes`.

Rolling the application back to 84 while V088 is applied is safe: image 84
never reads `award_number`.

---

## 7. Outstanding business approvals

| # | Decision | Blocks |
|---|---|---|
| 1 | SEC-005 depth (1, max 10) | nothing — confirm at leisure |
| 2 | SEC-005 broader inheritance | nothing — currently restricted |
| 3 | SEC-003 Subaward consequence | nothing — currently the stricter reading |
| 4 | Fund Center definition (SAP owners) | any Fund Center work |
| 5 | SEC-008 retention (90 days proposed) | **V089 and collection** |
| 6 | SEC-008 failure policies as BU policy | **collection** |
| 7 | SEC-008 privacy defaults | **collection** |
| 8 | SEC-008 `GRANT INSERT` | **collection** |
| 9 | First access grants | enforcement |
| 10 | Enabling enforcement | enforcement |

**None of 1–4 blocks this release.** 5–8 are why V089 is deferred. 9 and 10 are
a separate, later change with no code in it.

---

## 8. Evidence

```
API  1816 tests, 0 failures, 0 errors   (mvn clean test, all Testcontainers suites)
UI    741 tests, 0 failures · lint clean · production build clean
```

Rehearsed on throwaway PostgreSQL (`ReleaseMigrationRehearsalIntegrationTest`,
8 tests):

- V088 applies on top of V082–V087 **with existing CENTRAL, UNIT and IO grant
  rows**; no row lost or rewritten.
- The V087 uniqueness invariant **survives the index rebuild** — a duplicate
  scope is still refused, and two `PARENT_AWARD` grants for different Awards
  are now both storable.
- V089 applies with or without V088 — they are not order-coupled.
- Both rollbacks restore the prior schema and **refuse** rather than destroy
  evidence.
- **No archive table becomes writable** at any point: before, after V088,
  after V089 + the audit grant, and after rolling V089 back.

Startup with both flags off: `AuthorizationInertWhenDisabledTest` (6 tests) and
`BootstrapNonWebStartupTest`. No `authorization` or `activity-audit` key exists
in any `application*.yml`, so both features default off from the Java field
defaults.

Module functionality under the release build: Award 48, Document Explorer 32,
Attachment Search 25, Negotiation 22, Subaward 13, Global Search 5, Explorer 3,
Proposal 2, Dashboard 1 — all passing.
