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
4. Update both operator task definitions to the new digest and re-run
   `OperatorTaskDefinitionTest`.

**Rollback:** `aws ecs update-service --task-definition <previous revision>`.
Nothing else changes; no data has been written yet.

## Step 2 — register the operator task definitions  ⚠️ needs approval

```
aws ecs register-task-definition --cli-input-json \
  file://ops/grant-admin/dev-grant-admin.taskdef.json
aws ecs register-task-definition --cli-input-json \
  file://ops/grant-admin/dev-link-identity.taskdef.json
```

Then, before running either, check the committed files against the live API
definition — the committed file cannot see a variable added to the deployed
API later:

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

| # | Role | ARCHIVE_ADMIN | Grant | Kuali PERSON_ID | What it proves |
|---|---|---|---|---|---|
| 1 | Central Administrator | no | `CENTRAL` | not needed | SEC-002: the widest scope works at all |
| 2 | Unit Administrator | no | `UNIT <unit>` | not needed | SEC-002: an Award at the unit is visible, one below it is not |
| 3 | Unit Administrator, with descendants | no | `UNIT <unit> --include-descendants` | not needed | SEC-002: expansion is transitive, not one level |
| 4 | Research Staff | no | `CONTACT_DERIVATION` | **required** | SEC-003: contact-derived access, version-specific |
| 5 | **Grant Administrator** | **yes** | **none** | not needed | **administering access is not having it** |
| 6 | No-Grant Control | no | **none** | not needed | an authenticated user with no grant sees nothing, and is told why |

### Identity 5 is the one to get right

A Grant Administrator holds `ARCHIVE_ADMIN` and **no `access_grant` row**.
They must be able to issue, modify and revoke other people's access and see
no archive record themselves. This is asserted in code as matrix cases 20
and 20b — 20b has the administrator issue a `CENTRAL` grant to somebody
else, the widest grant there is, and still see nothing.

If identity 5 can see a record, stop the pilot. It means
`ARCHIVE_ADMIN` has acquired data scope somewhere, which is a defect in the
model and not a configuration question.

### Identities 5 and 6 are not the same test

| | 5: Grant Administrator | 6: No-Grant Control |
|---|---|---|
| Linked | yes | yes |
| Administrator | yes | no |
| Grant | none | none |
| Resolves to | `NotProvisioned / NO_ACTIVE_GRANTS` | `NotProvisioned / NO_ACTIVE_GRANTS` |
| Sees | nothing | nothing |
| Distinct point | a role that confers administration confers no data | the ordinary unprovisioned user's experience |

Both resolve identically, which is itself the finding: the engine does not
treat an administrator as special, so nothing about identity 5 depends on
remembering to withhold something.

### What this pilot does not cover

`IO` (SEC-004) and `PARENT_AWARD` (SEC-005) have no pilot identity. Both are
executed in `SecurityAcceptancePackIntegrationTest` against a real schema,
but neither will be observed on DEV by these six. If the business wants them
in the live pilot, that is two further identities and two further grants —
a decision, not an omission to be fixed silently.

## Step 4 — link the six identities  ⚠️ needs approval (writes `authz.identity_link`)

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
-- expect 7 ACTIVE: the bootstrap administrator plus the six
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

Dry run first, again the default. One task per grant; four grants for six
identities, because identities 5 and 6 receive none.

```
--actor=ARP-FK7QY195-QW5HV7G0
--grantee=<the ARP>
--type=CENTRAL|UNIT|CONTACT_DERIVATION
--unit-number=<n> [--include-descendants]      (UNIT only)
--operator=... --reason=... --approval-reference=...
[--confirm]
```

Grants are **not** issued to identity 5 or 6. If a run names either, stop:
that is the pilot's control being spent.

**Verify:**

```sql
SELECT grant_id, institutional_identifier, grant_type, unit_number,
       include_descendants, io_value, award_number, granted_by, granted_at
  FROM authz.access_grant WHERE revoked_at IS NULL ORDER BY grant_id;
-- expect exactly 4
-- expect NO row for identities 5 and 6

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
- What is being checked is that the rows are right: seven links, four
  grants, the PERSON_ID on identity 4, nothing on identities 5 and 6, and an
  audit row for every write.
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

- identity 5 or 6 can see any record;
- a denial returns anything other than 404 — a 403 distinguishes "exists but
  forbidden" from "not found", which is the disclosure 404 exists to prevent;
- Global Search returns 404 rather than 200 with zero results for identity 6;
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

| Step | Writes | Reversible by | Approved? |
|---|---|---|---|
| 1 API image | nothing | previous revision | ☐ |
| 2 task definitions | nothing | deregister | ☐ |
| 4 identity links | `authz.identity_link` | revocation (a new fact) | ☐ |
| 5 pilot grants | `authz.access_grant` | revocation (a new fact) | ☐ |
| 7 enforcement | nothing | previous revision | ☐ |
| 8 V089 + auditing | new table, then rows | forward-only | ☐ |

Steps 3 and 6 write nothing and need no approval. Every step that writes has
a dry run, and the dry run is the default.
