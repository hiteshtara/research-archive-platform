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

To check whether a commit above is contained in the current branch or a
remote:

```bash
git branch --contains <hash>              # local branch containment
git merge-base --is-ancestor <hash> bu/main && echo "on bu/main"
git merge-base --is-ancestor <hash> origin/main && echo "on origin/main"
```

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
- **Proposal attachment processing is active**, not stalled — ECS task
  `a315e91b4c364901b5db3d4e5a4403ca` (task-def
  `research-archive-platform-dev-loader:192`, command
  `attachment_orchestrator.py --ecs --modules award,proposal`), started
  2026-08-12T15:04:44-04:00, still `RUNNING` and making fresh progress
  (~2,000 files uploaded every ~6 minutes, 0 failed, S3 reconciliation
  clean on every batch). `archive.proposal_attachment` had 165,177
  `UPLOADED` / 240,602 `NOT_REQUESTED` / 405,779 total as of the
  2026-08-13 investigation — the `NOT_REQUESTED` remainder is real
  outstanding work this task is actively draining, not stale/missing
  data.
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
