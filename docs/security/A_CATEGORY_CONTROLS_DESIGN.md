# A-category administrative security controls: design for approval

Status: **DESIGN APPROVED to proceed, not implemented.** Written 2026-11-17
against `main` at `7101d75`. Business decisions recorded 2026-11-17 (section
12). Nothing in this document has been applied to DEV. Administrator writes,
record-level enforcement, V089 and V090 all stay off and unapplied.

**Release condition (decided):** administrator writes are **not** enabled
after PR 1, nor after any single PR. Enabling them needs three things: the
complete security test suite passing, a controlled DEV acceptance review,
and a separate explicit approval.

Source inputs: PR #155 (coverage matrix), the V086 relink-approval model,
and the code at the line numbers cited below. All code findings below were
read from source on 2026-11-17.

---

## 0. What was found while reading the code (new since #155)

PR #155 listed self-grant, widening, promotion, V087 status, the CENTRAL
default and the optional reason. Reading the write paths found **four more**
defects. Two of them are A-category in their own right.

| # | Finding | Where | Category |
|---|---|---|---|
| N1 | **The path identifier is ignored on grant modify, grant revoke and unlink.** `PATCH /identities/{X}/grants/{id}` modifies grant `id` whoever holds it. The audit row records the real grantee, so the trail is correct, but the URL lies about the target, and any UI or approval bound to `{X}` can be bypassed. | `AdminAuthorizationController` `modifyGrant`, `revokeGrant`, `unlink` | **A** |
| N2 | **Unlink then link bypasses the V086 two-person rule.** `relinkIdentity` requires a second approver when the identity holds active grants. `linkIdentity` has no such check. One administrator can unlink, then link a new Cognito subject, and the grants follow the identity. | `ArchiveAdminService.linkInTransaction` | **A** |
| N3 | **Revocation overwrites the grant's original reason.** `SET reason = COALESCE(:reason, reason)` replaces why the grant was made with why it was revoked. The audit table keeps both, but the grant row loses its justification. | `JdbcAccessGrantRepository.revoke` | B |
| N4 | **HTTP grant drops `awardNumber`.** The controller uses the 6-arg `GrantSpecification` constructor, so a PARENT_AWARD grant cannot be created over HTTP. It fails validation and does not widen anything. | `AdminAuthorizationController.grant` | B |
| N5 | **Modify is read-then-write without a row lock.** Widening cannot be judged correctly against a value another transaction is changing. | `JdbcAccessGrantRepository.modify` | A (prerequisite for control 2) |

Also confirmed while reading: `assignAdministrator` cannot assign to the actor themselves, because
the repository refuses an already-active member. The real promotion risk is
one administrator creating a second, which is control 3.

---

## 1. Control: no self-granting through the HTTP API

**Rule.** An administrator cannot create a grant for their own
institutional identifier. If they need archive access, another
administrator approves it through the approval model in section 3.

**Where.** `ArchiveAdminService.grantAccess`, not the controller. That
makes the check apply to `grantAccessInBulk` (which calls `grantAccess` per
grantee) and to the provisioning CLI. A self-grant in a bulk list becomes a
per-grantee `FAILED` outcome with reason `SELF_GRANT_REQUIRES_APPROVAL`.

**Error.** New `SelfAdministrationException`, mapped to
`403 SELF_ADMINISTRATION_REQUIRES_APPROVAL`.

**The same rule applies to:**
- `modifyAccess` on the actor's own grant when the change widens (control 2)
- `setSuspended` on self: refused outright. A suspended administrator no
  longer resolves (`IdentityResolver` requires an unsuspended identity), so
  self-suspension is a lockout, and for the last administrator it cannot be
  undone through the application.
- `linkIdentity` / `relinkIdentity` where the subject is the actor

**Self-revocation is still allowed** (of a grant or of ARCHIVE_ADMIN, subject
to the last-administrator guard). Reducing your own access is not
escalation.

**Residual risk, stated plainly.** The check compares institutional
identifiers. It cannot stop one person who controls two identities, for
example an administrator who links a second Cognito account to a fresh
identifier and then grants that identifier. Section 3 narrows this by making
every link to an identity that holds grants or ARCHIVE_ADMIN need approval.
Fully closing it would need an independent identity proof (BU directory),
which the archive does not have.

---

## 2. Control: no widening of your own grants

**Definition of widening** (computed against the row locked `FOR UPDATE`, see N5):

| Field | Widening | Narrowing / neutral |
|---|---|---|
| `includeDescendants` | `false → true` | `true → false`, unchanged |
| `expiresAt` | later date, or any date → `null` (no expiry) | earlier date, `null → date`, unchanged |

**Rules.**
1. **Bind to the path (fixes N1).** `modifyAccess`, `revokeAccess` and
   `unlinkIdentity` take the path subject and refuse with 404 when the
   grant or link does not belong to it. 404 rather than 403 so grant IDs
   cannot be enumerated across subjects.
2. **Self, widening:** refused without a consumed approval (section 3).
3. **Self, narrowing:** allowed by the actor alone.
4. **Other subject, widening:** allowed by one administrator, per decision
   D-ADM-2. A reason is mandatory (control 6) and the audit row records
   before and after values.
5. **Expiry in the past:** refused (400). Setting a past date to "expire now"
   is what revocation is for, and only revocation leaves the scope free under V087.

**Implementation.** Add `AccessGrantRepository.lockActive(grantId)`, a
`SELECT … FOR UPDATE` with `MANDATORY` propagation, the same pattern as
`revokeWithLastAdministratorGuard`. The service compares the locked row with
the request, decides, then updates. The audit detail gains
`before=` / `after=` for both fields.

---

## 3. Control: independent approval for promotion and escalation

### 3.1 Reuse V086's model, not its table

V086 already has the right properties, and tests prove them in
`ArchiveIssuedIdentityIntegrationTest`:

- approvals are bound to the exact target
- they are consumed once, by a conditional `UPDATE … WHERE status='APPROVED'`
- the two-person rule is a database `CHECK`, not only Java
- at most one live request exists per target (partial unique index)

`authz.relink_approval` is left **untouched**. It works, it is tested, and
migrating it buys nothing. A sibling table generalises the same shape:

```sql
-- V090__privilege_approval.sql  (proposed)
CREATE TABLE authz.privilege_approval (
    approval_id          BIGSERIAL PRIMARY KEY,
    action               VARCHAR(30) NOT NULL,   -- see 3.2
    subject_identifier   TEXT NOT NULL,          -- who gains the privilege
    -- Exact binding: what was approved is exactly what may execute.
    grant_type           VARCHAR(30),
    unit_number          TEXT,
    io_value             TEXT,
    award_number         VARCHAR(50),
    include_descendants  BOOLEAN,
    expires_at           TIMESTAMPTZ,
    target_grant_id      BIGINT REFERENCES authz.access_grant(grant_id),
    requested_by         TEXT NOT NULL,
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason               TEXT NOT NULL CHECK (length(btrim(reason)) >= 10),
    approved_by          TEXT,
    approved_at          TIMESTAMPTZ,
    approval_reason      TEXT,
    valid_until          TIMESTAMPTZ NOT NULL,   -- new vs V086: approvals go stale
    consumed_at          TIMESTAMPTZ,
    consumed_by          TEXT,
    revoked_at           TIMESTAMPTZ,
    revoked_by           TEXT,
    status               VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','CONSUMED','REVOKED')),
    CONSTRAINT ck_pa_action CHECK (action IN
        ('ADMIN_ASSIGN','ADMIN_RESTORE','SELF_GRANT','SELF_WIDEN',
         'LINK_PRIVILEGED','CENTRAL_GRANT')),
    -- V086's two-person rule, plus: the beneficiary can never approve.
    CONSTRAINT ck_pa_two_people   CHECK (approved_by IS NULL OR approved_by <> requested_by),
    CONSTRAINT ck_pa_not_subject  CHECK (approved_by IS NULL OR approved_by <> subject_identifier),
    -- (V086's status/timestamp consistency CHECKs reproduced verbatim)
    ...
);
CREATE UNIQUE INDEX ux_privilege_approval_live ON authz.privilege_approval
  (action, subject_identifier, COALESCE(grant_type,''), COALESCE(unit_number,''),
   COALESCE(io_value,''), COALESCE(award_number,''), COALESCE(target_grant_id,0))
  WHERE status IN ('PENDING','APPROVED');
```

### 3.2 What requires approval

| Action | Trigger | Recommendation |
|---|---|---|
| `ADMIN_ASSIGN` | every `assignAdministrator` | **Required.** Asked for explicitly |
| `SELF_GRANT` | `grantAccess` where subject = actor | **Required** (control 1) |
| `SELF_WIDEN` | widening `modifyAccess` on own grant | **Required** (control 2) |
| `LINK_PRIVILEGED` | `linkIdentity` on an identity holding active grants **or** ARCHIVE_ADMIN | **Required**: closes N2. Relink keeps V086 |
| `CENTRAL_GRANT` | any CENTRAL grant, to anyone | **Required. Decided 2026-11-17**, superseding D-ADM-2 for CENTRAL only |
| `ADMIN_RESTORE` | `setSuspended(false)` on an identity with an **active** ARCHIVE_ADMIN membership, which would make its administrator privileges usable again | **Required. Decided 2026-11-17.** Restoring a non-administrator stays a single-administrator action, with a reason |

### 3.2a Immutability after approval (required 2026-11-17)

An approval must authorise exactly what the approver saw. Once a row
exists, its bound fields cannot change. These are:
- `action`, `subject_identifier` and every specification column
- `target_grant_id`
- `requested_by`, `requested_at`, `reason`, `valid_until`

The only permitted transitions are the status columns:
- PENDING → APPROVED
- APPROVED → CONSUMED
- PENDING/APPROVED → REVOKED

Each transition sets only its own timestamp and actor columns.

This is enforced **in the database**: a `BEFORE UPDATE` trigger on
`authz.privilege_approval` raises if any bound column differs between `OLD`
and `NEW`, or if the status transition is not one of the four above. The
repository exposes no method that updates a bound column. Java can't be
trusted alone for this, because the risk being closed is a request edited
between approval and execution. A change means revoking the request and
submitting a new one.

### 3.2b Expiry (decided 2026-11-17)

`valid_until = requested_at + 72 hours`, measured from the **request**.
Past it, the approval can be neither approved nor executed: both the
approve and the consume `UPDATE` carry `AND valid_until > now()`. An
expired request stays as history and must be resubmitted as a new row.
Expired rows still in PENDING or APPROVED would block a resubmission under
the live unique index. So the request path first marks the caller's own
expired live row REVOKED with `revoked_by = 'EXPIRED'`, in the same
transaction, and audits it.

### 3.3 Ordering, enforced at every step

1. **Request:** any administrator, with a reason. The approval is bound to the
   exact action, subject and specification.
2. **Approve:** a different administrator who is neither the requester nor
   the subject (enforced by the database, as above). They must be an active administrator at approval time
   and they record an approval reason.
3. **Execute:** the protected write, in **one transaction**. It:
   - re-checks the approver is still an active administrator
   - checks `valid_until > now()`
   - checks the request matches the binding exactly
   - consumes the approval with a conditional `UPDATE`
   - performs the write, then the audit row with `approval=<id>`

   If any step fails the whole transaction rolls back, **including the
   consumption**, so a failed write never burns an approval.
4. **Revoke:** either party, or any administrator, while the approval is
   PENDING or APPROVED. A revoked approval authorises nothing.

`valid_until` is 72 hours from the request (3.2b). V086 has no expiry; it is
added here because an approval made last month should not authorise an
escalation today.

**Approver eligibility, checked at approval and again at execution:** the
approver must hold an *active* ARCHIVE_ADMIN membership, must not be
suspended, and must not be the requester or the beneficiary. The last two
are also database CHECKs. Losing eligibility in between voids the approval.

### 3.4 Consistency with V086

The executor may be any administrator, the requester included. As V086's
comment argues, the decision is what needs two people, and the binding
limits the executor to exactly what was agreed. The one change: for
`SELF_GRANT` and `SELF_WIDEN` the executor is necessarily the subject, and
that is acceptable *only because* `ck_pa_not_subject` keeps them off the
approver side.

---

## 4. Control: verify V087/V088 before grant writes are enabled

**What is actually known.**
- `SEC_009_READ_ONLY_EVIDENCE.md` (2026-11-09) recorded the index **present**
  in DEV via `pg_indexes`, but with the **V087** key, without
  `award_number`.
- V088, which **drops and rebuilds** that index with `award_number`, was
  executed in DEV on 2026-11-09.
- So the current definition depends on the order of those two events, and
  no record says which came first.

### 4.1 Verified 2026-11-17, read-only, from CloudWatch only

Evidence comes from the loader's own CloudWatch log group
(`/ecs/research-archive-platform-dev-loader`). No database connection was
opened, no credential was retrieved, and no task was launched.

| Fact | Evidence |
|---|---|
| V087 applied | `Applying migration V087: one active grant per scope` → `Migration V087 completed`, **2026-11-02T17:54:26Z**, run `7e638b66-195c-48e2-8e99-4746d40639e7` |
| The 2026-11-09 "V087-shaped index" observation was taken **before** V088 | `SEC_009_READ_ONLY_EVIDENCE.md` committed `1985b77` at 18:53:54Z; V088 ran four hours later |
| V088 applied | `Applying migration V088: parent award grants` → `Migration V088 completed` → `Migrations applied` → `Migration and schema validation complete`, **2026-11-09T22:47:55Z**, run `9aa3baa4-bcd4-49b3-841f-b93412440d7b`. V088's `DROP INDEX` + `CREATE UNIQUE INDEX … COALESCE(award_number,'')` are unconditional statements in that file, and its duplicate-refusing `DO` block did not raise |
| Nothing has run since | That V088 run is the only `Applying migration` event from 2026-11-09 to 2026-11-17. **V089 has never been applied** |
| The V088 column exists | API revision 104's `JdbcAccessGrantRepository` SELECTs `award_number`. `GET /api/v1/admin/identities/ARP-FK7QY195-QW5HV7G0` returned 200 in the browser on 2026-11-17 |

**Conclusion:** both migrations are recorded as applied, in order, and
nothing has run since. The ledger says the index is in its V088 shape.

**What is not proven:** the catalog has not been read since V088. Out-of-band
DDL would not show in these logs. The only remaining existing route that
needs no credential handling by an operator is an ECS one-off task that runs
SQL inside the container. The 2026-11-02 preflights used that route, as
`archive_admin` under a read-only session guard. Launching one is held for
separate approval, because it executes ad-hoc SQL as the owner role on
shared DEV.

### 4.2 The catalog read, when approved

Read-only, no writes:

```sql
SELECT indexdef FROM pg_indexes
 WHERE schemaname='authz' AND indexname='ux_access_grant_active_scope';
-- must contain COALESCE(award_number, '') AND WHERE (revoked_at IS NULL)

SELECT institutional_identifier, grant_type, count(*)        -- must return 0 rows
  FROM authz.access_grant WHERE revoked_at IS NULL
 GROUP BY 1,2, COALESCE(unit_number,''), COALESCE(io_value,''), COALESCE(award_number,'')
HAVING count(*) > 1;
```

**Make it permanent in code, not a checklist item.** Add a
`AdministrationSchemaPreconditions` bean that reads `pg_indexes` and
`information_schema` at startup. `archive_api` can read the catalog even
though it cannot read `public.schema_migration`. If the V088-shaped index
**or** the V090 table is missing, `administrationWritesAvailable` is forced
to `false`, whatever the env var says, and the reason is logged. Writes then
cannot be switched on over a schema that cannot hold the invariants.

---

## 5. Control: no CENTRAL default

- **UI:** the grant form starts with **no type selected**
  (`type: ""`, placeholder "Choose access type…"). `validateGrantForm`
  rejects an empty type. Choosing CENTRAL shows an inline warning ("all
  records, every unit") and, if 3.2's CENTRAL_GRANT is approved, the
  button changes to "Request approval".
- **Server:** already has no default; `grantType(null)` is refused. A test
  pins this so a future DTO default cannot reintroduce it.
- **Confirmed live 2026-11-17:** DEV's grant form displays "Central (all
  records)" as the initial value (harmless today because the button is disabled).

---

## 6. Control: mandatory reason on every change

**Server (authoritative).** A `Reasons.require(reason)` helper in the
service. Decided 2026-11-17: at least 10 characters, validated server-side,
with blank and placeholder reasons rejected. The rule after normalisation
(trim, collapse internal whitespace):

1. 10 to 500 characters
2. at least **two words containing letters**, so "1234567890" and "----------" fail
3. not a single repeated character or a repeated short token
   ("aaaaaaaaaa", "test test test")
4. not, case-insensitively and ignoring punctuation, a placeholder:
   `test`, `testing`, `n/a`, `na`, `none`, `null`, `reason`, `no reason`,
   `asdf`, `qwerty`, `xxx`, `tbd`, `todo`, `placeholder`, `see above`,
   `as discussed`, `per request`, `lorem ipsum`, and these repeated or
   combined with each other

The denylist is a deterrent, not a guarantee: a determined administrator can
still write a meaningless sentence. The reason is shown to the approver and
kept in the audit trail, and that is the real control. The list lives in one
place, used by both the server and the UI helper, so they cannot drift.

It is applied to all ten mutating service methods:
- `grantAccess`
- `modifyAccess`
- `revokeAccess`
- `assignAdministrator`
- `revokeAdministrator`
- `linkIdentity`
- `unlinkIdentity`
- `setSuspended`
- `relinkIdentity`
- every approval request/approve/revoke

The bulk path inherits it through `grantAccess`. Error:
`400 REASON_REQUIRED`.

The controller changes three `@RequestParam(required = false) reason` to
`required = true` (revoke grant, unlink, revoke administrator). The service
check still applies, because the CLI does not pass through the controller.

**Fix N3.** Revocation writes `revocation_reason` (a new nullable column in
V090) instead of overwriting `reason`.

**UI.** Required reason inputs inline, replacing the three `window.prompt`
calls (`AdminIdentityWorkspacePage.tsx:119`), which cannot be tested and
cannot enforce a minimum length.

**No CHECK on existing `access_grant.reason`.** Historical rows may be null,
and a `NOT VALID` constraint would be a second migration concern for little
gain. The service is the gate, and the approval table enforces it in SQL.

---

## 7. Test plan

Each control gets a unit test (service, mocked repositories) **and** an
integration test against disposable PostgreSQL (Testcontainers, the existing
pattern). Every test is written to fail against current `main` first.

### 7.1 Self-grant (control 1)
- HTTP `POST /identities/{self}/grants` → 403, no grant row, no audit row
- Bulk with self in the list → self `FAILED`, others `GRANTED`, summary counts correct
- CLI grant to self → refused
- Self-revoke still works; self-suspend refused; last-administrator guard unaffected

### 7.2 Widening (control 2)
- Self `false→true` descendants → 403; `true→false` → 200
- Self expiry later / to null → 403; earlier → 200; past → 400
- Other subject widening → 200 and audit shows before/after
- **N1:** `PATCH /identities/A/grants/{grantOfB}` → 404 and B's grant
  unchanged. Same for `DELETE` grant and `DELETE` link
- **Concurrency:** two transactions modify the same grant (one narrows, one
  widens) with a latch; the widening check sees the committed value, never a stale one

### 7.3 Approval model (control 3): ordering and concurrency
- Execute with no approval → refused
- Execute with PENDING approval → refused
- Approve by requester → refused (service **and** a direct SQL insert hits `ck_pa_two_people`)
- Approve by subject → refused (service and `ck_pa_not_subject`)
- Approve after revoke → refused; revoke after approve → execute refused
- Execute with a spec differing in any one bound field → refused (parameterised over all 8 fields)
- Approval for subject A used for subject B → refused
- Expired approval → refused
- Approver loses ARCHIVE_ADMIN between approve and execute → refused
- **Write fails after consume** (e.g. V088 duplicate) → transaction rolls
  back, approval is still APPROVED, no audit row
- **Concurrency:** N threads execute against one approval → exactly one write, N-1 refusals
- **Concurrency:** two simultaneous requests for the same target → one row (partial unique index)
- **N2:** unlink then link on an identity with grants → link refused without `LINK_PRIVILEGED`

### 7.4 V087/V088 precondition (control 4)
- Index absent → `administrationWritesAvailable=false` even with the env var on
- Index in its V087 shape (no `award_number`) → also false
- V090 table absent → also false
- All present → follows the env var

### 7.5 CENTRAL default and reason (controls 5, 6)
- UI helper: initial form state has no type; `validateGrantForm` rejects empty type and short reason
- UI component: Grant button disabled until a type is chosen and the reason is ≥10 characters
- Server: each of the ten write methods with blank, 9-character and 501-character reasons → 400, nothing written
- N3: revoke keeps the original grant `reason` and stores `revocation_reason`

### 7.5a Audit completeness (required 2026-11-17)
A table-driven integration test over **every** mutating service method
and every approval transition (request, approve, revoke, consume, expiry):
- **success** → exactly one new `access_audit` row, in the same transaction,
  naming the actor, subject and action, and the approval ID where one was consumed
- **refusal** (any reason) → zero new audit rows and zero changed domain rows
- a new mutating method added later without a row in this table fails the
  test (it enumerates `ArchiveAdminService`'s public methods by reflection
  against an allow-list of read-only ones)

### 7.5b Approval immutability (3.2a)
- Direct SQL `UPDATE` of each bound column on a PENDING row and on an APPROVED row → trigger raises
- Illegal status transitions (CONSUMED → APPROVED, REVOKED → APPROVED, PENDING → CONSUMED) → trigger raises
- Approve, then attempt to execute a request differing from the approved one → refused, approval still APPROVED

### 7.6 Reactivation (control 8), integration with the **real** evaluator
Using Award fixtures, as `AwardRecordAuthorizationIntegrationTest` does:
1. UNIT grant → Award in that unit visible
2. Suspend → same request 404s (and per-request resolution means no JWT refresh is needed)
3. Restore → visible again, **same `grant_id`**, no new grant row, audit shows SUSPENDED then RESTORED
4. An expired grant is **not** revived by restore
5. A revoked grant is **not** revived by restore
6. Unlink, then link again with `LINK_PRIVILEGED` approval → visible again under the new Cognito subject; the old subject resolves to nothing
7. A suspended administrator cannot administer; once restored by a *different* administrator, they can

---

## 8. Affected APIs

| Endpoint | Change |
|---|---|
| `POST /api/v1/admin/identities/{id}/grants` | self → 403; reason required; CENTRAL may need approval; accepts `awardNumber` (N4) |
| `PATCH /api/v1/admin/identities/{id}/grants/{grantId}` | bound to `{id}` (404); self-widen → 403; reason required; past expiry → 400 |
| `DELETE /api/v1/admin/identities/{id}/grants/{grantId}` | bound to `{id}`; `reason` required |
| `PUT /api/v1/admin/identities/{id}/suspension` | self → 403; reason required |
| `POST /api/v1/admin/identities/{id}/links` | privileged identity → requires `LINK_PRIVILEGED`; reason required |
| `DELETE /api/v1/admin/identities/{id}/links/{linkId}` | bound to `{id}`; `reason` required |
| `POST /api/v1/admin/administrators` | requires an `ADMIN_ASSIGN` approval |
| `DELETE /api/v1/admin/administrators/{id}` | `reason` required |
| **new** `GET/POST /api/v1/admin/approvals` | list / request |
| **new** `POST /api/v1/admin/approvals/{id}/approve` | approve |
| **new** `DELETE /api/v1/admin/approvals/{id}` | revoke |
| `GET /api/v1/me/capabilities` | `administrationWritesAvailable` also reflects the schema precondition |

All new and changed write endpoints keep `requireAdministrationWrites()`,
so they still return 409 in DEV until writes are deliberately enabled.

---

## 9. Migration requirements

- **One migration, V090** (`privilege_approval` table plus
  `access_grant.revocation_reason`). Additive only: no data is changed, and
  V082–V088 and `relink_approval` are untouched.
- **V089 stays held back.** The runner (`etl/archive_etl/upload/migrations.py`)
  only *warns* on a gap and applies any unapplied version in order. So V090
  can be applied while V089 sits in `migrations-pending-approval/`, and V089
  can still be applied later. V089 must be added to
  `RESERVED_MIGRATION_VERSIONS` so the gap is declared rather than silent.
  V089 touches only the activity-audit objects, so the order does not matter.
- **Deploy ordering** (the 2026-08-14 V076 incident): V090 must be applied
  and verified **before** an API image that uses it is deployed. The
  precondition bean (section 4) also makes a premature deploy fail safe:
  writes stay unavailable, rather than 500 errors.
- DEV application of V090 is a separate, explicitly approved step. It is
  not part of the implementation PRs.

---

## 10. Delivery plan and estimate

| PR | Contents | Migration | Estimate |
|---|---|---|---|
| **1. Hardening without schema** | Self-grant refusal (control 1). Path binding (N1). Mandatory reasons, server and UI (control 6). CENTRAL default removal (control 5). `FOR UPDATE` on modify (N5). Self-widen *refused outright* until PR 2 lands. Self-suspend refusal. Unlink→link of a privileged identity *refused outright* until PR 2 (N2). awardNumber (N4). Reactivation tests (control 8). | none | **3–4 days** |
| **2. Approval model** | V090, the repository, the service gates for the five actions in 3.2, approval endpoints, the schema-precondition bean (control 4), N3, and the full ordering/concurrency suite (7.3, 7.4) | V090 | **5–7 days** |
| **3. Approval UI** | Approvals inbox (pending, approve, revoke); "request approval" flows on grant form, administrator appointment and widening | none | **2–3 days** |
| Read-only DEV check | Section 4 queries | none | **< 1 hour**, needs approval of the read path |

**Total: about 10–14 engineer-days.**

PR 1 closes every A-category hole by *refusing* the dangerous action. Until
PR 2, it refuses CENTRAL grants, administrator assignment, administrator
restoration, self-grant, self-widening and privileged linking outright. PR 2
turns those refusals into approvable workflows. Each PR is reviewable on its
own: PR 1 needs no migration, PR 2 owns V090, PR 3 is UI only.

**Writes stay off after every PR** (release condition at the top). Enabling
them is a separate decision that follows the full suite and a controlled DEV
acceptance review.

## 11. Unresolved architectural decisions

1. **How the second administrator is created.** DEV has exactly one
   administrator. With `ADMIN_ASSIGN` requiring a second, distinct active
   administrator to approve, **no approval can ever be satisfied in DEV**, and
   the same applies to every environment's first rollout. Options:
   - (a) A one-time operator path, like bootstrap, that seats the second
     administrator. It records the operator and refuses once two or more
     active administrators exist. **Recommended.**
   - (b) Allow approval by "operator + administrator" while fewer than two
     administrators exist.
   - (c) Seat two administrators at bootstrap.

   This must be decided before PR 2. Otherwise PR 2 makes promotion
   permanently impossible.
2. **Break-glass when no eligible approver exists.** For example, the other
   administrator is suspended or has left. The same answer as decision 1
   probably fits, but it must be stated, because self-suspension is now
   refused and approvals cannot be self-approved.
3. **Whether the operator CLIs go through the approval gates.** The
   provisioning CLIs call `ArchiveAdminService`, so the gates apply to them
   automatically. That would make the CLI unable to seat a CENTRAL grant
   or an administrator without an approval row. Confirm that is wanted.
   The alternative is an explicit, audited operator override.
4. **Who may execute an approved action.** The design follows V086: any
   active administrator may execute, because the decision was made by two
   people and is bound exactly. The stricter option is requester-only.
5. **Reason denylist ownership.** Who maintains the placeholder list
   (6), and whether a ticket or reference number should be required for
   CENTRAL and administrator actions.
6. **Direct catalog verification of the V088 index** (4.2) via an ECS
   one-off task as `archive_admin` with a read-only session guard. Awaiting
   approval to launch.

## 12. Decisions recorded 2026-11-17

| # | Decision |
|---|---|
| D1 | Every CENTRAL grant requires independent approval by a second active administrator |
| D2 | Approval requests expire 72 hours after the request. Expired requests cannot be approved or executed, and must be resubmitted |
| D3 | Restoring a suspended identity requires independent approval when it reactivates administrator privileges |
| D4 | Every administrative write needs a meaningful reason of at least 10 characters, validated server-side. Blank and placeholder reasons are rejected |
| D5 | Delivery in three independently reviewable PRs: (1) close holes + regression tests, (2) approval workflow + V090, (3) approval UI |
| D6 | Administrator writes are not enabled after PR 1. Enabling them follows the full security suite and a controlled DEV acceptance review |

Also required (all are covered above):
- identity-in-URL ownership validation (N1)
- closing remove-and-relink (N2)
- preserving the grant reason on revoke (N3)
- retaining the Parent Award number (N4)
- no self-grant, unauthorised widening or self-suspension
- no promotion without approval
- an approver who is a different, active, non-beneficiary administrator
- single-use, transactionally consumed, concurrency-safe approvals
- approval immutability (3.2a)
- an audit record for every successful change (7.5a)
