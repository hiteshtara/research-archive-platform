# View Access As — temporary QA simulator

**Status: design for review. Nothing is implemented.** No endpoint, no UI, no
IAM change, no migration, no DEV change. This document exists to be approved or
rejected before any code is written.

**This feature is temporary.** It exists to accelerate authorization acceptance
testing and must be gone before production release. The removal plan and the
release gate are in sections 6 and 7, and they are the reason the architecture
in section 1 is shaped the way it is.

---

## 0. What the repository already provides

Everything in this section was read in the current tree, not assumed.

| Component | Path | Shape |
|---|---|---|
| `RecordAccessEvaluator` | `application/authorization/RecordAccessEvaluator.java` | `decide(AccessOutcome, RecordFacts, List<RecordFacts>) -> AccessDecision` |
| `AccessScopeResolver` | `application/authorization/AccessScopeResolver.java` | `resolve(IdentityResolution) -> AccessOutcome` |
| `RecordScopeSqlBuilder` | `application/authorization/RecordScopeSqlBuilder.java` | `build(AccessOutcome, RecordModule, alias, expandedUnits) -> SqlFragment` |
| `RecordAuthorizationGate` | `application/authorization/RecordAuthorizationGate.java` | the enforcement switch |
| `UnitHierarchy` | `application/authorization/UnitHierarchy.java` | one method: `isSameOrDescendant` |
| In-memory fixtures | `api/src/test/.../authorization/fixtures/` | `InMemoryAccessGrantRepository`, `MapUnitHierarchy`, `SyntheticArchive`, `SyntheticCognitoFederation`, … |

### The finding that shapes this whole design

**`RecordAccessEvaluator` is a pure function.** It performs no I/O, reads no
identity, touches no HTTP, and takes its `AuthorizationPolicy` as a constructor
argument. `AccessScopeResolver` is pure apart from three injected ports
(`AccessGrantRepository`, `ContactRelationships`, a policy `Supplier`).

So requirement 2 — reuse the existing engine — needs **no refactoring at all.**
Both classes can be constructed directly against synthetic inputs. Any design
that proposes extracting an interface, adding a `simulate()` method, or
parameterising production classes is doing unnecessary work and should be
rejected.

### Three traps in the existing code

**1. Do not route the simulator through `RecordAuthorizationGate`.** With
enforcement off it returns `AccessDecision.allow(Set.of())` unconditionally:

```java
if (!properties.isEnforcementEnabled()) {
    return AccessDecision.allow(java.util.Set.of());
}
```

DEV has enforcement off and must keep it off (requirement 1). A simulator built
on the gate would therefore report PERMIT for everything and look like it
worked. The simulator must construct `RecordAccessEvaluator` directly with an
explicit policy — which also satisfies "enforcement enabled only inside
isolated evaluation" without touching `AuthorizationProperties`.

**2. `AccessDecision` is two-valued; the brief asks for three.**

```java
public record AccessDecision(boolean allowed, Set<AccessReason> reasons, String denialCode)
```

There is no INDETERMINATE. It must be *derived* in the simulator from
`denialCode`, and `AccessDecision` must not be changed — it is live production
authorization code and widening it to three values would alter real behaviour.
Mapping in section 3.

**3. The simulator deliberately discloses more than the real API.** Denial is
confidential by design:

```java
/** A record outside the caller's scope. Deliberately a NoSuchElementException
 *  so it becomes the same 404 as a record that does not exist (design D-J). */
public class RecordNotAccessibleException extends NoSuchElementException
```

A simulator that says "DENY — OUT_OF_SCOPE for Award 100012-00002" has
confirmed that record exists. That is its *purpose* and also its central
threat. See section 5.

---

## 1. Temporary architecture

**A test-scope harness, not an application feature.** Not an HTTP endpoint in
the shared API, not a page in the deployed UI.

```
┌─────────────────────────────────────────────────────────────────┐
│ api/src/test/java/.../authorization/viewaccessas/   TEST SCOPE  │
│                                                                  │
│  ViewAccessAsHarness                                            │
│    ├── profile: SyntheticProfile (CENTRAL | UNIT | IO |          │
│    │            RESEARCH_STAFF | NONE)                           │
│    ├── grants:  InMemoryAccessGrantRepository   (never persisted)│
│    ├── policy:  AuthorizationPolicy             (explicit, local)│
│    ├── units:   MapUnitHierarchy  or  JdbcUnitHierarchy          │
│    │                                                            │
│    ├── AccessScopeResolver.resolve(..)  ──► AccessOutcome       │
│    ├── RecordAccessEvaluator.decide(..) ──► AccessDecision      │
│    └── RecordScopeSqlBuilder.build(..)  ──► SqlFragment         │
│                                   (Dashboard / Global Search)   │
└─────────────────────────────────────────────────────────────────┘
                              │ reads, read-only
                              ▼
          ┌──────────────────────────────────────────┐
          │ Disposable PostgreSQL (Testcontainers)   │
          │ all git-tracked migrations applied       │
          │ OR a DEV read-only replica fixture       │
          └──────────────────────────────────────────┘
```

Output is a report artifact — a surefire report plus a generated
Markdown/JSON decision table — not a live page.

### Why test scope, proven

Test-scope classes are not packaged into the deployable artifact. Measured on
the actual built jar (`research-archive-api-0.0.1-SNAPSHOT.jar`, 810 entries,
546 under `BOOT-INF/classes/`):

| Probe | Scope | Entries in jar |
|---|---|---|
| `SyntheticArchive` | test | **0** |
| `InMemoryAccessGrantRepository` | test | **0** |
| `MapUnitHierarchy` | test | **0** |
| `SyntheticCognitoFederation` | test | **0** |
| `RecordAccessEvaluator` | main | 1 |
| `AccessScopeResolver` | main | 2 |

The main-scope probes are the positive control: the search works, and the test
classes genuinely are not there.

**This is the single most important design decision.** It converts every
mandatory-removal requirement from a promise into a property of the build:

- "Verify production images contain no simulator classes or routes" — true by
  construction, and cheaply assertable (section 7).
- "Remove temporary IAM permissions" — none are ever granted; the harness needs
  no AWS access.
- "Destroy temporary QA infrastructure" — a Testcontainers container is
  destroyed when the JVM exits.
- "Do not create permanent schema migrations" — none; the harness applies the
  existing tracked migrations to a throwaway database.
- "Remove simulator configuration and feature flags" — there are none to
  remove.

### Why not the alternatives

| Option | Verdict |
|---|---|
| Endpoint in the shared DEV API behind a flag | **Rejected.** Violates requirement 1. Ships simulator classes in the production image, needs a flag, an endpoint, a QA role, and a removal checklist — and `docs/QA_STATUS_PAGE.md` records what happens to removal checklists (section 7). |
| Separate Spring profile (`@Profile("view-access-as")`) in main scope | **Rejected as the primary.** Still ships the classes and routes; the flag only hides them. Reconsider only if interactive search (requirement 5) proves unusable as a harness — see section 4, stage 3. |
| Separate QA service/infrastructure | **Rejected.** Most expensive option, needs real infrastructure to destroy, and duplicates what Testcontainers already gives for free. |

---

## 2. QA workflow

1. Tester picks a profile and a record set in a small checked-in fixture file
   (`view-access-as-profiles.json`), or passes them as system properties.
2. Runs one Maven command: `mvn test -Dtest=ViewAccessAsHarnessTest`.
3. The harness resolves the synthetic outcome once per profile, then evaluates
   each record, and writes a decision table.
4. Tester reads the table: profile, record, verdict, reason set, denial code,
   and *why* — the policy fields and grant that produced it.
5. The table is attachable to an acceptance-test record as sanitized evidence.

No login, no token, no session, no Cognito interaction of any kind —
requirement 8 is satisfied because there is no authentication surface at all.
Requirement 11 (explicit QA authentication/authorization) is satisfied
differently and more strongly than an application role would: running the
harness requires the ability to check out the repository and run the test
suite, which is already the developer trust boundary.

---

## 3. Test profiles and expected outcomes

### Verdict derivation (no change to `AccessDecision`)

| `AccessDecision` | Simulator verdict |
|---|---|
| `allowed = true` | **PERMIT** (with the `AccessReason` set: CENTRAL / DEPARTMENT / RESEARCH_STAFF_CONTACT / IO_GRANT) |
| `denialCode = OUT_OF_SCOPE` | **DENY** — the engine ran and the record is genuinely outside scope |
| `denialCode = ACCESS_NOT_PROVISIONED` | **DENY** — no link or no active grant |
| `denialCode = POLICY_NOT_CONFIGURED` | **INDETERMINATE** — no decision was made; the policy is incomplete |
| `denialCode = EVALUATION_FAILED` | **INDETERMINATE** — the engine threw |
| `denialCode` ∈ {`AMBIGUOUS_IDENTITY`, `REVOKED_IDENTITY`, `SUSPENDED_IDENTITY`, `REAUTHENTICATION_REQUIRED`} | **DENY** (identity-state denial, reported distinctly from scope denial) |

INDETERMINATE means *the engine could not decide*, and production treats it as
a denial. The simulator must label it separately so a tester never reads
"policy not configured" as "correctly denied" — the distinction the current
two-valued type cannot express.

### Profiles

Initial profile per requirement 4: **UNIT `1242040000`, `includeDescendants =
false`, no other grant, no administrator membership.**

| Profile | Grants | Expected |
|---|---|---|
| P-NONE | none | DENY `ACCESS_NOT_PROVISIONED` everywhere. `AccessScopeResolver` returns `NotProvisioned(NO_ACTIVE_GRANTS)` because `AccessScope.isEmpty()`. |
| P-UNIT-EXACT | UNIT 1242040000, descendants false | PERMIT Award/Proposal/Negotiation with `lead_unit_number = 1242040000`; DENY a child unit; DENY Subaward and IRB (not in `DEPARTMENT_MODULES`). |
| P-UNIT-DESC | UNIT 1242040000, descendants true | as above **plus** descendants, but **only** if policy `departmentMatch = LEAD_UNIT_WITH_DESCENDANTS`. Both the grant flag and the policy must agree — a test matrix must cover all four combinations. |
| P-CENTRAL | CENTRAL | PERMIT every module immediately — `scope.central()` short-circuits before any record fact is read. |
| P-IO | IO value | PERMIT only records whose `ioValues` intersect. **Currently untestable end to end:** `DisabledIoResolver` is wired until the authoritative IO field is confirmed (D-A), so `RecordFacts.ioValues` is empty in practice. The harness can still exercise it with synthetic facts; it must label the result as synthetic. |
| P-STAFF | CONTACT_DERIVATION | PERMIT Award/Proposal only (`CONTACT_MODULES`), and only where a contact row is an employee, matches the `KualiPersonId`, and the role code is in `policy.researchStaffRoles()`. |

**Known gap, not a simulator bug:** `researchStaffRoles` has never been
verified against real Kuali values. An earlier query used non-existent tables;
the real columns are `contact_role_code` and `key_person_project_role`. P-STAFF
results are therefore provisional until those values are confirmed.

### Module coverage

Requirement 6 wants four modules. The pure evaluator covers Award, Proposal and
Negotiation directly from `RecordFacts`. **Subaward is different:** it has no
approved department rule and is central-only in the evaluator, and its real
authorization runs through `subawardFamilyFundingAwardIds` with FAMILY-level
ANY-match. The harness must therefore drive Subaward through
`RecordFactsRepository`, not synthetic facts, or it will test a rule that does
not exist.

Requirement 7 (Dashboard, Global Search) is a different mechanism again —
`RecordScopeSqlBuilder.build(...) -> SqlFragment`, asserted as generated SQL
plus row counts against a seeded disposable database. It belongs in a later
stage (section 4).

---

## 4. Implementation plan

| Stage | Scope | Estimate | Depends on |
|---|---|---|---|
| 1 | Harness skeleton + verdict derivation + P-NONE/P-UNIT-EXACT/P-CENTRAL, Award and Proposal, synthetic facts only | 1–2 days | nothing — all ports already exist |
| 2 | Decision-table report artifact; P-UNIT-DESC four-way matrix; INDETERMINATE cases | 1 day | stage 1 |
| 3 | Real `RecordFactsRepository` against a seeded disposable database; Negotiation and Subaward | 2–3 days | stage 1; Subaward needs the funding-award path |
| 4 | Dashboard and Global Search via `RecordScopeSqlBuilder` | 2–3 days | stage 3 |
| 5 | P-STAFF | blocked | `researchStaffRoles` verified against real Kuali values |
| 6 | P-IO | blocked | D-A (authoritative IO field) |

Stages 1–2 deliver most of the acceptance-testing value and touch no
production code at all. **Interactive record search (requirement 5) is
deferred deliberately** — it is the only requirement that genuinely pulls
toward an application endpoint, and it should not be allowed to drag the whole
design there. Reassess after stage 3 with evidence about whether a
fixture-file-driven harness is actually too slow for testers.

---

## 5. Security boundaries

| # | Requirement | How it holds |
|---|---|---|
| 1 | Isolated, not shared DEV API | test scope; never deployed; `AuthorizationProperties` untouched |
| 8 | Never mint Cognito tokens or impersonate sessions | no authentication surface exists; `IdentityResolution.Mapped` is constructed directly as a value object |
| 9 | Never create persistent grants or identity links | `InMemoryAccessGrantRepository`; no `JdbcAccessGrantRepository`, no `authz.*` writes |
| 12 | Simulation audit separate from real administrative events | nothing is written to `authz.access_audit`; evidence is a build artifact. If an audit record is later wanted, `AdminAuditAction` is `VARCHAR(40)` with **no CHECK constraint**, so a `SIMULATION_*` value needs no migration — but writing to the real audit table would violate requirement 12's spirit, so prefer the artifact. |
| 13 | Destroyable infrastructure | Testcontainers; destroyed on JVM exit |

### The real threat: the simulator's purpose is disclosure

Production hides *whether a record exists* (`RecordNotAccessibleException` →
404). The simulator must say "DENY, OUT_OF_SCOPE, lead unit 1234567890" to be
useful — which confirms existence, names the unit, and is exactly what D-J
forbids the API from doing.

| Threat | Mitigation |
|---|---|
| **T1 — existence disclosure.** A tester learns an out-of-scope record exists. | Requirement 10 is the answer, and it must be read precisely: the simulator may only report on records **the tester is already authorized to see**. The synthetic profile narrows what is *reported as visible*; it must never widen what is *read*. In test scope against a disposable database this is automatic. Against any real DEV data it is not, and that is the bar stage 3 must clear. |
| **T2 — simulation result authorizes a real request.** | No shared state: the simulator holds no session, writes no grant, and the real API reads `AuthorizationProperties` and real grants only. A `SyntheticProfile` object cannot reach request handling because it does not exist in the deployed image. |
| **T3 — the simulator ships.** | Section 7. |
| **T4 — simulator and production disagree.** A harness that reimplements a rule proves nothing. | The harness must call `RecordAccessEvaluator` and `AccessScopeResolver` and must not contain a single authorization rule of its own. Any `if` statement about units, roles or modules inside the harness is a design violation. |
| **T5 — INDETERMINATE read as DENY.** A tester concludes authorization works when the policy was simply unset. | The three-valued mapping in section 3, and a harness assertion that fails loudly when a whole run is INDETERMINATE. |
| **T6 — stale evidence.** A decision table attached to an acceptance record outlives the policy that produced it. | Every artifact stamps the policy fields, the grant set, the commit SHA and the migration high-water mark. |

### How this differs from impersonation

Impersonation answers "what would *this user* see?" and requires their
identity — a session, a token, or a link. This answers "what would *any* holder
of this grant set see?" and requires no identity at all: the synthetic profile
is a grant shape, not a person. Nothing in the flow can authenticate as anyone,
because nothing in the flow authenticates.

---

## 6. Complete removal checklist

Stages 1–4 as designed introduce **only** files under
`api/src/test/java/edu/bu/archive/application/authorization/viewaccessas/`,
one fixture file, and this document. The checklist is therefore short by
design:

- [ ] Delete `api/src/test/java/.../authorization/viewaccessas/`
- [ ] Delete the profile fixture file
- [ ] Delete generated artifacts from CI (never committed)
- [ ] Keep this document, reclassified as history, plus sanitized evidence
- [ ] Run the authorization regression suites and confirm the count returns to
      its pre-simulator baseline with zero failures

Nothing else exists to remove: no UI, no endpoint, no flag, no IAM, no
migration, no infrastructure.

**If stage 3 or a later reversal introduces main-scope code**, this checklist
is no longer sufficient and the release gate in section 7 becomes load-bearing
rather than confirmatory. That is the moment to re-approve the design, not the
moment to extend the checklist.

---

## 7. Production release gate

### Why a checklist is not enough

`docs/QA_STATUS_PAGE.md` already documents a temporary QA feature with a
hand-written removal list, and records what went wrong:

> a branch cut after an unwanted change landed still carries that change
> unless it removes it, however cleanly the branch merges

and

> **Ancestry alone does not describe running behaviour.**

A removal checklist describes `main`. A release describes an *image*. The gate
must assert against the artifact.

### The gate

A test in the existing `edu.bu.archive.ops` package — alongside
`GrantAdminTaskDefinitionTest`, which already guards a one-off task definition
the same way — asserting on the built jar:

```
for every entry in BOOT-INF/classes/:
    fail if the path contains "viewaccessas"
    fail if the path contains "ViewAccessAs"
assert at least one known main-scope authorization class IS present
```

The final assertion is the positive control: without it, a gate that silently
fails to open the jar passes. That failure mode has bitten this project before
— three tooling false negatives while verifying jar contents were recorded as
"treat a tool error as *no result*, never a negative result."

Wire it into the `api-unit` CI job, which runs on every pull request and push
to `main`, and into `ops/deploy-api.sh` before the ECR push, so a deploy from a
dirty tree cannot bypass CI.

### Release-readiness verification

Verify against the image's own source tree, not `git merge-base`:

```
git show <release-sha>:api/src/test/java/edu/bu/archive/ops/ | grep -c ViewAccessAsAbsentTest   # expect 1 - the gate is present
docker run --rm --entrypoint sh <image> -c 'jar tf /app/app.jar | grep -c -i viewaccessas'      # expect 0
```

Run the jar inspection **in the image**, not on a host-built jar — the earlier
tooling lesson applies: `unzip` and `strings` are absent from the JRE base
image and macOS `strings` misreads `.class` files, so a host check can produce
a false clean.

---

## 8. Recommendation

Approve stages 1–2 as a test-scope harness. They are low cost, touch no
production code, satisfy every mandatory-removal requirement structurally
rather than procedurally, and deliver the authorization acceptance evidence
this is actually for.

Defer the interactive-search requirement until there is evidence a harness is
insufficient, because it is the one requirement that would force simulator code
into the production image — and at that point the removal guarantee stops being
a property of the build and becomes a promise someone has to keep.

---

## Appendix A — acceptance-test matrix

Every row asserts a verdict the engine produces, not one the harness computes.
`policy.departmentMatch` is abbreviated EXACT / DESC.

| # | Profile | Policy | Record | Expected | Asserts |
|---|---|---|---|---|---|
| V1 | P-NONE | EXACT | Award in 1242040000 | DENY `ACCESS_NOT_PROVISIONED` | empty scope is not "no filter" |
| V2 | P-UNIT-EXACT | EXACT | Award, lead unit 1242040000 | PERMIT {DEPARTMENT} | the baseline profile |
| V3 | P-UNIT-EXACT | EXACT | Award, another unit | DENY `OUT_OF_SCOPE` | |
| V4 | P-UNIT-EXACT | EXACT | Award, child of 1242040000 | DENY `OUT_OF_SCOPE` | descendants off means off |
| V5 | P-UNIT-DESC | EXACT | Award, child unit | **DENY** | grant flag alone is insufficient — policy must also allow it |
| V6 | P-UNIT-EXACT | DESC | Award, child unit | **DENY** | policy alone is insufficient — the grant must also opt in |
| V7 | P-UNIT-DESC | DESC | Award, child unit | PERMIT {DEPARTMENT} | both must agree (D4) |
| V8 | P-CENTRAL | EXACT | any module, incl. IRB and Subaward | PERMIT {CENTRAL} | short-circuits before facts are read |
| V9 | P-UNIT-EXACT | EXACT | Subaward in an in-scope family | DENY | no approved department rule for Subaward |
| V10 | P-UNIT-EXACT | EXACT | IRB record | DENY | a UNIT grant reaches no IRB |
| V11 | P-UNIT-EXACT | EXACT | Proposal, lead unit 1242040000 | PERMIT {DEPARTMENT} | |
| V12 | P-UNIT-EXACT | EXACT | Negotiation, lead unit 1242040000 | PERMIT {DEPARTMENT} | |
| V13 | P-UNIT-EXACT | EXACT | Negotiation with no lead unit (57 exist in DEV) | DENY | absent fact denies, never permits |
| V14 | P-UNIT-EXACT | **none** | Award in 1242040000 | **INDETERMINATE** `POLICY_NOT_CONFIGURED` | must not read as a correct DENY |
| V15 | P-UNIT-EXACT | EXACT | family spanning in- and out-of-scope versions, PER_VERSION | per-version verdicts differ | partial family is a real state |
| V16 | same | FAMILY_WIDE | same family | PERMIT for every version | `familyOf` widening applies |
| V17 | P-UNIT-EXACT | FAMILY_WIDE | family versions mixed with **another family's** versions passed in | PERMIT must not widen | `familyOf` filters by module + familyKey |
| V18 | P-STAFF | EXACT + roles | Award with a matching employee contact | PERMIT {RESEARCH_STAFF_CONTACT} | blocked on verified role values |
| V19 | P-STAFF | EXACT + roles | Award with a non-employee contact of the same person | DENY | `contact.employee()` is required |
| V20 | P-IO | EXACT | record with intersecting `ioValues` | PERMIT {IO_GRANT} | synthetic only — `DisabledIoResolver` is wired |
| V21 | P-UNIT-EXACT | EXACT | evaluator throws | **INDETERMINATE** `EVALUATION_FAILED` | failure denies, loudly |
| V22 | 8 profiles concurrently | EXACT | same record set | each profile's verdicts independent and stable | no shared mutable state in the engine |
| V23 | P-UNIT-EXACT | EXACT | Dashboard counts | counts and pages from the same `WHERE` (D2) | stage 4 |
| V24 | P-UNIT-EXACT | EXACT | Global Search | scoped; IRB absent, not zero (D9) | stage 4 |
| V25 | any | any | whole run | artifact stamps policy, grants, SHA, migration level | no stale evidence (T6) |

V5, V6 and V7 are the rows most likely to be got wrong, and the reason the
four-way matrix is explicit: `includeDescendants` on the grant and
`LEAD_UNIT_WITH_DESCENDANTS` on the policy are **both** required, and either
one alone must deny.

V22 is the concurrency requirement. It asserts a property of the engine —
`RecordAccessEvaluator` holds only a policy and a hierarchy, both immutable —
rather than of a database, so it is a cheap test that would catch any future
introduction of shared mutable state.

## Appendix B — what the tester reads

The stage-2 artifact. Plain text so it attaches to an acceptance record and
diffs cleanly between runs.

```
View Access As — decision table
profile   P-UNIT-EXACT   UNIT 1242040000, descendants=false
policy    versionScope=PER_VERSION  departmentMatch=EXACT_LEAD_UNIT
          researchStaffRoles=[PI, COI, MPI, KP]  contactDerivation=EXPLICIT_GRANT
scope     central=false  units=[1242040000/exact]  ios=[]  contactPerson=none
source    commit f2157b5   migrations through V087

MODULE      RECORD              VERDICT        REASONS      WHY
Award       100012-00002        PERMIT         DEPARTMENT   lead_unit_number = 1242040000
Award       100045-00001        DENY           -            OUT_OF_SCOPE: lead unit 1242050000 not granted
Award       100087-00003        DENY           -            OUT_OF_SCOPE: descendants not enabled on grant
Proposal    00098765            PERMIT         DEPARTMENT   lead_unit_number = 1242040000
Negotiation 4411                DENY           -            OUT_OF_SCOPE: no lead unit recorded
Subaward    3595                DENY           -            OUT_OF_SCOPE: no department rule for SUBAWARD

22 records   8 PERMIT   14 DENY   0 INDETERMINATE
```

The WHY column is the deliverable. A bare PERMIT/DENY tells a tester nothing
they could not have guessed; naming the fact that decided it is what makes the
run evidence.

## Appendix C — deferred interactive option (NOT recommended yet)

Sketched only so the trade-off is decidable. **Do not build this without
re-approving the design**, because it is the variant that puts simulator code
in the production image and turns section 6 from structural into procedural.

```
┌───────────────────────────────────────────────────────────────┐
│  View Access As                      ⚠ DEV/QA ONLY — temporary│
├───────────────────────────────────────────────────────────────┤
│  Profile  ( ) None   (•) UNIT   ( ) CENTRAL   ( ) IO          │
│                                  ( ) Research Staff           │
│                                                               │
│  Unit number  [1242040000        ]  [ ] include descendants   │
│  Policy       versionScope [PER_VERSION ▾]                    │
│               departmentMatch [EXACT_LEAD_UNIT ▾]             │
├───────────────────────────────────────────────────────────────┤
│  Record   Module [Award ▾]   Identifier [100012-00002      ]   │
│                                            [ Evaluate ]        │
├───────────────────────────────────────────────────────────────┤
│  ╔═══════════════════════════════════════════════════════════╗│
│  ║  PERMIT                                      DEPARTMENT   ║│
│  ║  lead_unit_number = 1242040000 matches the UNIT grant     ║│
│  ║  exactly. Descendants were not consulted.                 ║│
│  ╚═══════════════════════════════════════════════════════════╝│
│                                                               │
│  Simulation only. This does not change what you or anyone     │
│  else can see, and it grants nothing.                         │
└───────────────────────────────────────────────────────────────┘
```

Three constraints any such UI must satisfy, each one a reason it is deferred:

1. **It may only accept identifiers the tester can already read.** Evaluating
   an arbitrary identifier would turn the page into an existence oracle (T1) —
   the precise thing `RecordNotAccessibleException` exists to prevent. So the
   record picker must itself be scoped by the tester's real authorization,
   which means the page needs the real engine *and* the simulated one in the
   same request, with no possibility of confusing them.
2. **Every file it introduces must be listed** in section 6 and asserted absent
   by section 7's gate — including the route, the navigation entry, the icon
   import and the test-script entry. `docs/QA_STATUS_PAGE.md`'s removal list
   runs to eleven items for a read-only status page; this would be longer.
3. **It needs the QA role of requirement 11**, which does not exist. ARCHIVE_ADMIN
   is not it: an administrator holds no data grant by design (B14), so reusing
   that role would conflate two unrelated authorities.
