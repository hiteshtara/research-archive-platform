# DEV test enablement — the ordered runbook

One ordered sequence for getting record-level authorization into a state
where it can actually be tested on DEV, and back out again.

It exists because the other plans each assume the step before them. The
activation plan starts at "provision approved grants"; the activation
runbook says "every link in this stage is created by an administrator" and
tells you to use the administrative API. **The administrative API returns
404 for its entire tree while `app.authorization.enforcement-enabled` is
false**, which it is everywhere — so the first instruction in the sequence
could not be carried out, and the gap was invisible because it sat between
two documents rather than inside either.

See also, and do not duplicate:

| Document | What it owns |
|---|---|
| [`DEV_RELEASE_CHECKLIST_SEC001_SEC009.md`](DEV_RELEASE_CHECKLIST_SEC001_SEC009.md) | release contents, migrations, privileges, evidence |
| [`AUTHORIZATION_ACTIVATION.md`](AUTHORIZATION_ACTIVATION.md) | the identity and administrator model, and the SQL that verifies it |
| [`AUTHORIZATION_ACTIVATION_PLAN.md`](AUTHORIZATION_ACTIVATION_PLAN.md) | the staged activation and its rollback |
| [`GRANT_LIFECYCLE_ACCEPTANCE_TESTS.md`](GRANT_LIFECYCLE_ACCEPTANCE_TESTS.md) | grant, modify, revoke behaviour |
| [`../security/AUTHORIZATION_ACCEPTANCE_MATRIX.md`](../security/AUTHORIZATION_ACCEPTANCE_MATRIX.md) | the twenty acceptance cases, and which are executed |

## The ordering constraint that decides everything

Enforcement cannot go on first, and the administrative API cannot come
first either.

```
enforcement OFF  ->  admin API 404s  ->  cannot create links or grants
                                          through HTTP
enforcement ON   ->  grants are read  ->  with no links and no grants,
   with nothing provisioned                every record is denied to
                                           every user
```

So provisioning must happen while enforcement is off, through a path that
does not depend on HTTP. That path is the operator utilities — one-off ECS
tasks behind Spring profiles no deployment sets, dry-run by default, each
writing through the same `ArchiveAdminService` method the API would. The
startup safeguard added in #117 is what stops the second row above
happening quietly: an incomplete enforcement policy refuses to start rather
than denying everything.

## Step 0 — preconditions

| Check | How | Expected |
|---|---|---|
| Account | `aws sts get-caller-identity` | `770203350335`, `AWS_PROFILE=bu-nprd` |
| Deployed revision | `ops/status-api.sh` | matches `securityReadiness.json`'s `apiTaskDefinition` |
| Enforcement | the live task definition | **no** `APP_AUTHORIZATION_*` variable present |
| Migrations | `public.schema_migration` | V088 applied; **V089 not applied** |
| Administrator | `SELECT * FROM authz.admin_role_member` | exactly 1 ACTIVE |
| Grants | `SELECT count(*) FROM authz.access_grant` | 0 |

If the deployed revision and the snapshot disagree, stop: every digest in
this runbook is derived from the snapshot, and
`OperatorTaskDefinitionTest` will already be failing.

## Step 1 — deploy an API image containing the utilities  ⚠️ needs approval

**Why this is first and not optional.** The utilities are code. The image
running on DEV is `164b242` (revision 87), which predates them: it has no
`--award-number` flag, so a `PARENT_AWARD` grant cannot be created, and no
`link-identity` profile, so the task definition for it would start a
container with a profile nothing responds to.

1. Build from a clean detached worktree at the merge commit, per
   `ops/deploy-api.sh`. Never from a live working directory.
2. Change **only** the container image reference. Preserve every
   environment variable, secret, CPU, memory, network, IAM role and service
   setting. Add no `APP_AUTHORIZATION_*` variable — enforcement is a later,
   separate step.
3. Refresh `securityReadiness.json` with the new revision, commit and
   image digest. This is part of the deployment, not a follow-up: the
   task-definition test derives from it.
4. Point the operator definitions at the image just deployed:

   ```
   AWS_PROFILE=bu-nprd ops/sync-operator-taskdefs.sh
   ```

   It reads which task definition the **service** is running - not the latest
   revision of the family, which can differ - edits the committed files only,
   registers nothing, and warns if `securityReadiness.json` still records a
   different digest. Review the diff and commit it.

### Why this step is enforced in three places

An operator definition left behind does not fail loudly. It starts a
container that runs **older CLI code against today's database**. The
grant-admin definition was last bumped for #103 and was still saying so at
revision 87 - five releases later - and nothing reported it, because the
existing checks asserted the image was pinned by digest and never that it was
the *right* digest.

| Guard | Catches | When |
|---|---|---|
| `OperatorTaskDefinitionTest` | definition vs the recorded snapshot | CI, every PR |
| `scripts/verify-grant-admin-taskdef.sh` | definition vs the **running service** | before registering |
| `ops/sync-operator-taskdefs.sh` | corrects both | after a deploy |
| `ops/deploy-api.sh` | prints the follow-up with the new digest | end of every deploy |

The deploy script prints rather than edits, deliberately: a deploy should not
quietly rewrite committed files, and a digest change has to be reviewed like
any other.

**Do not register or run an operator task definition until step 4 is done.**
`verify-grant-admin-taskdef.sh` now exits non-zero on a mismatch and names
both digests - verified by pointing a definition at the old digest and
watching it refuse.

**Rollback:** `aws ecs update-service --task-definition <previous revision>`.
Nothing else changes; no data has been written yet.

## Step 2 — register the operator task definitions  ⚠️ needs approval

```
aws ecs register-task-definition --cli-input-json \
  file://ops/grant-admin/dev-grant-admin.taskdef.json
aws ecs register-task-definition --cli-input-json \
  file://ops/grant-admin/dev-link-identity.taskdef.json
```

Then, before running either, check the committed files against the live API —
the committed file cannot see a variable added to the deployed API later, and
cannot tell on its own whether its pinned image is still the running one:

```
scripts/verify-grant-admin-taskdef.sh ops/grant-admin/dev-grant-admin.taskdef.json
scripts/verify-grant-admin-taskdef.sh ops/grant-admin/dev-link-identity.taskdef.json
```

**Rollback:** deregister the revisions. They are their own families, so the
API service is untouched either way.

## Step 3 — the pilot population

Six identities. The point of the last two is that they are the controls: a
pilot in which everybody can see something proves only that permitting
works.

Eight identities. Six receive a grant; the last two are the controls.

| # | Role | ARCHIVE_ADMIN | Grant | PERSON_ID | Real DEV scope | What it proves |
|---|---|---|---|---|---|---|
| 1 | Central User | no | `CENTRAL` | — | n/a | SEC-002: the widest scope works at all |
| 2 | Department User | no | `UNIT` | — | unit `1250000000` | SEC-002: an Award at the unit is visible, one below it is not |
| 3 | Department User, descendants | no | `UNIT --include-descendants` | — | same unit; child `1250400000`, grandchild `1252060000` | SEC-002: expansion is transitive, not one level |
| 4 | Research Staff | no | `CONTACT_DERIVATION` | **required** | PERSON_ID `1000570` | SEC-003: contact-derived access, version-specific |
| 5 | **SAP IO User** | no | `IO` | — | account `9500234095` on Award `100004-00002` | SEC-004: IO reaches that Award, and no Proposal, Subaward or Negotiation |
| 6 | **Parent Award User** | no | `PARENT_AWARD` | — | Award `100380-00001`, **eight** children `-00002`…`-00009` | SEC-005: the Award plus its children at the configured depth, never across families |
| 7 | **Grant Administrator** | **yes** | **none** | — | n/a | **administering access is not having it** |
| 8 | No-Grant Control | no | **none** | — | n/a | an authenticated user with no grant sees nothing, and is told why |

### Why these records

Every scope value above was read from live DEV, not chosen for convenience.

`100380-00001` was picked over the other hierarchy roots because **eight**
children make depth unambiguous: at depth 0 the grantee reaches one Award, at
depth 1 they reach nine. A parent with a single child cannot distinguish
"expansion worked" from "expansion did nothing".

The negative control for role 6 is `100330-00002`, whose root is
`100330-00001` — a different family. It must stay unreachable; the hierarchy
walk compares `root_award_number` on every hop precisely so a grant cannot
cross families.

For role 5, `9500234095` is a real `account_number`. The utility states the
boundary itself: *"an IO grant never reaches Proposals, Subawards or
Negotiations"*, so role 5's negative test is any non-Award record.

The proposed runs, with the real output of each, are in
[`DEV_PILOT_DRY_RUNS.md`](DEV_PILOT_DRY_RUNS.md).

### Identity 7 is the one to get right

A Grant Administrator holds `ARCHIVE_ADMIN` and **no `access_grant` row**.
They must be able to issue, modify and revoke other people's access and see
no archive record themselves. This is asserted in code as matrix cases 20
and 20b — 20b has the administrator issue a `CENTRAL` grant to somebody
else, the widest grant there is, and still see nothing.

If identity 7 can see a record, stop the pilot. It means
`ARCHIVE_ADMIN` has acquired data scope somewhere, which is a defect in the
model and not a configuration question.

### Identities 7 and 8 are not the same test

| | 7: Grant Administrator | 8: No-Grant Control |
|---|---|---|
| Linked | yes | yes |
| Administrator | yes | no |
| Grant | none | none |
| Resolves to | `NotProvisioned / NO_ACTIVE_GRANTS` | `NotProvisioned / NO_ACTIVE_GRANTS` |
| Sees | nothing | nothing |
| Distinct point | a role that confers administration confers no data | the ordinary unprovisioned user's experience |

Both resolve identically, which is itself the finding: the engine does not
treat an administrator as special, so nothing about identity 7 depends on
remembering to withhold something.

### What this pilot still does not cover

SEC-004 and SEC-005 now have identities (5 and 6), so every record-level
requirement is observed on DEV by someone.

What remains uncovered is **SEC-006's suspension path** and **SEC-007's
denied-versus-missing pairing** as live observations. Both are executed in
`SecurityAcceptancePackIntegrationTest`, and both can be exercised on DEV
using the identities above without adding any: suspend identity 1 and
re-check, then compare an out-of-scope record against a non-existent one as
identity 2. Those are steps in the verification order below, not extra
people.

## Step 4 — link the eight identities  ⚠️ needs approval (writes `authz.identity_link`)

Dry run first. It is the default, and it writes nothing.

```
aws ecs run-task --cluster research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-link-identity \
  --launch-type FARGATE --network-configuration <as for the API> \
  --overrides '{"containerOverrides":[{"name":"link-identity","command":[
    "--aws-account-id=770203350335",
    "--environment=dev",
    "--cognito-user-pool-id=us-east-1_VJ4ekQ27c",
    "--cognito-region=us-east-1",
    "--cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c",
    "--cognito-subject=<the immutable sub UUID>",
    "--subject-identifier=<the ARP>",
    "--actor=ARP-FK7QY195-QW5HV7G0",
    "--operator=<the person running this>",
    "--reason=six-role authorization pilot",
    "--approval-reference=<the approval>"
  ]}]}'
```

Add `--confirm` to write. Add `--kuali-person-id=<numeric>` for identity 4
and only identity 4.

**The Cognito subject must be the immutable `sub` UUID.** A username, an
email address and a SAML NameID are all mutable; a link keyed to one stops
resolving, or starts resolving to whoever inherits the name. Fetch it out of
band (`aws cognito-idp admin-get-user`) and paste it. The utility refuses
anything that is not UUID-shaped.

**Identity 4 must carry the PERSON_ID.** Contact-derived access resolves
through it. Without it the `CONTACT_DERIVATION` grant in step 5 is written,
appears in the administrative table, is recorded in the audit trail, and
confers nothing — and `AccessScopeResolver` collapses the empty scope to
`NO_ACTIVE_GRANTS`, so the identity is indistinguishable from one holding no
grant at all. Both utilities now refuse or warn about this, but the order
matters: link with the PERSON_ID, then grant.

Exit status is 0 for a passing dry run or a completed link, 1 for a refusal.

**Verify** (from `AUTHORIZATION_ACTIVATION.md`, section 3):

```sql
SELECT identity_link_id, institutional_identifier, method,
       kuali_person_id, status, verified_by, verified_at
  FROM authz.identity_link ORDER BY identity_link_id;
-- expect 9 ACTIVE: the bootstrap administrator plus the eight
-- expect method = 'ADMIN_VERIFIED' on all of them
-- expect kuali_person_id NOT NULL on identity 4 only

SELECT cognito_issuer, cognito_subject, COUNT(*) FROM authz.identity_link
 WHERE status='ACTIVE' GROUP BY 1,2 HAVING COUNT(*) > 1;   -- expect no rows
```

**Rollback:** withdraw the link by its immutable id through the same utility:

```
--unlink-link-id=<identity_link_id>
--actor=ARP-FK7QY195-QW5HV7G0
--operator=... --reason=... --approval-reference=...
[--confirm]
```

Links are never deleted; this is a status change, and the row survives as
history — which is what makes a later re-establishment a *re-link*, the
separately-approved act that transfers grants to a new Cognito subject.

**It does not revoke the person's grants.** They keep every
`access_grant` row and simply stop resolving to the archive identity those
grants hang off. Unlinking answers "this Cognito identity is not this
person"; revoking answers "this person should not have this access". Withdraw
the grants separately (step 5's rollback) if that is what is meant.

## Step 5 — issue the pilot grants  ⚠️ needs approval (writes `authz.access_grant`)

Dry run first, again the default. One task per grant; **six** grants for
eight identities, because identities 7 and 8 receive none.

The exact proposed runs, and the real output of each, are in
[`DEV_PILOT_DRY_RUNS.md`](DEV_PILOT_DRY_RUNS.md).

```
--actor=ARP-FK7QY195-QW5HV7G0
--grantee=<the ARP>
--type=CENTRAL|UNIT|CONTACT_DERIVATION|IO|PARENT_AWARD
--unit-number=<n> [--include-descendants]      (UNIT only)
--io-value=<account>                           (IO only)
--award-number=<n>                             (PARENT_AWARD only)
--expires-at=2027-06-30T00:00:00Z              proposed for every pilot grant
--operator=... --reason=... --approval-reference=...
[--confirm]
```

Grants are **not** issued to identity 7 or 8. If a run names either, stop:
that is the pilot's control being spent.

**Every pilot grant is proposed with an expiry.** D-ADM-3 makes expiry
optional with no default, which is right for a production grant and wrong for
a pilot — an unreviewed pilot grant would otherwise become permanent through
inattention. Note the caveat the utility itself reports: V087 constrains
UNREVOKED grants, so an EXPIRED pilot grant still occupies its scope until it
is revoked. Closing the pilot means revoking, not waiting.

**Verify:**

```sql
SELECT grant_id, institutional_identifier, grant_type, unit_number,
       include_descendants, io_value, award_number, granted_by, granted_at
  FROM authz.access_grant WHERE revoked_at IS NULL ORDER BY grant_id;
-- expect exactly 6
-- expect NO row for identities 7 and 8

SELECT actor, action, occurred_at, detail ->> 'note'
  FROM authz.access_audit ORDER BY audit_id;
-- expect one ACCESS_GRANTED per grant, attributed to the administrator
```

**Rollback:** revoke by immutable grant id through the same utility.
Revocation is a status change, never a delete; the grant row and both audit
rows survive, and `isActiveAt` stops honouring it immediately.

## Step 6 — test with enforcement still OFF

Everything provisioned, nothing enforced. This is the step most worth not
skipping, because it costs nothing and catches provisioning mistakes while
they are still free.

- Each pilot user signs in and reaches the archive as before — **enforcement
  is off, so they see everything**. That is expected, and is not a finding.
- What is being checked is that the rows are right: nine links, six grants,
  the PERSON_ID on identity 4 and on no one else, nothing on identities 7 and
  8, an expiry on every grant, and an audit row for every write.
- Re-run `scripts/verify-grant-admin-taskdef.sh` for both definitions.

## Step 7 — enable enforcement  ⚠️ needs approval, and separately

One change to the API service: add the `APP_AUTHORIZATION_*` settings. It
is reversible by deploying the previous task definition revision, and that
is the whole rollback.

**Before:** the policy must be complete. An incomplete one is refused at
startup by `AuthorizationConfiguration.requireEnforcementSettings()` —
deliberately, because a half-configured policy would deny every record to
every user, and a service that starts and denies everything is harder to
diagnose than one that refuses to start and says why. Verify the intended
values through
[`AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md`](../security/AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md)
first; do not discover them from a failed deployment.

**Reversibility, stated plainly:**

| | Forward | Back |
|---|---|---|
| Mechanism | new task definition revision with the settings | `update-service` to the previous revision |
| Time | one service deployment | one service deployment |
| Data touched | none | none |
| Links and grants | unaffected | unaffected |
| Audit rows | unaffected | unaffected |

Enforcement is the only genuinely reversible step in this runbook, because
it is the only one that writes nothing. Steps 4 and 5 write rows; their
rollback is revocation, which is a new fact rather than an undo.

**Then verify the matrix on DEV.** Until this point every expectation in
`AUTHORIZATION_ACCEPTANCE_MATRIX.md` has been observed only against
throwaway containers. Work the numbered cases in order and record the
observed status beside the expected one.

**Stop conditions.** Any of these means roll back to the previous revision
before continuing:

- identity 7 or 8 can see any record;
- a denial returns anything other than 404 — a 403 distinguishes "exists but
  forbidden" from "not found", which is the disclosure 404 exists to prevent;
- Global Search returns 404 rather than 200 with zero results for identity 8;
- a Dashboard count disagrees with the page it summarises.

## Step 8 — SEC-008 activity auditing  ⚠️ needs approval, and last

Kept last on purpose. It is the only step that adds a table and the only one
whose rollback is not a service deployment.

| Item | State |
|---|---|
| Code | in revision 87 |
| Migration V089 | **deliberately unapplied**, and physically separated — see `DEV_RELEASE_CHECKLIST_SEC001_SEC009.md` section 2 |
| Retention | 90 days proposed, **not approved** |
| Failure policy | FAIL_CLOSED proposed for sensitive releases |
| Partitions | `ACTIVITY_AUDIT_PARTITIONS.md` |

Before applying V089, these need answers, and they are business decisions
rather than engineering ones: the retention period, whether a failed audit
write blocks the read it was recording, and whether a person is told when
their activity is inspected.

Apply V089 only through the ETL loader's `--migrate-only` mode, as every
other migration is applied. Deploying API code that depends on a migration
does not apply it — that cost a production incident on 2026-08-14 with
V076.

## Approval summary

| Step | Writes | Reversible by | Who approves | Approved? |
|---|---|---|---|---|
| 1 API image | nothing | previous task definition revision | deployment | ☐ |
| 2 task definitions | nothing | deregister the revisions | deployment | ☐ |
| 4 identity links (8) | `authz.identity_link` | `--unlink-link-id` (a new fact, not an undo) | business: who the pilot is | ☐ |
| 5 pilot grants (6) | `authz.access_grant` | `--revoke-grant-id` (a new fact) | business: what each may see | ☐ |
| 7 enforcement | nothing | previous task definition revision | security + business | ☐ |
| 8 V089 + auditing | a new table, then rows | forward-only | security + business | ☐ |

Steps 3 and 6 write nothing and need no approval. Every step that writes has
a dry run, and the dry run is the default.

## Rollback, per step

| Step | How to get back | Cost | Leaves behind |
|---|---|---|---|
| 1 API image | `aws ecs update-service --task-definition <previous revision>` | one deployment | nothing; revisions are immutable and kept |
| 2 task definitions | `aws ecs deregister-task-definition` | seconds | the revision, deregistered; they are their own families so the API is untouched either way |
| 4 links | `--unlink-link-id=<id> --confirm` per link | one task each | the link row, status REVOKED, plus an `IDENTITY_UNLINKED` audit row. **Grants are NOT touched** — unlinking says "this Cognito identity is not this person", revoking says "this person should not have this access" |
| 5 grants | `--revoke-grant-id=<id> --confirm` per grant | one task each | the grant row with `revoked_at` set, plus `ACCESS_REVOKED`. Nothing is deleted |
| 7 enforcement | deploy the previous task definition revision | one deployment | nothing. **This is the only step that writes nothing, so the only one that is genuinely reversible** |
| 8 V089 | forward-only. A `DROP TABLE` would be a new migration and a new decision | — | the table and any rows written |

Two things worth being explicit about:

**Revocation is not an undo.** Steps 4 and 5 roll back by writing a new fact.
The history survives on purpose — that is what makes the audit trail an audit
trail — so "rolled back" means "withdrawn and recorded", never "as if it had
not happened".

**An expired grant still occupies its scope.** V087 constrains UNREVOKED
grants, so after 2027-06-30 a pilot grant continues to block a replacement
for the same scope until it is revoked. Closing the pilot means revoking.

## Readiness matrix

"Ready for Testing" and "Accepted" are deliberately separate. Nothing in
this runbook can set the second one.

| # | Requirement | Code | Deployed | Operator path | Pilot role | Ready for Testing | Accepted |
|---|---|---|---|---|---|---|---|
| SEC-001 | Identity mapping | yes | rev 87 | `link-identity` CLI (**new, undeployed**) | all eight | after step 1 | no |
| SEC-002 | Unit / descendants | yes | rev 87 | grant CLI | 2, 3 | after step 1 | no |
| SEC-003 | Contact derivation | yes | rev 87 | grant CLI + PERSON_ID on the link | 4 | after step 1 | no |
| SEC-004 | IO scope | yes | rev 87 | grant CLI | **5** | after step 1 | no |
| SEC-005 | Parent Award | yes | rev 87 | grant CLI (**was impossible; fixed**) | **6** | after step 1 | no |
| SEC-006 | Revocation / suspension | yes | rev 87 | grant CLI revoke; suspension via the service | 1 (suspend and re-check) | after step 1 | no |
| SEC-007 | Denial is 404 | yes | rev 87 | n/a | 2 and 8 | executed in the pack | no |
| SEC-008 | Activity auditing | yes | rev 87 | V089 **unapplied** | n/a | after step 8 | no |
| SEC-009 | Read-only role | yes | in force | n/a | n/a | executed | no |

Most rows say *after step 1* because the deployed image is `164b242` and does
**not** contain the `--award-number` flag or the `link-identity` profile.
Running either utility today runs the revision-87 code.
