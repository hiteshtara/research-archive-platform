# Authorization retest plan — revision 93 baseline

Prepared 2026-11-12. **No window scheduled, nothing activated.** Supersedes the
11 November plan, which aborted on the administrator API defect now fixed.

## What changed since the aborted window

| | 11 November | Now |
|---|---|---|
| Code baseline | revision 91 / image `f0f42ab6` / commit `458de9c` | **revision 93 / image `f08672c4` / commit `9729eff`** |
| Admin audit query | uncast `:subject` and `:action` — 500 on two endpoints | **fixed**, cast as the timestamps already were |
| Regression coverage | none over the audit read path | **5 tests**, all 16 nullable-filter combinations, in the CI PostgreSQL job |
| Enforcement config | revision 92 (enforcement ON, pre-fix image) | revision 92 **retired — do not reuse**; a new revision must be staged from **93** |

**Revision 92 must not be reused.** It pins the pre-fix image *and* carries
enforcement ON. The next enforcement revision is staged fresh from **93**,
changing only the six approved variables.

## Evidence the defect is fixed

1. **Shipped artifact** — the jar inside image `f08672c4` contains
   `CAST(:subject AS TEXT)` / `CAST(:action AS TEXT)`; the uncast forms are
   absent. Byte-verified in the image, not from source.
2. **Isolated PostgreSQL 17.11**, all 86 migrations applied: the old form
   fails with the exact production error, `could not determine data type of
   parameter $1`; the shipped form prepares and returns rows for the all-NULL
   and subject-only shapes, and narrows to zero for an unknown subject.
3. **CI** — 5 regression tests pass in the guarded PostgreSQL job
   (`…$AuditReadFilters`), within 520 tests, 0 failures.
4. **The image with enforcement ON, isolated** — starts in 15.5 s, health 200,
   admin tree live and returning 403 `NOT_AN_ARCHIVE_ADMINISTRATOR`, **no 500**.

### The limit of that evidence, stated plainly

None of it exercises the admin SQL **over HTTP as an authenticated
administrator**. That needs a real Cognito token for an ARCHIVE_ADMIN, which
cannot be minted outside a live environment. So the first genuine HTTP
exercise of the fixed query happens in the next window — which is exactly why
it is now the first mandatory check.

## Order of operations in the next window

Changed from last time: the administrator endpoints move **before** the
scoped tests, so a recurrence costs two minutes rather than the whole window.

| # | Step | Abort rule |
|---|---|---|
| 0 | Fresh BU login + Duo; `auth_time` < 12 h; federated identity confirmed | stale or reused session → stop |
| 1 | 40-check pre-flight → GO/NO-GO | any mandatory FAIL → do not register |
| 2 | Register the new enforcement revision **from 93**; wait healthy | fails to start → restore 93 |
| 3 | **Paired enforcement gate** — `212137-00001` → 200, `114471-00001` → 404 | anything else → restore 93 |
| 4 | **MANDATORY, FIRST: `GET /api/v1/admin/identities/{ARP}`** | **any 500 → restore 93 immediately** |
| 5 | **MANDATORY, FIRST: `GET /api/v1/admin/audit`** | **any 500 → restore 93 immediately** |
| 6 | Five principal/grant assertions from step 4's body | any failure → restore 93 |
| 7 | D1 — versions search: `totalElements` 2, sequences [3, 2], sequence 1 absent | 3 sequences → family-wide, abort |
| 8 | D2 — `/history/3` → 403 `AI_NOT_AVAILABLE_FOR_PARTIAL_ACCESS` | 200 → rule not firing, abort |
| 9 | Tests 1–10 | per the frozen matrix |
| 10 | Test 13 — grant-2 revocation and verification | — |
| 11 | Test 12 — native Cognito control, secure handoff, expect 403 `ACCESS_NOT_PROVISIONED` | 401 → auth-compatibility issue, UNVERIFIED |
| 12 | Restore revision **93**; verify | — |

Steps 4 and 5 are the change. Last time they ran *after* the gate and their
failure ended the window; now they run immediately after it, before any test
result is interpreted, because they are the known-risk surface.

### Retained unchanged

Paired enforcement gate at 200/404. D1 and D2 exactly as approved. Grant-2
revocation test. Native Cognito control with the secure handoff — no password
requested, captured, stored or logged. Every 403 classified by its application
error code, never by status alone. All rollback safeguards.

### Rollback target changes

**Revision 93, not 91.** 93 is the corrected baseline now serving; 91 remains
ACTIVE as a second fallback. Rollback is `update-service --task-definition
…:93` plus `wait services-stable`, measured at about 3 minutes.

## Preconditions before a window is scheduled

| # | Precondition | State |
|---|---|---|
| 1 | Admin audit fix merged and deployed | **done** — revision 93 serving |
| 2 | Regression tests in the guarded CI job | **done** |
| 3 | Isolated verification of the fixed query | **done** |
| 4 | New enforcement revision staged from 93, six variables only | **not done** — needs approval |
| 5 | Notification to the eight users, with the cancellation settled first | **not done** |
| 6 | 40-check pre-flight | at window time |

## Duration and timing

90 minutes, 4:00–5:30 PM ET, with test 12 by +65 minutes, rollback begun by
+70 and verified by +90. No date proposed here: the eight users have had one
window moved and one cancelled, so the next notification should go out at
least three business days ahead and should follow the cancellation rather than
arrive alongside it.
