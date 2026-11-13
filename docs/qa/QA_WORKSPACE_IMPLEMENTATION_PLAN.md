# QA workspace — hardening issue and implementation plan

Recorded 2026-11-11. **Nothing implemented.** No migration, schema, endpoint,
UI change, IAM policy, Cognito group or grant. Thursday's DEV authorization
test is frozen; revision 91 and staged revision 92 are untouched. Production
untouched, auditing OFF, V089 unapplied.

## Decisions recorded

| # | Decision |
|---|---|
| **U1** | **All four** QA/security JSON sources move behind authenticated endpoints; build-time imports removed from the public bundle; repository files preserved as controlled source data |
| **U2** | Production QA result visibility is a **blocker**. No production deployment until record-existence disclosure is addressed and separately approved |
| **U3** | In production, catalog cases containing real record identifiers must be scoped to the viewer's authorized records, or replaced with approved non-sensitive fixtures |
| **U4** | `ArchiveQaLead` **not created**, no members assigned. Membership requires separate explicit approval |
| **U5** | Retention provisionally 12 months after test-cycle closure, pending BU records-management approval. **No automatic deletion** |
| **U6** | **DEV-only** for phase 1. Production QA deployment **not approved** |

---

# Part 1 — SEC-HARDENING-1: public security-assurance exposure

**Severity: low for data, moderate for assurance disclosure. Not a breach.**

Four JSON files are imported at build time in `ui/src` and all four ship in
full in the publicly fetchable bundle (verified 2026-11-11, no credentials):
`qaSnapshot.json` (66.5 KB), `securityRequirements.json` (25.5 KB),
`securityReadiness.json` (23.3 KB), `qaOpenFindings.json` (3.4 KB).

No credential, no personal data, no real archive-issued identity, no unit
number. The Cognito pool ID is necessarily client-side. What *is* exposed:
image digests, ECS revisions, internal schema vocabulary, the
`ArchiveAttachmentViewer` group name, and — the material item — a readable
inventory of where this system's security assurance is weakest.

## The accuracy review (requirement 6)

Requirement 6 asks for accuracy, not concealment. Four coverage-gap statements
are publicly readable. **Three are stale.**

| Statement (abridged) | Requirement | Verdict | Evidence |
|---|---|---|---|
| "No automated coverage yet. The resolver and the SQL clause exist; no test drives an end-to-end SAP-account grant." | SEC-004 | **STALE** | `IoAccessAgreementIntegrationTest` — titled *"SEC-004 IO access: the SQL predicate and the evaluator agree"*, `@Tag("database")`, six named tests including *"the live grant really does reach rows — the empty-scope test is not passing vacuously"*, exercising the **real** resolver and **real** SQL strategy against committed migrations. Plus acceptance-matrix case 14 drives an `IO` grant on an account number to a visibility decision. The requirement's own status block already says `testsPassed: 'yes'`, contradicting its prose |
| "No test asserts the absence of a write path, and no record exists of the database grants the API role actually holds." | SEC-009 | **STALE — both halves** | `ArchiveReadOnlyPrivilegeIntegrationTest` (13 tests) and `ArchiveApiRoleIntegrationTest` (18 tests), **both in the CI database job**; and `docs/security/ARCHIVE_API_DATABASE_ROLE.md` records the grants table by table. The status block says `testsPassed: 'no'`, which is now also wrong |
| "No test case was supplied… Proposed: evidence that no write path reaches the archive schema, plus the database grants the API role holds." | SEC-009 | **PARTLY STALE** | the *proposed* evidence now exists. What remains genuinely absent is an **agreed acceptance criterion**, which is a different claim |
| "No test case was supplied… Acceptance cannot be agreed until the scope question below is answered." | SEC-008 | **ACCURATE** | `decisionApproved: 'open'`. SEC-008 has a real open business decision. Leave as is |

**Two internal contradictions found alongside.** SEC-004 and SEC-009 each carry
a `status.testsPassed` value that disagrees with their own `tests` prose. The
existing `securityReadinessConsistency.test.mjs` does not catch this, because
it checks consistency *between files* and not between a requirement's status
flags and its narrative. A consistency rule for that is worth adding.

**Remediation order matters.** Correct the three stale statements *before or
with* the move behind authentication. Hiding them first would satisfy the
letter of U1 while leaving inaccurate claims in the record — which is exactly
what requirement 6 forbids.

---

# Part 2 — Implementation plan

## A dependency that shapes the phasing

**U1 needs no database.** The four JSON files can be packaged as API resources
and served from authenticated endpoints with no schema, no migration and no
grant. Only *results* need the `qa` schema.

So hardening ships first and independently, and the schema work — the part
carrying migration risk — is deferred behind it.

## Migration facts this plan must respect

- **`V090` is the next free number.** `V088` is the highest on `main`.
  **`V089` is reserved**: it is the planned SEC-008 `authz.activity_audit`
  migration, specified in `SEC_008_ACTIVITY_AUDIT.md`, which records it as
  *"not done anywhere"* — it exists on no branch. Taking V089 would collide
  when SEC-008 is finally written.
- **Spring does not apply migrations.** `spring.flyway.enabled: false`; the
  Python ETL applies them and tracks `public.schema_migration`.
- **Deploying API code that needs a migration does not apply it.** The V076
  incident: code was deployed, the migration was not applied, and the API 500'd
  until applied separately. Order is therefore **migration → verify → deploy
  API**, never the reverse.
- **The loader image must be able to see the migration.** Confirm
  `git merge-base --is-ancestor <migration-commit> <loader-image-commit>`
  before assuming `--migrate-only` finds anything.
- **Migrations are forward-only.** `V032__drop_protocol_archive.sql` is the
  precedent: there are no down-migrations, so schema rollback is a *new* drop
  migration, not a reversal.

### A grant hazard to avoid, learned from SEC-008

`archive_api_role.sql` grants `SELECT ON ALL TABLES IN SCHEMA authz`, so
re-running the role script silently re-grants read access that a migration had
revoked. **My earlier design used the same blanket form for `qa`** — that is a
latent trap. Corrected: grant `SELECT` **table by table** in the role script,
never `ON ALL TABLES`, so a future table is not auto-readable and a re-run
cannot widen access.

## Phases

### Phase 0 — accuracy corrections (no code)

Correct the three stale statements; add the status-versus-prose consistency
rule. Data-file and test changes only.

**Tests:** existing UI consistency suite, plus the new rule.
**Rollback:** revert the commit.

### Phase 1 — authenticated data endpoints (U1), DEV only

Package the four JSON files as API resources. Add authenticated read endpoints.
**Remove the build-time imports from `ui/src`** — leaving them would keep the
public copy alive. Strip digests and ECS revisions from what is published. Keep
the repository files as controlled source data.

**No schema, no migration, no grant.**

**Tests:** controller tests asserting 401 unauthenticated and 200
authenticated; a UI test that the page renders from fetched data; **and the
bundle audit below.**
**Rollback:** revert API and UI; no data exists to lose.

### Phase 2 — `qa` schema and results (DEV only)

`V090__qa_workspace.sql`: schema `qa`; `test_cycle`, `test_case`,
`test_result`, `assignment`, `defect`; the append-only trigger; `test_case`
seeded from `qaSnapshot.json`. Role-script grants added **table by table**.
Then the write endpoints and server-side visibility scoping.

**Dependency order:** apply `V090` via the ETL loader → verify in
`public.schema_migration` → confirm the role grants → **then** deploy the API.

**Tests:** migration rehearsal (`ReleaseMigrationRehearsalIntegrationTest` is
the precedent); the **CI parity test in the API suite** asserting exact
test-ID matching between `qaSnapshot.json` and the V090 seed, failing on
missing, duplicate or unexpected IDs; append-only trigger tests proving
`UPDATE` and `DELETE` are refused; authorization tests proving an ordinary
tester cannot read another tester's results and that a non-QA-lead delegated
submission is refused; and a test proving `ArchiveQaLead` confers **no**
archive-record access.

**Rollback:** revert the API to the previous task definition (immediate, and
the schema is then simply unused). If the schema must go, that is a **new
forward migration** dropping it — never an in-place reversal.

### Phase 3 — dashboard panels (DEV only)

Assignments, progress, pass/fail/blocked counts, untested modules **including
zero-case modules**, defects, unresolved decisions. Counts computed under the
same server-side filter as row reads, each naming its denominator.

**Rollback:** revert the UI; data untouched.

### Phase 4 — production (NOT APPROVED)

Blocked on **U2** and **U3**. Requires its own security decision and approval.

## Post-implementation bundle audit (requirement 1)

Run after phase 1, and it must be run against the **deployed** bundle, not a
local build:

1. `curl` the deployed `index.html`, extract the asset path, fetch the bundle.
2. Assert **zero** occurrences of distinctive long string literals from each of
   the four JSON files — the same sampling method used to find the exposure.
3. Assert zero occurrences of `sha256:`, ECS revision strings, and the four
   coverage-gap statements.
4. Assert the page still works authenticated, so the data moved rather than
   vanished.
5. Record the bundle size before and after.

Step 2 matters because a negative check that never ran reads identically to a
clean result — the same failure mode as a surefire run that matched no tests.
The audit must assert on strings **known to be present today**, so it would
fail if pointed at the wrong artifact.

---

## Still open

| # | Item |
|---|---|
| U2 | production result visibility — blocker, own security decision |
| U3 | production catalog scoping or approved non-sensitive fixtures |
| U4 | `ArchiveQaLead` initial members — explicit approval |
| U5 | retention — BU records-management approval |
| U6 | production deployment — not approved |
| New | should the SEC-004 and SEC-009 statements be corrected in place, or corrected **and** annotated with when they became stale? Annotating preserves the audit trail of what was believed when |

## Not authorized

No implementation, migration, schema, endpoint, UI change, IAM policy, Cognito
group, grant or role. No invitation. No deletion or retention job. DEV
unchanged. PR #135 stays draft and PRs #131–#134 remain unmerged.
