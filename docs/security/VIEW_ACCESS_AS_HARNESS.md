# View Access As — temporary QA harness (operator notes)

**Temporary. Must be removed before production release.** The approved design
is `VIEW_ACCESS_AS_TEMPORARY_QA_DESIGN.md` (merged in PR #106); this document
covers running it and removing it.

## How to run it

```
cd api
mvn test -Dtest=ViewAccessAsHarnessTest
```

The decision table is printed and written to
`api/target/view-access-as/decision-table.txt`. It is regenerated per run and
never committed.

To run only the table:

```
mvn test -Dtest='ViewAccessAsHarnessTest#theDecisionTable'
```

> **A bare `-Dtest=ClassName` can silently skip the outer class's own test
> methods** when the class has `@Nested` children — observed here: a filtered
> run reported 20 passing tests with `theDecisionTable` contributing 0. It runs
> correctly under a full `mvn test` (confirmed in the surefire XML, which lists
> all 20 cases in one report). Count the tests, or read the report; do not
> infer from BUILD SUCCESS.

## Every file introduced

All code is **test scope**. Nothing is under `src/main`, so nothing is
packaged.

| File | Purpose |
|---|---|
| `api/src/test/java/edu/bu/archive/application/authorization/viewaccessas/SyntheticProfile.java` | a grant shape to evaluate against; builder for CENTRAL / UNIT / IO / Research Staff / revoked / expired / not-yet-valid |
| `…/viewaccessas/Verdict.java` | the three-valued PERMIT / DENY / INDETERMINATE reading of `AccessDecision`, and the Rule string |
| `…/viewaccessas/ViewAccessAsHarness.java` | composes the real `AccessScopeResolver` and real `RecordAccessEvaluator`; owns no authorization rule |
| `…/viewaccessas/DecisionTable.java` | renders and writes the `Record \| Profile \| Expected \| Actual \| Rule \| PASS/FAIL` table |
| `…/viewaccessas/ViewAccessAsHarnessTest.java` | the profile scenarios and the decision-table run |
| `api/src/test/java/edu/bu/archive/ops/ViewAccessAsAbsentFromJarTest.java` | the release gate: jar, compiled main classes, and source tree |
| `docs/security/VIEW_ACCESS_AS_HARNESS.md` | this document |

Generated, never committed: `api/target/view-access-as/decision-table.txt`.

**Not introduced:** no endpoint, no controller, no UI file, no route, no
Spring profile, no feature flag, no configuration property, no IAM policy, no
Terraform, no migration, no infrastructure, no AWS call.

## The release gate

`ViewAccessAsAbsentFromJarTest` has three assertions, each with a positive
control so that a broken inspection cannot pass as a clean one:

1. **The built jar** carries no entry matching `viewaccessas`/`ViewAccessAs`,
   and *does* carry `RecordAccessEvaluator.class` and
   `AccessScopeResolver.class`.
2. **`target/classes`** — what will be packaged — contains no such file, and
   does contain `RecordAccessEvaluator.class`.
3. **`src/main/java`** contains no such file.

### Why three and not one

Assertion 1 alone has a blind spot, and it is not theoretical — it was
measured. During `mvn package` the test phase runs **before** repackaging, so
the jar under inspection is the *previous* build's. A planted
`src/main/.../ViewAccessAsLeak.java` was caught by assertions 2 and 3 and
**passed** assertion 1, because the jar on disk predated the leak. Assertion 2
is therefore the one that actually guards CI.

Assertion 2 matches regular files only. Maven's incremental compile deletes an
orphaned `.class` but leaves its directory behind, and an empty directory
ships no code. The jar check matches directory entries too, so it is the
stricter of the two — if they ever disagree, that is why.

### Verified behaviour

| Check | Result |
|---|---|
| Clean tree | 3 tests pass, 0 skipped |
| Planted `src/main` leak | **2 of 3 fail**, naming the offending file |
| Fresh jar, independent inspection | 803 entries, **0** matching the simulator, positive controls present |

The gate runs in the `api-unit` CI job on every pull request and every push to
`main`.

**One step remains:** wiring the same check into `ops/deploy-api.sh` before the
ECR push, so a deploy from a dirty working tree cannot bypass CI. It is not in
this change because that would put the gate's only non-test-scope footprint in
the production deploy path; it should be a separate, deliberate change.

## Removal

```
git rm -r api/src/test/java/edu/bu/archive/application/authorization/viewaccessas
git rm api/src/test/java/edu/bu/archive/ops/ViewAccessAsAbsentFromJarTest.java
git rm docs/security/VIEW_ACCESS_AS_HARNESS.md
```

Then run the authorization suites and confirm the test count drops by exactly
23 (20 harness + 3 gate) with zero failures.

Keep `VIEW_ACCESS_AS_TEMPORARY_QA_DESIGN.md`, reclassified as history, and any
sanitized decision tables attached to acceptance records.

Nothing else exists to remove — no flag to unset, no permission to revoke, no
infrastructure to destroy, no migration to reverse. That is the whole point of
the test-scope choice.

## What this is not

It does not authenticate, and cannot. The `IdentityResolution.Mapped` it builds
carries a synthetic issuer (`simulation://view-access-as/not-a-user-pool`) that
no Cognito pool could issue and nothing signs; `SecurityConfiguration` would
reject it. No token is minted, parsed or substituted.

It answers "what would *any* holder of this grant shape see?", not "what does
*this user* see". The profile is a grant shape, not a person — so there is
nothing to impersonate.

Grants are `AccessGrant` value objects in an in-memory repository. Nothing
reaches `authz.access_grant` or `authz.identity_link`, so a profile cannot
outlive the JVM, and nothing it produces can authorize a real API request.

Records are synthetic `RecordFacts`. **No archive row is read**, which is why
no DEV record detail — restricted or otherwise — can appear in the output.
Driving the harness from real data via `RecordFactsRepository` is a later stage
and must first satisfy the disclosure bound in the design document: the
simulator may only report on records the tester is already authorized to read.
