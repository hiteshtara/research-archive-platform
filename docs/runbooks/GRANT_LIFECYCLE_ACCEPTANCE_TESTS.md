# Grant lifecycle acceptance tests — plan

**Status:** prepared, not executed. No grant has been created or revoked, no
identity has been enrolled, and enforcement is OFF.

**Revised 2026-11-03** to add prerequisite A0 (the grantee identity has to be
created before any of this can run, and it must not be the administrator), a
24-hour expiry on A2 and A8, the audit-trail provenance checks P1–P3, and the
active-versus-historical counting rule.

> ## ⚠️ Every step marked WRITE is NOT YET APPROVED
>
> Steps that use `--confirm`, and every step in Group B, require **separate,
> explicit approval before each run**. They are written down so they can be
> reviewed, not so they can be run.
>
> As of 2026-11-03 the approved scope is **dry runs only**: `--dry-run` with no
> `--confirm`. The outstanding business decisions in
> `docs/security/AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md` - in particular
> who the first grantee should be - are unresolved, so no real grant can be
> approved yet regardless of whether the tooling is ready.

Two groups, because they need different environments:

- **Group A — lifecycle mechanics.** Needs no enforcement. Can run against DEV
  with real (test) grants once a grantee is approved.
- **Group B — authorization behaviour.** Needs enforcement ON, which must
  **never** be enabled on the shared API service. Runs in disposable
  Testcontainers fixtures or an isolated one-off task.

Throughout: `--dry-run` first, `--confirm` only for the step under test, and
the migration-role read-only check before and after each step.

---

## Prerequisite A0 — the grantee must exist, and it must not be the administrator

**This plan cannot be executed as first written.** It assumed a grantee
identity was available. DEV has exactly **one** row in
`authz.identity_link` — the administrator's own — against 7 federated and 6
native Cognito users, because until now there was no way to create a second
one: `ArchiveAdminService.bootstrapFirstAdministratorWithIdentity` is
bootstrap-only, and nothing else linked an identity.

Granting to the ARCHIVE_ADMIN identity instead would invalidate Group B
outright. B14 asserts that an administrator with no data grant sees **no**
records; if the administrator is also the grantee, B14 and B1 contradict each
other and neither result means anything. The grantee must be an ordinary
non-administrator identity.

| # | Mode | Step | Expected |
|---|---|---|---|
| A0a | DRY RUN | `--enrol-cognito-subject=<sub> --dry-run` | `DRY_RUN_OK`, 0 rows in `authz.identity_link`, **no identifier minted** |
| A0b | **WRITE — not approved** | same `--confirm` | `APPLIED`, **1** new `identity_link` (`method = ADMIN_VERIFIED`, `status = ACTIVE`), **1** `IDENTITY_LINKED`, **0** `admin_role_member` rows added, **0** grants |

The archive identifier is **minted by the utility**, never supplied, so A0b's
output is the only place the grantee's `ARP-…` identifier appears. Record it:
every later step in Group A needs it as `--grantee`.

A0b is blocked on two things that do not exist yet — see "Remaining blockers"
in the enrollment PR: the Cognito SDK dependency and a `cognito-idp:AdminGetUser`
permission on the task role. Until both are present the subject cannot be
corroborated against the pool, and a confirmed enrollment is **refused** by
design (a dry run only warns). Do not work around this by removing the check:
it is the reason the utility can be trusted to link the right human.

### Infrastructure baseline

Before and after Group A, `terraform plan` in `terraform/environments/dev`
must report **"No changes. Your infrastructure matches the configuration."**
That is the verified baseline as of 2026-11-03 (run against `main` at
`ca7fe5b`), not an assumption — none of these steps touch Terraform-managed
state, so any drift that appears belongs to something else and must be
explained before continuing.

> **Run the plan from `main`, and check which commit you are on first.**
> Planning dev from a branch that predates the infrastructure commits reports
> **3 to add, 6 to change, 19 to destroy** — including
> `aws_cognito_identity_provider.shibboleth`, the entire `db_tunnel` and
> `cloudshell_analyst` read-only database path, and the Subaward failure-alert
> wiring, each "because … is not in configuration". That is the *branch*
> missing `db_tunnel.tf` and the Shibboleth provider, not the environment
> having drifted. Confirmed 2026-11-03: the same state planned clean from
> `main` minutes later.
>
> Do not treat such a plan as drift to be reconciled, and never `apply` it —
> it would delete BU Shibboleth federation and the approved read-only database
> access path. `terraform.tfvars` and `backend.hcl` are both gitignored, so a
> fresh worktree also needs them copied in before `plan` means anything.

---

## Group A — lifecycle mechanics (enforcement OFF)

Image: the post-`f82672d` API image. Task family
`research-archive-platform-dev-grant-admin`, registered and deregistered per
run.

Each row is marked **DRY RUN** (approved) or **WRITE** (not yet approved).

| # | Mode | Step | Command | Expected |
|---|---|---|---|---|
| A1 | DRY RUN | dry run a create | `--type=UNIT --unit-number=… --dry-run` | `DRY_RUN_OK`, 0 grants, 0 new audit rows |
| A2 | **WRITE — not approved** | create, **expiring in 24 hours** | same `--confirm --expires-at=<now + 24h, ISO-8601>` | `APPLIED`, **1** grant, **1** `ACCESS_GRANTED`; `expires_at` persisted |
| A3 | **WRITE — not approved** | duplicate create | repeat A2 | `REFUSED` "identical active grant already exists", still 1 grant, **no second audit row** |
| A4 | **WRITE — not approved** | duplicate differing only in `--include-descendants` | `--include-descendants --confirm` | `REFUSED` by the V087 index (the app pre-check does not catch it), 1 grant |
| A5 | DRY RUN | dry run a revoke | `--revoke-grant-id=<id> --dry-run` | `DRY_RUN_OK`, grant still live |
| A6 | **WRITE — not approved** | revoke | same `--confirm` | `APPLIED`, `revoked_at` set, **1** `ACCESS_REVOKED`, grant row still present |
| A7 | **WRITE — not approved** | repeat revoke | repeat A6 | `REFUSED` "was already revoked", **still exactly one** `ACCESS_REVOKED` |
| A8 | **WRITE — not approved** | re-grant the same scope, **expiring in 24 hours** | A2 again, with a fresh `--expires-at=<now + 24h>` | `APPLIED` — V087 constrains unrevoked grants only; **2** grant rows, **1** active, the A2 row still present and still revoked |
| A9 | **WRITE — not approved** | create with expiry | `--expires-at=<future> --confirm` | `APPLIED`, `expires_at` persisted |
| A10 | **WRITE — not approved** | past expiry | `--expires-at=<past> --confirm` | `REFUSED` "not in the future", nothing written |
| A11 | **WRITE — not approved** | combine verbs | `--revoke-grant-id=… --type=UNIT --confirm` | `REFUSED` "cannot be combined with" |
| A12 | **WRITE — not approved** | non-administrator actor | `--actor=<not an admin> --confirm` | `REFUSED` "holds no ACTIVE ARCHIVE_ADMIN membership" |

**Invariants after every step:** no row deleted from `authz.access_grant`; no
row changed or deleted in `authz.access_audit` (append-only by trigger);
`archive.*` counts unchanged; API revision 84 untouched; nightly at 237.

### Every grant expires

A2 and A8 both set `--expires-at` 24 hours ahead. Under D-ADM-3 a grant with
no `--expires-at` **never expires**, so an unbounded test grant would outlive
the test; an expiry is the backstop for a revoke step that is skipped, refused
or forgotten. It does not replace A6 — `AccessGrant.isActiveAt(now)` requires
`revoked_at IS NULL` **and** `valid_from <= now < expires_at`, and V087's
unique index is **not** time-aware, so an expired-but-unrevoked grant still
occupies its scope and still blocks a re-grant. Revoke explicitly; let the
expiry catch what revocation misses.

### Provenance must be read from the audit trail, not from the grant row

| Check | Read from | Expected |
|---|---|---|
| P1 — creation reason and approval | the `ACCESS_GRANTED` row in `authz.access_audit` | the full `operator=…; approval=…; <reason>` string the operator passed to A2 |
| P2 — revocation reason and approval | the `ACCESS_REVOKED` row | the revocation's own `operator=…; approval=…; <reason>`, distinct from P1's |
| P3 — the grant row after revocation | `authz.access_grant.reason` | now holds the **revocation** reason |

**P3 is a trap, not a nicety.** `JdbcAccessGrantRepository.revoke` writes
`reason = COALESCE(:reason, reason)` on the grant row, so a revocation
**overwrites the original creation reason in place**. After A6 the grant row
can no longer answer "why was this granted?" — only the append-only
`ACCESS_GRANTED` audit row can. Capture P1 **before** running A6, and never
cite `access_grant.reason` as creation provenance.

### Historical and active grants are different questions

After A6 and A8 there are two rows for one scope. Keep them distinct in every
count:

- **active** — `revoked_at IS NULL AND valid_from <= now() AND now() < expires_at`
- **historical** — every row, including revoked and expired ones

Nothing is ever deleted, so a rising `COUNT(*)` on `authz.access_grant` is the
expected outcome of a correct lifecycle test, not evidence of a duplicate. A
step that reports "1 grant" must say which of the two it counted.

Every one of A1–A12 already has an automated equivalent in
`GrantProvisioningIntegrationTest` (67 tests across the combined suite). Group
A is the same assertions against real DEV data, which is why it is worth doing
once and not repeatedly.

---

## Group B — authorization behaviour (enforcement ON, isolated only)

> **NOT YET APPROVED, in full.** Every case below needs enforcement ON and is
> blocked on the outstanding policy decisions. Nothing in this group may run
> against the shared API service under any circumstances.

**Not on the shared API service.** Either a disposable Testcontainers fixture
or a one-off task with `app.authorization.enforcement-enabled=true` plus all
five policy settings, in an isolated family, never registered against the
running service.

Prerequisite: Anthony's four outstanding decisions — see
`docs/security/AUTHORIZATION_POLICY_DECISIONS_OUTSTANDING.md`. Until those are
set, `policy()` is empty and every record is denied, which is a valid test
(B0) but not a useful one.

| # | Case | Expected |
|---|---|---|
| B0 | enforcement on, policy unset | every record denied, `ENFORCED_POLICY_NOT_CONFIGURED` |
| B1 | grantee reads an Award with `lead_unit_number = 1242040000` | permitted |
| B2 | grantee reads an Award in another unit | denied — **404, not 403** (a 403 would confirm the record exists) |
| B3 | `includeDescendants=false`, child unit record | denied |
| B4 | `includeDescendants=true` + `LEAD_UNIT_WITH_DESCENDANTS`, child unit | permitted (D4) |
| B5 | family-spanning read under `PER_VERSION` | partially-visible family ⇒ AI endpoints deny with `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` |
| B6 | same under `FAMILY_WIDE` | permitted |
| B7 | Award hierarchy with an unauthorized relative | relative **omitted entirely**, no stub (D5) |
| B8 | Subaward funded by an in-scope Award family | permitted (D6a, FAMILY level) |
| B9 | Subaward whose funding Award is RESTRICTED | omitted, leaving no trace (D7) |
| B10 | Negotiation by lead unit / by type-gated association | permitted per D8; association-type gate blocks document-number collisions |
| B11 | IRB record | **denied** — a UNIT grant reaches no IRB |
| B12 | Dashboard | scoped counts; counts and pages from the same `WHERE` (D2); IRB figures **absent, not zero** (D9) |
| B13 | Global Search | scoped; IRB absent; cross-module indexes fail closed |
| B14 | administrator with no data grant | sees **no** records — ARCHIVE_ADMIN confers none |
| B15 | sign-in older than `maxSignInAge` | re-authentication required |
| B16 | revoke mid-session | access gone on the next request |

### Structural scope for UNIT `1242040000` — measured, read-only

| Module | Exact lead unit | With descendants (`12420*`) | Archive total |
|---|---|---|---|
| Award | **10,636** versions / **1,793** families | **25,552** / **3,993** | 267,386 |
| Proposal | **4,976** versions | — | 119,788 |
| Subaward | **230** rows / **116** families via funding-award families | — | 93,735 |
| Negotiation | not a lead-unit query (no unit column) | — | 10,775 |
| IRB | **0** | — | — |

**Structural scope is an upper bound, not an authorization decision.** What is
actually permitted depends on `versionScope`, `departmentMatch` and the
D6a/D7/D8 rules. The Negotiation figure is deliberately absent: it needs the
association-type gate applied and has not been measured.

---

## Rollback

Group A: `--revoke-grant-id=<id> --confirm` — audited, nothing deleted.
Group B: discard the fixture or stop the task; no shared state is touched.
Neither group changes API revision 84 or the nightly schedule.
