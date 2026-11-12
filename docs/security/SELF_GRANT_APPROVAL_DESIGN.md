# Self-grant privilege escalation — threat model and approval design

**Design only. Nothing implemented.** No endpoint, migration, schema or code
change. Separate from PR #138, which is frontend only.

Every claim in §1 was verified by reading `ArchiveAdminService`,
`InstitutionalIdentifier` and the V085 migration at `9729eff`.

---

## 1. What an administrator can do today

### 1.1 Direct self-grant — unguarded

```java
public AccessGrant grantAccess(InstitutionalIdentifier actor,
                               InstitutionalIdentifier subject, ...) {
    requireAdministrator(actor);
    requireSubject(subject);
    ...
    granted = grants.create(subject, specification, actor.value());
```

`actor` and `subject` are **never compared**. An ARCHIVE_ADMIN can issue
themselves `CENTRAL` — which reaches every record in the archive — or any
`UNIT`, `IO`, `CONTACT_DERIVATION` or `PARENT_AWARD` grant, in a single call.
It is audited; it is not prevented.

### 1.2 Self-widening through `modifyAccess` — a second path, also unguarded

```java
public AccessGrant modifyAccess(InstitutionalIdentifier actor, long grantId,
                                Instant expiresAt, boolean includeDescendants) {
    requireAdministrator(actor);
    AccessGrant modified = grants.modify(grantId, expiresAt, includeDescendants);
```

This one takes a **`grantId`, not a subject**, so there is not even a subject
to compare. An administrator holding a narrow `UNIT 1250400000` grant can flip
`includeDescendants` to `true` and extend `expiresAt`, widening their own
access **without creating a grant at all**.

**An approval control that guards only `grantAccess` would leave this open.**
A design that stopped at "block self-grant" would be defeated by a one-line
`PATCH`.

### 1.3 Administrator membership — the sock-puppet path

```java
public AdminRoleMember assignAdministrator(InstitutionalIdentifier actor,
                                           InstitutionalIdentifier subject, String reason) {
    requireAdministrator(actor);
    requireSubject(subject);
    AdminRoleMember assigned = adminRoles.assign(subject, actor.value(), reason);
```

Any administrator can make **any identity** an administrator. So an
administrator who controls a second BU identity can promote it, then use it to
"independently" approve their own self-grant.

**This is the control's central weakness, and no approval scheme defeats it on
its own.** Mitigations in §4.4.

### 1.4 Identifier comparison is exact string equality

```java
public record InstitutionalIdentifier(String value) {
    public InstitutionalIdentifier {
        if (value == null || value.isBlank()) { throw ... }
    }
}
```

A `record`, so `equals` is **exact string equality**. There is **no trim, no
case folding, no canonicalisation**. `"ARP-X"`, `"arp-x"` and `" ARP-X"` are
three different identifiers to Java.

A naive `actor.equals(subject)` guard is therefore unsafe if the database
lookup is more forgiving than Java's comparison — a subject differing only in
case or padding could resolve to the same row while failing the equality check.
This is the "alternate identifiers bypassing approval" risk, and §5 addresses
it directly.

### 1.5 What is already controlled

| Control | State |
|---|---|
| Every operation requires an active ARCHIVE_ADMIN | enforced on all 12 endpoints |
| The last administrator cannot be removed | enforced, 409 |
| Relink: the requester cannot approve their own relink | **enforced** |
| Relink: the approver cannot be the identity being linked | **enforced** |
| One active link per Cognito identity, one per archive identity | enforced, V085 |
| Every administrative change is audited | enforced |

The relink path already has genuine four-eyes. **The grant path has none.**
This design closes that asymmetry.

---

## 2. Threat model

| # | Threat | Today | After this design |
|---|---|---|---|
| **T1** | Admin grants themselves CENTRAL | **open** | blocked — requires approval |
| **T2** | Admin widens their own grant via `modifyAccess` | **open** | blocked — treated as a self-grant |
| **T3** | Admin promotes a second identity they control, then self-approves | **open** | §4.4 — reduced, not eliminated |
| **T4** | Case/whitespace variant of the subject identifier evades the check | **open** | §5 canonical comparison |
| **T5** | Admin relinks their identity to acquire another's grants | controlled | unchanged |
| **T6** | Two concurrent approvals race to activate | n/a | §8 single-transaction activation + version pin |
| **T7** | Approval replayed after use | n/a | §8 single-use, status-pinned |
| **T8** | Requester suspended between request and approval | n/a | §4.5 re-checked at activation |
| **T9** | A pending request confers access before approval | n/a | §6 — pending rows are not grants |
| **T10** | Approval sits open indefinitely | n/a | §4.3 expiry |

---

## 3. What counts as a self-grant

A request is **self-directed** when, after canonicalisation (§5), the
**subject resolves to the same archive identity as the actor**, or to any
archive identity actively linked to the **same Cognito identity** as the actor.

The second clause matters: V085 permits one active link per identity, but an
administrator may have held others historically, and the check must look at
the *current* active link rather than the identifier alone.

**Scope of the control — all of these are self-directed operations:**

- `POST /identities/{id}/grants` where the subject is self
- `PATCH /identities/{id}/grants/{grantId}` where the grant's **grantee** is self
- `DELETE` of a self grant is **not** restricted — removing your own access is
  not escalation, and blocking it would obstruct someone de-escalating.

---

## 4. Approval workflow

### 4.1 States

```
REQUESTED ──approve──> APPROVED ──activate──> ACTIVATED
    │                     │
    ├──reject──> REJECTED │
    ├──cancel──> CANCELLED (requester only)
    └──expire──> EXPIRED  <─────┘
```

Approval and activation are **separate**: approval records a decision;
activation creates the grant. They occur in one transaction (§8), so there is
no window in which an approval exists without its grant.

### 4.2 Who may approve

A different, **currently active** ARCHIVE_ADMIN. Enforced:

1. approver ≠ requester (canonical comparison)
2. approver ≠ subject (canonical comparison)
3. approver holds an **active** administrator membership at the moment of
   approval, re-read rather than trusted from the request

Mirrors the relink control already in `ArchiveAdminService`, deliberately —
one proven pattern rather than a second invented one.

### 4.3 Expiry

A request not decided within **7 days** expires and cannot be approved. Expiry
is evaluated **at approval time** from `requested_at`, not by a sweeper job, so
a stalled scheduler cannot leave an approvable stale request.

### 4.4 The sock-puppet problem (T3), honestly

No approval scheme prevents an administrator who can create administrators
from manufacturing their own approver. Mitigations, in order of strength:

1. **Make it visible, not merely logged.** Promotion to administrator and
   approval of a self-grant are both audited; the design adds a derived
   signal: *approver was promoted by the requester*. Cheap, and turns a
   silent path into an obvious one.
2. **Cooling-off**: an administrator promoted within the last N days cannot
   approve a self-grant from the administrator who promoted them. Defeats the
   casual case; does not defeat a patient one.
3. **Out-of-band**: require administrator promotion itself to carry an
   approval reference, as grant 2 already does.
4. **Accept and detect**: rely on audit review.

**Recommendation: 1 and 2 now, 3 as a separate decision.** None of these is a
complete answer, and the design should not claim one. The honest statement is
that this control raises the cost of self-granting and makes it conspicuous;
it does not make it impossible for someone who can appoint administrators.

### 4.5 Re-checks at activation

Re-read at activation, never trusted from the request: requester still an
active administrator; subject identity not suspended; approver still active;
request still `APPROVED` and unexpired; no conflicting active grant for the
same scope (V087).

---

## 5. Canonical identity comparison

Comparison is **never** on the string supplied by the caller.

1. The actor is resolved **server-side** from the validated token, exactly as
   `actor()` does today. A caller-supplied actor is never accepted.
2. The subject is resolved to its **canonical archive identity row** before
   comparison — the primary key, not the submitted string.
3. Comparison is on the **resolved row identity**, so case and whitespace
   variants converge before the check.
4. Additionally, both sides are compared on their **currently active Cognito
   link**, so two identifiers linked to one person are recognised as the same
   person.

**Recommendation:** add normalisation to `InstitutionalIdentifier`'s compact
constructor — trim, and reject any value that is not already canonical — so
the type cannot hold a non-canonical value. That is a small, separately
reviewable change, and it would remove a whole class of comparison bug rather
than guarding one call site.

---

## 6. Schema

`V091__self_grant_approval.sql` — **V089 is reserved** for SEC-008's
`authz.activity_audit` and **V090** for the QA workspace, so this takes 091.

```sql
CREATE TYPE authz.self_grant_status AS ENUM
  ('REQUESTED','APPROVED','ACTIVATED','REJECTED','CANCELLED','EXPIRED');

CREATE TABLE authz.self_grant_request (
    request_id        BIGSERIAL PRIMARY KEY,
    requested_by      TEXT NOT NULL,     -- canonical archive identity
    subject           TEXT NOT NULL,     -- canonical; = requested_by for self-grants
    grant_type        VARCHAR(32) NOT NULL,
    unit_number       VARCHAR(50),
    io_value          VARCHAR(50),
    award_number      VARCHAR(50),
    include_descendants BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ,       -- the requested grant's expiry
    target_grant_id   BIGINT,            -- set for a modifyAccess request (T2)
    justification     TEXT NOT NULL,
    status            authz.self_grant_status NOT NULL DEFAULT 'REQUESTED',
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by        TEXT,
    decided_at        TIMESTAMPTZ,
    decision_reason   TEXT,
    activated_grant_id BIGINT,           -- the grant this produced
    version           INTEGER NOT NULL DEFAULT 1   -- optimistic lock (§8)
);

-- One open request per requester and scope: stops a flood of identical
-- requests, and makes "approve" unambiguous.
CREATE UNIQUE INDEX ux_self_grant_open
    ON authz.self_grant_request (requested_by, grant_type,
                                 COALESCE(unit_number,''), COALESCE(io_value,''),
                                 COALESCE(award_number,''))
    WHERE status IN ('REQUESTED','APPROVED');
```

**A pending row is not a grant (T9).** It lives in its own table; nothing in
`AccessScopeResolver` reads it. Access can only come from `authz.access_grant`,
which activation writes. There is no state in which a pending request confers
anything.

**Existing grants are untouched (requirement 9).** The migration creates a
table and adds no constraint to `access_grant`. Whether existing self-granted
rows — if any — should be reviewed or revoked is a **separate decision with
its own migration**.

### Role grants

Table-by-table, never `ON ALL TABLES` — the trap `SEC_008_ACTIVITY_AUDIT.md`
documents, where re-running the role script silently re-grants what a
migration revoked:

```sql
GRANT SELECT, INSERT ON authz.self_grant_request TO archive_api;
GRANT UPDATE (status, decided_by, decided_at, decision_reason,
              activated_grant_id, version) ON authz.self_grant_request TO archive_api;
GRANT USAGE ON SEQUENCE authz.self_grant_request_request_id_seq TO archive_api;
-- No DELETE. A withdrawn request becomes CANCELLED; the row is the evidence.
```

---

## 7. API

All under the existing administrator tree, so they inherit
`requireEnforcementEnabled()` and `requireAdministrator(actor)` unchanged.

| Method | Path | Authorization |
|---|---|---|
| `POST` | `/api/v1/admin/self-grants` | active admin; subject must be self |
| `GET` | `/api/v1/admin/self-grants` | active admin; lists open requests |
| `POST` | `/api/v1/admin/self-grants/{id}/approve` | active admin, **≠ requester, ≠ subject** |
| `POST` | `/api/v1/admin/self-grants/{id}/reject` | same as approve |
| `POST` | `/api/v1/admin/self-grants/{id}/cancel` | **requester only** |

**And the enforcement that makes it real:** `grantAccess` and `modifyAccess`
**refuse** a self-directed operation outright with
`SelfGrantRequiresApprovalException` → **403
`SELF_GRANT_REQUIRES_APPROVAL`**, naming the request route. Without that
refusal the new endpoints are optional and the old path still works.

---

## 8. Audit and transaction safety

New audit actions — `AdminAuditAction` is `VARCHAR(40)` with **no CHECK
constraint**, so these need no migration:
`SELF_GRANT_REQUESTED`, `SELF_GRANT_APPROVED`, `SELF_GRANT_REJECTED`,
`SELF_GRANT_CANCELLED`, `SELF_GRANT_EXPIRED`, `SELF_GRANT_ACTIVATED`.

Every row records actor, subject, request id, and for approval the requester —
so "who approved whose self-grant" is answerable from the audit alone.

**Transaction safety:** approval and activation are **one transaction**. The
grant insert and the status update commit together or not at all, following the
existing pattern that `anAdministrativeChangeAndItsAuditRowCommitTogether`
already proves for administrative writes.

**Concurrency (T6):** the status update is pinned on `version`
(`WHERE request_id = ? AND version = ? AND status = 'REQUESTED'`). Two
simultaneous approvals: one updates one row, the other updates zero and is
rejected as already decided. No lock held across a user decision.

**Replay (T7):** single-use by the same predicate — an already-`ACTIVATED`
request matches no update, so a replayed approval writes nothing. The V087
unique index is the backstop: a duplicate active grant for one scope cannot
exist even if two activations somehow raced.

---

## 9. Bootstrap, emergency access and the single-administrator case

**The hard case:** with exactly one administrator, no self-grant can ever be
approved, because the only possible approver is the requester.

**This must not silently bypass approval**, and it must not deadlock.

**Recommended: explicit degradation, not a hidden exception.** When
`countActive() == 1`, a self-grant request is **refused**, not auto-approved,
with a message saying a second administrator is required. The operator path is
then to bootstrap a second administrator through the existing operator CLI —
which runs as a one-off ECS task, outside the web surface, and is already how
the first administrator was created.

That keeps the property that matters: **no self-grant is ever activated without
a second human**, with the CLI as the out-of-band channel rather than a
back door in the API.

**Rejected alternatives,** recorded so they are not revisited silently:
auto-approval when only one administrator exists — defeats the control exactly
when the system is most vulnerable; a break-glass flag — an unaudited bypass
by another name; and a time-delayed self-approval — a patient attacker simply
waits.

---

## 10. Regression tests

**Core refusals:** `grantAccess` with actor == subject → 403
`SELF_GRANT_REQUIRES_APPROVAL`; `modifyAccess` on a grant whose grantee is
self → the same (T2 — the path a `grantAccess`-only guard would miss).

**Canonical comparison (T4):** case variants, leading and trailing whitespace,
and a second archive identifier linked to the same Cognito identity — each must
be recognised as self.

**Approval rules:** requester cannot approve their own request; the subject
cannot approve; a suspended approver is refused; an administrator whose
membership was revoked between request and approval is refused.

**Concurrency (T6):** two approvals of one request in parallel — exactly one
activates, the other is refused as already decided, and exactly one grant
exists afterwards.

**Replay (T7):** approving an `ACTIVATED` request writes nothing and changes
no grant.

**Pending confers nothing (T9):** with a `REQUESTED` row present,
`AccessScopeResolver` returns the same outcome as with none — asserted against
the resolver, not by reading the table.

**Expiry:** an 8-day-old request cannot be approved; a 6-day-old one can.

**Transaction safety:** a forced failure during activation leaves no grant and
no `ACTIVATED` status.

**Single administrator:** with one active administrator, a request is refused
with the "second administrator required" reason — and **no grant is created**.

**Non-regression:** revoking your own grant still works; the relink four-eyes
controls still behave; `requireAdministrator` still refuses non-administrators
on all 12 endpoints.

All against Testcontainers PostgreSQL with the committed migrations, routed
into the **guarded** CI database job.

---

## 11. Decisions taken 2026-11-12

| # | Decision |
|---|---|
| 1 | Independent approval applies to **all scope types** — CENTRAL, UNIT, IO, CONTACT_DERIVATION, PARENT_AWARD. No type is exempt |
| 2 | **Any modification that expands access is a new privilege request** — see §11a |
| 3 | **Administrator promotion itself requires independent approval**, and a newly promoted administrator may not approve a request that predates their promotion — see §11b |
| 4 | Identity resolution uses **canonical database identity ids and active Cognito links**. Raw `InstitutionalIdentifier` string equality is never the comparison |
| 5 | **Seven-day** expiry. An expired request cannot be approved or reactivated |
| 6 | **No mandatory cooling-off** for ordinary approvals. Independence and auditability are the controls |
| 7 | Existing grants preserved. A **separate read-only review** of historic grants *and administrator assignments* is prepared — see §11c |
| 8 | Self-approval refused when one administrator exists. A separately controlled, audited bootstrap establishes a second. **No automatic approval and no web-based bypass** |
| 9 | Transaction-safe approval, single-use requests, concurrency and replay protection — §8, unchanged |
| 10 | **Two administrator accounts do not guarantee two independent people** — see §11d |

### 11a. Expansion is a new request (decision 2)

The rule is about the **effect**, not the endpoint. A change is expanding, and
therefore requires approval, when it:

- enables `includeDescendants` on an existing grant
- extends `expiresAt`, or removes an expiry
- changes scope to a broader unit, or to a broader grant type
- in any other way makes the reachable record set larger

**Narrowing and revocation remain unrestricted.** Disabling descendants,
shortening an expiry or revoking entirely are de-escalations; obstructing them
would make the system less safe, not more.

This closes the `modifyAccess` path in §1.2 by effect rather than by guarding
one method signature, so a future endpoint that widens access inherits the
control instead of silently escaping it. **Implementation note:** the test is
computed by comparing the reachable scope before and after, not by matching on
parameter names.

### 11b. Administrator promotion requires approval (decision 3)

`assignAdministrator` becomes an approvable operation on the same machinery.
This is what §4.4 could not achieve on its own: an administrator can no longer
unilaterally manufacture an approver.

**And the ordering rule that makes it bite:** an administrator promoted at time
T **may not approve any request created before T**. Without it, the attack is
merely two steps — promote a confederate, then have them approve the request
that was already waiting. Keying on *request age relative to promotion* rather
than on elapsed time is what decision 6 allows us to do: no arbitrary waiting
period, and the specific sequence that matters is still blocked.

Stored as a comparison of `self_grant_request.requested_at` against the
approver's `admin_role_member.granted_at`, both already recorded.

### 11c. Historic review (decision 7)

Read-only, no migration, nothing revoked:

1. every active grant whose `granted_by` equals its grantee — historic
   self-grants
2. every `ACCESS_MODIFIED` audit row where the actor was the grantee
3. every administrator assignment, with who promoted whom, as a graph — so a
   chain of promotions is visible rather than only single hops

Output is a report for human judgement. **Nothing is revoked automatically**;
a historic self-grant may well be legitimate, and this design cannot tell.

### 11d. Two accounts are not two people (decision 10)

**Recorded as a known limitation, not solved.** Everything above enforces
*account* independence: a different active administrator, not the requester,
not the subject, not promoted after the request. None of it establishes that
the two accounts are two **people**. One person holding two BU identities
satisfies every automated check.

This is not closable in software. The control that addresses it is procedural:
**administrator promotion requires independent identity verification** — a
named human confirming the person being promoted is who they claim and is
distinct from the promoter. Decision 3 gives that procedure a place to attach,
since promotion now carries an approval record.

The design must not claim more than it does. What it guarantees is that **no
self-grant activates without a second administrator account acting
deliberately, and every step is attributable**. Whether those accounts are two
people is assured by the promotion procedure, not by this code.

## 12. Not authorized

No implementation, migration, endpoint, schema, role grant or test. V089
reserved for SEC-008, V090 for the QA workspace, V091 proposed here and not
created. Revision 93 serving with enforcement OFF; auditing OFF; grant 2
unchanged; Production untouched.
