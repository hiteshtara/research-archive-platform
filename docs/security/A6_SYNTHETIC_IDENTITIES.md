# A6: synthetic identities for the first enforcement window

**Status: prepared, not executed.**
- No Cognito user, identity link or grant has been created.
- No ECS task has been launched, not even a dry run.
- Every command below is written out for review. Each write needs the owner's
  separate approval (sub-steps **A6-0** to **A6-3**).

**Purpose:** give the automated probes in
[`A8_ENFORCEMENT_WINDOW_1.md`](A8_ENFORCEMENT_WINDOW_1.md) signed-in DEV
identities that belong to nobody. That keeps three rules intact:
- no real user's access changes;
- nobody is impersonated;
- the BU acceptance session (Lalitha) keeps its own named T-accounts, which
  this package does not create.

Companion to [`DEV_SECURITY_ACTIVATION_AND_BU_ACCEPTANCE_PLAN.md`](DEV_SECURITY_ACTIVATION_AND_BU_ACCEPTANCE_PLAN.md)
(step A6) and [`DEV_SECURITY_PILOT_PACKAGE.md`](DEV_SECURITY_PILOT_PACKAGE.md).

---

## 1. How a synthetic identity can sign in to DEV

I checked four mechanisms against the code and configuration.

| Mechanism | Usable for live DEV probes? | Why |
|---|---|---|
| BU Shibboleth (SAML) through Cognito | **No** | Every SAML sign-in is a real BU person, so a synthetic person cannot exist on this path |
| **Native Cognito user + `ADMIN_USER_PASSWORD_AUTH`** | **Yes. This is the mechanism.** | See below |
| Identity lab / `authz-demo` persona header | No | Local and disposable only. Compiled solely with `-Pauthz-demo`. It refuses to start unless the database is a loopback DB marked disposable, and `DemoClassesAbsentFromDefaultBuildTest` proves the DEV image has none of it |
| View Access As | No | A design document only (`VIEW_ACCESS_AS_TEMPORARY_QA_DESIGN.md`). By design it simulates the evaluator rather than exercising the real request path, so it cannot be live evidence |

Why the native path works:

- **The app client keeps `COGNITO` as an identity provider** next to
  Shibboleth (`terraform/modules/cognito/main.tf`).
- **Admin-only user creation:** `allow_admin_create_user_only = true`, so
  nobody can self-register.
- **Browser-free token flow:** `cognito_allow_admin_password_auth = true` in
  DEV adds `ALLOW_ADMIN_USER_PASSWORD_AUTH` to the client. That allows a token
  through `aws cognito-idp admin-initiate-auth` with no browser.
  - The variable's own documentation says it exists for exactly this:
    admin-created dev/test users.
- **Real token path:** the API validates the resulting token exactly as it
  validates a Shibboleth one (same issuer, same `sub` semantics). The probes
  therefore exercise the real request path.

**Caveat, not yet re-checked live.** The DEV setting comes from the local,
untracked `terraform.tfvars`. Today's read-only check of the live app client
could not run because the AWS session token had expired.
- A6-0 must first confirm, read-only, that the live client lists
  `ALLOW_ADMIN_USER_PASSWORD_AUTH`:
  `aws cognito-idp describe-user-pool-client`.
- If it is absent, the probes have no sign-in path. Adding the flow would be a
  Cognito configuration change, which is a separate decision and not part of
  this package.

### `auth_time` and the sign-in age guard

`admin-initiate-auth` issues a token whose `auth_time` is the moment of sign-in.
So `APP_AUTHORIZATION_MAX_SIGN_IN_AGE` (proposed `12h`) is satisfied for the
whole two-hour window. The probe tool signs in fresh at the start of the
window.

---

## 2. The synthetic set

Every value is obviously synthetic and cannot collide with a real person:

- **Cognito username:** `rap-synth-a6-<role>`.
- **Email attribute:** `rap-synth-a6-<role>@example.invalid`. `.invalid` is
  reserved (RFC 2606) and can never receive mail.
- **Archive identifier:** `ARP-SYNTHA60-0000000N`. It is well formed under
  `ArchivePrincipalId`'s Crockford alphabet and visibly synthetic, so it cannot
  be mistaken for one minted for a person.
- **Cognito `sub`:** assigned by Cognito at creation (immutable). Read back
  with `admin-get-user`; never typed by hand.
- **Grant expiry:** the day after the window, `--expires-at=<window date + 1>T00:00:00Z`.
  Expiry is a backstop only. **The window closes by revoking** (§5), because
  V087 keeps an expired-but-unrevoked grant occupying its scope.

Scopes follow BU's own test records in
[`LALITHA_BU_ACCEPTANCE_CHECKLIST.md`](LALITHA_BU_ACCEPTANCE_CHECKLIST.md), so
the automated and BU results are about the same records:

| Record | Award | Account (IO) | Lead unit |
|---|---|---|---|
| A | 212128-00002 | 9500316722 | 2573180018 |
| B | 211144-00007 | 9500317253 | 2573180018 |
| C | 209407-00002 | 9500312705 | 1330420000 |

| # | Username | ARP | Grant | Used in window 1 |
|---|---|---|---|---|
| S1 | `rap-synth-a6-dept` | `ARP-SYNTHA60-00000001` | `UNIT 2573180018` (no descendants) | **yes** |
| S2 | `rap-synth-a6-pi` | `ARP-SYNTHA60-00000002` | `CONTACT_DERIVATION`. **Blocked**, see §3 | **yes, if §3 is approved** |
| S3 | `rap-synth-a6-oav` | `ARP-SYNTHA60-00000003` | `IO 9500312705` | **yes** |
| S4 | `rap-synth-a6-none` | `ARP-SYNTHA60-00000004` | **none: No-Grant Control** | **yes** |
| S5 | `rap-synth-a6-dept-desc` | `ARP-SYNTHA60-00000005` | `UNIT 1250000000 --include-descendants` (pilot value) | no, later window |
| S6 | `rap-synth-a6-parent` | `ARP-SYNTHA60-00000006` | `PARENT_AWARD 100380-00001` (pilot value) | no, later window |

**Not created:**
- a CENTRAL identity: refused until phase 2, V090 and a second administrator
  are in place;
- an administrator identity: writes stay off.

**Not in any Cognito group.** Specifically, not `ArchiveAttachmentViewer`.
Attachment downloads therefore return `ATTACHMENT_ACCESS_DENIED` for every
synthetic user, whatever their grant. That is outside the eight criteria and
is not counted either way.

### Do the records exist in DEV?

Not verified from the repository. The values come from BU's spreadsheet
(`ANTHONY_REQUIREMENTS_TRACEABILITY.md`, `LALITHA_BU_ACCEPTANCE_CHECKLIST.md`);
no repo document records them as observed in DEV. The probe tool's
`capture-baseline` step settles it.
- It reads them with enforcement still OFF, using the read-only endpoints, and
  records their internal ids.
- It **refuses to continue** if any of A, B or C is missing, or if A/B are not
  in unit 2573180018 or C is not in unit 1330420000.

---

## 3. Blocker: the Research Staff identity (S2)

`CONTACT_DERIVATION` resolves through `identity_link.kuali_person_id`, which
must equal an `archive.award_person.person_id`.

- **DEV holds real archive data only.** It has no synthetic Kuali person.
- **Without a PERSON_ID:** S2's grant is written and confers nothing.
  `AccessScopeResolver` collapses it to `NO_ACTIVE_GRANTS`.
- **With a PERSON_ID:** it has to be a real person's, specifically the PI of
  Award A.

What a synthetic link carrying a real PERSON_ID does and does not do. Checked
against V082 and V085:

- It does **not** change the real person's access. The only unique indexes on
  `identity_link` are one active link per Cognito identity and one per archive
  identity. Nothing makes `kuali_person_id` unique, so the person's own future
  link is not blocked.
- It does **not** sign in as them. The Cognito identity is the synthetic one.
- It **does** reproduce that named person's record set under a synthetic
  account for the length of the window.
  - With enforcement off, every DEV user can already read those records, so it
    exposes nothing new.
  - It is still the closest thing in this package to acting "as" a real
    person.

**Owner decision required. This package does not assume one.**

| Option | Effect on the eight criteria |
|---|---|
| **(a) Approve a scope-only S2 link** carrying Award A's PI's PERSON_ID: labelled synthetic, expiring at window end, unlinked at close | US-PI-001 #2 is proven by automation in window 1 |
| **(b) No S2**: US-PI-001 #2 is checked only in the BU session with the real T-PI account | Automation proves **7** criteria; PI-001 #2 waits for BU's T-PI |

The PERSON_ID is not written here. If (a) is approved, it is found at A6-1 time
by the documented Award-first pairing (`PHASE2_PILOT_PLAN.md` §1). That means a
read-only lookup of Award A's PI role row through the existing approved
read-only path, never by name.

---

## 4. Commands, in order (none has been run)

Values in `<>` are filled in at execution time. `$WINDOW_END` is the day after
the window, as `YYYY-MM-DDT00:00:00Z`.

### A6-0: Read-only checks (no approval needed; nothing changes)

```bash
export AWS_PROFILE=bu-nprd
aws sts get-caller-identity --query Account --output text      # expect 770203350335
aws cognito-idp describe-user-pool-client --user-pool-id us-east-1_VJ4ekQ27c \
  --client-id <app client id> --query 'UserPoolClient.ExplicitAuthFlows'
# expect ALLOW_ADMIN_USER_PASSWORD_AUTH; if absent, stop (see section 1)
aws cognito-idp admin-get-user --user-pool-id us-east-1_VJ4ekQ27c --username rap-synth-a6-dept
# expect UserNotFoundException for all six: nothing pre-exists
```

### A6-1: Create the synthetic Cognito users (**APPROVAL: account creation**)

One per role. `--message-action SUPPRESS` sends nothing. The password is
generated by the operator's tooling, stored only in Secrets Manager under
`rap/dev/synthetic/a6/<role>`, and never printed, logged or committed.

```bash
aws cognito-idp admin-create-user --user-pool-id us-east-1_VJ4ekQ27c \
  --username rap-synth-a6-dept --message-action SUPPRESS \
  --user-attributes Name=email,Value=rap-synth-a6-dept@example.invalid Name=email_verified,Value=true
aws cognito-idp admin-set-user-password --user-pool-id us-east-1_VJ4ekQ27c \
  --username rap-synth-a6-dept --password "<from secrets manager, not shown>" --permanent
aws cognito-idp admin-get-user --user-pool-id us-east-1_VJ4ekQ27c --username rap-synth-a6-dept \
  --query 'UserAttributes[?Name==`sub`].Value' --output text      # -> <sub>
```

**Rollback:**
```bash
aws cognito-idp admin-disable-user --user-pool-id us-east-1_VJ4ekQ27c --username <user>
```
Deletion is a separate, later step and needs its own confirmation.

### A6-2: Link each identity (**APPROVAL: writes `authz.identity_link`**)

The dry run is the default. Shape as in [`../runbooks/DEV_TEST_ENABLEMENT.md`](../runbooks/DEV_TEST_ENABLEMENT.md) step 4.

```bash
aws ecs run-task --cluster research-archive-platform-dev-api \
  --task-definition research-archive-platform-dev-link-identity \
  --launch-type FARGATE --network-configuration <as for the API> \
  --overrides '{"containerOverrides":[{"name":"link-identity","command":[
    "--aws-account-id=770203350335",
    "--environment=dev",
    "--cognito-user-pool-id=us-east-1_VJ4ekQ27c",
    "--cognito-region=us-east-1",
    "--cognito-issuer=https://cognito-idp.us-east-1.amazonaws.com/us-east-1_VJ4ekQ27c",
    "--cognito-subject=<sub of rap-synth-a6-dept>",
    "--subject-identifier=ARP-SYNTHA60-00000001",
    "--actor=<acting administrator ARP>",
    "--operator=<the person running this>",
    "--reason=A6 synthetic identity for A8 enforcement window 1 (not a person)",
    "--approval-reference=<A6-2 approval>"
  ]}]}'
```

- Run it once per identity S1–S4, changing `--cognito-subject` and
  `--subject-identifier`.
- `--kuali-person-id=<numeric>` goes on **S2 only, and only under option (a)**.
- Read every dry run's output.
- Then repeat with `--confirm` added.

### A6-3: Issue the grants (**APPROVAL: writes `authz.access_grant`**)

Three grants: S1, S2 (if approved) and S3. **S4 gets none.** If a run names S4,
stop: that would use up the control.

```bash
# same run-task shape, task definition research-archive-platform-dev-grant-admin, container grant-admin
"--aws-account-id=770203350335", "--environment=dev",
"--actor=<acting administrator ARP>", "--grantee=ARP-SYNTHA60-00000001",
"--type=UNIT", "--unit-number=2573180018",
"--expires-at=$WINDOW_END",
"--operator=<...>", "--reason=A6 synthetic, A8 window 1", "--approval-reference=<A6-3 approval>"
```

| Grantee | Scope arguments |
|---|---|
| `ARP-SYNTHA60-00000001` | `--type=UNIT --unit-number=2573180018` |
| `ARP-SYNTHA60-00000002` | `--type=CONTACT_DERIVATION` (option (a) only) |
| `ARP-SYNTHA60-00000003` | `--type=IO --io-value=9500312705` |

These are ordinary UNIT, IO and CONTACT_DERIVATION grants. None is CENTRAL, so
the approval gate does not apply. They go through the command-line tool only,
because administrator writes stay OFF.

---

## 5. Rollback and close-out

At the end of the window, in this order, each with `--confirm` under the A6
close-out approval:

1. **Revoke** each grant: `--revoke-grant-id=<id>`. This is a status change;
   both audit rows survive.
2. **Unlink** each identity: `--unlink-link-id=<id>`. Also a status change.
   Unlinking does not revoke, which is why step 1 comes first.
3. **Disable** the four Cognito users (`admin-disable-user`). Their Secrets
   Manager entries are scheduled for deletion with the default recovery window.

Expected end state:
- no ACTIVE link or unrevoked grant names `ARP-SYNTHA60-*`;
- history rows remain;
- no real identity, link or grant has been touched at any point.

---

## 6. What needs the owner's approval

| Sub-step | Action | Category |
|---|---|---|
| A6-1 | Create 4 synthetic Cognito users (S1–S4) | account creation |
| A6-2 | 4 identity links (`--confirm`) | write to shared DEV |
| A6-3 | 2 or 3 grants (`--confirm`) | write to shared DEV |
| **§3** | **S2 option (a) or (b)** | **owner decision** |
| Close-out | revoke, unlink, disable | write to shared DEV |
