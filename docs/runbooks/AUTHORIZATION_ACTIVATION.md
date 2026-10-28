# Record authorization — activation runbook

**Status: proposal awaiting approval. Nothing in this document has been
run.** No migration is applied, no administrator exists, no grant exists,
`app.authorization.enforcement-enabled` is `false` everywhere, and the
deployed API does not contain the administrative surface.

Read with [`IDENTITY_AUTHORIZATION_ARCHITECTURE.md`](../security/IDENTITY_AUTHORIZATION_ARCHITECTURE.md)
(the model) and [`ARCHIVE_ADMIN_MANAGEMENT_DESIGN.md`](../security/ARCHIVE_ADMIN_MANAGEMENT_DESIGN.md)
(the administrative surface).

---

## 0. The shape of the change, and why the order matters

Seven stages. **Each is separately reversible, and only stage 6 changes
what any existing user sees.** Stages 1–5 are invisible to users: the
schema is additive, the administrative API 404s while enforcement is off,
and no code path reads a grant.

```
1 migrate        → tables exist, all empty, nothing reads them
2 deploy         → admin code present, entire /api/v1/admin tree 404s
3 bootstrap      → one administrator exists, still holds no data access
4 link + grant   → identities verified, grants issued, still not enforced
5 validate       → prove scoping in a copy, with enforcement on, off-production
6 enable         → the only user-visible step
7 observe        → or roll back in one property change
```

The ordering constraint that is easy to get wrong: **administrators must
be provisioned before enforcement is switched on, but the administrative
API is unavailable while enforcement is off.** That is deliberate — an
admin API that appeared to work while no grant was read would accept
changes with no effect. It is resolved by stage 3 using the operator
bootstrap path rather than HTTP, and by stage 4 running with enforcement
temporarily enabled in a non-production environment. **Do not resolve it
by enabling enforcement in production to create the first administrator.**

---

## 1. V082–V084 migration safety

### What they do

| | Adds | Changes existing data |
|---|---|---|
| `V082` | schema `authz`; `identity_link`, `person_status`, `access_grant`, `access_audit` + append-only trigger | none |
| `V083` | `kim_principal`, `principal_crosswalk` | none |
| `V084` | `admin_role_member` | none |

**All three are purely additive.** No `ALTER` of an existing table, no
`UPDATE`, no `DELETE`, no `INSERT`, no backfill; every table and index is
`CREATE ... IF NOT EXISTS`. The one `DROP` in the set is
`DROP TRIGGER IF EXISTS tr_access_audit_append_only`, immediately before
the same migration recreates it, on a table that same migration creates —
re-run safety, not a change to anything that exists. **Nothing in the
`archive` schema is touched**, so no archived record can be altered by
applying them.

### Pre-checks

```bash
# 1. The loader image must be a descendant of the migration commits, or
#    --migrate-only finds nothing to apply. This exact failure happened
#    with V076 on 2026-08-14 and 500'd production.
git merge-base --is-ancestor <commit adding V084> <loader image source commit> \
  && echo "loader image contains V084" || echo "STOP: rebuild the loader image"

# 2. Confirm the target's current level, on dev RDS, via ECS - not local Postgres.
#    Expect 081 or lower; V081 is reserved for separate Award work.
#    SELECT MAX(version) FROM public.schema_migration;
```

### Apply

Via the ETL loader's `--migrate-only` on ECS Fargate (the established
path; Spring does not run Flyway — `spring.flyway.enabled: false`).

### Verify

```sql
SELECT table_name FROM information_schema.tables WHERE table_schema='authz' ORDER BY 1;
-- expect exactly: access_audit, access_grant, admin_role_member,
--                 identity_link, kim_principal, person_status, principal_crosswalk
SELECT COUNT(*) FROM authz.access_grant;        -- expect 0
SELECT COUNT(*) FROM authz.admin_role_member;   -- expect 0
```

### Rollback

**There is no down migration, and none should be written.** The tables
are empty and unread; leaving them costs nothing. If they must go, that
is a separate reviewed forward migration — never an ad-hoc `DROP SCHEMA`,
which would discard the append-only audit trail.

> ⚠️ `AuthorizationInertWhenDisabledTest` asserts the application starts
> and behaves identically with these tables present and enforcement off.
> Applying the migrations alone changes nothing a user can observe.

---

## 2. Controlled first-administrator bootstrap

### Why it is not an HTTP endpoint

The first administrator cannot be appointed by an existing administrator,
so any HTTP route to it would be a privilege-escalation path around the
whole model. `bootstrapFirstAdministrator` refuses once any active
administrator exists, so it cannot be replayed later to inject one.

### Decisions needed before this stage (both open)

| | |
|---|---|
| **D-ID-2(a)** | **Who** the first administrators are — at least two, so stage 2 of the last-administrator guard is never a single point of failure |
| **D-ID-3** | **Which BU attribute** is the institutional identifier. Until BU IAM confirms, there is nothing stable to key a membership on |

**D-ID-3 blocks this stage.** Bootstrapping against a guessed attribute
creates a membership that may key on a value that later changes, and the
schema deliberately keys on the institutional identifier rather than a
login name because login names change.

### Procedure (to be run once, by a named operator, with a witness)

1. Record the operator identity and the reason — `bootstrapFirstAdministrator`
   requires both and refuses a blank operator.
2. Invoke it through an operator path (an ECS one-off task), **never a
   deployed HTTP route**.
3. Verify: one row in `authz.admin_role_member`, `granted_by='BOOTSTRAP'`,
   and one `ADMIN_ROLE_BOOTSTRAPPED` row in `authz.access_audit`.
4. Immediately appoint the second administrator **through the API** by the
   first, so the appointment is audited normally.

### Verify the invariant that matters

```sql
SELECT COUNT(*) FROM authz.access_grant
 WHERE institutional_identifier IN (
   SELECT institutional_identifier FROM authz.admin_role_member WHERE status='ACTIVE');
-- expect 0: being an administrator must create no data access
```

### Rollback

Revoke the membership through the API. The last-administrator guard will
refuse to leave zero, so roll back by revoking the *second* administrator
first. The bootstrap row is history and is never deleted.

---

## 3. Identity-link verification

**Automatic Shibboleth-to-KRIM matching is not approved and stays
disabled.** Every link in this stage is created by an administrator who
has verified the person by some means outside the archive.

### Rules

- **Email is never an identity key.** It is mutable and reassignable.
- **`PRNCPL_NM` is never string-matched** against an eppn local part. It
  looks like a username and is not guaranteed to be one.
- **A KRIM-inactive principal may be linked** (D-ID-1). 61% of referenced
  principals are inactive; refusing them would deny most legitimate users.
- **One active link per Cognito identity**, enforced by
  `ux_identity_link_active_cognito`. A second would resolve as `Ambiguous`,
  which denies everything.

### Verify

```sql
-- no ambiguity: one active link per Cognito identity, one identity per person
SELECT cognito_issuer, cognito_subject, COUNT(*) FROM authz.identity_link
 WHERE status='ACTIVE' GROUP BY 1,2 HAVING COUNT(*) > 1;   -- expect no rows
SELECT institutional_identifier, COUNT(DISTINCT kuali_person_id)
  FROM authz.identity_link WHERE status='ACTIVE' AND kuali_person_id IS NOT NULL
 GROUP BY 1 HAVING COUNT(DISTINCT kuali_person_id) > 1;    -- expect no rows
```

Also confirm each link's `method='ADMIN_VERIFIED'`; an `AUTO_VERIFIED`
row at this stage would mean enrollment is on when it should not be.

> **Dependency: D-ID-4.** Administrator identity search reaches only
> 7.89% of referenced people until the targeted KRIM backfill runs. The
> UI states this, and an identifier can always be entered manually, but
> the backfill should precede any large provisioning exercise.

---

## 4. Admin grant/revoke acceptance tests

Run in a **non-production environment with enforcement temporarily on**,
against synthetic identities. Each row is already covered by an automated
test; this is the human-run confirmation on a real deployment.

| # | Action | Expected |
|---|---|---|
| A1 | Non-administrator calls any of the 13 endpoints | **403**, code `NOT_AN_ARCHIVE_ADMINISTRATOR` |
| A2 | Administrator with no grant opens a record | **404** — administration is not data access |
| A3 | Grant CENTRAL, re-request immediately | visible on the **next request**, no sign-out |
| A4 | Revoke it, re-request immediately | **404** on the next request, JWT still valid |
| A5 | Suspend an identity holding grants | everything denied; grants still present, not revoked |
| A6 | Restore | access returns without re-granting |
| A7 | Revoke the only administrator | **409**, code `LAST_ARCHIVE_ADMINISTRATOR` |
| A8 | `POST /api/v1/admin/bootstrap` | **404** — no such route |
| A9 | Grant an SAP account (IO), open a related Proposal | **404** — IO reaches Awards only |
| A10 | Inspect `authz.access_audit` after A3–A6 | exactly one row per change, correct actor and action |
| A11 | Attempt `UPDATE`/`DELETE` on `access_audit` | refused by the append-only trigger |
| A12 | Open an unlinked identity in the admin UI | **"Not provisioned"** warning; any grants shown as having no effect |

A2 and A12 are the two that catch the most likely misunderstanding:
holding the role, or holding a grant without a link, is not access.

---

## 5. Shared-search and Dashboard authorization validation

The easiest place for a scoping bug to hide is an aggregate, because a
count leaks the existence of records a user cannot open.

| Surface | Check | Expected |
|---|---|---|
| Global Search (keyword) | search as a unit-scoped user | only in-scope records; **`totalElements` agrees with the page** |
| Global Search (semantic) | same query | same scoping; the semantic leg is scoped too |
| Global Search — IRB | any user, **including Central** | **no IRB rows at all** when enforcement is on |
| Dashboard — Award | unit-scoped user | counts scoped |
| Dashboard — Subaward, Negotiation | unit-scoped user | counts scoped |
| **Dashboard — Proposal** | unit-scoped user | ✅ counts scoped (D2 closed 2026-10-27) |
| Documents / Archived File Finder | unit-scoped user | scoped; no out-of-scope filenames |
| Attachment download | out-of-scope attachment id, guessed | **404**, never 403 |

> ### ✅ D2 closed — and ⚠️ what is still open
>
> **The Proposal Dashboard counts are scoped as of 2026-10-27.** All three
> Proposal-derived figures move together: the family count, the
> version-row count, and the PROPOSAL branch of the document tally.
> Eleven integration tests cover Central, department, descendants,
> research staff, no-grant, denied, unprovisioned and enforcement-off,
> and nine of them fail if the scope is removed.
>
> **Still unscoped: the five IRB-derived figures** — `irb`, `submissions`,
> `funding_records`, `timeline_events`, and the IRB branch of the
> document tally. There is no approved IRB record rule, so there is
> nothing to apply. Note the inconsistency to settle before stage 6: IRB
> is excluded from shared Global Search when enforcement is on, but the
> Dashboard still reports archive-wide IRB totals to every caller. These
> are aggregates, not records, and the exposure is small — but the two
> surfaces should agree, and that is a decision rather than a bug fix.

Also confirm counts and pages come from the **same `WHERE`**: a scope
predicate applied to the page but not the count makes `totalElements`
leak, which is the failure mode the engine was built to avoid.

---

## 6. Enforcement activation and rollback

### Required properties

`app.authorization.enforcement-enabled=true` **plus** every policy
strategy, which have **no defaults**:

```
app.authorization.version-scope
app.authorization.department-match
app.authorization.research-staff-roles
app.authorization.contact-derivation
app.authorization.max-sign-in-age
```

A missing strategy does **not** fall back to permissive: `policy()`
returns empty and the gate denies every record
(`ENFORCED_POLICY_NOT_CONFIGURED`). That is correct behaviour and it is
also a total outage for record access, so set them in the same change.

### Sequence

1. Enable in **dev** first, with the stage 5 checks re-run live.
2. Soak with a small named pilot group that already holds grants.
3. Only then consider test, then production — each as its own approval.

### Rollback

**Set `app.authorization.enforcement-enabled=false` and redeploy.** One
property. The engine short-circuits in three independent places —
property default `false`, service short-circuit, and `PERMIT_ALL` port
defaults — so disabling restores exactly today's behaviour.

Rollback is **non-destructive**: grants, links and audit rows stay. The
administrative API returns to 404, which is the expected consequence, not
a second fault.

**No data rollback is needed or possible**, and none should be attempted:
the audit trail is append-only by database trigger.

---

## 7. The existing API is unchanged until stage 6

This is the claim the whole plan rests on, so it is evidenced rather than
asserted:

| | |
|---|---|
| Property default | `enforcementEnabled` is a plain Java `boolean` — `false` unless explicitly set |
| Service short-circuit | `RecordAuthorizationService` returns allow-all when the property is off |
| Port defaults | every `*RecordAccess` port has a `PERMIT_ALL` default plus a `requireEnforceableWiring` startup guard |
| Admin surface | the whole `/api/v1/admin` tree returns **404** while enforcement is off |
| Proven by test | `AuthorizationInertWhenDisabledTest` — the context starts with all authorization code present, every administrative path 404s, and an ordinary request is not blocked |
| Proven again | `everyAdministrativeEndpointIs404`, `nothingIsWrittenWhileTheSurfaceIsAbsent` |
| Deployment | the currently deployed image **does not contain** the admin controller at all; stages 1–5 require a deploy that is itself separately approved |

**Nothing in stages 1–5 changes a response any current user receives.**

---

## Open items blocking activation

| | Blocks |
|---|---|
| **D-ID-3** — which BU attribute is the institutional identifier (BU IAM) | stages 2–6 |
| **D-ID-2(a)** — who the first administrators are | stage 3 |
| ~~**D2** — unscoped Proposal Dashboard count~~ | **closed 2026-10-27** |
| **D9** — IRB Dashboard figures stay archive-wide while IRB is hidden from shared search | stage 6 (decision, not a fix) |
| **D-ID-4(a)** — approval for the KRIM backfill into dev RDS | stage 3's usability |
| Approval to deploy an API image containing the admin surface | stage 2 |

The first three are hard blocks. The last two are practical ones.
