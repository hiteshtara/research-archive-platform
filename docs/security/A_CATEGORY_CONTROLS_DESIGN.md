# A-category administrative security controls: design for approval

Status: **PROPOSED, not implemented.** Written 2026-11-17 against `main` at
`7101d75`. Nothing in this document has been applied to DEV.
Administrator writes, record-level enforcement and V089 all stay off.

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
        ('ADMIN_ASSIGN','SELF_GRANT','SELF_WIDEN','LINK_PRIVILEGED','CENTRAL_GRANT')),
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
| `CENTRAL_GRANT` | any CENTRAL grant, to anyone | **Business decision needed.** It conflicts with D-ADM-2 (one administrator suffices). Recommended yes, because CENTRAL is unrestricted access to every record |
| Restoring a suspended administrator | `setSuspended(false)` on an ARCHIVE_ADMIN | Optional. Recommended as `ADMIN_ASSIGN`-equivalent |

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

`valid_until` defaults to 72 hours. V086 has no expiry; it is added here
because an approval made last month should not authorise an escalation
today.

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

**Treat it as unverified until re-read.** One read-only check, from
CloudShell or the approved read path, no writes:

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
service: non-blank after trim, at least 10 characters, at most 500. It is
applied to all ten mutating service methods:
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

PR 1 closes every A-category hole by *refusing* the dangerous action. PR 2
turns those refusals into approvable workflows. Writes could safely be
enabled after PR 1, with promotion and self-access limited to the operator
CLI until PR 2 merges.

## 11. Decisions needed

1. **CENTRAL_GRANT approval.** Should every CENTRAL grant need a second
   administrator? This overrides D-ADM-2 for that one type. Recommended: yes.
2. **Approval validity window.** Proposed 72 hours.
3. **Approval of administrator restoration.** Should restoring a suspended
   administrator need approval? Recommended: yes.
4. **Minimum reason length.** Proposed 10 characters.
5. **Run the section 4 read-only check now?** Can it run independently of
   implementation?
