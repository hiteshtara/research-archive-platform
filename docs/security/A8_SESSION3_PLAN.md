# A8 session 3: targeted re-test after the session 2 QA (plan)

Status: **prepared 2026-11-19. Not approved and not scheduled.** It does not start until:
1. an independent review of the harness corrections below finds them sound;
2. the owner approves this session.

Shared DEV stays on `research-archive-platform-dev-api:107` with enforcement OFF. The session
uses the same isolated, enforcement-ON private task as session 2 (`-a8:2`, image `7257b72f…`).
It adds no new accounts, grant types, settings, migrations or infrastructure.

## 1. Owner decisions recorded (2026-11-19)

- The independent review of session 2 and its corrections are accepted. **US-OAV-001 #2 stays
  IN PROGRESS.**
- Isolated enforcement-ON testing is **accepted as technical evidence**. The separate
  requirements for **shared-DEV activation** and **named-user acceptance** stay in place. The
  recorded acceptance criteria are not changed in order to close them.
- **US-OAV-001 #1 → DONE** when the workbook is available (technical count 30/47).
  US-OAV-001 #2 and US-SEC-003 #1/#2 stay IN PROGRESS.
- Unchanged: no change to shared-DEV enforcement, administrator writes, auditing, IRB
  settings or migrations; no change to the owner's administrator role or CGS grant;
  Production untouched.

## 2. H3 (SAP) live test: what DEV data allows

Found by read-only discovery (four one-off loader tasks, `default_transaction_read_only`
bound at connect; evidence in `a8-session3-prep-2026-11-19`):

- **SAP rows sit on the parent award.** DEV holds 113,794 transmissions on 14,761 award
  versions. A transmission's children are other award numbers.
- **No transmitting award has an IO.** Of those 14,761 versions, **0** carry an account
  number. An IO grant (Awards only, decision D-A) therefore never opens SAP data directly.
  There is no "authorized SAP data" for an IO account anywhere in the archive. This is a
  data fact, not a gap in the test.
- **The IO route is the parent of the IO award.** Award C (`209407-00002`, IO 9500312705, S3's
  existing grant) is the only child in **9 transmissions** stored on its parent
  `209407-00001` (award_id 3868241). The parent has no IO and lead units
  1330420000/1333250000, and every transmission has payloads. Without the fixes, an IO
  account reaching the parent would see them.
- **The Department route proves both directions.** S1's existing grant (UNIT 2573180018, no
  descendants) opens these transmitting awards:

| Root (award_id) | Transmissions | Child kept (in unit) | Child removed (other unit) |
|---|---|---|---|
| `110224-00001` (1022767) | 5, all with payloads | `110224-00002` | `110224-00003` (2573180000) |
| `208096-00001` (3816385) | 11, all with payloads | `208096-00002`, `208096-00004` | `208096-00003` (2572050000) |

Live H3 probes:

| Probe | Role | Pass condition |
|---|---|---|
| root `sap-transmissions` (kind `sap_scoped`) | S1 | 200; **same transmission count as baseline** (authorized SAP still accessible); in-unit child present; other-unit child absent; `sentData`/`returnedData` null |
| root `summary`, `hierarchy` (strict) | S1 | allowed; the hidden child is never in the body, and the baseline cannot downgrade a hit to REVIEW |
| hidden child by number | S1, S3 | 403/404 |
| root `sap-transmissions` | S3 / S4 | 403/404 / 403 `ACCESS_NOT_PROVISIONED` |
| C's parent: `sap-transmissions`, summary, versions, T&M history, attachments, by number | S1, S3 (S4) | 403/404 (403 `ACCESS_NOT_PROVISIONED`) |

**Known limit:** the Award Report PDF reads the same SAP method, but its compressed PDF text
cannot be inspected reliably. It stays covered by the integration tests only.

**Baseline note:** as in sessions 1 and 2, the enforcement-OFF baseline requests these
awards' `sap-transmissions`. Payload text is held in memory only. The fixture keeps the
transmission count, the child award numbers and whether a payload was present, never the
payload.

## 3. Harness corrections (`scripts/a8_authz_matrix.py`)

| Session 2 QA gap | Correction | Self-test cases |
|---|---|---|
| `/api/v1/proposals/*` never probed: the fixture looked for a `proposalId` key, which no response carries | Ids come from Proposal search `currentProposalId` (fallback: funding-link `navigableActiveProposalId`/`exactLinkedProposalId`), then are verified by `GET /api/v1/proposals/{id}` returning the same number. **A missing required fixture now refuses the baseline** instead of silently dropping probes | fake-API discovery: ids resolve; an unresolved id refuses; missing scope sets refuse |
| Global Search judged on `subawardId`, which its items never carry | Kind `no_records` judges Global Search on its `module` + `recordId` items. For the IO account, **any** SUBAWARD, NEGOTIATION or PROPOSAL item in **any** Global Search is a LEAK (D-A) | 3 |
| Only subaward 92899 checked, but C funds 5 subawards (186 version ids) | The IO account must receive **no** subaward record at all (record count, not text). The newest version of each of the other 4 codes gets detail/versions/amounts deny probes. All 5 codes are searched | 7 |
| Negotiation gaps: 12511 probed only for S1; no `?query=` probes; an empty IO list could not PASS | 12511 × 7 endpoints for S3 and S4. `?query=` and `?associationId=` for A, B, C and G. IO negotiation lists are judged by record count (`no_records`), so an empty list is decisive | 2 + plan checks |
| Dashboard judged only on "awards below baseline" | **Exact:** awards = 244 / 1 and award history = 1,674 / 14 (DB scope sets, which matched session 2's dashboards); IO proposals, negotiations and subawards = 0; IRB figures absent; no figure above the unscoped baseline. Over = LEAK, under = FAIL | 8 |
| Allowed pages checked only for the 4 fixture awards | Every 200 body of an allowed or list probe is scanned for **any** award number outside the role's full allowed set (244 / 1). A record-level listing is a LEAK; any other mention is a REVIEW, adjudicated with its JSON path | 8 |
| NOT TESTED known only after the run | The baseline writes the **predicted NOT TESTED list** (with reasons) before enforcement starts. Any unpredicted NOT TESTED blocks the go/no-go | 1 |

Self-test: **74 cases PASS** (was 37). Plan: **829 probes** (was 666), including 40 v1
Proposal probes, 2 `sap_scoped`, 22 `no_records`, and 67 H3 / no-record / 12511 probes.

## 4. Acceptance bar

- **0 FAIL, 0 LEAK, 0 unresolved REVIEW.** Each REVIEW is adjudicated from its JSON path
  before any go/no-go, and none is reclassified without written reasoning.
- **NOT TESTED = exactly the baseline-predicted set,** fixed and reported before the
  enforced run.
  - Expected to include **10 of session 2's 11**, with unchanged reasons: 3 excluded real-person lookups, 6 Explorer/IRB out of scope, and S1's Negotiation list for unit 1330420000.
  - The 11th (S3's list for that unit) becomes decisive.
- **Both H3 routes PASS:** S1 `sap_scoped` on both roots, and every S3 probe on C's parent.

## 5. Execution (unchanged from session 2)

The steps are those of `A8_SESSION2_DEPLOYMENT_PLAN.md` §9:
1. pre-checks (IP, VPN, shared `:107` 1/1, listener default only);
2. re-enable S1/S3/S4; grants S1 UNIT 2573180018 and S3 IO 9500312705, each dry run then `--confirm`, expiring at session end + 24 h; attachment group;
3. baseline on shared with `--scope-sets`; report the predicted NOT TESTED;
4. `-a8:2` task, inert rule until healthy, then the real-host rule;
5. isolation proof both ways;
6. matrix, then cross-check;
7. cleanup on every exit path (rule, task, TG, grants, group, disable users) and verify.

- **Limits:** 3 h maximum, with an operator present.
- **Abort:** delete the rule first, on any real disclosure, failed isolation or shared-DEV impact.

Before the baseline, the scope sets are recomputed with the same read-only query, so they
cannot be stale.

## 6. What it can move

A clean session 3 lets the owner re-assess US-OAV-001 #2 on technical evidence. It does not
satisfy the separate shared-DEV activation or named-user acceptance requirements, so the
criterion stays IN PROGRESS until those are met. US-SEC-003 #1/#2 still need the IRB release
decision. Formal BU acceptance stays separate.

## 7. Live state confirmed before this plan (2026-11-19 ~03:05Z, read-only)

- **Shared DEV:** `:107` 1/1, rollout COMPLETED, target healthy; no enforcement setting.
- **Private environment:** no a8 task, target group or listener rule (default rule only).
- **Synthetic accounts and grants:** S1/S3/S4 disabled; grants 3–6 revoked.
- **Owner:** grant 2 (UNIT 1250000000) and admin seat 3 unchanged.
- **Database:** 86 migrations, latest V088.
