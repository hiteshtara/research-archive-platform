# Current project state

Repository-owned, hand-maintained milestone/state record for
`research-archive-platform`, read verbatim by
`scripts/restore-project-context.sh`. Update this file as milestones
change — do not let `restore-project-context.sh` or any other script
regenerate it automatically; it is meant to hold judgment calls a script
can't infer on its own (what's actually done vs. in progress, why a
commit exists, what to do next).

Keep entries short. Each commit entry should be verifiable independently
(`git log <hash>`, `git branch --contains <hash>`) — this file records
*why* a commit exists and what state it left things in, not a
substitute for `git log`.

## Commits — pushed and deployed as of 2026-08-13

| Commit | Summary | Status |
|---|---|---|
| `bb22466` | Award Custom Data API/UI feature (repository, service, controller, DTO, frontend client/type/component, nav entry, tests) | **Deployed** — API task-def `research-archive-platform-dev-api:52` (image `20260813T180142Z-5576a3c`), UI Amplify job `55` (`SUCCEED`). Dev RDS already had the underlying data (see `4bc9adb` below); this commit was the only missing piece. |
| `876e023` | `CLAUDE.md` + `docs/runbooks/LOCAL_SETUP.md`: documents AWS RDS as the authoritative dev database, ECS Fargate as the supported access path, and the missing-bastion-blocks-only-the-local-tunnel distinction | Documentation only, no code behavior change |
| `f7c8028` | Removed `scripts/start-db-tunnel.sh` + `api/scripts/dev.sh` (unsupported local SSM tunnel to dev RDS — no EC2 bastion exists) and updated every active reference to point at the ECS route instead | Documentation/script removal only |
| `4bc9adb` | `docs/architecture/AWARD_CUSTOM_DATA_DESIGN.md`: records verified Oracle staging vs. dev RDS Award Custom Data counts | Documentation only |
| `5576a3c` | `scripts/restore-project-context.sh` + `docs/project-memory/CURRENT_STATE.md` (this file) | Tooling only |

All five are on `bu/main`, `origin/main`, and local `main` (all three refs
verified identical, `5576a3c...`, as of the push below).

## Commits — pushed and deployed as of 2026-08-14 (Negotiation attachments)

| Commit | Summary | Status |
|---|---|---|
| `85886b9` | Centralized `AttachmentAuthorizationService` (Cognito `ArchiveAttachmentViewer` group) across Award/Proposal/Subaward/Negotiation; Negotiation attachment support; `V076` migration | Code + migration file, not yet applied at commit time |
| `7f7194e` | Frontend attachment-section hiding completed for every domain; `ArchiveAttachmentViewer` Cognito group Terraform | **Deployed to Amplify** (job `65`, `SUCCEED`) ahead of the API - see "two incidents" below for why that split mattered |
| `bb24ab6` | Negotiation search: exact negotiation_id/document_number match ranks first (previously ordinary substring search could bury an exact ID past page 1) | Code |
| `a4a31cc` | Exposes `oracleAttachmentId`/`oracleFileId`/`description` on `NegotiationAttachmentResponse`; drops `checksum` (storage internal, never user-facing) | Code |
| `43c8427` | Archived File Finder: removes the nonexistent "Negotiation number" field (Negotiation ID is the only business identifier - see `docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md`); guards Negotiation ID (420) vs Association ID (419) | Code |
| `a406adf` | Hides the Version filter for Negotiation (no version chain exists) | Code |
| `3e4a144` | `NegotiationArchiveRepositorySchemaIntegrationTest` - real Postgres via Testcontainers, all committed migrations applied, real repository SQL | Test-only |
| `43859c2` | Fixes `findAttachments`'s three unaliased SELECT columns (`archived_attachment_id`/`original_file_name`/`byte_size`) that broke row-mapping for every Negotiation with real attachment rows - see the design doc's "two incidents" section | Code + regression tests seeded with real fixture rows |

**Deployed**: API task-definition revision **59** (health `200 UP`),
Amplify job `66` (`SUCCEED`, commit `a406adf`) then unchanged through
`3e4a144`/`43859c2` (test/backend-only, no UI change - no new Amplify
build needed or triggered for those two). `V076` applied to dev RDS via
the ETL loader's `--migrate-only` mode (schema-only - see the design
doc for the full incident writeup and why the previously-registered
loader image had to be rebuilt first).

To check whether a commit above is contained in the current branch or a
remote:

```bash
git branch --contains <hash>              # local branch containment
git merge-base --is-ancestor <hash> bu/main && echo "on bu/main"
git merge-base --is-ancestor <hash> origin/main && echo "on origin/main"
```

## Commits — Subaward nightly sync (2026-08-15), ETL-only, no API/UI deploy

| Commit | Summary | Status |
|---|---|---|
| `4ca4703` | `load_subawards_from_csv.py` `--sync-all`/`--reconcile-only`; removes the destructive no-verb default (an explicit operation is now required - `--full-refresh` gates the old TRUNCATE path); 23 new tests | Code + tests |
| `42f5d19` | `terraform/modules/subaward_sync_schedule/`: EventBridge Scheduler (2am America/New_York), least-privilege IAM, CloudWatch alarms/SNS topic | Terraform, plan-reviewed, not yet applied at commit time |
| *(pending)* | `main.tf`'s scheduler `input` gains a task-level `cpu=1024/memory=3072` override (the default 512/1024 OOM-killed a real full-population `--sync-all` run - see below); this doc | Terraform + docs |

**Initial full load, completed 2026-08-15**: the business-data load
deferred since the 2026-08-14 500-code pilot (see
`project_award_custom_data_oracle_reconciliation`-adjacent memory) was
finished via `--sync-all` rather than manual code-chunking, since
`--sync-all` already provides the same per-family isolation more safely.
Two attempts at the shared task definition's default size (512 CPU/1024
MiB) were OOM-killed (exit 137) during the pre-loop full-population read
(11 datasets, ~2M rows total, held in memory before the per-family
UPSERT loop starts) - confirmed via direct reconciliation query that
both left **zero** partial writes (per-family transactions mean the kill
happened before any family's transaction began). Fixed with a
task-level `cpu=1024/memory=3072` override (mirroring the identical,
already-proven fix for the Award backfill in
`docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md`'s "CPU/memory sizing"
section). Clean result:

```text
requested=3,265  completed=3,265  failed=0
subaward             inserted=78,382  unchanged=10,436
subaward_amount      inserted=168,293 unchanged=13,484
subaward_contact     inserted=160,473 unchanged=25,277
subaward_custom_data inserted=855,869 unchanged=155,621
subaward_funding     inserted=6,530   unchanged=749
subaward_attachment  inserted=419,723 unchanged=40,392
subaward_notification inserted=28,785 unchanged=124
subaward_template_info inserted=78,382 unchanged=10,436
Reconciliation: oracle=3,265 rds=3,265 oracle_only=0 rds_only=0
```

Post-load verification: `archive.subaward` distinct codes/rows =
3,265/88,818 (exact Oracle match), zero orphan child rows across all 7
FK-linked child tables, zero duplicate `subaward_id` PKs,
`archive.subaward_attachment_archive` (binaries - a separate pipeline,
never touched by this loader) unchanged: 1,764 rows, checksum
`65818048a5d00b7ada509f8e5e08c3c9` identical before and after.

**Idempotency proven**: an immediate second `--sync-all` run (same
image, task-def revision 222) reported `inserted=0 updated=0` on every
table, `unchanged` exactly matching the first run's total row counts,
and the identical clean reconciliation - confirms the UPSERT logic is
genuinely idempotent, not just "ran without error."

**Image/task-def revisions used**: ECR image
`research-archive-platform-dev-loader:20260815T021447Z-42f5d19`
(digest `sha256:ae65a464958a0acb1d96dc875279621751d46563bb9adae2dfb8cfd3cf73a360`),
task-definition revisions 218 (initial, pre-memory-fix), 221 (first
clean full load, 1024/3072), 222 (idempotency proof, 1024/3072).

**Terraform scheduler**: not yet applied as of this entry - a scoped
`terraform plan -target=module.subaward_sync_schedule` (10 to add, 0 to
change/destroy, real dev backend) was reviewed, but the schedule's
`input` needed the same `cpu`/`memory` override discovered above before
being safe to enable unattended (the same OOM would otherwise recur
every night). See `docs/runbooks/SUBAWARD_NIGHTLY_SYNC.md` for the full
operational writeup.

## Git remotes — two of them, do not assume which one anything uses

- `bu` → `git@github.com:bu-ist/research-archive-platform.git` — the BU
  source-of-record remote.
- `origin` → `git@github.com:hiteshtara/research-archive-platform.git` —
  a personal-account remote.
- **The Amplify UI app (`d288p9gmoteftb`) is connected to `origin`
  (`hiteshtara/research-archive-platform`), not `bu`** — verified via
  `aws amplify get-app --query 'app.repository'`, not assumed. A commit
  only pushed to `bu` is invisible to Amplify.
- **Before every UI deployment, inspect the live Amplify repository
  connection (`aws amplify get-app`) and confirm the target commit
  exists on that exact remote** (`git merge-base --is-ancestor <hash>
  <remote>/main`) — do not assume Amplify follows whatever remote the
  local branch's Git upstream happens to track (as of 2026-08-13, local
  `main`'s tracked upstream is `bu/main`, which is *not* what Amplify
  watches).
- The API (ECS) has no equivalent "connected repo" concept — its image
  is built and pushed from whatever commit a human/agent has checked
  out locally when `ops/deploy-api.sh` runs (see below), independent of
  either remote's state at that moment.

## Verified Award Custom Data facts (as of 2026-08-13)

**These are point-in-time verified facts, not live values.** Re-verify
via `scripts/restore-project-context.sh --aws`/`--oracle` (or the
underlying ECS/Oracle-runner commands directly) before relying on them
for anything beyond historical context — both Oracle staging and dev RDS
can change independently of this file.

- Oracle staging (`stg.db.kualitest.research.bu.edu`, schema `KCOEUS`):
  `AWARD` = 267,386 rows / 40,926 distinct Award numbers.
  `AWARD_CUSTOM_DATA` = 6,328,084 rows.
- Dev RDS (`archive` schema): `award_version` = 267,386 rows / 40,926
  distinct Award numbers (matches staging exactly — repopulated from
  staging 2026-08-12, see `docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md`).
  `award_custom_data` = 6,328,064 rows.
- The 20-row Custom Data gap between staging and dev RDS is exactly the
  20 Oracle rows whose `AWARD_ID` has no matching `AWARD` row — excluded
  by dev RDS's foreign-key constraint, not a bug or an incomplete load.
- Award `204713-00117` (the running example used throughout this
  investigation): 7 versions (`award_id` 2673287, 2750879, 2805868,
  2900501, 2932424, 3108429, 3160098), 260 `award_custom_data` rows,
  already present in dev RDS.
- An ECS Fargate one-off task (`--load-award-id 3160098 --dry-run`,
  cluster `research-archive-platform-dev-etl`, task definition
  `research-archive-platform-dev-loader:192`) proved all 48 Award child
  datasets are already synchronized between Oracle staging and dev RDS
  for this family: every table reported `inserted=0 updated=0`, and
  pre/post `archive.award_custom_data` row counts on dev RDS were
  byte-for-byte identical (rollback proof).
- **Conclusion: no Award Custom Data (or broader Award) load is required
  for this family.** What's missing is deploying `bb22466`'s API/UI code.

## Verified Award/Proposal attachment loading facts (as of 2026-08-13)

**These are point-in-time verified facts, not live values.** Re-verify
via a read-only ECS diagnostic task before relying on them beyond
historical context — both the active task's progress and dev RDS
counts change continuously.

- **Award attachments are complete**: `archive.attachment_object` shows
  127,752 `UPLOADED` + 6 `MISSING_SOURCE_CONTENT` (structural — Oracle
  has no blob for these 6 files) = 127,758/127,758, 0 real pending, 0
  failed. S3 reconciliation clean (0 mismatches). All 103
  `AWARD_ATTACHMENT`/`PHYSICAL_FILE` batches are `COMPLETED`.
- **Proposal attachment processing is complete, not still running** —
  corrected 2026-08-15 (was previously documented here as "active").
  ECS task `a315e91b4c364901b5db3d4e5a4403ca` (task-def
  `research-archive-platform-dev-loader:192`, command
  `attachment_orchestrator.py --ecs --modules award,proposal`), started
  2026-08-12T15:04:44-04:00, ran through 72 Proposal binary batches and
  printed its final orchestration summary at 2026-08-14T02:55:52Z: this
  run alone uploaded 149,248 Proposal files (0 failed, 0
  missing-source-content), and its last reconciliation batch checked
  all 405,779 `archive.proposal_attachment` rows clean (0 mismatches).
  The task no longer appears in `aws ecs describe-tasks`/`list-tasks`
  (past ECS's stopped-task retention window as of 2026-08-15) —
  completion is evidenced via its final CloudWatch log line, not a live
  ECS task state. Retained here as completed history, not current
  activity; do not re-launch a follow-up task for this work.
- **Do not launch a retry or a second loader/orchestrator task.** No
  failed items exist in either domain (0 `FAILED` in
  `attachment_object`/`proposal_attachment`), so there is nothing to
  retry, and a second concurrent task would race the running one on the
  same `archive.etl_batch`/`etl_batch_item`/`attachment_object`/
  `proposal_attachment` rows.
- **The 6,657 `archive.etl_batch_item` rows showing `PENDING` status for
  the `AWARD_ATTACHMENT`/`PHYSICAL_FILE` domain are stale bookkeeping,
  not real pending work** — cross-checked directly: every one of those
  6,657 `entity_key` (file_id) values already shows `UPLOADED` in the
  authoritative `archive.attachment_object.upload_status` column. They
  belong to batch 65 (836 items) and batch 66 (5,821 items — the
  external-file backfill, see below), both `COMPLETED` at the batch
  level; only their individual item rows never got flipped from
  `PENDING`. Don't treat non-`COMPLETED` `etl_batch_item` rows as proof
  of outstanding work without cross-checking the domain's own
  authoritative status column first.
- The external-file backfill (`etl/backfill_external_attachment_file_data_id.py`,
  task `9ea0174f811445b9bd04123df103f27b`, completed 2026-08-11) is a
  completed sub-component of Award's overall completion, not a separate
  unfinished job: 5,821 external files, 5,821 S3 objects verified, 0
  missing, 0 size mismatches, 2,467,950,513 bytes (2.47 GB) transferred.

## Deployment architecture — verified 2026-08-13, not assumed

`clean committed source → local Docker build → BU non-production ECR →
ECS Fargate` is the real, established, and *only* API deployment path
for this project right now:

- API images are built on a developer Mac using `ops/deploy-api.sh`
  (`mvn clean package -DskipTests` + `docker build`). The build must run
  against a **clean committed worktree** (e.g. `git worktree add
  --detach <path> <commit>`), never the live working directory directly
  — the live tree routinely has uncommitted changes that must not leak
  into a release image.
- Images are pushed to BU non-production ECR
  (`770203350335.dkr.ecr.us-east-1.amazonaws.com/research-archive-platform-dev-api`).
- ECS task definitions use immutable `<timestamp>-<short-sha>` tags
  (e.g. `20260813T180142Z-5576a3c`) — never `:latest` for what's actually
  deployed, even though `ops/deploy-api.sh` also pushes a mutable
  `:latest` tag as a side effect (**recorded as technical debt, not
  fixed** — the task definition never references `:latest`, so it
  doesn't block anything; left unchanged deliberately during the
  2026-08-13 release rather than touching the script mid-release).
- Containers run only in BU non-production ECS Fargate
  (`research-archive-platform-dev-api` cluster/service). No local
  application container and no local database are ever part of the
  deployed runtime — the build step itself also never touches a
  database (pure Maven + Docker build, no DB connection).
- **No AWS CodeBuild project or CI-based deployment pipeline exists for
  this project as of 2026-08-13** — verified via `aws codebuild
  list-projects` (57 projects in the account, all belonging to unrelated
  BU services) and `.github/workflows/ci.yml` (tests only: `mvn test` /
  `npm test`+`lint`+`build` / `uv run pytest` — no Docker build, no ECR
  push, no deploy step). An AWS-native build pipeline (CodeBuild or
  similar) is potential future work, not current functionality — do not
  document or assume one exists until it's actually built.
- UI deploys go through the existing Amplify app, which auto-triggers a
  build on push to its connected branch (see remotes section above) —
  starting a job manually is usually unnecessary once the right remote
  has the commit.

## CloudShell analyst access and Negotiation external-BLOB fix (2026-08-15)

**CloudShell analysis access is operational.** Zero-fixed-cost AWS
CloudShell VPC environment access to dev RDS PostgreSQL as
`archive_analyst` (read-only: `CONNECT`/`USAGE`/`SELECT`,
`default_transaction_read_only = on`) works end to end - see
`docs/runbooks/CLOUDSHELL_ANALYSIS.md` (architecture, one-time setup,
the 2026-08-15 incident writeup) and
`docs/runbooks/CLOUDSHELL_DATABASE_ACCESS.md` (step-by-step workflow +
query cookbook, consolidated from a previously duplicated/corrupted
draft). Direct Mac/VPN access to private RDS remains unavailable (this
VPC has no BU VPN/TGW route - see
`docs/runbooks/VPN_RDS_CONNECTIVITY_INVESTIGATION.md`); CloudShell is
the approved interactive read-only path until that changes.

`scripts/mac-show-analyst-password.sh` and
`scripts/mac-generate-analyst-password.sh` were hardened and covered by
`scripts/tests/test-mac-analyst-password-helpers.sh` (fully mocked - no
live AWS, no real clipboard) after a live defect: a successful AWS call
whose stderr got merged into stdout (`2>&1`) could silently corrupt the
JSON being parsed, and the show-password script claimed success with no
verification that `pbcopy` actually updated the clipboard. See
`CLOUDSHELL_ANALYSIS.md`'s "Setup incident and password-helper
hardening" section for the full factual writeup - no password or hash
recorded anywhere.

**Database findings** (from the CloudShell investigation, all
re-queryable via `CLOUDSHELL_DATABASE_ACCESS.md`'s cookbook):
`archive` schema had 113 base tables as of 2026-08-15. Attachment
storage genuinely differs by domain (Award: `award_attachment` →
`attachment_object`; Proposal: `proposal_attachment` owns its own
upload status; Subaward: `subaward` → `subaward_attachment` →
`subaward_attachment_archive`; Negotiation: `archived_attachment` via
`module_code`/`parent_record_id` convention, no PostgreSQL FK) - see
`docs/DECISIONS.md`/the architecture docs for why. Negotiation
`source_attachment_id` duplicate count and parent-orphan count were both
zero.

**Negotiation external-BLOB fix** (commit `ed7a211`, pushed): the
exporter never selected `ATTACHMENT_FILE.FILE_DATA_ID`, so 26,572 of
28,923 Negotiation attachments (91.9%) were archived as MISSING despite
having real, retrievable content in Oracle `FILE_DATA`. Fixed
(`InlineOrExternalBlobReader`, 40 new/updated tests) and proven on
exactly one fixture (Negotiation 12788, attachment 29373, File ID
164229, 140,288 bytes, S3 checksum verified, PostgreSQL flipped from
MISSING to ARCHIVED for that one row only). **The full 26,572-row
backfill has not run** - paused pending explicit approval. Do not mark
the 9 genuinely-missing rows as archived; do not reprocess the 2,342
already-correct inline rows unnecessarily when it does run.

## Live AWS/Amplify state — verified 2026-08-15 (14:04 EDT), not assumed

**These are point-in-time verified facts** (`AWS_PROFILE=bu-nprd`,
account `770203350335` confirmed via `aws sts get-caller-identity`
before every call below). Re-verify before relying on them beyond
historical context.

**Persistent resources (always-on):**
- API: ECS service `research-archive-platform-dev-api` (cluster of the
  same name) — 1 desired / 1 running task
  (`eb5a912c47ae4366a2a6d71d3b1804fd`).
- Database: RDS `research-archive-platform-dev-postgres`
  (`db.t4g.micro`, Postgres) — status `available`.

**Scheduled/one-off resources (not always-on — do not read a zero
running-task count here as a problem):**
- ETL: cluster `research-archive-platform-dev-etl` — 0 running tasks.
  Expected: this cluster runs no persistent service
  (`activeServicesCount: 0`); every task is a one-off `run-task`
  launch (loader runs, the Subaward nightly sync below, etc.), so an
  idle cluster between runs is normal, not a fault.
- Subaward nightly sync: EventBridge Scheduler
  `research-archive-platform-dev-subaward-nightly` — `State: ENABLED`,
  `cron(0 2 * * ? *)`, timezone `America/New_York` (2:00 AM ET daily).
  Target: task-definition
  `research-archive-platform-dev-loader:222`, image
  `research-archive-platform-dev-loader:20260815T021447Z-42f5d19`,
  command `python3 load_subawards_from_csv.py --ecs --sync-all`,
  task-level override `cpu=1024/memory=3072` (the fix from the initial
  full-load OOM, see above). Next expected execution: **2026-08-16
  02:00 America/New_York** (computed from the cron expression against
  the current time, 2026-08-15 14:04 EDT — EventBridge Scheduler has no
  API to read a precomputed "next fire time" directly).
- Proposal attachment task `a315e91b4c364901b5db3d4e5a4403ca`: confirmed
  **completed**, not running — see the corrected bullet in "Verified
  Award/Proposal attachment loading facts" above.

**Amplify UI (`d288p9gmoteftb`, connected to `origin` per the remotes
section above):**
- Branch `main`, latest job **71**, status `SUCCEED`, commit
  `ed7a2111f2feca0d00d71d8e5f3037f7542c5df9` ("fix(negotiation): resolve
  externally-stored attachment BLOBs, not just inline"), built
  2026-08-15T10:34:38-04:00–10:36:08-04:00.
- Local `main` HEAD as of this check (`6c0b174`, "fix(ops): harden
  CloudShell analyst credential helpers and document workflow") is
  **not yet on `origin/main`** (`git merge-base --is-ancestor` returns
  false) — it is therefore not deployed to Amplify. This is expected,
  not a gap: push to `origin/main` first if that commit needs to reach
  the live UI.

## Open items

- The reconciliation in this file covers Award `204713-00117`/`204713-00088`
  specifically, not a full-population diff — the underlying counts
  (267,386/40,926/6,328,084) suggest full correspondence, but no full
  key-level diff across every Award has been run.
- `ops/deploy-api.sh` still pushes a mutable `:latest` ECR tag alongside
  the immutable one — harmless (nothing references it) but worth
  removing in a future, non-release-blocking cleanup.
- The Payment, Reports & Terms redesign (Payment/Invoice, Special
  Approval, Closeout subsections; separating Report Terms from Sponsor
  Terms in the UI) is scoped but not started — deliberately excluded
  from the 2026-08-13 Custom Data release. Report Terms themselves are
  *not* missing — `AwardTermsSection.tsx` already renders both Sponsor
  and Report Terms (built in `61eaff5`, already live before this
  release); the gap is presentation/grouping, not missing data or code.
- **`database/migrations/V071__extend_search_embedding_for_evidence_documents.sql`
  is an unresolved reproducibility gap, discovered 2026-08-14, not
  repaired.** It is completely uncommitted (`git log --all` shows zero
  history for it) yet is already marked applied in dev RDS's
  `public.schema_migration` (`version = 71`) — some commit's DDL effect
  reached dev RDS and the commit itself later disappeared from git
  history. A fresh clone cannot currently reconstruct dev RDS's real
  schema from git history alone. Unrelated to Negotiation attachments;
  tracked here only because it was found while verifying `V076` was the
  sole pending migration for that release — see
  `docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md`'s "Tracked,
  unresolved" section for the full writeup and where to start if picked
  up. `V073__extend_subaward_attachment_archive_status.sql` is the same
  kind of uncommitted local file but is *correctly* unapplied (never
  shipped in any image) — no gap there, just don't commit it as a
  side effect of unrelated work.
