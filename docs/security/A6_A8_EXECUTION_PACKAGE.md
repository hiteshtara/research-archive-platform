# A6 + A8 window 1: execution package for approval

**Status: prepared, not executed.** As of 2026-11-17:
- no Cognito user, identity link or grant has been created;
- no operator task definition has been registered;
- enforcement is OFF.

This package supersedes the open choices in
[`A6_SYNTHETIC_IDENTITIES.md`](A6_SYNTHETIC_IDENTITIES.md) and
[`A8_ENFORCEMENT_WINDOW_1.md`](A8_ENFORCEMENT_WINDOW_1.md) with the owner's
decisions of 2026-11-17. Where they differ, this file wins.

## 0. Owner decisions applied (pilot test design only, not BU policy)

| Decision | Value |
|---|---|
| Synthetic Research Staff identity | **Option (b): none.** The probe tool runs with `--no-pi`. US-PI-001 #2 waits for BU's real T-PI account |
| `APP_AUTHORIZATION_VERSION_SCOPE` | `FAMILY_WIDE` |
| `APP_AUTHORIZATION_CONTACT_DERIVATION` | `EXPLICIT_GRANT` |
| `APP_AUTHORIZATION_MAX_SIGN_IN_AGE` | `12h` |
| Window length | at most 2 hours; synthetic probes first, then Lalitha's session |

**Option (b) needs three synthetic accounts, not four.** The fourth in the
original design was the synthetic PI.
- A mirror Department account for C's unit would add a second-unit check, but
  it needs new probes. It is **not** included.

## 1. What has been verified, and what is still unverified

| Check | Result | How |
|---|---|---|
| DEV API runs the integrated code | rev **106**, `sha256:c85be89a…5b90`, `main` `1c3f0c8` | A1, 2026-11-17 |
| Operator task definitions point at that image | **yes, in PR #170** (CI run 44196515849 green). Not registered yet | `ops/sync-operator-taskdefs.sh`; `verify-grant-admin-taskdef.sh` OK for both files against the live service |
| Operator tools at the integrated code | `GrantProvisioningIntegrationTest` 66/0, `IdentityLinkProvisioningIntegrationTest` 31/0, `PilotDryRunPlanIntegrationTest` 4/0, `AdminVerifiedPilotPathIntegrationTest` 13/0, `AdminEscalationAttackIntegrationTest` 8/0 | local Testcontainers, at `cd67d36` |
| Probes have a sign-in path | `ALLOW_ADMIN_USER_PASSWORD_AUTH` on the DEV client; `COGNITO` is an identity provider; the client has no secret | `describe-user-pool-client`, read-only (A6-0) |
| Nothing pre-exists | no `rap-synth-a6-*` Cognito user; no `ARP-SYNTHA60*` or `rap-synth` archive identity | `admin-get-user`; `/api/v1/admin/identities?q=` |
| Records exist, units correct | A `212128-00002` and B `211144-00007`: lead unit **2573180018**. C `209407-00002`: lead unit **1330420000** | Award people/units with the `leadUnit` flag, enforcement off |
| The window settings bind and start | All six variables start the rev-106 image. With one left out, startup is refused **naming exactly that one**. A mistyped enum value refuses to start, naming the variable | local run of the rev-106 image against a throwaway Postgres |
| **C's IO account is `9500312705`** | **UNVERIFIED.** It comes from BU's spreadsheet. No API response exposes account numbers | see §2.4 |

## 2. A6: synthetic identities

### 2.1 The three accounts and their intended permissions

| # | Cognito username | Email (cannot receive mail) | Archive identifier | Grant | Expires | Kuali person |
|---|---|---|---|---|---|---|
| S1 | `rap-synth-a6-dept` | `rap-synth-a6-dept@example.invalid` | `ARP-SYNTHA60-00000001` | `UNIT 2573180018`, no descendants | `<window date + 1>T00:00:00Z` | **none** |
| S3 | `rap-synth-a6-oav` | `rap-synth-a6-oav@example.invalid` | `ARP-SYNTHA60-00000003` | `IO 9500312705` | same | **none** |
| S4 | `rap-synth-a6-none` | `rap-synth-a6-none@example.invalid` | `ARP-SYNTHA60-00000004` | **none** (No-Grant Control) | n/a | **none** |

The numbering keeps the identifiers of `A6_SYNTHETIC_IDENTITIES.md`. S2 (PI)
is not created.

**Sign-in name (found at execution, 2026-11-18).** The DEV pool has
`UsernameAttributes=[email]`, so the short names above cannot be Cognito
usernames. Each account was created with its `@example.invalid` address as its
only sign-in name; Cognito assigned an internal username equal to the `sub`.
Every Cognito command in this package therefore uses the email address. The
short names remain the labels used in this document and in the secrets' paths.

None of the three is:
- in a Cognito group;
- an administrator;
- holding a CENTRAL grant.

### 2.2 Writes, in order

| Step | Writes | Rows |
|---|---|---|
| A6-1 | 3 Cognito users (`--message-action SUPPRESS`, permanent password) plus 3 Secrets Manager entries `rap/dev/synthetic/a6/<role>` | Cognito / Secrets Manager only |
| A6-1b | register `dev-grant-admin:6` and `dev-link-identity:2` from PR #170's files | task definition revisions; no data |
| A6-2 | 3 identity links, `method=ADMIN_VERIFIED`, **no `--kuali-person-id`** | 3 `authz.identity_link` + 3 audit rows |
| A6-3 | 2 grants (S1, S3), each with `--expires-at`; S4 gets none | 2 `authz.access_grant` + 2 audit rows |

**Every link and grant step is a dry run first.** That is the tool's default.
- Each dry run's output is read before the same command is repeated with
  `--confirm`.
- A dry-run ECS task reads the database and writes nothing
  (`GrantProvisioningIntegrationTest.aDryRunCreatesNoGrantAndNoAuditRow`).

Task network, the same as the API service:

```
--launch-type FARGATE --network-configuration 'awsvpcConfiguration={subnets=[subnet-0812bd0296da79d44,subnet-03d8ef305b4e55f87],securityGroups=[sg-02840378085557988],assignPublicIp=ENABLED}'
```

Common arguments:

```
--aws-account-id=770203350335 --environment=dev
--actor=<the active ARCHIVE_ADMIN's archive identifier>
--operator=<person running it> --approval-reference=<approval id>
--reason="A6 synthetic identity for A8 window 1 (not a person)"
```

| Run | Task definition | Arguments |
|---|---|---|
| link S1 | `dev-link-identity:2` | `--cognito-user-pool-id=us-east-1_VJ4ekQ27c --cognito-region=us-east-1 --cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c --cognito-subject=<sub of rap-synth-a6-dept> --subject-identifier=ARP-SYNTHA60-00000001` |
| link S3 | same | `… --cognito-subject=<sub of rap-synth-a6-oav> --subject-identifier=ARP-SYNTHA60-00000003` |
| link S4 | same | `… --cognito-subject=<sub of rap-synth-a6-none> --subject-identifier=ARP-SYNTHA60-00000004` |
| grant S1 | `dev-grant-admin:6` | `--grantee=ARP-SYNTHA60-00000001 --type=UNIT --unit-number=2573180018 --expires-at=<window date + 1>T00:00:00Z` |
| grant S3 | same | `--grantee=ARP-SYNTHA60-00000003 --type=IO --io-value=9500312705 --expires-at=<window date + 1>T00:00:00Z` |

The `sub` values are read back with `admin-get-user` after A6-1. Nobody types
them.

### 2.3 Expected allow/deny matrix, enforcement ON

| Identity | A `212128-00002` (2573180018) | B `211144-00007` (2573180018) | C `209407-00002` (1330420000) |
|---|---|---|---|
| S1 Department, `UNIT 2573180018` | **allow** | **allow** | **deny** (404, no C details) |
| S3 Other Authorized Viewer, `IO 9500312705` | **deny** | **deny** | **allow** |
| S4 No-Grant Control | **403 `ACCESS_NOT_PROVISIONED`** | same | same |

What "deny" means here:
- the summary and by-number lookup return 404;
- the record is absent from Award search and Global Search;
- its number and title do not appear in another record's related lists:
  - related Proposals, Subawards and Negotiations (#159);
  - Time-and-Money (H1);
  - Negotiation association (H2);
  - SAP transmissions (H3);
  - AI evidence search (H5; marked untested if AI is off).

`FAMILY_WIDE` means all versions of an in-scope family are visible. It does
not widen across families.

### 2.4 The one open data risk: C's IO

If `9500312705` is not C's account, two things follow:
- S3's positive control fails. The window then records US-OAV-001 #2 as
  **not tested**, never as passed.
- The failure is safe: a wrong IO can only hide records, not leak them.

There are two ways to settle it before the window:
- **(i)** One read-only SELECT of `account_number` for these three Awards,
  through the existing approved read-only ECS loader route. That is a task
  execution, so it needs its own approval.
- **(ii)** Accept the spreadsheet value and let the baseline step and positive
  control reveal a mismatch.

**Recommendation: (i).** It costs one approval and protects the window from
being spent on a bad fixture.

### 2.5 No synthetic account is linked to a real person's Kuali identity

- No link command carries `--kuali-person-id`.
- No grant is `CONTACT_DERIVATION`.
- Under `EXPLICIT_GRANT`, contact-based access needs both a person ID on the
  link and a `CONTACT_DERIVATION` grant. Neither exists.
- **Check after A6-2, before A6-3:** `/api/v1/admin/identities/ARP-SYNTHA60-0000000N`
  for each of the three must show one ACTIVE link, `ADMIN_VERIFIED`, with **no
  Kuali person**. If any shows one, stop and unlink.

### 2.6 The operator tools cannot bypass the approval controls

Both command-line tools write only through `ArchiveAdminService`, the same
service as the HTTP API.
- `grantAccess` runs, in order:
  1. `requireAdministrator`;
  2. `requireSubject`;
  3. the reason check;
  4. `refuseSelfGrant`;
  5. `refuseCentralWithoutApproval` (D1).
- `linkIdentity` refuses an administrator or privileged identity without the
  re-link approval path (N-161-1/2, #161).

What this means in practice:
- **CENTRAL** is refused whoever asks
  (`GrantProvisioningIntegrationTest.aCentralGrantIsRefusedUntilTheApprovalWorkflowExists`).
- **A self-grant** is refused (`aSelfGrantIsRefused`).
- **A dry run** writes nothing.
- **The production cluster** is refused even for a dry run
  (`aProductionClusterIsRefusedEvenForADryRun`).

No other code path writes grants, links or administrator rows. The only other
writer, automatic enrollment, is disabled: the bean is
`IdentityEnrollment.DISABLED` unless `app.authorization.enrollment.enabled=true`,
which is not set anywhere.

The command-line tools ignore the HTTP writes flag **by design**: that is how
provisioning works while administrator writes stay OFF. They do not carry any
`APP_AUTHORIZATION_*` policy (`OperatorTaskDefinitionTest`).

### 2.7 Cleanup and rollback

At window close, in this order. Each step is a dry run first, then
`--confirm`.

```bash
# 1. revoke each grant: a status change; the row and both audit rows survive
#    dev-grant-admin:6 with: --revoke-grant-id=<id> + common arguments (the grantee is read from the grant)
# 2. unlink each identity: a status change. Unlinking does NOT revoke, hence step 1 first
#    dev-link-identity:2 with: --unlink-link-id=<id> + common arguments ONLY
#    (the tool refuses --subject-identifier/--cognito-subject on an unlink)
#    link ids: 5 (S1 dept), 7 (S3 oav), 6 (S4 none)
# 3. disable the Cognito users (the pool signs in by email, see 2.1)
aws cognito-idp admin-disable-user --user-pool-id us-east-1_VJ4ekQ27c --username rap-synth-a6-dept@example.invalid
aws cognito-idp admin-disable-user --user-pool-id us-east-1_VJ4ekQ27c --username rap-synth-a6-oav@example.invalid
aws cognito-idp admin-disable-user --user-pool-id us-east-1_VJ4ekQ27c --username rap-synth-a6-none@example.invalid
# 4. schedule the three secrets for deletion (default 30-day recovery window)
aws secretsmanager delete-secret --secret-id rap/dev/synthetic/a6/dept
aws secretsmanager delete-secret --secret-id rap/dev/synthetic/a6/oav
aws secretsmanager delete-secret --secret-id rap/dev/synthetic/a6/none
```

- **Deleting the Cognito users** is a separate, later step with its own
  confirmation.
- **Expiry is a backstop, not cleanup.** V087 keeps an expired but unrevoked
  grant occupying its scope.
- **End state:**
  - no ACTIVE link and no unrevoked grant names `ARP-SYNTHA60-*`;
  - the history rows remain;
  - no real identity, link or grant has been touched.

## 3. A8: enforcement window 1

### 3.1 The ECS change

Register **revision 107**. It is revision 106 copied exactly, with the same
method and field diff as A1, plus these six variables:

```
APP_AUTHORIZATION_ENFORCEMENT_ENABLED=true
APP_AUTHORIZATION_DEPARTMENT_MATCH=LEAD_UNIT_WITH_DESCENDANTS
APP_AUTHORIZATION_VERSION_SCOPE=FAMILY_WIDE
APP_AUTHORIZATION_CONTACT_DERIVATION=EXPLICIT_GRANT
APP_AUTHORIZATION_RESEARCH_STAFF_ROLES=PI,MPI,COI
APP_AUTHORIZATION_MAX_SIGN_IN_AGE=12h
```

**Unchanged from 106:**
- the image `sha256:c85be89a…5b90`;
- `APP_AUTHORIZATION_ADMINISTRATION_ENABLED=true` (reads only);
- `APP_EXPLORER_ENABLED=false`.

**Not set:**
- administration writes;
- activity audit;
- enrollment;
- `APP_IRB_API_ENABLED`.

V089 and V090 stay unapplied. No `terraform apply`.

Then run:

```bash
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api --task-definition research-archive-platform-dev-api:107
```

### 3.2 Rollback

```bash
aws ecs update-service --cluster research-archive-platform-dev-api \
  --service research-archive-platform-dev-api --task-definition research-archive-platform-dev-api:106
```

- It takes one rolling deployment, about 3 minutes; A1 took 3 minutes 3
  seconds.
- Enforcement writes nothing, so no data needs rolling back.
- **Further back** is `:105`.

**If 107 fails to start:** the service has 100% minimum healthy and no
circuit breaker.
- The 106 task keeps serving, and ECS keeps retrying 107.
- The abort is the same rollback command.

### 3.3 Running order (2-hour cap)

| Time | Step |
|---|---|
| T−1 day | Notice to DEV users (A8 §6) |
| T−30 min | `a8_enforcement_probe.py capture-baseline --execute`, enforcement still OFF. Stops if A, B or C is missing or in the wrong unit |
| T0 | register 107, `update-service`, wait for COMPLETED |
| T+10 | capabilities probe for S1, S3 and S4: `enforcementEnabled=true`, writes false. **Abort if not** |
| T+15 | full run: `run --execute --no-pi` (26 probes) |
| T+30 | go/no-go on synthetic results |
| T+30 to T+100 | Lalitha's session (§3.5) |
| **T+110** | `update-service` to `:106`; confirm `enforcementEnabled=false` |
| T+120 | A6 cleanup (§2.7). **The window ends here at the latest** |

### 3.4 Monitoring, every 10 minutes and at each step

- **ECS:** `describe-services` shows rollout state, running/desired 1/1, the
  last 5 service events, and the task's image digest.
- **Load balancer:** target `rap-dev-api-tg` stays healthy (`/actuator/health`).
- **Logs:** a CloudWatch filter on the new task stream for `ERROR`,
  `Exception` and status `5\d\d`.
- **Capabilities probe:** repeated for S4. It must still say
  `enforcementEnabled=true` and writes false.
- **Owner session:** the owner's own capabilities call. With enforcement on and
  no grant, a 403 "not provisioned" is **expected** for the owner's archive
  pages; the Security Administration reads must still work.
- **Tokens:** Cognito access tokens last 60 minutes, so the probe tool signs in
  again for its second pass.

### 3.5 Lalitha's session needs a decision before the window

With enforcement on, any account without an active link and grant sees only
"No archive access has been provisioned".
- That includes Lalitha's account and BU's named T-accounts.
- This package provisions **synthetic** accounts only. It changes no real
  identity.

| Option | What Lalitha can do |
|---|---|
| **(L1) Witness:** she joins a shared screen while the synthetic probes and a browser session as S1/S3/S4 run | Observe and sign off on what she saw. No real accounts change |
| **(L2) Provision BU's T-accounts:** T-DEPT, T-OAV, T-NONE, T-PI, as BU names them, with links and expiring grants under a separate approval | Test S2–S6 herself, and US-PI-001 #2 becomes testable. This changes real accounts, so it needs BU to name them and you to approve it |

**Recommendation: L1 for window 1.** Plan L2 for window 2, once BU has named
the T-accounts.

### 3.6 Abort conditions

Roll back to `:106` immediately if any of these happens:
- a capabilities probe shows `enforcementEnabled=false` or writes available;
- **any** LEAK: a hidden record's number or title appears in any body;
- S4 receives record data or a 200 list;
- 5xx errors or exceptions on in-scope reads, or the health target goes
  unhealthy;
- 107 does not reach COMPLETED within 15 minutes;
- an unexpected DEV-user impact is reported;
- the T+110 time is reached.

**A FAIL is not fixed inside the window.**

### 3.7 What can change afterwards

- **Up to 7 criteria** can become automated-verified live, all except
  US-PI-001 #2:
  - US-DEP-001 #1 #2;
  - US-DEP-005 #1 #2;
  - US-OAV-001 #2;
  - US-SEC-003 #1 #2.
- **US-OAV-001 #2 depends on §2.4.**
- The tracker moves only on PASS evidence.
- The BU count stays **25/47** until you direct otherwise on that evidence.

## 4. Approvals requested (each separate)

| ID | Approve | |
|---|---|---|
| A6-0b | one read-only SELECT of `account_number` for Awards A, B and C (§2.4 option i) | ☐ |
| PR #170 | merge, so the operator definitions on `main` match what is registered | ☐ |
| A6-1 | create 3 Cognito users + 3 secrets (account creation) | ☐ |
| A6-1b | register `dev-grant-admin:6`, `dev-link-identity:2` | ☐ |
| A6-2 | 3 identity links (dry run, then `--confirm`) | ☐ |
| A6-3 | 2 grants with expiry (dry run, then `--confirm`) | ☐ |
| A8 | revision 107 and the window, date ________, start ____ (2h cap) | ☐ |
| L1 / L2 | Lalitha's session mode | ☐ |
| Close-out | revoke, unlink, disable, schedule secret deletion | ☐ |

Status 2026-11-18: A6-0b, PR #170, A6-1, A6-1b and A6-2 approved and
**done** (§5). A6-3 approved on condition: created only once the A8 window is
scheduled, expiring 24 hours after the planned window end.

## 5. A6 execution record (2026-11-18)

Operator: hitesh (executed by Claude Code). Approval reference
`OWNER-APPROVAL-2026-11-18-A6`. AWS account 770203350335. Production
untouched. Enforcement, administrator writes, auditing, IRB exclusion OFF;
V089/V090 unapplied; the API service stayed on `:106` throughout.

### 5.1 PR #170

Merged at exact head `cd67d36bdf0d143cf056147b74004801aad5d266` (CI run
44196515849 green, six files) as merge commit
`c339e94984e5f51c1a94acd9eb96232d1f7526d7`, 14:32:54Z.

### 5.2 A6-0b, read-only IO check: PASS

One-off task `438d5f9e27f546f7b661ba5a83628726` on loader `:237` (the nightly
sync's revision; no new image or revision), connection bound read-only at
connect and verified (`default_transaction_read_only=on`,
`transaction_read_only=on`). Matched on `TRIM(award_version.account_number)`,
the column `AwardAccountNumberIoSql` uses.

| Award | Version rows | Carry `9500312705` | Other account |
|---|---|---|---|
| C `209407-00002` (1330420000) | 14 | 9 (5 blank, no other value) | none |
| B `211144-00007` (2573180018) | 2 | 0 | one, ending 7253 |
| A `212128-00002` (2573180018) | 1 | 0 | one, ending 6722 |

`9500312705` belongs to exactly one award number (C), 9 rows, never unit
2573180018. Under `FAMILY_WIDE` the IO predicate is wrapped per
`award_number` (`RecordScopeSqlBuilder.familyWrap`), so S3 reaches all 14 of
C's versions. The §2.3 matrix stands.

### 5.3 A6-1, Cognito users and secrets

| Role | Sign-in (email) | Cognito username = `sub` | State | Groups | Secret |
|---|---|---|---|---|---|
| S1 dept | `rap-synth-a6-dept@example.invalid` | `a4f894d8-a031-700d-4e33-b135f205398c` | CONFIRMED, enabled | none | `rap/dev/synthetic/a6/dept` (`…dept-m0cRHs`) |
| S3 oav | `rap-synth-a6-oav@example.invalid` | `54584488-9051-7088-04de-bb7027375924` | CONFIRMED, enabled | none | `rap/dev/synthetic/a6/oav` (`…oav-oD31OS`) |
| S4 none | `rap-synth-a6-none@example.invalid` | `242884e8-10a1-70af-55c4-b14efea21923` | CONFIRMED, enabled | none | `rap/dev/synthetic/a6/none` (`…none-8cT1rn`) |

`MessageAction=SUPPRESS`; nothing was sent. Passwords: 32 random characters,
generated in memory, set permanent, stored only in the secret (JSON
`username`, `password`, `userPoolId`, `sub`), never printed or logged. Secrets
are tagged `purpose=rap-a6-synthetic`, `cleanup=after-a8-window-1`.

### 5.4 A6-1b, operator task definitions

`scripts/verify-grant-admin-taskdef.sh` passed for both files at `c339e94`,
then registered:
- `research-archive-platform-dev-grant-admin:6`
- `research-archive-platform-dev-link-identity:2`

Both on image `sha256:c85be89a…5b90` (the rev-106 API image), profiles
`aws,grant-admin` / `aws,link-identity`, zero `APP_AUTHORIZATION_*`
variables. The running API service was not changed.

### 5.5 A6-2, identity links

Dry runs first (`DRY_RUN_OK`, every check passed, including "acting
administrator holds an ACTIVE membership" and "archive identity has never
been linked"), then `--confirm` (`APPLIED`). Actor `ARP-FK7QY195-QW5HV7G0`,
the only ACTIVE ARCHIVE_ADMIN (member id 3).

A first dry-run attempt was refused before any check could pass
(`--cognito-region is required`): the shell passed the three Cognito options
as one argument. The tool reported "Nothing was written"; the run was
repeated with the options separated.

| Link id | Archive identifier | Method | Status | Kuali person | Audit row |
|---|---|---|---|---|---|
| 5 | `ARP-SYNTHA60-00000001` (S1) | ADMIN_VERIFIED | ACTIVE | **null** | 6 |
| 7 | `ARP-SYNTHA60-00000003` (S3) | ADMIN_VERIFIED | ACTIVE | **null** | 8 |
| 6 | `ARP-SYNTHA60-00000004` (S4) | ADMIN_VERIFIED | ACTIVE | **null** | 7 |

Verified afterwards by a read-only query (loader task
`68c483fd2e054f289b853aed148024e0`): each link's `cognito_subject` is the
matching synthetic `sub`; no synthetic `sub` is linked to any other identity;
`login_name` null; zero grants, administrator rows or person-status rows for
`ARP-SYNTHA60-*`. Totals after: 4 links (4 ACTIVE), 1 grant (pre-existing,
not synthetic, untouched).

### 5.6 Not done (by the owner's condition)

**A6-3 grants are not created.** They are created only after the A8 window is
scheduled, with `--expires-at` = planned window end + 24 hours:
- S1: `--grantee=ARP-SYNTHA60-00000001 --type=UNIT --unit-number=2573180018`
  (no `--include-descendants`);
- S3: `--grantee=ARP-SYNTHA60-00000003 --type=IO --io-value=9500312705`;
- S4: none.

### 5.7 Resource register and cleanup

| Resource | Identifier | Expiry | Cleanup |
|---|---|---|---|
| Cognito user S1 | `a4f894d8-…398c` / dept email | none (Cognito) | `admin-disable-user`, later delete with approval |
| Cognito user S3 | `54584488-…5924` / oav email | none | same |
| Cognito user S4 | `242884e8-…1923` / none email | none | same |
| Secret ×3 | `rap/dev/synthetic/a6/{dept,oav,none}` | none | `delete-secret` (30-day recovery) |
| Identity link 5, 7, 6 | `authz.identity_link` | none | `dev-link-identity:2 --unlink-link-id=<id> --confirm` (status change, row kept) |
| Audit rows 6–8 | `authz.access_audit` | permanent | none: history is kept |
| Task definitions | `dev-grant-admin:6`, `dev-link-identity:2` | n/a | keep; superseded by the next sync |

Until A8, the links confer no access: enforcement is off, and they hold no
grants.

## 6. A8 window 1: approved schedule and preparation (2026-11-18)

**Approved:** Friday 2026-11-20, 10:00–12:00 ET (15:00–17:00Z), reference
`OWNER-APPROVAL-2026-11-18-A8`. L1 witness mode for Lalitha; no real BU test
account is created or changed. Grants expire `2026-11-21T17:00:00Z`.
**Prerequisites before execution:** Lalitha's availability confirmed and the
§6 DEV-user notice sent; if either is unmet, postpone and ask for a new date.

Preparation done 2026-11-18, all non-writing:

| Check | Result |
|---|---|
| Grant dry runs on `dev-grant-admin:6` | both `DRY_RUN_OK`: S1 UNIT 2573180018 only, no descendants; S3 IO (Awards only); expiry accepted; no identical active grant; actor ACTIVE. Nothing written |
| Revision 107 input (built from `:106`, **not registered**) | field diff = exactly the six `APP_AUTHORIZATION_*` additions; image `c85be89a…`, 5 secrets, `APP_EXPLORER_ENABLED=false`, `ADMINISTRATION_ENABLED=true` (reads) unchanged; no audit, IRB, writes or enrollment key |
| Synthetic sign-in (ADMIN_USER_PASSWORD_AUTH, client `seqgmc8s…`) | S1, S3, S4 → `/api/v1/me/capabilities` 200, `administrator=false`, `enforcementEnabled=false`, writes unavailable. Tokens never printed |
| `a8_enforcement_probe.py self-test` | PASS (9 cases) |
| Baseline rehearsal (enforcement OFF, GET only) | records A/B/C resolved; T&M ids for B and C found; **no Negotiation linked to C**, so the H2 probe will be recorded as not tested, not passed |

Friday running order (ET): 09:00 prerequisites re-confirmed → 09:15 A6-3
grants (dry run, `--confirm`, read-only scope verification) → 09:30 baseline
→ 10:00 register 107 (re-diffed) and deploy → 10:10 capabilities → 10:15
probes → 10:30 documented go/no-go → L1 session → **11:50 rollback to `:106`**
→ verify enforcement OFF + core smoke → cleanup (§2.7) → workbook.

## 7. Revised A8 window 1: fully synthetic and automated (approved 2026-11-18)

Supersedes §3.3's Lalitha slot and §3.5. **Approved by the owner on 2026-11-18,
together with A8_OWNER_MANUAL_UAT.md (manual session Fri 10:30–11:30 ET). No
Lalitha participation in this first technical test. Nothing has been
executed: enforcement stays OFF until the DEV-user notice has been sent and
every prerequisite in §7.4 is re-confirmed at 09:00 Friday.**

### 7.1 Who takes part

- **No business user.** Lalitha does not take part live; she reviews the
  evidence afterwards.
- Identities: only S1 (Department, `UNIT 2573180018`), S3 (IO `9500312705`)
  and S4 (No-Grant Control). No real BU identity is used, queried or impersonated.
- Operator present throughout (hitesh, with Claude Code executing) to monitor
  and to roll back.

### 7.2 The matrix: `scripts/a8_authz_matrix.py`

It is baseline-differential:
- With enforcement OFF, it records every probe path's status and which fixture
  identifiers each body carried. It stores no bodies.
- With enforcement ON, it repeats every probe as S1, S3 and S4 and compares
  each one with its baseline.

It writes nothing. It sends GETs, plus one read-only POST to AI evidence search.

Verdicts are recorded separately:

| Verdict | Meaning |
|---|---|
| **PASS** | Matches the expected result and the baseline |
| **FAIL** | Wrong status, in-scope access lost, enforcement not confirmed, or an admin path open to a non-admin |
| **LEAK** | An out-of-scope identifier appeared in a body. **The run stops at once and DEV is rolled back** |
| **REVIEW** | An out-of-scope identifier appeared, but it also appears in an in-scope baseline body for the same role. A person decides |
| **NOT TESTED** | The baseline cannot tell allowed from denied: the record has no data there, the endpoint is group-gated, disabled or out of scope. **Never counted as PASS** |

Fixture records, resolved and checked at baseline:

| Record | Identifier | Unit | S1 Dept | S3 IO | S4 |
|---|---|---|---|---|---|
| Award A | `212128-00002` (3784681) | 2573180018 | allow | deny | 403 |
| Award B | `211144-00007` (3899037) | 2573180018 | allow | deny | 403 |
| Award C | `209407-00002` (3476042) | 1330420000, IO 9500312705 | deny | allow | 403 |
| Award G (CGS) | `212137-00001` (3798004) | 1250000000 | deny | deny | 403 |
| Negotiation 12373 (A's) | | 2573180018 | allow | deny (IO never reaches Negotiations) | 403 |
| Negotiation 12511 | | 1330420000 | deny | (not probed) | |
| Subaward 92899 (C's, code 3259) | | requisitioner 1000000000 | deny | deny (IO never reaches Subawards) | 403 |
| Proposal 01092706 (1092805) | | 2573180018 | allow | deny | 403 |
| Proposal 01444988 (1452409, G's) | | 1250400000 | deny | deny | |

Coverage of the 2026-11-18 rehearsal: **700 probes**, of which 626 can produce
a verdict.

| Area | Endpoints probed |
|---|---|
| Award workspace | all 26 `/api/v1/awards/{id}/…` paths (summary, versions, people, unit/sponsor/central-admin contacts, amounts, terms, custom data, comments, budget ×5, report PDFs) and 9 by-number/legacy `/api/awards/{n}/…` paths, for A, B, C and G |
| Search | Award search, Award version search, Global Search (by number and by title) |
| Documents | Document Explorer (`query`, `businessRecordNumber`), document search, Archived File Finder |
| Related records | funding proposals, funding subawards, negotiations, legacy `/proposals` and `/funding` (#159) |
| Time & Money | summary, actions, history; own transaction allowed; another award's transaction and document refused both directly and **through an in-scope award id** (H1: the latter is already 404 with enforcement off, scored as structural) |
| SAP | `sap-transmissions` for every fixture (H3) |
| Negotiations | detail and 6 sub-resources for 12373 and 12511; lists by unit of A/B and of C (H2) |
| Subawards | detail and 11 sub-resources for 92899; list and Global Search by its code |
| Proposals | 8 `/api/v1/proposals/{id}` paths and 3 legacy paths; proposal search; Global Search by proposal number |
| AI Evidence Search | `POST /api/ai/awards/{n}/evidence-search`, allowed and denied, per role (H5). It is enabled on DEV via `app.search.semantic.enabled=true` |
| Dashboard | the scoped Awards count must fall below the unscoped 40,926 |
| Admin API | identities, administrators, audit: refused to all three accounts |
| No-Grant Control | every path above as S4: 403 `ACCESS_NOT_PROVISIONED`, no identifier |

**Cross-module indexes are refused, not filtered (found 2026-11-18, code-verified).**
`/api/documents/search`, `/api/v1/documents` and `/api/v1/attachments/search`
UNION Award, Proposal, Negotiation, Subaward and IRB rows. Under enforcement
they call `AwardRecordAccess.requireUnrestrictedForCrossModuleSearch`, which
throws for every scoped caller: `403 ACCESS_DENIED` ("Access to the archive is
denied for this account."). The matrix judges these 32 probes (S1 and S3) as
kind `refused`: 403 `ACCESS_DENIED` with no identifier is PASS; any 200 is FAIL.
Before this fix they would have been reported as false FAILs. UX observation for
the record: the specific "use the module-specific search" text is not shown; the
caller sees the generic denial message.

**Global Search drops IRB under enforcement.** `GlobalSearchService.irbExcludedFromSharedSearch()`
returns true once enforcement is on, so IRB rows leave shared search during the
window. Only the IRB module's own `/api/irb` endpoints stay reachable.

Known NOT TESTED (40 at rehearsal), each with its reason in the evidence:
- **Attachments and report-with-attachments (29 at rehearsal):** 403
  `ATTACHMENT_ACCESS_DENIED` for the synthetic accounts, because they are not in
  the Cognito group `ArchiveAttachmentViewer` (US-DEP-002). The matrix records
  any `ATTACHMENT_ACCESS_DENIED` as NOT TESTED, never PASS or FAIL. **§7.5
  shows the group is safe to add and gives the prepared steps.** If it is
  added, these probes are judged normally.
- **Investigators (3):** a lookup by a named person's email. Excluded, because
  this test never queries a real identity.
- **Explorer (off) and IRB (3 + 3):** IRB is excluded from release scope. Its
  API stays on, because IRB exclusion is not activated in A8, so IRB stays
  reachable to any signed-in user during the window, exactly as today.
- **AI summary and AI questions:** not probed. `app.ai.enabled` is unset, and
  probing them would require a model call at baseline.
- **Two list searches** that returned no fixture identifier at baseline.

The 26-probe `a8_enforcement_probe.py` also runs as an independent cross-check.

### 7.3 Running order, Friday 2026-11-20 (ET)

| Time | Step | Stop if |
|---|---|---|
| Thu | DEV-user notice sent (§6 of A8_ENFORCEMENT_WINDOW_1.md) | not sent: postpone |
| 09:00 | **notice actually sent**: search mukadder@bu.edu Sent Items for the subject "Research Archive DEV: access restricted Friday 20 Nov", with all 10 recipients. A draft is not enough. Also: AWS account, `:106` serving 1/1, no other task running | **not found in Sent Items: A8 does not start**; then run the cancellation cleanup below |
| 09:15 | A6-3 grants: dry run, then `--confirm`; read-only query shows exactly 2 grants, the right type/unit/IO, `include_descendants=false`, expiry `2026-11-21T17:00:00Z`, none for S4 | any difference |
| 09:20 | approved 2026-11-18: §7.5 group step (add the 3 synthetic users to `ArchiveAttachmentViewer`, verify membership is exactly those 3 plus the existing members). It must be before the baseline, so baseline attachment probes return 200 | any other user changes |
| 09:30 | baseline: matrix `baseline --execute` + `capture-baseline --execute` | a fixture missing or in the wrong unit |
| 10:00 | register 107 (re-diffed: exactly the six settings); `update-service` | 107 not COMPLETED by 10:15 |
| 10:10 | capabilities for S1, S3, S4: enforcement true, writes false (automatic stop) | otherwise |
| 10:12 | matrix `run --execute` (~2–3 min, 8 parallel workers; stops automatically on LEAK), then the 26-probe cross-check | LEAK, S4 data, 5xx |
| 10:30 | **documented go/no-go**: tally by verdict, every FAIL/REVIEW listed, ECS/ALB/log health | |
| 10:30–11:30 | only if separately approved: the owner's manual session (A8_OWNER_MANUAL_UAT.md). Otherwise roll back straight after go/no-go | |
| **≤ 11:50** | `update-service` to `:106`; wait COMPLETED; capabilities `enforcementEnabled=false`; core smoke (dashboard 40926/267386/33960/119788/10775/3511/491678, search, detail, Global Search, documents) | |
| ≤ 12:00 | cleanup §2.7: revoke grants → unlink 5, 7, 6 → (if §7.5 was applied) remove the 3 users from `ArchiveAttachmentViewer` → disable the 3 users → schedule secret deletion; read-only check: 0 unrevoked synthetic grants, 0 ACTIVE synthetic links, group members back to the pre-window list | |
| after | workbook: per-criterion PASS / FAIL / NOT TESTED with evidence file hashes; BU stays 25/47 unless live PASS evidence supports a change and the owner directs it | |

Abort, then roll back immediately, on any of:
- any LEAK;
- S4 receiving data or a 200 list;
- a capabilities check not showing enforcement;
- unexpected 5xx errors, or the ALB target going unhealthy;
- 107 not COMPLETED by 10:15;
- material DEV-user impact;
- 11:50 reached.

**Cancellation or abort cleanup (owner condition 4).** Whatever ends the window
(cancelled at 09:00, aborted at any step, or completed), the same cleanup runs:
- roll back to `:106` if 107 was ever deployed;
- remove S1, S3 and S4 from `ArchiveAttachmentViewer` (a no-op if never added);
- check that the group's membership equals the pre-window list;
- revoke grants, unlink 5/7/6, disable the users, schedule secret deletion.

If the window is only postponed, the group is still removed. It is re-added at
09:20 on the new date.

**Security failures and usability limitations are recorded separately.** The
evidence tally has two lists:
1. **Security:** PASS / FAIL / LEAK / REVIEW / NOT TESTED, judged by the matrix.
2. **Usability observations:** never counted as a FAIL. Known in advance:
   - **U1:** Documents search, the document explorer and the Archived File
     Finder refuse every scoped account with the generic "Access to the archive
     is denied for this account." The "use the module-specific search" guidance
     is not shown.
   - **U2:** Global Search silently omits IRB under enforcement, with no notice
     to the user.

   Anything the owner observes in the manual session goes here unless it
   discloses a record that should not be visible.

### 7.4 Still needed before Friday

1. ~~The owner's approval of this §7~~: approved 2026-11-18, with the manual
   session (Fri 10:30–11:30 ET).
2. **The notice.** The audience is approved: 10 addresses, each verified on
   2026-11-18 as a DEV pool sign-in (antcast, dbaldwin, dhaywood, jfnick,
   lhuval, lsreddy, mukadder, rolilly, rrussell, wrh @bu.edu). The text is
   drafted for sending from mukadder@bu.edu. **Not sent: the sending method
   awaits the owner's confirmation.** Enforcement is not enabled until it has
   gone out.
3. ~~The §7.5 group step~~: approved 2026-11-18 for 09:20 Friday, for A8
   only, and removed in every cleanup path.
4. **Notice draft created** in mukadder@bu.edu Drafts on 2026-11-18 (10
   recipients). The owner reviews and sends it. **The 09:00 check requires it in
   Sent Items.**

### 7.5 `ArchiveAttachmentViewer`: does it grant record access? (verified 2026-11-18)

**No. It is an additional gate, never an alternative to record scope.**
Evidence, on `origin/main` c339e94:

| Check | Finding |
|---|---|
| Where the group is read | Cognito groups become `ROLE_<group>` in `SecurityConfiguration.jwtAuthenticationConverter()`. `ROLE_ArchiveAttachmentViewer` is read in exactly one place: `AttachmentAuthorizationService.requireAttachmentAccess`. No authorization-package class (scope resolver, grants, admin seat) reads any Cognito group |
| Per-record list and download, all four modules | The controller calls `requireAttachmentAccess`, then the service calls `recordAccess.requireAward` / `requireProposal` / `requireSubaward` / `requireNegotiation` before any row is read (`AwardArchiveService` findAttachments/downloadAttachment; `ProposalArchiveV1Service`; `SubawardArchiveService`; `NegotiationArchiveService`) |
| Report with attachments | Group gate, then `findSummary`, which calls `recordAccess.requireAward` first |
| Archived File Finder (`/api/v1/attachments/search`) | Group gate, then `requireUnrestrictedForCrossModuleSearch`: refused for every scoped caller |
| Explorer attachments | Group gate; Explorer is OFF on DEV |
| No-Grant (S4) | Passes the group gate, then `requireProvisioned` refuses: 403 `ACCESS_NOT_PROVISIONED`. Without the group, S4's attachment probes stop at the group gate and prove nothing |

**Owner approval, 2026-11-18:** add the group at 09:20 Friday for A8 only, and
remove it in every cleanup path.

Side effect before and after the window: with enforcement OFF, any account in
the group can open every attachment, as the existing members do today. The
membership is therefore added at 09:20 on Friday, not earlier, and removed at
cleanup. Passwords stay in Secrets Manager only.

**No unrelated attachments are opened while enforcement is OFF (owner condition 3):**
- The 09:30 baseline reads attachment *lists* (metadata) only, and only for the
  test records A, B, C and G, plus negotiation 12373/12511, subaward 92899 and
  proposals 01092706/01444988.
- It never downloads a file.
- It skips `report-with-attachments.pdf`, the one endpoint that embeds file
  content. That probe is judged only under enforcement: allow = 200 PDF, deny =
  403/404.
- The evidence keeps a status, content type, SHA-256 and the test identifiers
  seen, never a response body.
- Nobody opens attachments by hand before 10:10.

**Live proof that the group adds no record permission:**
- S4 is in the group but has no identity scope. Every attachment probe for S4
  must return 403 `ACCESS_NOT_PROVISIONED`.
- S1 and S3 are in the group. Their out-of-scope attachment probes must return
  403/404 with no file names.
- Any 200 is a LEAK, and the window aborts.

Prepared, not executed (AWS_PROFILE=bu-nprd, account 770203350335):

```
# before: record the current membership
aws cognito-idp list-users-in-group --user-pool-id us-east-1_VJ4ekQ27c --group-name ArchiveAttachmentViewer --query 'Users[].Username'
# add (09:20 Friday, only on the owner's go)
for u in rap-synth-a6-dept@example.invalid rap-synth-a6-oav@example.invalid rap-synth-a6-none@example.invalid; do
  aws cognito-idp admin-add-user-to-group --user-pool-id us-east-1_VJ4ekQ27c --group-name ArchiveAttachmentViewer --username "$u"
done
# verify: the before list plus exactly these 3
# cleanup (before disabling the users)
for u in rap-synth-a6-dept@example.invalid rap-synth-a6-oav@example.invalid rap-synth-a6-none@example.invalid; do
  aws cognito-idp admin-remove-user-from-group --user-pool-id us-east-1_VJ4ekQ27c --group-name ArchiveAttachmentViewer --username "$u"
done
```

Tokens are issued fresh by `a8_tokens.py` at each step, so the new group claim
is present from the baseline onwards.
