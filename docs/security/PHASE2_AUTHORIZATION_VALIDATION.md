# Phase 2 — real-user authorization validation (DEV)

Performed 2026-11-09 against DEV: API task definition 85, V088 applied, Amplify
job 181, enforcement **OFF**, activity auditing **OFF**, Production untouched.

**Nothing was written.** No grant created, modified or revoked; no privilege
granted; no task definition registered; no service updated; no enforcement or
audit flag changed.

---

## 1. Authorization inventory

### How it was read

The approved grant-administration path **does not exist in DEV** — no
`grant-admin` task definition is registered (the registered families are
`api`, `dbtunnel` and `loader` only). The tunnel's documented role,
`archive_analyst`, has no access to the `authz` schema, which is why these
figures could not be measured on 2026-11-03 or 2026-11-09.

Read instead with the `archive_api` credential, which holds
`SELECT ON ALL TABLES IN SCHEMA authz` (`archive_api_role.sql:113`), over the
existing `dbtunnel` task, under the approved safeguards:

| Safeguard | Evidence |
|---|---|
| AWS account confirmed | `770203350335` before anything else |
| Endpoint confirmed | `research-archive-platform-dev-postgres…`, PostgreSQL 17.9 |
| Credential handling | fetched from Secrets Manager into memory; never printed, never persisted |
| Dedicated, non-pooled connection | one `psycopg` connection, closed at the end |
| Read-only bound **at connect** | `options=-c default_transaction_read_only=on` in the connection string |
| Session verified | `SHOW default_transaction_read_only` → `on` |
| Transaction verified | explicit `BEGIN READ ONLY`, `SHOW transaction_read_only` → `on` |
| Queries | a fixed predefined list, `authz.*` only; no procedure, no dynamic SQL, no DDL, no DML |
| Teardown | connection closed, tunnel stopped, dbtunnel cluster back to **0 tasks** |

### Aggregate counts

| Measure | Count |
|---|---|
| Active administrators | **1** |
| Revoked administrators | 0 |
| Active identity links | **1** (ADMIN_VERIFIED; 0 AUTO_VERIFIED) |
| Revoked identity links | 0 |
| Distinct linked identities | 1 |
| **Active grants** | **0** |
| **Expired grants** | **0** |
| **Revoked grants** | **0** |
| Pending grants (valid_from in the future) | 0 |
| Total grant rows | **0** |
| CENTRAL grants | 0 |
| UNIT grants | 0 |
| IO grants | 0 |
| PARENT_AWARD grants | 0 |
| CONTACT_DERIVATION grants | 0 |
| Suspended persons (`person_status`) | 0 (table empty) |
| Relink approvals | 0 |
| `access_audit` rows | 2 |

States are mutually exclusive by construction: revoked wins over expired,
expired over active, and a grant whose `valid_from` is in the future is counted
as pending rather than active. With a total of 0 rows there is nothing to
double count.

### Schema state

| | |
|---|---|
| `access_grant`, `identity_link`, `admin_role_member`, `person_status`, `relink_approval`, `kim_principal`, `principal_crosswalk`, `access_audit` | all present |
| `access_grant.award_number` (V088) | **present** — the migration is live |
| `authz.activity_audit` (V089) | **absent** — correctly still held back |

### Missing mappings

| Check | Result |
|---|---|
| Active grantees with no active identity link | **0** (there are no grants) |
| Active administrators with no active identity link | **0** — the one administrator is linked |
| Duplicate active grant scopes | **0** — V087's uniqueness invariant holds in live data |

**The real gap is upstream.** `authz.kim_principal` and
`authz.principal_crosswalk` are **both empty (0 rows)**. These are the tables
that map a BU person to the institutional identifier every grant is keyed on,
and they are ETL-loaded. Until they carry data, an administrator issuing a
grant has no supported way to look up the institutional identifier of the
person they intend to grant to — they would be typing an identifier from
somewhere else and trusting it. That is the blocking prerequisite for a pilot,
and it is not a code change.

---

## 2. Authorization validation — isolated testing

Enforcement is OFF in DEV, so the evaluator cannot be exercised through the
live API without turning it on. It was exercised instead against throwaway
PostgreSQL carrying the committed migrations, using the real production
classes.

### A gap found and closed

`AwardRecordAuthorizationIntegrationTest` pins the SQL predicate against the
Java evaluator for every scope — except IO. Its fixture says so:

> `IO is not exercised here: the SQL side uses IoSqlStrategy.NONE, so the Java
> side must resolve no IO values either, or the two halves of the equivalence
> test would be comparing different rules.`

Production wires neither of those. `AuthorizationConfiguration:150-159` supplies
the real `AwardAccountNumberIoResolver` and the real `AwardAccountNumberIoSql`.
**The configuration that actually ships was the one configuration nothing
compared**, and SEC-004 is one of the capabilities the readiness page reports as
deployed.

`IoAccessAgreementIntegrationTest` (new, 6 tests) closes it. It runs the real
resolver and the real SQL strategy against Award rows whose account numbers are
deliberately awkward — exact, whitespace-padded, different, empty string, NULL —
and asserts the two halves select the same set.

**They agree.** No defect was found. The padded account number matches (both
sides `TRIM`), and the empty-string and NULL rows are reachable by neither.
That last point matters: a predicate written with `COALESCE` would have swept
in every Award that was never given an account number.

### Results

| Suite | Tests | Result |
|---|---|---|
| Award end-to-end (`AwardRecordAuthorizationIntegrationTest`) | 36 | PASS |
| Proposal end-to-end | 40 | PASS |
| Negotiation end-to-end | 34 | PASS |
| Subaward end-to-end | 59 | PASS |
| **IO agreement (new)** | **6** | **PASS** |
| Authorization-focused selection (evaluator, resolver, SQL builder, gate, parent award, contact derivation, SEC-003 AI, grant provisioning, bulk assignment, identity) | 418 | PASS |
| **Full API suite** (201 classes, all Testcontainers suites) | **1822** | **PASS — 0 failures, 0 errors** |

The full suite was 1816 before this work; the six added are the new IO
agreement tests. ERROR lines in that run are deliberate log output from the
SEC-008 failure-policy tests asserting what a failed audit write does; they are
assertions, not failures.

### Matrix

| # | Scenario | Verdict | Evidence |
|---|---|---|---|
| 1 | CENTRAL sees everything, including records with no unit or contact | **PASS** | `centralSeesEverythingIncludingRecordsWithNoUnitOrContact`, `decision3_centralSeesArchiveWideTotals` |
| 2 | UNIT reaches only its own unit, and the count agrees | **PASS** | `departmentScopeReturnsOnlyItsOwnUnitAndTheCountAgrees`, `decision4_departmentTotalsDescribeOnlyTheirOwnRecords` |
| 3 | UNIT with descendants reaches child units; without, reaches nothing | **PASS** | `parentUnitWithDescendantsReachesTheChildUnit`, `parentUnitWithoutDescendantsReachesNothing` |
| 4 | IO reaches matching Awards only — evaluator and SQL agree | **PASS** | `theIoPredicateAndTheEvaluatorAgreeOnEveryAwardRow` (new) |
| 5 | IO does not travel to Proposal, Subaward or Negotiation | **PASS** | `ioDoesNotTravelToTheOtherModules` (new) |
| 6 | PARENT_AWARD expands at the configured depth, never across families | **PASS** | `ParentAwardAccessTest` (22), `AwardHierarchyExpansionIntegrationTest` (11) |
| 7 | PARENT_AWARD decides Awards, and only Awards | **PASS** | "The evaluator decides Awards, and only Awards" (6) |
| 8 | SEC-003 contact access is version-specific | **PASS** | `RecordAccessEvaluatorTest$Sec003ContactAccessIsAlwaysVersionSpecific` (20), `theContactPredicateSelectsOnlyTheVersionTheyAreListedOn` |
| 9 | A contact on a superseded version does not reach the current record | **PASS** | `aContactOnASupersededAwardVersionDoesNotReachTheNegotiationAssociatedWithIt` |
| 10 | An unapproved contact role grants nothing | **PASS** | `contactScopeIgnoresAListingWhoseRoleIsNotApproved` |
| 11 | A user with no grant sees nothing and leaks no count | **PASS** | `aPersonWhoIsNotAContactAnywhereSeesNothing`, `anUnresolvedOrDeniedIdentitySeesNothingAndLeaksNoCount` |
| 12 | An unprovisioned caller gets an explicit problem, not an empty archive | **PASS** | `decision1_anUnprovisionedCallerGetsAnExplicitProblemNotAnEmptyArchive` |
| 13 | Revoking takes effect on the next request, with no cache to clear | **PASS** | `revokingAGrantTakesEffectOnTheNextRequestWithNoCacheToClear` |
| 14 | Expired and not-yet-valid grants are ignored at scope resolution | **PASS** | `AccessScopeResolverTest.expiredAndNotYetValidGrantsAreIgnored` |
| 15 | A scope emptied by expiry or revocation denies every row rather than matching all | **PASS** | `anEmptiedIoScopeDeniesEverything` (new) |
| 16 | An empty scope denies rather than matching everything | **PASS** | `aScopeWithNoUsableClauseDeniesRatherThanMatchingEverything` |
| 17 | Evaluator and rendered SQL agree on every Award version | **PASS** | `theRenderedPredicateAndTheJavaEvaluatorAgreeOnEveryAwardVersion` |
| 18 | Counts always match what paging can actually reach | **PASS** | `theCountAlwaysMatchesWhatPagingCanActuallyReach` (Award), `theCountAlwaysMatchesWhatPagingCanReach` (Negotiation) |
| 19 | An unauthorized record is indistinguishable from one that does not exist | **PASS** | `anUnauthorizedRecordIsIndistinguishableFromOneThatDoesNotExist`, `denialIsIndistinguishableFromAbsence` |
| 20 | A direct record URL is denied for an out-of-scope Award | **PASS** | `aDirectRecordUrlIsDeniedForAnOutOfScopeAward` |
| 21 | Exact document-number callout is withheld out of scope (no probing) | **PASS** | `theExactDocumentCalloutIsWithheldForAnOutOfScopeAward`, `aContactScopedCallerCannotProbeForDocumentNumbersOutsideTheirAwards` |
| 22 | Cross-module search is refused for a scoped caller, not leaked | **PASS** | `crossModuleSearchIsRefusedForAScopedCallerRatherThanLeakingAwardRows` |
| 23 | Historical versions: a family-spanning response needs every version visible | **PASS** | `aFamilySpanningResponseRequiresEveryVersionToBeVisible`, `familyCompletenessIsSatisfiedWhenEveryVersionIsVisible` |
| 24 | Unauthorized hierarchy relatives are omitted, not stubbed | **PASS** | `decision5_unauthorizedHierarchyRelativesAreOmittedNotStubbed` |
| 25 | Subaward inherits from its funding Awards (1 of N authorized is enough) | **PASS** | `scenario1`–`scenario6` |
| 26 | A Subaward with no funding Award is denied | **PASS** | `scenario6_aSubawardWithNoFundingAwardIsDenied` |
| 27 | Negotiation reaches through its Award association, by award number not document number | **PASS** | `anAwardAssociatedNegotiationIsReachableThroughItsAward`, `theAwardPathResolvesAwardNumberAndNeverTheWorkflowDocumentNumber` |
| 28 | A document-number collision cannot acquire Award access | **PASS** | `aTypeNoneNegotiationCannotAcquireAwardAccessThroughADocumentNumberCollision` |
| 29 | AI endpoints refuse a partially visible Award family | **PASS** | `Sec003AiPartialFamilyAccessTest` (5) |
| 30 | Every Award-keyed service method refuses a denied caller | **PASS** | `everyAwardKeyedServiceMethodRefusesADeniedCaller` |
| 31 | Live-data validation against real DEV records with grants | **BLOCKED** | 0 grants exist and enforcement is OFF; validating through the live API requires both, which is the pilot itself |

**No FAIL.** The single BLOCKED row is blocked by design, not by a defect.

---

## 3. Proposed pilot — by role

Cognito users were **not enumerated** and no identity was invented. This
account holds `AdminGetUser` but not `ListUsers`, and the instruction was
explicit. Each row below needs a named person supplied by the business; the
identifier itself should be handed over out of band and never committed.

| Role | Grant to issue | Intended scope | Expected experience | Business approval required |
|---|---|---|---|---|
| **Central Administrator** | `CENTRAL` | Every record in every module | Archive-wide totals; unchanged from today | Who may hold archive-wide read. Note the existing administrator is an *admin-role* member, which is the right to **administer grants** — not itself a data grant |
| **Department Administrator** | `UNIT`, `include_descendants` per decision | One lead unit, optionally its sub-units | Only that unit's Awards/Proposals/Subawards/Negotiations; counts scoped to match | Which unit; and whether sub-units are included — D4 settled `LEAD_UNIT_WITH_DESCENDANTS` as the policy default, but the per-grant flag is still a choice |
| **Research Staff** | none — derived | Only the Award *versions* they are listed on, in an approved role | Narrow and version-specific; no access to earlier versions they were not on | Which contact role codes count as Research Staff (`researchStaffRoles` has **no default**) |
| **No-grant control** | none | Nothing | Must see an explicit "access not provisioned" response, never an empty archive that looks like a broken site | None — this is the control that proves denial is distinguishable from emptiness |

Prerequisite for all four: an **active identity link** per participant. One
exists today. `kim_principal` and `principal_crosswalk` are empty, so there is
currently no supported lookup from a BU person to an institutional identifier.

---

## 4. Enforcement readiness

Determined from the code and pinned by tests, not by switching anything on.

### The finding that matters most

`RecordAuthorizationGate.decide()` (lines 44-52):

```java
if (!properties.isEnforcementEnabled()) return AccessDecision.allow(Set.of());
var policy = properties.policy();
if (policy.isEmpty()) return AccessDecision.deny(POLICY_NOT_CONFIGURED);
```

`AuthorizationProperties.policy()` returns empty when **any** strategy is
missing, and the strategies have **no defaults**. DEV's task definition 85
carries no `APP_AUTHORIZATION_*` variable of any kind.

**So setting `enforcement-enabled=true` on its own denies every record to every
user — including a Central Administrator — because the denial happens before
any grant is consulted.** The application does not refuse to start; it starts
and denies. Pinned by
`RecordAuthorizationGateTest.thePolicyHasNoDefaultsAndAnEnforcedGateWithoutOneDeniesEverything`.

The gate computes `Status.ENFORCED_POLICY_NOT_CONFIGURED` for exactly this
case, but **nothing in the application consumes that status** — no endpoint, no
health indicator, no log line. An operator who flipped the flag would get no
signal other than a dark archive.

### Impact of enabling enforcement today

| Question | Answer with 0 grants and no policy | Answer with grants and a policy |
|---|---|---|
| Hide records from legitimate users? | **Yes — from everyone, immediately.** Denial precedes grant evaluation | Only outside the grantee's scope |
| Change Dashboard counts? | **Yes — to zero** for every signed-in user | Scoped; counts match what paging reaches |
| Restrict historical Award versions? | Yes — all of them | A family-spanning response requires every version visible, else refused |
| Affect Proposal / Negotiation / Subaward navigation? | Yes — all refused | Scoped per module; Subaward inherits from funding Awards, Negotiation through its Award association |
| Affect attachment access? | **No change.** The attachment gate is an independent Cognito group check (`ROLE_ArchiveAttachmentViewer`) already in force today | Still independent; both gates apply |
| Affect AI features? | Yes — refused with everything else | AI refuses a partially visible Award family rather than summarising part of it |

Attachment access is worth stating plainly: it is **already enforcing**, today,
with enforcement off. Turning record-level enforcement on neither relaxes nor
tightens it.

---

## 5. Outstanding business decisions

| # | Decision | Blocks |
|---|---|---|
| 1 | `versionScope` — FAMILY_WIDE or version-scoped | **Enforcement.** No default; unset means deny-all |
| 2 | `departmentMatch` — D4 settled `LEAD_UNIT_WITH_DESCENDANTS` as policy; confirm it is the value to set | **Enforcement** |
| 3 | `contactDerivation` | **Enforcement** |
| 4 | `researchStaffRoles` — which contact role codes count | **Enforcement** and the Research Staff pilot row |
| 5 | SEC-005 depth (1, maximum 10) | Nothing today — no PARENT_AWARD grant exists |
| 6 | SEC-005 broader inheritance to Subawards/Negotiations | Nothing — deliberately restricted |
| 7 | SEC-003 Subaward consequence | Nothing — currently the stricter reading |
| 8 | Fund Center definition | Nothing — `Fund Center` is the lead unit padded for SAP, so UNIT already covers it |
| 9 | SEC-008 retention, failure policies, privacy defaults, `GRANT INSERT` | V089 and audit collection |
| 10 | Who holds the first grants, and whether they expire | **The pilot** |
| 11 | Loading `kim_principal` / `principal_crosswalk` | **The pilot** — no supported identifier lookup without them |

---

## 6. Safe pilot activation — proposed, not executed

Ordered so that the archive is never dark and every step is individually
reversible.

1. **Load the identity source.** Populate `authz.kim_principal` and
   `authz.principal_crosswalk` via the ETL. Until this exists, every grant is
   keyed on an identifier nobody can verify. Read-only to the archive; no
   authorization behaviour changes.
2. **Confirm the four policy values** (decisions 1-4 above). These are business
   answers, not configuration yet.
3. **Register the grant-admin task definition** from the version-controlled
   JSON in `ops/`, and verify it with `scripts/verify-grant-admin-taskdef.sh`
   and `GrantAdminTaskDefinitionTest`. This creates the administrative path;
   it does not enforce anything.
4. **Create identity links** for the four pilot participants — one write each,
   each audited, each individually revocable.
5. **Issue the pilot grants** (CENTRAL, UNIT; Research Staff is derived and
   needs none; the control user gets none). Still invisible: enforcement is off,
   so a grant changes nothing yet. Verify by reading them back.
6. **Set the policy variables and the enforcement flag in the same task
   definition revision.** This is the one irreversible-feeling step and the
   reason for the ordering: enforcement without policy is a dark archive, so
   the two must never be separate revisions.
7. **Verify immediately, in this order:** the no-grant control user sees an
   explicit "access not provisioned" response (not an empty archive); the
   Central Administrator sees unchanged Dashboard totals; the Department
   Administrator sees scoped totals that match their own paging; Research Staff
   sees only their listed versions.
8. **Stop at the first surprise** and roll back before investigating.

---

## 7. Rollback

| Step undone | How | Effect |
|---|---|---|
| Enforcement + policy (step 6) | Update the ECS service to the previous task definition revision | Instant return to today's behaviour; grants become inert again. **One command, no database change.** |
| Grants (step 5) | Revoke through the grant-admin CLI — never `DELETE` | Grant row kept as audit evidence, `revoked_at` set, effective on the next request with no cache to clear |
| Identity links (step 4) | Revoke, same way | Participant falls back to "not provisioned" |
| Task definition (step 3) | Deregister | Removes the administrative path |
| Identity source (step 1) | Nothing to undo | Read-only reference data |

The API image itself does not change in any of this, so there is no image
rollback and no database migration to reverse. Enforcement is a configuration
flag, and configuration is a task-definition revision.
