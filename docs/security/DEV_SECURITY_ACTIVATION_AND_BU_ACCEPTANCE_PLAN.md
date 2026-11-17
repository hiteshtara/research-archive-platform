# DEV security activation and BU acceptance plan

**Status:** plan only. Nothing in this document has been executed. Every step
marked **APPROVAL** needs a separate, explicit approval from the owner, at the
exact commit, image digest or command named. An approval for one step does
not carry over to the next.

**Scope:** shared DEV only. **Production is out of scope** and is not touched by
anything here.

**Supersedes the ordering in** [`DEV_ACTIVATION_APPROVAL_CHECKLIST.md`](DEV_ACTIVATION_APPROVAL_CHECKLIST.md)
(2026-11-10). That checklist predates the H1–H5 fixes, V090 and the
CENTRAL-approval gate, and its order no longer works (see §2). Its per-step
detail is still correct and is referenced below rather than repeated. The
eight-role population in [`DEV_SECURITY_PILOT_PACKAGE.md`](DEV_SECURITY_PILOT_PACKAGE.md)
and the policy values in [`PHASE2_PILOT_PLAN.md`](PHASE2_PILOT_PLAN.md) still
apply.

**Baseline:** `main` at `1c3f0c8` (PR #166 merge), 2026-11-17.

---

## 1. Where things stand

| Item | State on 2026-11-17 |
|---|---|
| BU acceptance criteria | **25 / 47 DONE.** This number changes only on live DEV evidence |
| DEV API | ECS `research-archive-platform-dev-api:105`, image `sha256:b0648a8b…1415`, **pushed 2026-11-13** |
| Record-level enforcement | **OFF** (`APP_AUTHORIZATION_ENFORCEMENT_ENABLED` not set) |
| Administrator reads / writes | reads **ON**, writes **OFF** |
| Activity auditing | **OFF**, and V089 is not applied |
| Privilege approvals | V090 **not applied**. Phase 1 merged (#166) with no live caller |
| Archive Explorer | **OFF** in DEV (API and UI), since 2026-11-17 |
| IRB release exclusion | merged (#159), **not activated** (`APP_IRB_API_ENABLED` defaults to `true`) |
| UI (Amplify `main`) | job 216, `1c3f0c8`, SUCCEED |

### The fact that sets the order

**The DEV API image predates every security fix merged on 2026-11-17.** The
fixes in #158, #160, #161, #162, #164, #165 and #166 are in `main` but not in
the image DEV is running:

- H1–H3: T&M IDOR, negotiation association leak, SAP payload leak (#164);
- H5: AI evidence-search excerpts (#165);
- N-161-1/2: relink escalation and unbound approvals (#161);
- the audit fixes (#160).

With enforcement OFF, none of these leaks is reachable in a way that matters,
because every authenticated user can already see every record. **Turning
enforcement on against image `b0648a8b` would test code known to be
defective.** That would fail criteria for reasons already fixed. Every
activation step therefore starts with deploying current `main`.

---

## 2. Dependencies the earlier checklist did not have

1. **CENTRAL grants are refused until phase 2 ships.**
   - `ArchiveAdminService` (#161, decision D1) refuses every CENTRAL grant with
     `ApprovalRequiredException`, through HTTP, bulk and the `grant-admin`
     command-line tool alike.
   - The approval workflow that would satisfy that check exists (#166), but
     nothing calls it yet.
   - So **pilot role 1 (Central User) and scenario S1 cannot be provisioned**
     until V090 is applied, phase 2 is merged and a second administrator is
     seated.
   - That blocks the 9 Central User criteria.
2. **Moving a migration into `database/migrations/` schedules it for
   application.**
   - The ETL runner applies every pending file it finds.
   - The nightly Subaward sync calls it unconditionally. It does so from the
     loader image registered in its task definition, so the risk arises only
     once that image is rebuilt from a commit containing the moved file.
   - Release V089 and V090 **in one PR, moved together**. That avoids the
     version gap that releasing V090 alone would create (see
     [`../../database/migrations-pending-approval/README.md`](../../database/migrations-pending-approval/README.md)).
   - Then apply them deliberately with `--migrate-only`, before the next
     rebuilt loader image reaches the nightly schedule.
3. **Enforcement in shared DEV affects every DEV user,** not only the pilot.
   - Anyone without a link and grant sees an "access not provisioned" response
     for the whole window.
   - Existing DEV users need notice. A standing grant for them also needs
     CENTRAL, which is (1) again.

---

## 3. The plan: steps, in dependency order

Each step lists what changes, what it writes to shared data, and how it is
rolled back. **Steps 1–3 change no access for anyone.**

### Step 0. Read-only pre-checks (no approval needed; nothing changes)

- `aws sts get-caller-identity` returns `770203350335`.
- DEV has `public.schema_migration` at V088 and no `authz.privilege_approval`
  or activity-audit tables. Use the read-only path in
  [`../runbooks/`](../runbooks/), not a new access route.
- List the existing `authz.identity_link`, `authz.access_grant` and
  `authz.archive_admin` rows, with counts and scopes only. This is the
  baseline for rollback.
- Record the pre-enforcement Dashboard totals
  ([`PHASE2_PILOT_PLAN.md`](PHASE2_PILOT_PLAN.md) §3).
- Check that the operator task definitions (`dev-grant-admin`,
  `dev-link-identity`) are registered and match the service image
  (`scripts/verify-grant-admin-taskdef.sh`).

### Step 1. Deploy current `main` to DEV, with every flag unchanged. **APPROVAL A1**

- **Change:** build from a clean detached worktree at the approved commit, push
  to BU non-production ECR and register revision 106.
  - The new revision copies 105 exactly except for the image.
  - `APP_EXPLORER_ENABLED=false` is kept. No `APP_AUTHORIZATION_*` variable is
    added.
  - Use `buildx --platform linux/amd64`.
- **Same approval:** refresh the operator task definitions to the same image
  (`ops/sync-operator-taskdefs.sh`), so the command-line tools run the same
  code as the API.
- **Writes to shared data:** none. No migration ships in this image, because V089
  and V090 are still held back.
- **Verify:**
  - `/api/v1/me/capabilities` still shows `enforcementEnabled=false` and
    `administrationWritesAvailable=false`;
  - Dashboard totals match Step 0;
  - the Explorer is absent;
  - the smoke tests pass.
- **Rollback:** `update-service` back to `:105` (one deployment).

### Step 2. Activate the IRB release exclusion. **APPROVAL A2**

- **Change:** a new revision adding `APP_IRB_API_ENABLED=false`.
- **Effect:**
  - `/api/irb/**` and `/api/investigators` are not registered;
  - IRB leaves Documents and Global Search;
  - the IRB Dashboard figures are omitted.
- **Writes:** none.
- **Verify:**
  - IRB routes return 404;
  - search returns no IRB results;
  - the other Dashboard figures are unchanged.
- **Rollback:** the previous revision.
- This can be combined with Step 1 if you prefer one deployment. It is listed
  separately because it removes a visible feature.

### Step 3. Phase 2 engineering: wire approvals into administrator writes. **Code PR, then merge APPROVAL A3**

- **Scope:**
  - call `requireConsumedApproval` before each D1 action in
    `ArchiveAdminService`: appointing an administrator, CENTRAL grants,
    self-grants, widening and relinking a privileged identity;
  - add the approval request, approve and list endpoints;
  - add a schema precondition that refuses to start if the approval table is
    missing while writes are enabled.
- **Process:**
  - draft PR, then independent QA, then CI, then owner merge at the exact head;
  - **No change to DEV:** it ships with the next approved image (A1-style
    redeploy).
- **Writes:** none.

### Step 4. Release and apply V089 and V090. **APPROVAL A4**

**Decisions needed before approval:** audit retention, the routine-read
failure policy, privacy defaults and the `GRANT INSERT` (see §5).

1. One PR `git mv`s both files into `database/migrations/`, raises the
   `MigrationReleaseScopeTest` ceiling to V090, and records the approval in the
   commit message.
2. Build the loader image from that merge commit. Confirm it descends from the
   merge with `git merge-base --is-ancestor`.
3. Run one `--migrate-only` ECS task.
4. Then run the two grant scripts, `archive_api_activity_audit_grant.sql` and
   `archive_api_privilege_approval_grant.sql`. Both refuse to run before their
   migration exists.

- **Writes:** schema and privileges only. No data rows.
- **Verify:**
  - `schema_migration` shows V089 and V090;
  - `archive_api` has INSERT/SELECT only on the audit table (no UPDATE/DELETE);
  - the approval immutability trigger is present.
- **Rollback:** the hand-run scripts in `database/rollback/`. Each is
  forward-only on data that already exists. Before Step 5 nothing has been
  written, so rollback is clean.
- **Auditing is not on yet.** Applying V089 creates the tables; the flag is
  Step 7.

### Step 5. Seat the second administrator. **APPROVAL A5**

- **Change:** one run of the second-administrator bootstrap command-line tool.
  - It is a dry run by default, requires provenance, seats exactly one
    administrator, and refuses ever to run again.
- **Decision needed:** **who.** It must be a named BU person, not the
  requester.
- **Writes:** one `authz.archive_admin` row plus its audit row.
- **Rollback:** a status change through the normal revocation path. The
  last-administrator guard applies.

### Step 6. Provision the pilot population. **APPROVAL A6, one per run or batch**

- **Change:** the eight identity links and six grants in
  [`DEV_SECURITY_PILOT_PACKAGE.md`](DEV_SECURITY_PILOT_PACKAGE.md), through the
  command-line tools (writes stay off).
  - The CENTRAL grant goes through the Step-3 approval flow: one administrator
    requests it and the other approves it.
- **Identities:**
  - **automated probes use synthetic identities only;**
  - BU participants (T-CEN, T-DEPT, T-DEPT2, T-PI, T-OAV, T-NONE) are named
    by BU;
  - each person's identifier is supplied out of band;
  - no real user's existing access is changed, and nobody is impersonated.
- **Research staff roles:** `PI,MPI,COI` (owner decision, 2026-11-17). Co-PI =
  MPI awaits BU confirmation (§5).
- **Rollback:** revoke grants, then unlink identities. Status changes keep the
  history. **Expiry is not revocation.**

### Step 7. Turn on activity auditing. **APPROVAL A7**

- **Change:** a revision setting `app.authorization.activity-audit.enabled=true`.
  - Downloads and administrator actions fail closed. The routine-read policy is
    set as decided in §5.
  - The API refuses to start if required audit settings are missing.
- **Before:** confirm the exact environment variable name by checking that
  the capabilities response or the startup log reflects it.
- **Writes:** audit rows from then on. **No automatic deletion.** The retention
  script remains manual and dry-run by default.
- **Rollback:** the previous revision. Audit rows already written are kept.

### Step 8. Controlled enforcement window. **APPROVAL A8**

- **Change:** a revision adding
  - `APP_AUTHORIZATION_ENFORCEMENT_ENABLED=true`;
  - `APP_AUTHORIZATION_DEPARTMENT_MATCH=LEAD_UNIT_WITH_DESCENDANTS`;
  - `APP_AUTHORIZATION_VERSION_SCOPE`, `APP_AUTHORIZATION_CONTACT_DERIVATION`
    and `APP_AUTHORIZATION_MAX_SIGN_IN_AGE` as decided in §5;
  - `APP_AUTHORIZATION_RESEARCH_STAFF_ROLES=PI,MPI,COI`.
- **Safeguard:** the API refuses to start if any required value is missing.
- **Time-boxed:** a stated start and end, announced to DEV users beforehand.
- **Order inside the window:**
  1. **Automated live probes (synthetic identities).** Run first:
     - the H1–H5 and N-161 attack replays;
     - the related-record lists;
     - the 404-shaped denial;
     - the No-Grant Control;
     - Dashboard totals for Central matching the baseline.
     If any probe fails, **close the window**: roll back to the previous
     revision and fix in a PR.
  2. **BU acceptance session (Lalitha)** against
     [`LALITHA_BU_ACCEPTANCE_CHECKLIST.md`](LALITHA_BU_ACCEPTANCE_CHECKLIST.md),
     Parts 1 and 2.
  3. **Close the window:** roll back to the revision without enforcement,
     unless an extended window is approved.
- **Writes:** none from enforcement itself. Audit rows are written if Step 7
  is active.
- **Rollback:** `update-service` to the previous revision. It takes effect in
  one deployment.

### Step 9. Administrator writes. **APPROVAL A9 (last, optional for acceptance)**

- **Change:** `app.authorization.administration-writes-enabled=true`.
- **Precondition:** Steps 3, 4 and 5 are complete and QA has replayed the
  escalation attacks live.
- **Why last:** this is the only step that lets the web interface change
  access. The command-line tools cover everything acceptance needs.
- **Rollback:** the previous revision.

---

## 4. What each step can move: the 22 open criteria

These are counts the steps *make testable*. A criterion moves to DONE only
when it passes live in DEV. Business acceptance is recorded separately.

| Unblocked by | Criteria | Count |
|---|---|---|
| A1 + A6 + A8 (fixes are on `main`; need live proof) | US-DEP-001 #1 #2, US-DEP-005 #1 #2, US-PI-001 #2, US-OAV-001 #2, US-SEC-003 #1 #2 | 8 |
| A4 + A7 + audit decisions | US-SEC-004 #1 #2 | 2 |
| BU confirmation, then A8 | US-PI-001 #1 (Co-PI = MPI), US-OAV-001 #1 (IO reaches Subaward/Negotiation through the funded Award) | 2 |
| BU definition needed first | US-CEN-003 #3 ("approvals"), US-PI-002 #1 ("proposal package") | 2 |
| BU decision, then engineering | US-PI-003 #1 (version history vs. no unauthorized versions), US-SEC-001 #1 (role-level assignment), US-SEC-002 #1 #2 #3 (sync source and propagating revocation) | 5 |
| Engineering gap, not yet scheduled | US-CEN-004 #1 (search by school), US-DEP-002 #1 (Documents / File Finder 403 for scoped users), US-SEC-005 #3 (ETL role can write to the document bucket) | 3 |
| | **Total** | **22** |

**Ceiling, not forecast:**
- If every live check passes and BU confirms the two scope questions,
  A1–A8 can take the count from 25 to at most **37 / 47**.
- The remaining 10 need BU definitions or decisions first. Five of those also
  need engineering work.
- **The Central User criteria are already counted DONE on synthetic evidence.
  They are re-verified live in A8.** A live failure moves them back.

---

## 5. Decisions needed, and who makes them

| # | Decision | Needed before | Owner |
|---|---|---|---|
| D-1 | Audit retention period (90 days is provisional) | A4 | BU |
| D-2 | Routine-read audit failure policy (FAIL_OPEN is proposed only) | A4 / A7 | BU + owner |
| D-3 | Audit privacy defaults; approve the `GRANT INSERT` script | A4 | owner |
| D-4 | Second administrator: who | A5 | BU |
| D-5 | Pilot participants and their identifiers (eight roles) | A6 | BU |
| D-6 | `version-scope`, `contact-derivation`, `max-sign-in-age` values (recommendations in `PHASE2_PILOT_PLAN.md` §2) | A8 | BU + owner |
| D-7 | Confirm Co-PI = MPI, and IO reach into Subaward/Negotiation | A8 | BU |
| D-8 | Enforcement window dates, and notice to DEV users | A8 | owner |
| D-9 | Definitions: "approvals" (CEN-003), "proposal package" (PI-002) | criteria | BU |
| D-10 | Version history rule (PI-003.1), role-level assignment (SEC-001.1), sync source (SEC-002) | criteria | BU |

---

## 6. Boundaries that hold throughout

- Production is not touched.
- No `terraform apply`, and the API image pin is not reconciled. Revisions are
  registered from the running definition, as was done for revision 105.
- The Archive Explorer stays OFF.
- No credentials are retrieved and no new database access route is created.
- No automatic audit deletion.
- Automated authorization tests use synthetic identities. Real users' access is
  not changed, and nobody is impersonated.
- Every rollback is a task-definition revision or a recorded status change;
  nothing is hard-deleted.
- The BU count stays at 25/47 until live evidence supports a change. The
  tracker workbook is updated only from that evidence.

---

## 7. Shortest path to the first live evidence

If the aim is the earliest honest movement of the count:

1. **A1:** deploy `main`; nothing else changes.
2. **A6 (non-CENTRAL roles only):**
   - Department, Department with descendants, Research Staff, IO, Parent
     Award, No-Grant Control;
   - none of these needs V090 or phase 2.
3. **A8:** a short window.
   - Run the synthetic probes, then the BU session for S2–S6.
   - **S1 (Central) is deferred.**

This proves up to 8 criteria live without waiting for phase 2, V089/V090 or
the audit decisions. The Central scenario and SEC-004 follow once A3–A5 and A7
are done.
