# Grant lifecycle acceptance tests — plan

**Status:** prepared, not executed. No grant has been created or revoked, and
enforcement is OFF.

Two groups, because they need different environments:

- **Group A — lifecycle mechanics.** Needs no enforcement. Can run against DEV
  with real (test) grants once a grantee is approved.
- **Group B — authorization behaviour.** Needs enforcement ON, which must
  **never** be enabled on the shared API service. Runs in disposable
  Testcontainers fixtures or an isolated one-off task.

Throughout: `--dry-run` first, `--confirm` only for the step under test, and
the migration-role read-only check before and after each step.

---

## Group A — lifecycle mechanics (enforcement OFF)

Image: the post-`f82672d` API image. Task family
`research-archive-platform-dev-grant-admin`, registered and deregistered per
run.

| # | Step | Command | Expected |
|---|---|---|---|
| A1 | dry run a create | `--type=UNIT --unit-number=… --dry-run` | `DRY_RUN_OK`, 0 grants, 0 new audit rows |
| A2 | create | same `--confirm` | `APPLIED`, **1** grant, **1** `ACCESS_GRANTED` |
| A3 | duplicate create | repeat A2 | `REFUSED` "identical active grant already exists", still 1 grant, **no second audit row** |
| A4 | duplicate differing only in `--include-descendants` | `--include-descendants --confirm` | `REFUSED` by the V087 index (the app pre-check does not catch it), 1 grant |
| A5 | dry run a revoke | `--revoke-grant-id=<id> --dry-run` | `DRY_RUN_OK`, grant still live |
| A6 | revoke | same `--confirm` | `APPLIED`, `revoked_at` set, **1** `ACCESS_REVOKED`, grant row still present |
| A7 | repeat revoke | repeat A6 | `REFUSED` "was already revoked", **still exactly one** `ACCESS_REVOKED` |
| A8 | re-grant the same scope | A2 again | `APPLIED` — V087 constrains unrevoked grants only; 2 grant rows, 1 live |
| A9 | create with expiry | `--expires-at=<future> --confirm` | `APPLIED`, `expires_at` persisted |
| A10 | past expiry | `--expires-at=<past> --confirm` | `REFUSED` "not in the future", nothing written |
| A11 | combine verbs | `--revoke-grant-id=… --type=UNIT --confirm` | `REFUSED` "cannot be combined with" |
| A12 | non-administrator actor | `--actor=<not an admin> --confirm` | `REFUSED` "holds no ACTIVE ARCHIVE_ADMIN membership" |

**Invariants after every step:** no row deleted from `authz.access_grant`; no
row changed or deleted in `authz.access_audit` (append-only by trigger);
`archive.*` counts unchanged; API revision 84 untouched; nightly at 237.

Every one of A1–A12 already has an automated equivalent in
`GrantProvisioningIntegrationTest` (67 tests across the combined suite). Group
A is the same assertions against real DEV data, which is why it is worth doing
once and not repeatedly.

---

## Group B — authorization behaviour (enforcement ON, isolated only)

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
