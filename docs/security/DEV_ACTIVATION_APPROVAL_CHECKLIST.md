# Approval checklist — DEV authorization activation

Every decision that must be made before record-level authorization can be
tested, enforced or audited on DEV, with **who** decides it and what is
blocked until they do.

Engineering items are marked as such and are not waiting on anyone. They are
listed so the distinction is visible: a reader should be able to tell at a
glance what is a decision and what is just work.

Nothing in this list has been done. No grant, identity link, task definition,
enforcement setting or migration has been applied.

---

## A. Deployment — needs an operator and a release decision

| # | Item | Blocked until | Who |
|---|---|---|---|
| A1 | Build, push and deploy an API image containing the operator utilities | — | deployment owner |
| A2 | Refresh `securityReadiness.json` and run `ops/sync-operator-taskdefs.sh` | A1 | deployment owner |
| A3 | Register the `grant-admin` and `link-identity` task definitions | A2 | deployment owner |

**Why A1 is not optional.** The deployed image is `164b242` (revision 87). It
has no `--award-number` flag, so a `PARENT_AWARD` grant cannot be created, and
no `link-identity` profile, so that task definition would start a container
with a profile nothing responds to. Pilot roles 5 and 6 — the SAP IO and Parent
Award users — cannot be provisioned at all until A1 is done.

**A2 is not housekeeping.** An operator definition left pointing at the old
digest runs **older CLI code against today's database**. Three guards now
catch that (`OperatorTaskDefinitionTest` in CI,
`verify-grant-admin-taskdef.sh` against the running service, and the follow-up
`deploy-api.sh` prints), and `sync-operator-taskdefs.sh` is the one command
that fixes it.

---

## B. The pilot population — business decisions

| # | Decision | Who | Blocked until |
|---|---|---|---|
| B1 | **Who** the eight pilot participants are | business | — |
| B2 | Each participant's institutional identifier, from BU IAM — never derived from archive data | business / BU IAM | B1 |
| B3 | Each participant's Cognito `sub`, via `AdminGetUser` | operator, from B2's login name | B2 |
| B4 | For role 4 only: the Kuali `PERSON_ID`, paired by the Award-first procedure | business + operator | B1 |
| B5 | That roles 7 and 8 receive **no grant**, and that this is the point | business | B1 |
| B6 | The pilot's grant **expiry date** (proposed 2027-06-30) | business | B1 |

**B4 is the one that cannot be checked afterwards.** A wrong `PERSON_ID`
pairing resolves cleanly, the evaluator decides correctly, and the wrong
records are reached. Name matching is not verification —
`AdminVerifiedPilotPathIntegrationTest.aSharedDisplayNameIsNotAnIdentity`
shows two different principals sharing a display name. The procedure in
[`PHASE2_PILOT_PLAN.md`](PHASE2_PILOT_PLAN.md) section 4b — ask the
participant to name one Award they expect to see, look it up **by Award
number**, take the `person_id` from that row — is the only control.

**B6 matters more than it looks.** D-ADM-3 makes expiry optional with no
default. Without one, a pilot grant becomes permanent through inattention. And
note the caveat the utility itself reports: V087 constrains *unrevoked*
grants, so an expired grant still occupies its scope until revoked. Closing
the pilot means revoking, not waiting.

---

## C. Writing to shared DEV — needs explicit approval per step

| # | Action | Writes | Reversible by | Who |
|---|---|---|---|---|
| C1 | Link the eight identities | `authz.identity_link` | `--unlink-link-id` (a new fact) | business + security |
| C2 | Issue the six grants | `authz.access_grant` | `--revoke-grant-id` (a new fact) | business + security |

Both default to a dry run; the proposed runs and their real output are in
[`../runbooks/DEV_PILOT_DRY_RUNS.md`](../runbooks/DEV_PILOT_DRY_RUNS.md).

Six grants, not eight — roles 7 and 8 receive none.

---

## D. Enforcement — security and business, together, and last

| # | Decision | Who | Note |
|---|---|---|---|
| D1 | `app.authorization.version-scope` | business | FAMILY_WIDE or PER_VERSION changes what a contact sees |
| D2 | `app.authorization.department-match` | business | `EXACT_LEAD_UNIT` ignores `include_descendants` entirely, so roles 2 and 3 become the same test |
| D3 | `app.authorization.contact-derivation` | business | `EXPLICIT_GRANT` or `VERIFIED_PRINCIPAL` |
| D4 | `app.authorization.research-staff-roles` | business | DEV has only PI, COI, KP, MPI |
| D5 | `app.authorization.parent-award-depth` | business | 0 is the granted Award alone; 1 adds its children. Role 6's expected result depends entirely on this |
| D6 | `app.authorization.max-sign-in-age` | security | |
| D7 | Turn enforcement on | security + business | the only step that writes nothing, so the only genuinely reversible one |

The outstanding values are tracked in
[`AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md`](AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md).

**Decide these before deploying, not during.** An incomplete policy is refused
at startup by `AuthorizationConfiguration.requireEnforcementSettings()` —
deliberately, because a half-configured policy would deny every record to
every user, and a service that starts and denies everything is harder to
diagnose than one that refuses to start and says why.

**D5 is worth singling out.** Role 6 is granted `PARENT_AWARD 100380-00001`,
which has eight children. At depth 0 that role reaches one Award; at depth 1,
nine. Without D5 decided, role 6 has no expected result to test against.

---

## E. SEC-008 activity auditing — the four open questions

The engineering is done and the migration is deliberately held outside the
applied set. `MigrationReleaseScopeTest.v089IsHeldBack()` asserts that
separation, so V089 cannot be applied by the loader even by accident.

| # | Decision | Who | Proposed |
|---|---|---|---|
| E1 | **Retention period** | business + records management | 90 days — *"a number for review, not an approved BU retention policy"* |
| E2 | **Failure policy for routine reads** — does a failed audit write block the read? | security + business | `FAIL_CLOSED` (business direction 2026-11-09, for planning and testing). There is deliberately **no default** |
| E3 | **Privacy defaults** — search terms never stored, no content, actor is the institutional identifier | security + privacy | as documented, pending approval |
| E4 | **Is a person told when their activity is inspected?** | privacy / policy | no channel for it exists today |
| E5 | Apply V089 | security + business | blocked by E1–E4 |

Still not built, and correctly so: **no retention job**. It is operational and
needs E1 decided first — a job that deletes on a period nobody has approved is
worse than no job.

---

## F. Engineering — not waiting on anyone

Listed so the distinction is visible.

| # | Item | State |
|---|---|---|
| F1 | `PARENT_AWARD` grant creation through the CLI | done (PR #123) |
| F2 | Distinct Parent Award grants not treated as duplicates | done (PR #123) |
| F3 | Identity linking while enforcement is off | done (PR #123) |
| F4 | `CONTACT_DERIVATION` PERSON_ID validation, both ends | done (PR #123) |
| F5 | Operator image / task-definition sequencing, three guards | done (PR #123) |
| F6 | Corrected activation runbooks | done (PR #123) |
| F7 | The acceptance matrix, executed | done (PR #123) |
| F8 | Eight-role dry-run plans | done (PR #123) |
| F9 | Server-side authorization of the readiness page | **not started** — recorded as a follow-up, out of scope |

---

## The six approvals, separately

Each is self-contained so it can be granted, refused or deferred on its own.
They are ordered by dependency, not by importance.

---

### Approval 1 — Deploy an API image containing the operator utilities

**What is being approved:** building from a clean detached worktree at the
reviewed commit, pushing to BU non-production ECR, and updating the DEV API
service's container image reference.

**Changes:** the API container image, and nothing else. Every environment
variable, secret, CPU, memory, network setting and IAM role is preserved. No
`APP_AUTHORIZATION_*` variable is added — enforcement is approval 5.

**Why it is first:** the deployed image is `164b242` (revision 87). It has no
`--award-number` flag, so a `PARENT_AWARD` grant cannot be created, and no
`link-identity` profile, so that task definition would start a container with
a profile nothing responds to. Pilot roles 5 and 6 cannot be provisioned at
all until this is done.

**Writes to shared data:** none.

**Rollback:** `aws ecs update-service --task-definition <previous revision>`.
One deployment. Task definition revisions are immutable and retained.

**Follow-up that is part of this approval, not after it:** refresh
`securityReadiness.json` and run `ops/sync-operator-taskdefs.sh`. Leaving
either behind means the operator utilities run different code from the API.

**Approved by:** ____________________  **Date:** __________

---

### Approval 2 — Register the two operator task definitions

**What is being approved:** `aws ecs register-task-definition` for
`research-archive-platform-dev-grant-admin` and
`research-archive-platform-dev-link-identity`.

**Changes:** two new task definition revisions in their own families. The API
service is not touched — these are separate families, which is the point.

**Preconditions, all enforced rather than trusted:**

- `OperatorTaskDefinitionTest` passes (CI);
- `scripts/verify-grant-admin-taskdef.sh` passes for **both** files — it
  compares each against the image the **service is actually running** and
  exits non-zero on a mismatch;
- approval 1 complete, including the sync.

**Writes to shared data:** none. Registering a definition runs nothing.

**Rollback:** `aws ecs deregister-task-definition`. Seconds.

**Approved by:** ____________________  **Date:** __________

---

### Approval 3 — Create the eight pilot identity links

**What is being approved:** running the `link-identity` utility eight times
**with `--confirm`**, writing eight rows to `authz.identity_link`.

**Changes:** eight `ACTIVE` rows with `method = 'ADMIN_VERIFIED'`, plus eight
`IDENTITY_LINKED` audit rows. One link carries a Kuali `PERSON_ID` (role 4);
seven do not.

**What a link does NOT do:** confer access to any record. The utility prints
that on every run. Access requires a grant, which is approval 4.

**Preconditions:** approvals 1 and 2; the eight participants named; each
institutional identifier from BU IAM; each Cognito `sub` from `AdminGetUser`;
role 4's `PERSON_ID` paired by the Award-first procedure in
`PHASE2_PILOT_PLAN.md` section 4b.

**Dry run first.** It is the default, and the proposed runs with their real
output are in [`../runbooks/DEV_PILOT_DRY_RUNS.md`](../runbooks/DEV_PILOT_DRY_RUNS.md).

**Rollback:** `--unlink-link-id=<id> --confirm` per link. This is a **status
change, not a delete** — the row survives as history, which is what makes a
later re-establishment a *re-link*. It does **not** revoke grants.

**The risk that cannot be rolled back:** a wrong `PERSON_ID` pairing on role
4. It resolves cleanly, the evaluator decides correctly, and the wrong records
are reached. Name matching is not verification. The pairing procedure is the
only control.

**Approved by:** ____________________  **Date:** __________

---

### Approval 4 — Issue the six pilot grants

**What is being approved:** running the `grant-admin` utility six times with
`--confirm`, writing six rows to `authz.access_grant`.

**Changes:** six `ACTIVE` grants — one each of `CENTRAL`, `UNIT`,
`UNIT --include-descendants`, `CONTACT_DERIVATION`, `IO` and `PARENT_AWARD` —
plus six `ACCESS_GRANTED` audit rows. **Every grant carries an expiry**
(proposed `2027-06-30`).

**Six, not eight.** Roles 7 (Grant Administrator) and 8 (No-Grant Control)
receive none. If a run names either, stop — that is the pilot's control being
spent.

**Preconditions:** approvals 1–3; the scope values confirmed (unit
`1250000000`, IO account `9500234095`, Award `100380-00001`, PERSON_ID
`1000570`); the expiry date agreed.

**Rollback:** `--revoke-grant-id=<id> --confirm` per grant. A status change;
nothing is deleted and both audit rows survive.

**Expiry is not rollback, and cannot close the pilot.** V087 constrains
*unrevoked* grants, so an expired grant **still occupies its scope** — nobody
can be granted that same scope again until it is revoked. Proven by four tests
in `GrantProvisioningIntegrationTest.ExpiryIsNotRevocation`. Closing the pilot
means revoking.

**Approved by:** ____________________  **Date:** __________

---

### Approval 5 — Activate enforcement

**What is being approved:** adding the `APP_AUTHORIZATION_*` settings to the
DEV API service.

**Changes:** one new task definition revision and one service update. **No
data is written.**

**Preconditions:** approvals 1–4, and **every policy value decided** (section
D). An incomplete policy is refused at startup by
`requireEnforcementSettings()` — deliberately, because a half-configured
policy would deny every record to every user, and a service that starts and
denies everything is harder to diagnose than one that refuses to start and
says why.

`parent-award-depth` in particular: at 0 role 6 reaches one Award, at 1 it
reaches nine. Without it decided, role 6 has no expected result.

**Rollback:** deploy the previous task definition revision. One deployment,
no data touched. **This is the only genuinely reversible step in the whole
sequence**, because it is the only one that writes nothing.

**Stop conditions — roll back before continuing if any occur:** role 7 or 8
can see any record; a denial returns anything other than 404; Global Search
returns 404 rather than 200-with-zero-results for role 8; a Dashboard count
disagrees with the page it summarises.

**Approved by:** ____________________  **Date:** __________

---

### Approval 6 — Apply V089 and enable activity auditing

**What is being approved:** two separable things, and they should be approved
separately even here.

**6a — apply the migration.** Creates `authz.activity_audit` and its monthly
partitions. Applying it **enables nothing**; collection stays off until the
flag is set. Applied only through the ETL loader's `--migrate-only` mode, as
every migration is. Deploying code that depends on a migration does not apply
it — that cost a production incident on 2026-08-14 with V076.

**6b — turn collection on.** Sets
`app.authorization.activity-audit.enabled`.

**Preconditions — four decisions, none of them engineering:**

1. the **retention period** (90 days proposed — *"a number for review, not an
   approved BU retention policy"*);
2. the **failure policy for routine reads** — does a failed audit write block
   the read? `FAIL_CLOSED` is the business direction of 2026-11-09 for
   planning; there is deliberately **no default**;
3. the **privacy defaults** — search terms never stored, no content, actor is
   the institutional identifier;
4. whether a person is **told** when their activity is inspected. No channel
   for that exists today.

**Rollback:** 6b is a service deployment. **6a is forward-only** — a
`DROP TABLE` would be a new migration and a new decision. A rollback script
exists and is rehearsed by `ReleaseMigrationRehearsalIntegrationTest`, but
using it is a decision, not an undo.

**Why it is last:** it is the only step that adds a table, and the only one
whose rollback is not a service deployment. V089 is physically held in
`database/migrations-pending-approval/` so the loader cannot apply it by
accident, and `MigrationReleaseScopeTest.v089IsHeldBack()` asserts that
separation on every CI run.

**Approved by (6a):** ____________________  **Date:** __________

**Approved by (6b):** ____________________  **Date:** __________

---

## The shortest path to testing something

If the goal is to observe one requirement working on DEV with the least
approval surface:

1. **A1–A3** — deploy and register. No data written.
2. **B1–B3** for *one* participant, and **C1** for that one link.
3. **C2** for a single `CENTRAL` grant.
4. **D1–D7** — enforcement on.

That is SEC-001, SEC-002 and SEC-007 observed with one person. It does not
cover SEC-003, SEC-004, SEC-005 or SEC-006, each of which needs its own role —
which is why the pilot is eight people and not one.
