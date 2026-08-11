# Award RAG Deployment Readiness

Repository: `https://github.com/hiteshtara/research-archive-platform`
(remote `origin`; a second remote `bu` → `git@github.com:bu-ist/research-archive-platform.git`
also exists locally — confirm which one CI/deploy actually watches before
pushing, see §4).
Branch: `main`. HEAD at initial audit time: `e76e95f08a0e23b5fde5471e5bc33481af7d3385`
("feat(search): add award evidence embedding pipeline").

**Checkpoint A update**: the Related Negotiations backend chain (Task 1)
is now complete, tested, and committed locally on top of `e76e95f` — see
§3. **This has not been pushed or deployed.** The deployed environment
described in §1 below is unchanged and still does not include this fix
until a real deploy happens — do not present Related Negotiations as live
until that deploy is confirmed via the same read-only checks used
throughout this document.

This document is the **single source of truth for what is safe to say is
live** versus what still requires deployment or further work. Every
classification below cites the exact code and, where the claim is about
the *running* environment rather than the *committed* code, the exact
read-only AWS check used to verify it (all performed this session:
`aws ecs describe-services`/`describe-task-definition`, `aws elbv2
describe-load-balancers`, `aws amplify list-jobs`, and two unauthenticated
HTTPS probes — `/actuator/health` and one protected endpoint expected to
return 401). No authenticated request was made against the deployed
application; see §5 for what would still need a real login to confirm.

## 1. Deployed environment — verified state (not the working tree)

| Component | Deployed image / commit | Verified via |
|---|---|---|
| API (ECS service `research-archive-platform-dev-api`) | `20260810T210010Z-f4d2973`, task-def revision 44, 1/1 healthy, rollout COMPLETED | `aws ecs describe-services` / `describe-task-definition` |
| UI (Amplify app `research-archive-platform-dev`) | commit `f4d2973`, job 44, status SUCCEED | `aws amplify list-jobs --branch-name main` |
| API health | `{"status":"UP"}` | unauthenticated `GET https://<api-alb>/actuator/health` → 200 |
| API auth enforcement | `401` on a protected endpoint with no token | unauthenticated `GET /api/v1/awards/search?query=test` → 401 |
| UI reachability | 200 | unauthenticated `GET https://main.d288p9gmoteftb.amplifyapp.com/` |
| `APP_SEARCH_SEMANTIC_ENABLED` | `true` (live env var on the running API task) | `aws ecs describe-task-definition` container env |
| `APP_AI_ENABLED` / any `app.ai.*` override | **not present** in the running task's environment, and not present in `terraform/environments/dev/terraform.tfvars`'s `additional_api_environment_variables` block | same describe-task-definition call + `grep` of `terraform.tfvars` |

**HEAD (`e76e95f`) is 2 commits ahead of what's deployed** (`5be6a6b`
attachment fix, `e76e95f` evidence-embedding pipeline). Both of those
commits touch **only** `etl/` and `database/migrations/` — zero Java, zero
UI files (confirmed via `git show --stat` on both). **This means the
currently-deployed API and UI are functionally current for everything
demonstrable through the app** — the two undeployed commits only matter for
running the ETL loader, not for what a user sees.

## 2. Capability classification

Legend: **LIVE** = confirmed reachable in the deployed dev environment
today. **NOT DEPLOYED** = code exists and is tested, but not active in
dev (flag off, or not yet pushed/deployed). **PREP** = requires demo data
preparation (indexing, fixture setup) before it can be shown. **PLANNED**
= no consuming code exists yet, deploying and indexing alone cannot make
this demoable. **UNAVAILABLE** = not implemented, or confirmed broken in
the current deploy.

| # | Capability | Classification | Evidence |
|---|---|---|---|
| 1 | Global keyword search | **LIVE** | `GlobalSearchService.java` — 6-branch concurrent fan-out (IRB/Award/Negotiation/Subaward/Proposal/Semantic), each fault-isolated via `joinOrRecordFailure`. Deployed and healthy (§1). |
| 2 | Full-dataset Award search | **LIVE** | `AwardV1Controller.search()` → `AwardArchiveService.search()` → `AwardArchiveRepository.searchAwards()`, no `LIMIT`/sampling — covers all 8,773 Award families. |
| 3 | Semantic Award search | **LIVE** | `SemanticSearchRepository`, `app.search.semantic.enabled=true` confirmed on the running API task; 8,597 real `AWARD_SUMMARY` embeddings already populated (§ dataset doc). |
| 4 | Search by Award number | **LIVE** | `AwardArchiveRepository.searchAwards()` WHERE-ORs exact `award_number`. |
| 5 | Search by title | **LIVE** | Same method, `title` clause. |
| 6 | Search by investigator | **LIVE** | Same method, PI `full_name` clause. |
| 7 | Search by sponsor | **LIVE** | Same method, `sponsor_code`/`sponsor_name` clause. |
| 7b | Search by account number | **UNAVAILABLE** (via the search box) | `findFamilies()` does query `account_number`, but that method is wired only to the legacy, unversioned `AwardArchiveController` (`/api/awards/families`), which Global Search and `AwardV1Controller.search()` do not call. The search box a client would use cannot search by account number today. |
| 8 | Result-type filtering | **UNAVAILABLE** | `GlobalSearchPage.tsx` renders `module` as a read-only `Chip`; no filter control exists; `GlobalSearchItemResponse` has no filter parameter. Confirmed by repo-wide grep for filter/tab/select controls — none found. |
| 9 | Award-family history | **LIVE** | `AwardV1Controller` `GET /api/v1/awards/{awardId}/versions` → `AwardArchiveService.findVersions()`; UI: `AwardVersionsSection.tsx`, wired into `AwardDashboardPage.tsx`. |
| 10 | Award-version navigation | **LIVE** | Same as above. |
| 11 | Investigators and roles | **LIVE** | `AwardV1Controller` `GET /people` → `AwardArchiveService.findPeople()`; UI `AwardContactsSection.tsx`. |
| 12 | Amount and funding history | **LIVE** | `GET /amounts` → `findAmounts()`; UI `AwardAmountsSection.tsx`. |
| 13 | Sponsor and reporting terms | **LIVE** | `GET /terms` → `findTerms()`; UI `AwardTermsSection.tsx`. |
| 14 | Award comments | **LIVE** | `GET /comments` → `findComments()` (reproduces Kuali's own comment-filtering logic); UI `AwardCommentsSection.tsx`. |
| 15 | Related proposals | **LIVE** | `GET /funding-proposals` → `findFundingProposals()`; UI `AwardFundingProposalsSection.tsx`. |
| 16 | Related negotiations | **IMPLEMENTED AND TESTED, NOT DEPLOYED** | See §3. UI was already committed and deployed. The completing service method and controller endpoint are now written, covered by new tests, and committed locally (Checkpoint A) — but **not pushed or deployed**. Do not claim this is live until deployment is confirmed via the same read-only checks used elsewhere in this document. |
| 17 | Related subawards | **LIVE** | `GET /funding-subawards` → `findFundingSubawards()`; UI `AwardFundingSubawardsSection.tsx`. |
| 18 | Attachment metadata | **LIVE** | `GET /attachments` → `findAttachments()`; UI `AwardAttachmentsSection.tsx`. |
| 19 | Attachment viewing/downloading | **LIVE** (code path); **requires one live click-through to fully confirm** | `AwardV1Controller.downloadAttachment()` streams via the API process, never exposes a raw S3 URL (`S3AwardAttachmentStorage.open()`). This code long predates the two undeployed commits, so it is deployed. The ETL-side EXTERNAL-blob fix (`5be6a6b`) never touched this Java path, so the fix doesn't need to be deployed for downloads to work — but this specific claim has not been exercised end-to-end with a real click since the fix; see readiness checks. |
| 20 | AI Award summary | **NOT DEPLOYED, and not reachable from any page even where enabled** | `app.ai.enabled` defaults `false` (`application.yml`), no override in dev's ECS task env or `terraform.tfvars`. Separately and more fundamentally: `AwardAiSummaryPanel.tsx` is not imported by any page component — a repo-wide grep found zero page-level usages. Even flipping the flag on today would not make this reachable through normal browsing without a UI wiring change. |
| 21 | Evidence-level semantic retrieval | **PLANNED / PHASE 3** | `build_evidence_embedding.py` (commit `e76e95f`) is populate-only. `SemanticSearchRepository.findNearest()` explicitly filters `WHERE document_type IN (:summaryDocumentTypes)` to the 4 `*_SUMMARY` types only — evidence-type rows are invisible to it by design. A full repo search found **zero** other Java code that reads evidence-type rows. Deploying and indexing alone cannot make this demoable — there is no retrieval consumer to deploy. Do not run real evidence indexing or claim it would make this visible. |
| 22 | Structured source citations (evidence-level) | **PLANNED / PHASE 3** | No API/UI code exists to read or cite evidence-type rows. `AwardCitationValidator` exists and is well-tested, but it validates citations for the AI Summary/Questions feature only (structured Award fields, not `search_embedding` rows) — that feature is separately not reachable via the UI today (#20). |
| 23 | Authentication and authorization | **LIVE** | `SecurityConfiguration` (Cognito JWT resource server, `matchIfMissing=true`) is active in dev — confirmed live via an unauthenticated 401 on a protected endpoint. `LocalSecurityConfiguration` (permit-all) only activates when `app.security.enabled=false`, which is not set in dev. |
| 24 | Insufficient-evidence / error behavior | **LIVE** for ordinary 404s (standard `NoSuchElementException` → 404 handling, long-standing); **NOT DEPLOYED-REACHABLE** for the AI-specific fail-closed 503 (`AiExceptionHandler`), since the AI path itself isn't reachable via the UI. |

## 3. Related Negotiations — fixed and tested locally (Checkpoint A), not yet deployed

**Prior finding (superseded)**: the completing service method and
controller endpoint were sitting uncommitted with zero test coverage,
while the UI tab that calls them was already deployed — a confirmed demo
blocker.

**Current state, as of Checkpoint A:**

- `AwardArchiveRepository.findAssociatedNegotiationRows()` — committed in
  `f4d2973` (currently deployed), tested
  (`AwardArchiveRepositoryTest.java:606-631`). Unchanged this checkpoint.
- `AwardArchiveService.findAssociatedNegotiations()` and
  `AwardV1Controller`'s `GET /api/v1/awards/{awardId}/negotiations` — found
  already written and functionally correct (mirroring
  `findFundingProposals`/`findFundingSubawards` exactly, including the
  same `NoSuchElementException`-on-missing-Award convention). No
  production code needed to change. **Now covered by new tests**: 4 new
  service tests (`AwardArchiveServiceTest.java`) and 3 new controller
  tests (`AwardV1ControllerTest.java`), using real fixtures — Award
  `104949-00002` (negotiations `11241` Fully Executed and `11471`
  Abandoned), Award `101929-00001` (zero negotiations, confirms the
  empty-result path returns `200 []`, not an error), and the missing-Award
  `NoSuchElementException` → 404 path. All committed as of this
  checkpoint.
- `AwardAssociatedNegotiationsSection.tsx` (the UI tab) and its API client
  call — unchanged, already committed and deployed in `61e84d1`. Verified
  this checkpoint: its DTO field usage
  (`negotiationId`/`documentNumber`/`negotiationStatusDescription`/
  `negotiationAgreementTypeDescription`/`negotiatorFullName`) matches the
  Java `AwardAssociatedNegotiationResponse` record exactly — no UI or
  repository change was needed.

**This fix has NOT been pushed or deployed.** The table in §1 above still
describes the deployed environment as of `f4d2973`, which does not include
it. **Do not present Related Negotiations as live until a deploy is run
and confirmed** via the same read-only checks (`aws ecs
describe-task-definition`, a live authenticated click-through) used
elsewhere in this document. Demo Award 4 (`103162-00001`, 6 real
Award-linked negotiations, fully verified with real data this checkpoint)
remains available as a second working example once deployed.

## 4. Controlled deployment sequence (not run — documentation only)

1. **Push the reviewed commits.** `git push origin main` (confirm `origin`
   is the intended remote — this repo also has a `bu` remote pointing at
   `git@github.com:bu-ist/research-archive-platform.git`; verify which one
   CI/CD watches before pushing, since pushing to the wrong remote silently
   does nothing for deployment).
2. **Verify CI.** Check `.github/workflows/` (currently untracked in this
   working tree — confirm it's committed before relying on it) or whatever
   the project's actual CI pipeline is; confirm the push triggers a green
   build.
3. **Deploy the application and ETL image to dev.** API/UI: whatever the
   project's standard deploy path is (Amplify auto-deploys `main` on push,
   per the job history in §1; the API/ECS side needs its own image
   build+push+task-definition update — mirror the exact steps documented in
   `docs/operations/AWS_TROUBLESHOOTING_RUNBOOK.md` if one exists, rather
   than improvising new commands). ETL: `scripts/run-evidence-embedding.sh`
   requires a **new loader image build** — the currently-registered loader
   task definition (revision 181, image tag `file-data-fix2`) predates
   `build_evidence_embedding.py`.
4. **Verify migration `V071`.** Already applied to dev — confirmed this
   session (`public.schema_migration` shows version 71 and 72 both
   present). No action needed unless a fresh environment is used.
5. **Confirm the existing full-dataset `AWARD_SUMMARY` embeddings.**
   Already confirmed this session: 8,597 rows present (§1 of the dataset
   document). No action needed.
6. **Run `build_evidence_embedding.py --dry-run` for only the four selected
   Award families:**
   ```bash
   scripts/run-evidence-embedding.sh --award-number 204713-00133 --dry-run
   scripts/run-evidence-embedding.sh --award-number 204713-00001 --dry-run
   scripts/run-evidence-embedding.sh --award-number 104949-00002 --dry-run
   scripts/run-evidence-embedding.sh --award-number 101929-00001 --dry-run
   scripts/run-evidence-embedding.sh --award-number 103162-00001 --dry-run
   ```
   (Five calls, not four — Demo Award 1 spans two Award numbers, both need
   indexing since the demo uses both.) Each requires `ECR_REPOSITORY_URI`,
   `SUBNET_IDS`, `SECURITY_GROUP_ID` set per the script's own header.
7. **Review proposed inserts, updates, unchanged rows, and deletions** from
   each dry-run's JSON report before proceeding.
8. **Obtain explicit approval before any real Bedrock calls** — per
   standing instruction, this audit does not obtain or assume that
   approval.
9. **Run real evidence indexing only for the four (five) demo Award
   numbers** — same commands as step 6, without `--dry-run`.
10. **Verify evidence counts and citations** — re-run the `document_type`
    coverage query from the dataset document, scoped to these five Award
    numbers, and confirm `source_table`/`source_primary_key` on a sample
    row resolve back to a real structured record.
11. **Verify the UI and API** — smoke-test each capability in §2 marked
    LIVE, plus re-check whether the Related Negotiations fix (if completed
    per §3) now works end to end.
12. **Rehearse the complete presentation** using
    `AWARD_RAG_LIVE_DEMO_RUNBOOK.md`.
13. **Record rollback steps** — for the API/UI: redeploy task-definition
    revision 44 / Amplify job 44 (the currently-verified-good state, §1).
    For evidence rows: `build_evidence_embedding.py`'s own hard-delete
    reconciliation only ever touches the exact Award number + document
    types in a given run, so rolling back is re-running with
    `--dry-run` first to confirm, or manually deleting the specific
    `(module='AWARD', document_type IN (...), parent_business_identifier
    IN (...))` rows for the five demo Award numbers — no broader blast
    radius is possible given the code's own scoping guarantees (verified
    by this session's own test suite for `build_evidence_embedding.py`).

**Evidence-level semantic retrieval (capability #21) has no consuming
code.** Steps 6–10 populate the table; they do not make retrieval
demoable, because nothing queries it yet. If the client meeting requires
showing evidence-level retrieval as a *live* feature rather than a
described/mocked one, that requires new API work (a retrieval
endpoint/service reading `document_type` in the evidence set) beyond the
scope of this deployment sequence — flag this explicitly if it comes up in
the meeting.

## 5. Checks that require credentials/a live session (not performed)

Per the explicit boundary for this audit, the following were **not**
performed and are documented here as the exact next step instead:

- **Attachment download click-through**: log in to
  `https://main.d288p9gmoteftb.amplifyapp.com/` as an authorized test user,
  open Award `204713-00001`, Attachments tab, click download on the sample
  file (`award_attachment_id` 297106), confirm the file downloads
  successfully and its `content_type` renders sanely in the UI (not the
  garbled raw value seen in the database).
- **Related Negotiations live confirmation**: same login, open Award
  `104949-00002`, click the Negotiations tab, confirm whether it currently
  errors (expected, per §3) or works (would mean the uncommitted
  service/controller changes are somehow already present some other way —
  worth investigating if observed).
- **Global Search live smoke test**: run the five documented client use
  cases from the presentation (Award-number search, title/concept search,
  investigator search, sponsor search, keyword-vs-semantic comparison)
  through the real UI with a real session.
- **AI Summary/Questions reachability**: confirm there is truly no route to
  these panels by attempting to navigate the deployed UI end to end (the
  code-level finding — no page imports the panel components — is treated
  as decisive, but a live click-through would remove all doubt).

## 6. Bedrock calls requiring explicit approval

- `scripts/run-evidence-embedding.sh` (real, non-`--dry-run` runs) for the
  five demo Award numbers in the deployment sequence's step 9 — each
  evidence row triggers one real `amazon.titan-embed-text-v2:0` Bedrock
  `InvokeModel` call. Approximate volume: sum of the "Terms" + "Amounts" +
  "Comments" + "People" + one "Version"-per-row-set counts in the dataset
  document's coverage matrix for these five Awards — largest single
  contributor is `204713-00001`'s 10,864 term rows and 1,369 amount rows,
  so a full unfiltered run on that one Award alone would be several
  thousand Bedrock calls. **Do not index every evidence type for
  `204713-00001`** — its 10,864 term rows make full unfiltered ingestion
  unsuitable for a demo. Recommend running `--document-types` scoped to a
  curated subset per Award (e.g. skip `AWARD_TERM` on `204713-00001`
  specifically) rather than the full default type list, to keep the real
  Bedrock call count proportionate to what will actually be shown. This
  remains documentation only — no indexing or Bedrock call has been run.
- No other Bedrock calls are required or implied by this audit.
