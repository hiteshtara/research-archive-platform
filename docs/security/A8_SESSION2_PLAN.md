# A8 session 2: re-test of the F1/F2/F3/F5 fixes (plan)

Status: **prepared 2026-11-18, not scheduled.** It does not start until:
1. PR #171 (F1, IO inheritance) and PR #172 (F2/F3/F5, refusal responses) are merged to `main`.
2. The updated API image is **separately approved for deployment**.
3. The owner approves this session.

It uses the reusable Security QA environment (`RAP_SECURITY_QA_ENVIRONMENT.md`)
exactly as session 1 did. Shared DEV stays enforcement OFF throughout.

## 1. Image and task definitions

| Step | What | Approval |
|---|---|---|
| 1 | Build the API image from merged `main` (clean detached worktree, `ops/deploy-api.sh` build path), then push to non-prod ECR | part of the deployment approval |
| 2a | **Either:** deploy it to shared DEV as `research-archive-platform-dev-api:107` (rev 106 with only the image changed; enforcement stays OFF), then derive `-a8:2` from rev 107 | separate deployment approval (owner) |
| 2b | **Or:** leave shared DEV on 106 and register only `-a8:2` = rev 106 with the new image plus the six settings | owner chooses 2a or 2b |
| 3 | Re-diff `-a8:2` against its base revision: exactly the six settings (and the image, if 2b) | pre-check |

Recommendation: **2a**. It proves the fixed image runs on shared DEV with
enforcement OFF before the private run, the same order as A1 then A8. That route
is a shared-DEV change and needs its own approval.

## 2. Accounts

As session 1, using the retained inert set:
- **Accounts:** re-enable S1/S3/S4.
- **Grants:** S1 UNIT 2573180018 with no descendants, S3 IO 9500312705, S4 none. Each is created with dry run, then `--confirm`, then the scope query, and expires at session end + 24 h.
- **Attachment group:** added after the pre-window membership is recorded.
- **Cleanup:** on every exit path.

## 3. What must change versus session 1 (the acceptance bar)

Session 1 matrix: 630 PASS, **25 FAIL**, 0 LEAK, 11 NOT TESTED. Session 2 expected result:

| Session-1 FAIL group | Probes | Session-2 expectation | Fix |
|---|---|---|---|
| IO (S3) opens subaward 92899 (12 endpoints) | 12 | **404 on all 12**: kind `deny`, PASS | F1 #171 |
| No-Grant Global Search 200 | 12 | **403 `ACCESS_NOT_PROVISIONED`**: kind `none`, PASS | F2 #172 |
| No-Grant AI Evidence Search 503 | 1 | **403 `ACCESS_NOT_PROVISIONED`**: kind `none`, PASS | F3 #172 |

Expected totals: **655 PASS, 0 FAIL, 0 LEAK, the same 11 NOT TESTED** (each with its reason
unchanged). The 666-probe plan is unchanged, so session 1 and session 2 are
directly comparable. Any FAIL, or any new NOT TESTED, is investigated before a
go/no-go. No verdict is reclassified.

**Matrix change made 2026-11-18 (self-test passes with 37 cases; still 666 probes):**
- **New kind `no_sub_c`.** It replaces the text-based check on 5 IO-account probes:
  - Award C's `funding-subawards` tab (was `allow`);
  - the Subaward list and Global Search for C's subaward codes 3259 and 3260 (was `list`).
- **Why the text check fails here.** Subaward 92899's title is identical to Award C's, so text cannot tell them apart. After F1 the old kinds would report false FAILs ("in-scope identifiers missing").
- **How the new check judges.** PASS when no record in the body has `subawardId` or `navigableCurrentSubawardId` 92899 (or the request is refused); LEAK if it is present.
- **Session 1 coverage gap.** Under this check, session 1's responses for these 5 probes would have been LEAK. F1 was caught there only through the 12 detail-endpoint probes.

**Further probes still to add** (reviewed and self-tested before the session):
- S3 Subaward **list and search** for 92899's code and title return nothing (F1, SQL side), and the dashboard Subaward count for S3 is 0.
- S3 Award C's **funding-subawards** and **negotiations** tabs are empty (F1, related lists).
- S4 `GET /api/global-search?query=…` body has `code=ACCESS_NOT_PROVISIONED` and no `results` key.

**Unchanged expectations that must still PASS** (regression guard):
- S3 opens Award C and its Time & Money, SAP, versions and attachments.
- S1 keeps every Department result from session 1.

## 4. Manual and browser checks (owner, own BU login, browser-automated)

- Repeat D2–D14, which should give the same results as session 1.
- **D3 re-test of F5.** If the owner's sign-in is more than 12 h old at session time, Award search must show **"Please sign in again…"**, not "Unable to search". If it is fresh, D3 is recorded as PASS on search, and the 401 wording is covered by the UI unit tests. No setting is changed to force an old session.
- **New check:** as S4 in the local UI, Global Search shows "Your access has not been set up yet…" with the contact line, and no empty-results page.

## 5. Order, limits and abort

As session 1 (§8.3 / §9 of the package):
1. pre-checks;
2. grants;
3. baseline on shared;
4. C1–C3, an inert rule, healthy, then C4;
5. isolation proof both ways;
6. matrix, then cross-check, then manual;
7. cleanup.

- **Limits:** 3 h maximum, with an operator present.
- **Abort:** delete the rule first, on any real disclosure, failed isolation or shared-DEV impact.

## 6. What it can move

On a clean session 2, these become candidates for DONE, on the owner's direction:
- US-OAV-001 #2;
- US-SEC-003 #1 and #2.

Formal BU acceptance stays separate. The technical count stays at 29/47 until then.
