# Authorization testing strategy

Recorded 2026-11-11 from an audit of the tree at `341ff74`. Read in the
repository, not inferred: every claim below names the file, test or command it
came from.

**Nothing in this document changes code, CI, deployment configuration, or the
frozen Thursday window.** It exists to be reviewed.

---

## 1. Decisions recorded

| # | Decision |
|---|---|
| 1 | Thursday's approved DEV enforcement window is **unchanged** |
| 2 | **No** production backdoor and **no** user impersonation feature |
| 3 | The existing authorization test suites are the **primary regression mechanism** |
| 4 | PR **#107** (test-scope QA harness) — **recommended for approval**, subject to code review and proof that no simulator class or endpoint enters the deployable artifact |
| 5 | PRs **#113** and **#119** — **recommended for closure or supersession** if the harness already covers them. **Not closed yet** |
| 6 | The misrouted CI suites are documented here with a proposed fix. **CI is not changed** |
| 7 | Traceability matrix for tests 1–13, in §7 |
| 8 | The simulated-vs-real distinction, in §6 |
| 9 | Four authorization-scoped modules confirmed; Protocols and IRB excluded, with reasons, in §5 |
| 10 | No change to CI, application code, deployment configuration or PR #132 before Thursday |

No new grant, identity, impersonation endpoint or administrative privilege is
authorized. Production is untouched.

---

## 2. Administrator access as it exists today

Twelve endpoints under `/api/v1/admin` — four reads and eight writes — all
gated on `app.authorization.enforcement-enabled` and all routed through
`actor()`, whose first statement is `requireEnforcementEnabled()` and which
then resolves the caller's institutional identity. `ArchiveAdminService` calls
`requireAdministrator(actor)` independently at the service layer.

Because DEV enforcement is **off**, that whole tree currently returns 404. The
only working administrative path today is the three operator CLIs —
`grant-admin`, `link-identity`, `bootstrap-admin` — each behind its own Spring
profile and run as a one-off ECS task.

**Being an administrator confers no access to any record.** Matrix cases 20
and 20b assert exactly this, and both are automated.

Full detail, including the eight write endpoints and their test coverage, is in
`ADMIN_API_EXPOSURE_DURING_ENFORCEMENT.md`.

---

## 3. There is no impersonation capability, and no near-miss

Audited on `main`:

| Check | Result |
|---|---|
| `ViewAccessAs` / `view-access-as` / `impersonat*` in `api/src` or `ui/src` | **zero matches** — documents only |
| `actAs`, `onBehalfOf`, `assumeIdentity`, `effectiveIdentity`, `overrideIdentity`, `X-Act-As`-style headers, `sudo`, `runAs` | **zero matches** |
| Implementations of `CurrentIdentityProvider` | exactly one — `JwtCurrentIdentityProvider` |

Identity comes from the validated JWT and from nothing else. There is no code
path by which any caller, administrator included, can present as another
identity.

### `PERMIT_ALL` is a null object, and it fails closed

`AwardRecordAccess.PERMIT_ALL` exists and `ExplorerService` and
`AwardContactService` fall back to it when no `AwardRecordAccess` bean is
available. That is not a bypass, because both then call
`requireEnforceableWiring`, which refuses to start the application:

```java
if (settings != null && settings.isEnforcementEnabled()) {
    throw new IllegalStateException(
            "app.authorization.enforcement-enabled is true but no " + portName
                    + " implementation is available, so record authorization would "
                    + "silently permit every record. Refusing to start. …");
}
```

So enforcement-on with a permissive port is a startup failure, not a silent
hole. **The honest caveat:** with enforcement **off** — DEV's current and
approved state — there is no record authorization at all, for anyone. That is
the designed state, not a defect, but it should never be described as
"authorization is working in DEV".

### What the earlier plans became

`VIEW_ACCESS_AS_TEMPORARY_QA_DESIGN.md` (497 lines, "design for review,
nothing is implemented") plus three unmerged draft PRs: **#107** test-scope QA
harness, **#113** interactive dev-only demonstration, **#119** design for an
authorization preview. The design's own recommendation is to approve a
test-scope harness and defer the interactive variant, because that is the one
form that would put simulator code into the production image.

### Can an administrator simulate a researcher without their password?

Not by impersonation — nothing can authenticate as anyone else. Two real
options:

1. **Provisioning.** An administrator creates a grant matching the
   researcher's scope and observes the result. This *changes authorization
   state* and is audited. It is honest, but it is provisioning, not
   simulation.
2. **The test-scope harness.** It answers "what would any holder of this grant
   set see?" rather than "what would *this user* see". A synthetic profile is
   a grant shape, not a person, and nothing in the flow authenticates.

---

## 4. What already exists, measured

**53 authorization test files holding roughly 740 test methods** — about 263
in 14 `@Tag("database")` suites and about 474 pure unit tests.

A note on the counts, because it bit this audit: a `grep -c '@Test'` and a
regex matching `@Test` + method signature disagree by a few percent (22 versus
20 on the acceptance pack, where **21** actually executed). Static counts below
are approximate and labelled as such; only figures from an actual run are
stated exactly.

The centrepiece is `SecurityAcceptancePackIntegrationTest`, whose
`@DisplayName` is *"Security acceptance pack (AUTHORIZATION_ACCEPTANCE_MATRIX)"*
— an automated implementation of the acceptance matrix itself, the same matrix
whose cases 2 and 3 are Thursday's paired gate.

Executed locally on 2026-11-11 against disposable Testcontainers PostgreSQL:
**21 of 21 test methods ran, 0 failures, 0 errors, exit 0.** Declared and
executed method names were diffed to confirm none was silently skipped — see
the surefire caveat in §8.

Cases it asserts: 1–5 (CENTRAL, UNIT ± descendants, transitive expansion),
12–14 (contact derivation with and without a PERSON_ID, IO), 16–20b (no-grant
control, revoked grant, suspended identity, unlinked identity, administrator
confers nothing), 21–24 (PARENT_AWARD: self, depth 1, another family, depth 0).
That is **18 of the 24** matrix cases.

The remaining module cases (6–11, 15) are covered by the four per-module
suites — Award, Proposal, Negotiation and Subaward — holding roughly 169 tests
between them. `Sec003VersionScopeAgreementIntegrationTest` (about 6) covers
PER_VERSION versus FAMILY_WIDE agreement.

### Fixtures available for synthetic-identity testing

`SyntheticCognitoFederation`, `SyntheticCognitoProfile`,
`SyntheticSamlAssertion`, `SyntheticArchive`, `MapUnitHierarchy`,
`InMemoryAccessGrantRepository`, `InMemoryIdentityLinkRepository`,
`InMemoryAdminRoleRepository`, `InMemoryAdminAuditRepository`,
`InMemoryAdminIdentityRepository`, `InMemoryEnrollmentStore`,
`RecordingAdminAuditSink`.

### Why no refactoring is required

`RecordAccessEvaluator` is a pure function: no I/O, no identity, no HTTP, and
its `AuthorizationPolicy` arrives as a constructor argument. `AccessScopeResolver`
is pure apart from three injected ports. Both can be constructed directly
against synthetic inputs, which is why the design rejects any proposal to
extract an interface or add a `simulate()` method to production classes.

---

## 5. The four authorization-scoped modules, and the two exclusions

**Scoped: Award, Proposal, Negotiation, Subaward.** Each has a
`*RecordAuthorizationIntegrationTest` and a row shape in
`RecordScopeSqlBuilder`.

**Protocols — excluded because the domain does not exist.** The Protocol
Archive was removed in full: API, UI, ETL loaders, Oracle SQL, and a
forward-only schema-removal migration, `V032__drop_protocol_archive.sql`. There
is no `protocol` package under `application/`. `V022`–`V024` created those
tables historically and `V032` dropped them. Protocol authorization tests
cannot be written because there is nothing to authorize. Any test plan naming
Protocols should be corrected rather than satisfied.

**IRB — excluded because authorization removes it rather than scoping it.**
Design D9 removes the Dashboard's IRB fields for **every** caller once
enforcement is on — "absent, not zero" — and the UI strips `module: "IRB"`
rows and IRB failure messages client-side before anything renders. IRB is
therefore outside the record-authorization model, not a gap in it.

So a test plan covering "five modules plus Protocols" is really **four scoped
modules**, one deliberately excluded domain (IRB) and one that no longer
exists (Protocols).

---

## 6. Simulated authorization decisions are not authentication or deployment validation

This distinction is the reason the simulator cannot replace a live window, and
it is worth stating precisely because the two are easy to conflate.

**What a simulated decision establishes.** That given an `AccessOutcome` and a
set of `RecordFacts`, the engine returns the expected `AccessDecision`; and
that `RecordScopeSqlBuilder` emits a predicate selecting the expected rows.
These are statements about *authorization logic*.

**What it cannot establish.** Six things, each of which is a statement about
*the running system* rather than the logic:

| Only live enforcement proves | Why simulation cannot |
|---|---|
| The deployed revision binds the six `APP_AUTHORIZATION_*` variables and actually turns enforcement on | The harness constructs its policy directly and deliberately bypasses `AuthorizationProperties` |
| The real Shibboleth → Cognito → identity link → grant chain resolves end to end | No authentication surface exists; `IdentityResolution.Mapped` is a value object |
| `auth_time` freshness and the 12-hour rule | No token exists to carry an `auth_time` |
| The administrative API becomes reachable when the flag flips | Depends on the real flag in the real container |
| ALB and ECS health, and the fail-fast startup guard, in the deployed image | Not a deployed image |
| What a real user's browser renders on denial | No UI in scope |

There is also a trap the design names explicitly and this document repeats
because it would invalidate a whole run: **a simulator must not be built on
`RecordAuthorizationGate`**, which returns `AccessDecision.allow(Set.of())`
unconditionally while enforcement is off. Such a simulator would report PERMIT
for everything and look like it worked.

---

## 7. Traceability: Thursday's tests 1–13

### An enumeration gap, stated first

**Tests 1–10 are not individually enumerated in any committed document.** Only
tests 11, 12 and 13 are described, in
`DEV_ENFORCEMENT_WINDOW_2026_11_12.md`. The 1–10 list exists in working
session state. This is a traceability defect in its own right: an acceptance
test whose cases live outside version control cannot be audited or repeated.
Recommendation: enumerate 1–10 in the runbook **after** Thursday, since the
runbook is frozen until then.

The mapping below is therefore **by category** for 1–10 and exact for 11–13.

| Thursday test | Automated coverage | Still needs live DEV | Gap |
|---|---|---|---|
| **1–10** scoped read behaviour across the four modules, Dashboard and Global Search | Matrix cases 1–5 and 6–15, across `SecurityAcceptancePackIntegrationTest` (21) and the four `*RecordAuthorizationIntegrationTest` suites (~169) | Config binding; the real token chain; UI rendering | the 1–10 enumeration itself |
| **11** AI summary / questions | `Sec003AiPartialFamilyAccessTest` (~5) | — | **NOT EXECUTED** on DEV: AI disabled, endpoint 404s. Not a pass and not a failure |
| **12** No-grant control | **Matrix case 16**, "a linked identity with no grant sees nothing (the No-Grant Control)" — automated and passing | Real native Cognito sign-in; whether a native token carries `auth_time` | the *authentication* half is live-only |
| **13** Revocation takes effect | **Matrix case 17**, "a revoked grant denies what it used to permit" — automated and passing | That revocation through the real admin API persists against real `authz` tables | — |
| **Paired gate** 212137-00001 → 200, 114471-00001 → 404 | **Matrix cases 2 and 3 are this gate**, automated | That enforcement is actually ON in revision 92 | — |
| **D1 / D2** PER_VERSION discriminator | `Sec003VersionScopeAgreementIntegrationTest` (~6) | Per-version behaviour against real DEV data and the real family-completeness rule | — |

**Conclusion.** The *authorization logic* behind every one of Thursday's tests
is already automated and green (roughly 740 authorization tests, with the
acceptance pack's 21 verified by execution on 2026-11-11). What Thursday adds, and the only thing it
adds, is the six live-only assurances in §6. That is a real and currently
unmet need — so the window is justified — but it is not matrix coverage, and
future windows should not be spent re-proving matrix cases.

---

## 8. CI routing defect

Seven authorization suites, holding roughly **117 tests**, are named in
**neither** CI job.

`ci.yml` has two API jobs. The unit job runs `mvn test` with a list of
**name-based exclusions**; the database job runs an explicit **inclusion**
list with a guard step, *"no live database, AWS or remote Docker
configuration"*. There is no `excludedGroups` in `api/pom.xml`, so
`@Tag("database")` is not used for routing at all — only these two hand-written
lists are.

A suite absent from both lists is therefore **not skipped**: default surefire
includes match `*Test`, so it runs in the **unit** job, without the database
job's guard.

| Suite | Tag | Tests (approx.) |
|---|---|---|
| `SecurityAcceptancePackIntegrationTest` | `@Tag("database")` | 21 (measured) |
| `ProposalSubawardLexicalRelevanceIntegrationTest` | `@Tag("database")` | ~38 |
| `AdminVerifiedPilotPathIntegrationTest` | `@Tag("database")` | ~13 |
| `IoAccessAgreementIntegrationTest` | `@Tag("database")` | ~6 |
| `Sec003VersionScopeAgreementIntegrationTest` | `@Tag("database")` | ~5 |
| `PilotDryRunPlanIntegrationTest` | `@Tag("database")` | ~4 |
| `IdentityLinkProvisioningIntegrationTest` | **none** | ~31 |

The last row is a different and worse defect: it is `@Testcontainers` with a
`PostgreSQLContainer` field but carries **no `@Tag("database")`**, so any
future tag-based filtering would miss it and it is not declared as needing a
database at all.

### A surefire caveat that makes this easy to get wrong

Every test in these suites lives in a JUnit 5 `@Nested` class. Running
`mvn test -Dtest=SecurityAcceptancePackIntegrationTest` reports:

```
Tests run: 0, Failures: 0, Errors: 0
```

Zero, not an error — nested classes are not matched by the outer class name,
and `-Dtest='Outer*'` picks up only the outer class's own methods. The nested
names must be listed explicitly:

```
-Dtest='Outer,Outer$Nested1,Outer$Nested2,…'
```

This is how a run covering nothing can be mistaken for a clean pass, and it
happened once during this audit before being caught. Any verification of these
suites must diff **declared** against **executed** method names rather than
trusting the summary line. Tag-based routing (fix item 3 below) also removes
this hazard, because `-Dgroups=database` selects nested classes correctly.

### Proposed fix, not applied

1. Add `@Tag("database")` to `IdentityLinkProvisioningIntegrationTest`.
2. Move all seven into the database job's inclusion list and add them to the
   unit job's exclusion list.
3. Then replace both hand-written lists with tag-based routing —
   `-DexcludedGroups=database` for the unit job and `-Dgroups=database` for the
   database job — so a new suite is routed by its own declaration and cannot be
   forgotten. This is the step that prevents the defect recurring; items 1–2
   only fix today's instance.
4. Re-run both jobs and record the before/after test counts.

Not applied: no CI change before Thursday.

---

## 9. PR dispositions

| PR | What it is | Recommendation |
|---|---|---|
| **#107** | Temporary View Access As QA harness, test scope | **Recommend approval**, subject to code review and to the deployable-artifact check below. Not merged |
| **#113** | Interactive View Access As demonstration, dev-only | **Recommend closing or superseding** if the harness covers it. **Not closed.** It is the variant that would place simulator code in the production image |
| **#119** | Design: authorization preview, not impersonation | **Recommend closing or superseding** as design now captured here and in the QA design document. **Not closed** |

### The condition on #107

Approval requires proof that **no simulator class or endpoint enters the
deployable application**. The check must be run **inside the image**, not on a
host-built jar: the JRE base image has no `unzip` or `strings`, and macOS
`strings` misreads `.class` files, so a host check can report a false clean.
Extract the jar and byte-search in Python, and assert both that the
`viewaccessas` package is absent from `BOOT-INF/classes/` and that a known
production authorization class *is* present as a positive control.

---

## 10. Outstanding decisions

| # | Decision | Owner |
|---|---|---|
| D-1 | Apply the CI routing fix, including tag-based routing | after Thursday |
| D-2 | Enumerate Thursday's tests 1–10 in the runbook | after Thursday |
| D-3 | Does the #107 harness add coverage beyond the existing 760 tests? | after Thursday |
| D-4 | Close or supersede #113 and #119 | after D-3 |
| D-5 | Business decision **2.1** — PER_VERSION vs FAMILY_WIDE as permanent policy | **OPEN**; Thursday produces evidence, not closure |
| D-6 | Whether a permanent "View As User" support feature is ever wanted | not recommended; see §3 |

SEC-003 is closed and independent of 2.1: contact-derived access is
version-specific unconditionally, whatever `versionScope` says.
