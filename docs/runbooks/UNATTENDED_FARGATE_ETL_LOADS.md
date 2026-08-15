# Unattended Fargate ETL Loads

How to run a large, long-running, AWS-native ETL population load (create
batches, load batches, checkpoint, reconcile) as **one detached ECS Fargate
task** that survives local terminal closure, SSM tunnel teardown, and SAML
credential expiry. This pattern was designed and proven on the Award
staging→dev population load (2026-08-12): 267,386 Award version rows across
40,926 Award numbers, 32 batches, 6.06 hours, exit code 0, zero manual
intervention after launch.

This is an **operational pattern**, not new application code — the
orchestrator is a short Python driver script that calls the existing,
already-shipped, idempotent loader functions
(`etl/load_awards_from_csv.py`'s `_run_create_award_batch` /
`_run_load_award_batch`) in a loop. It is passed to the already-deployed
loader container image via an ECS `run-task` command override — nothing is
committed to the repo or baked into a new image revision.

---

## When to use this pattern

Use it whenever a load is too large to run as a single `--create-batch`/
`--load-batch` CLI invocation from a workstation, and must survive:

- closing the terminal or laptop lid
- an SSM tunnel or VPN session ending
- `bu-nprd` SAML credentials expiring (typically ~1-12h depending on IdP config)

Do **not** use it for anything that fits in one short-lived, foreground CLI
call — the existing `etl/load_awards_from_csv.py` CLI flags
(`--load-award-id`, `--create-batch`, `--load-batch`, `--show-batch`) remain
the right tool for small/manual work.

---

## Preflight: source/target verification (mandatory, every time)

Before writing anything, confirm — via read-only queries only:

1. **Source is the approved source.** For Oracle-backed loads, check
   `ORACLE_DSN` (resolved into the task's environment by
   `configure_ecs_environment(..., include_oracle=True)`) contains the
   expected staging hostname (`stg.db.kualitest.research.bu.edu`) and not a
   production-looking one. Never assume — read the actual resolved DSN.
2. **Target is the approved target.** Confirm the RDS instance / database
   name matches the expected dev environment (e.g. via
   `SELECT current_database()`, or by cross-checking known dev-only rows).
3. **Root cause of any partial/prior state.** Query the relevant
   `archive.etl_batch`/`etl_batch_item` history and the target table's
   current row count/key range before assuming a fresh load — a previous
   partial run may already be resumable.
4. **Exact missing-row inventory.** A full key-diff between source and
   target (fetch both full key sets, diff in Python/SQL) — never estimate.
5. **Protection baselines.** Snapshot every row count that must remain
   unchanged by this load (e.g. attachment reference counts, embedding/
   summary row counts by type, a handful of canary business-key rows) —
   these become the stop-condition baselines the orchestrator checks after
   every batch.
6. **Storage headroom.** Confirm `AllocatedStorageGB`/`MaxAllocatedStorageGB`
   (RDS autoscaling ceiling) against a conservative growth estimate.
7. **Real throughput baseline.** Before committing to a many-hour run, do
   ONE real batch manually (or let the orchestrator's first iteration serve
   as the baseline) and use its measured duration — historical/estimated
   throughput numbers can be wrong by an order of magnitude.

Report all of the above back before the first write, in an exact
`Domain | Source | Target | Expected inserts | Expected final` table.

---

## Orchestrator design

A single Python script, self-contained, passed via ECS task command
override (never committed). Structure proven on the Award load:

```text
main():
  resolve source/target credentials (configure_ecs_environment)
  verify source DSN looks like the approved source (else STOP)
  acquire a dedicated Postgres advisory lock (see below)
  if lock not acquired: report the holder's application_name, exit nonzero
  loop:
    look for an existing incomplete batch (status = 'CREATED') for this
      domain/entity_type and resume it if found
    else create the next batch (existing --create-batch equivalent)
    if the new batch selected zero rows: population complete, break
    load the batch (existing --load-batch equivalent) inside its own
      transaction
    run stop-condition checks (see below)
    if any stop condition trips: log clearly, exit nonzero (batch state is
      left exactly as-is - safe to resume later)
    log a compact checkpoint (see Logging below)
  run final reconciliation, log it, exit 0 only if fully clean
```

### PostgreSQL advisory locking

One session-level advisory lock, held for the orchestrator's entire
lifetime by never closing the connection that acquired it:

```sql
SELECT pg_try_advisory_lock(hashtext('<domain>:<load-name>')::bigint);
-- ... hold the connection open for the whole run ...
SELECT pg_advisory_unlock(hashtext('<domain>:<load-name>')::bigint);
```

`pg_try_advisory_lock` (not the blocking `pg_advisory_lock`) so a second,
accidental concurrent launch fails fast rather than queuing. Session-level
(not the `_xact` variant) so the lock survives across the many separate
transactions the loop opens per batch — only the connection's lifetime
matters, not any single transaction. If the holding connection ever drops
(crash, task kill, network partition), Postgres releases the lock
automatically — a later task can always proceed.

To let a blocked launch identify *who* holds the lock, `SET
application_name = '<label>:<own task ARN>'` on the locking connection
right after connecting (before acquiring the lock), then on failure query:

```sql
SELECT a.application_name, a.pid, a.query_start
FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
WHERE l.locktype = 'advisory';
```

**Known pitfall**: `SET application_name = :bindparam` fails in Postgres —
`SET` does not accept bind parameters. Build the literal inline (the value
here is always an AWS-generated task ARN — alphanumeric/colon/slash/hyphen
only — but strip stray quote characters defensively before inlining
regardless of source).

### Resume semantics

The generic batch framework (`archive.etl_batch`/`etl_batch_item`, shared
across domains — see `docs/architecture/ETL_BATCH_FRAMEWORK.md` if present,
or the framework module's own docstring) uses this status lifecycle:

```text
CREATED  -> membership persisted, load not yet (successfully) run
READY    -> load ran and finished (the *only* terminal status the loader's
            own _finish()-equivalent sets on success - not "COMPLETED")
```

A batch stuck at `CREATED` means its load step never reached its own
finish/commit routine — safe to call the load step again (idempotent
UPSERT), whether that failure was a crash, an OOM, a network blip, or an
intentional stop-condition halt. **Never** treat `READY` as "needs a
retry" — every successfully loaded batch ends at `READY` permanently, so
resume logic must specifically look for `CREATED`, not "not READY".

The create-step's own selection query must independently exclude:
(a) any entity key already `COMPLETED` as a batch item, regardless of that
item's batch's overall status, (b) any entity key belonging to a still-
`READY`/`PROCESSING` batch, **and** (c) any entity key already present in
the target table by direct query — (c) matters because an archive's
original population is often loaded before/outside the batch framework
entirely, so it has no batch-tracking history to exclude it by (a)/(b)
alone. Confirmed this project's existing Award loader already implements
all three (`_excluded_completed_and_active_award_ids`).

### Atomic batch transactions

Each batch's actual data UPSERTs run as one Postgres transaction — a bad
row anywhere in the batch rolls back the *whole batch*, not just rows after
it. Batch/item bookkeeping (`etl_batch`/`etl_batch_item` status) is
separate, always-committed accounting, unaffected by a data-transaction
rollback. This is what makes "resume from `CREATED`" safe: either the
batch's real data is fully committed (and its items marked `COMPLETED`
inside the same final transaction that flips the batch to `READY`), or none
of it is.

### Stop conditions (checked after every batch, not just at the end)

Re-derive these from what the specific load must protect, but the shape
proven on the Award load:

- failure rate (missing-from-source rows / requested rows) exceeds ~1%
- any protected/unrelated row count changed from its captured baseline
  (attachment references, embedding/summary counts by type, etc.)
- a canary business-key row (something known to be stable and load-bearing
  elsewhere in the app, e.g. a demo fixture) changed or disappeared
- a duplicate-key count grew beyond a pre-enumerated, already-understood
  baseline
- an orphan-child-row check (`LEFT JOIN ... WHERE parent.id IS NULL`) on a
  couple of representative child tables returns nonzero
- database size approaches the RDS storage ceiling
- (implicitly, by simply never calling it) the loader's destructive/
  full-replace code path is never invoked

Any stop condition halts the loop immediately with a nonzero exit and a
clear log line. The in-flight or most recent batch is left exactly as the
framework's own bookkeeping recorded it — always safe to inspect or resume.

---

## ECS task launch

Reuses the existing, already-deployed loader task definition and image —
no new build, no new revision, no deploy:

```bash
aws sts get-caller-identity --profile bu-nprd   # confirm account first

B64=$(python3 -c "
import base64, gzip
print(base64.b64encode(gzip.compress(open('orchestrator.py','rb').read())).decode())
")
CMD="python3 -c \"import base64,gzip;exec(gzip.decompress(base64.b64decode('$B64')))\""

aws ecs run-task \
  --cluster <etl-cluster-name> \
  --task-definition <loader-task-family>:<revision> \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[<private-subnet>],securityGroups=[<loader-sg>],assignPublicIp=DISABLED}" \
  --overrides "{\"cpu\":\"<override-if-needed>\",\"memory\":\"<override-if-needed>\",\"containerOverrides\":[{\"name\":\"loader\",\"command\":[\"/bin/sh\",\"-c\",\"$CMD\"]}]}" \
  --region us-east-1 \
  --profile bu-nprd
```

The orchestrator script is base64+gzip-encoded and passed as a container
**command override** on the existing task definition — this is how every
ad hoc read-only investigation script in this project is already run
(never a new image, never a committed file). `run-task` returns immediately
with a task ARN; once the task reaches `RUNNING`, it needs nothing further
from the launching machine.

### Secrets, task role, networking

- **Source credentials** (e.g. Oracle): resolved from the existing
  Secrets Manager secret via `configure_ecs_environment(...,
  include_oracle=True)` — never hardcoded, never logged.
- **Target credentials** (Postgres): resolved the same way via the
  existing secret already wired into the loader task definition.
- **AWS permissions**: the task's own ECS task role (already scoped for
  the loader's normal duties) — no new IAM grant needed for a load that
  only reads Oracle and writes Postgres.
- **Networking**: the loader's existing private subnet + security group
  (already has the VPC peering path to reach Oracle, and the normal RDS
  path to reach Postgres). No new peering, route, or security-group rule.

### Container image and command

The task definition's registered image is used unmodified. The "command"
is entirely the override — a `/bin/sh -c '...'` wrapping a Python one-liner
that decompresses and `exec()`s the orchestrator source. This keeps the
orchestrator logic auditable (it's a plain committed-nowhere script you can
diff/review before encoding) while requiring zero image rebuild/push/deploy
for one-off operational work.

---

## CPU/memory sizing and cost estimation

Before assuming the existing task size is adequate (or bumping it), check
real Container Insights metrics from a representative batch:

```bash
aws cloudwatch get-metric-statistics \
  --namespace ECS/ContainerInsights \
  --metric-name CpuUtilized \
  --dimensions Name=ClusterName,Value=<cluster> Name=TaskDefinitionFamily,Value=<family> \
  --start-time <batch-start> --end-time <batch-end> \
  --period 60 --statistics Average Maximum --region us-east-1
```

(Per-task `TaskId` dimension is not published by standard Container
Insights — only `ClusterName` and `ClusterName`+`TaskDefinitionFamily` are
available. This is still attributable to one task if it's the only task of
that family running in the window.)

On the Award load's first real batch (task-def default: 512 CPU units /
1024 MiB), CPU sat at ~500/512 (~98%) for nearly the whole ~24-minute batch
while memory peaked at 366/1024 MiB (~36%) — clearly CPU-bound. Bumped to
1024 CPU / 2048 MiB via a **task-level `run-task` override** (not a new
task-definition revision) for the full run; total run time came in at 6.06
hours for the remaining population, well under the ~17h estimate at the
smaller size.

**Fargate cost is low regardless of exact duration at this task size.**
Standard on-demand Fargate Linux/x86 pricing (~$0.04048/vCPU-hour,
~$0.004445/GB-hour): 1 vCPU / 2GB for even a full 24-hour run is
approximately **$1.20** — increasing CPU while proportionally cutting
runtime roughly cancels out in the vCPU-hour × GB-hour cost model. Always
compute and state the actual estimate before launching (`vCPU × hours ×
$0.04048 + GB × hours × $0.004445`), and treat a stated cost ceiling (e.g.
"stop if estimate exceeds $20") as a hard precondition to launching, not a
formality.

---

## CloudWatch monitoring (while it runs, and after it's gone)

```bash
# Task status
aws ecs describe-tasks --cluster <cluster> --tasks <task-arn> --region us-east-1 \
  --query 'tasks[0].{status:lastStatus,exitCode:containers[0].exitCode}'

# Tail the latest checkpoint lines
aws logs tail /ecs/<loader-log-group> \
  --log-stream-names loader/loader/<task-id> --region us-east-1 --since 30m \
  | grep CHECKPOINT
```

**Retention pitfall confirmed on the Award load**: `describe-tasks` stops
returning a task a few hours after it reaches `STOPPED` (task metadata
retention, not a log retention setting) — `list-tasks --desired-status
STOPPED` goes empty too. This is expected, not evidence of failure. The
CloudWatch **log stream survives** far longer (governed by the log group's
own retention policy) — that's the durable record. When re-checking a task
launched hours or days earlier, go straight to the log stream, not
`describe-tasks`.

**`get-log-events` pitfall**: called with `--start-from-head --limit N`,
you get the **first** N events chronologically, not the latest — on a
long, verbose run (thousands of per-chunk extraction log lines), repeating
that call gives you the same stale head every time, never the tail. Either
omit `--start-from-head` (returns the latest events) or use
`filter-log-events`/paginate forward with `nextForwardToken` to actually
reach the end of a long stream. Confirmed: an earlier check in this same
session concluded (wrongly) that the Award load had silently died mid-batch,
purely because of this pagination mistake — the task was in fact still
running cleanly and completed successfully six hours later.

**Checkpoint log format** (one JSON line per completed batch, plus a
`=== N-BATCH SUMMARY ===`-prefixed repeat every 5 batches):

```text
CHECKPOINT {"checkpoint": "batch_complete", "batch_id": ..., "batch_number_this_run": ...,
  "awards_selected": ..., "awards_inserted": ..., "child_rows_inserted": ...,
  "batch_duration_seconds": ..., "total_award_rows": ..., "remaining_missing_awards": ...,
  "failed_rows": ..., "database_size": "...", "estimated_time_remaining_hours": ...,
  "elapsed_this_run_hours": ...}
```

Never log credentials, secret values, or full row/record contents in
checkpoint lines — counts and durations only.

---

## Final reconciliation

Logged once, when the create-step selects zero remaining rows (population
exhausted) — a single `FINAL RECONCILIATION {...}` JSON line covering:
source count, target count, remaining-missing (must be 0), distinct
business-key count, every relevant child-domain count, duplicate/orphan
checks, every protection baseline re-checked against its captured value,
total batches (all-time, for this domain — note the underlying
`etl_batch_id` sequence is shared across domains, so this is *not* the same
as the max `batch_id` seen), total duration, and a single `final_status`
(`SUCCESS` only if every check passed). Exit code 0 only in that case.

### Real result — Award load, 2026-08-12

```text
oracle_staging_award_count: 267386   dev_award_version_count: 267386   remaining_missing: 0
distinct_award_numbers: 40926
duplicate_business_key_groups: 112 (pre-existing, known-legitimate baseline - unchanged)
orphan_award_person_rows: 0
award_attachment_count: 720428 (baseline 720428 - unchanged)
search_embedding_by_type: unchanged (639 evidence rows across 7 types + 24,558 summary rows across 4 types)
total_batches_all_time: 32   duration_this_run_hours: 6.06   final_status: SUCCESS
```

A same-day direct Oracle-vs-dev comparison across all 16 Award child tables
found 12 exact matches and 4 with a combined 73-row gap (out of ~16.6M child
rows, ≈0.0005%) — explained by Kuali staging being a live BU test
environment that kept accepting writes during/after the ~6-hour extraction
window, not a loader defect (the loader itself reported zero rejected rows
throughout). Worth re-running this specific cross-check after any future
long-running staging extraction, since staging is not a frozen snapshot.

---

## Reusing this pattern

### A related but distinct pattern: the Subaward nightly sync

The **Subaward nightly sync** (`--sync-all`, see
[SUBAWARD_NIGHTLY_SYNC.md](SUBAWARD_NIGHTLY_SYNC.md)) is *not* an
application of this document's batch-orchestrator-loop pattern, despite
solving a similar-sounding problem ("keep an ongoing recurring load
running unattended"). The differences are deliberate:

- **Scheduled recurring, not a one-time population load.** This runbook's
  pattern is for a single large backfill that runs once (or is manually
  re-launched); the Subaward sync runs automatically every night via
  EventBridge Scheduler, forever, with no operator launch step at all.
- **No custom orchestrator script, no base64/gzip command-override
  encoding.** `--sync-all` is a normal, committed, tested CLI flag on
  `load_subawards_from_csv.py` - the entire "orchestrator" is just
  `python3 load_subawards_from_csv.py --ecs --sync-all`.
- **No `etl_batch`/`etl_batch_item` batching at all.** Every Oracle
  family is read and UPSERTed in one task run, family-by-family, each in
  its own transaction - there is no batch-creation/resume step because
  the full population comfortably fits in one task's read (a few minutes
  for ~3,300 families as of 2026-08-14 - reconsider this pattern only if
  Subaward's population grows enough to make a single-task full read
  itself the bottleneck).
- **Advisory lock prevents overlap the same way**, but as a guard against
  the scheduled run overlapping a manual `--sync-all`/`--load-subaward-code`
  invocation, not for the same reason (there's no long-running single
  task to protect from being launched twice).

Use this runbook's pattern for a new large one-time backfill; use
`--sync-all`'s pattern for a domain's small enough ongoing population
that just needs to stay in sync every night without an operator.

### For the attachment domain (built, 2026-08-12 - `etl/attachment_orchestrator.py`)

The general shape applies directly - a create-step that selects the next N
not-yet-loaded physical files (excluded via durable Postgres state, not
`etl_batch_item` history alone), a load-step split into two checkpointed
**stages** (metadata, then binary - see below), idempotent S3 upload
(skip-if-already-verified with a hash/size check, never blind re-upload),
the same whole-task advisory lock. Full design and rationale in
`docs/architecture/ARCHIVE_ATTACHMENT_LOAD_INVENTORY.md`; summarized here
as the pattern's second real, concrete application:

- **Two stages per module, always in order**: METADATA (bring missing
  reference rows in from Oracle - never touches a BLOB or S3) then BINARY
  (stream physical file content - never re-reads a BLOB for a file already
  durably `UPLOADED`). Metadata-before-binary is enforced structurally, not
  by convention: the binary stage only ever selects from batches whose
  metadata stage already flipped `etl_batch.status` to `READY`.
- **Reuse existing domain loaders where they already exist** rather than
  building parallel logic: Award needed zero new loading logic at all
  (`load_award_attachments.py`'s `_run_create_batch`/`_run_load_batch`/
  `_run_upload` were already complete and already tested); only Proposal
  and Subaward - which only had a CSV-file-driven generic plugin, not a
  database-driven batch loader - needed new, analogous functions, and even
  those reuse the existing metadata-UPSERT function
  (`load_proposals_from_csv.upsert_proposal_attachments`) rather than
  duplicating it.
- **A non-integer physical-file identity doesn't fit `etl_batch_item`
  directly.** `entity_key` is a plain `BIGINT` (see V037); a UUID
  `file_data_id` (Proposal/Subaward) can't be stored there. Resolution:
  persist the real batch membership in `archive.etl_batch.
  selection_parameters` (JSONB, already present) and use a synthetic
  per-batch ordinal as `entity_key` purely for that one batch's own
  item-level status tracking - never as a cross-batch identity. Cross-batch
  "already selected" exclusion falls back to a direct durable-Postgres-
  existence check instead of `etl_batch_item` history, which is safe
  specifically because the single whole-task advisory lock rules out any
  concurrent batch-selection race.
- **A physical file can be referenced by many rows, and possibly by
  another module.** Never assume distinct-file counts sum cleanly across
  modules without checking - a same-region `INTERSECT` query across the
  relevant Oracle source columns settled it empirically (zero overlap, in
  this case) rather than assuming either way. Batch and upload-state
  queries stay module-scoped regardless, so a future overlap couldn't be
  silently mishandled even if the data changed.
- **A missing durable "not started"/"in progress" state is a real
  blocker, not a detail.** One of the three module schemas
  (`archive.subaward_attachment_archive`) had a `CHECK` constraint that
  only allowed terminal states (`ARCHIVED`/`MISSING`/`FAILED`) - discovered
  only while implementing the binary stage, not during the original
  preflight. Fixed with a small, additive migration
  (`V073__extend_subaward_attachment_archive_status.sql`), mirroring
  Award's own earlier precedent (`V036`) for the identical problem -
  worth explicitly checking for on any future domain, not assumed present.
- **A child table's population is bounded by its own parent's
  population.** Discovered mid-implementation: Subaward's attachment table
  is a child of the core Subaward record loader, whose own population is
  itself only 0.6% loaded (513 of 88,818) - the attachment loader correctly
  produces near-zero new rows today, not because of a bug, but because a
  metadata row can't be inserted for a `subaward_id` with no core record
  yet (a real foreign key). Always check whether a "child domain's" gap is
  actually downstream of a parent-population gap before assuming the child
  domain is independently loadable.
- **Never mark a unit of work done before reconciling it.** Even when an
  existing, reused function (Award's `_run_upload`) already sets its own
  internal completion marker unconditionally, the orchestrator adds its
  own, additional gate on top - a real S3 HEAD-check reconciliation pass
  after every batch, independent of and in addition to whatever the reused
  function itself already tracks. A dirty reconciliation stops the whole
  run, not just the one module.
- Cost estimation for this domain needed six separated categories (Fargate
  compute, S3 PUT requests, S3 storage - one-time vs. monthly separately,
  data transfer, and Secrets Manager/CloudWatch/database overhead) rather
  than one number - S3 storage and PUT-request costs dominate over Fargate
  compute for an I/O-bound, byte-heavy domain like this one, the inverse of
  the Award core-record load's own SQL-aggregation-bound cost profile.

### For a future full production load

If/when real Kuali **production** Oracle connectivity is ever provisioned
(a deliberate infrastructure decision this project has not made — see
`docs/ORACLE_STAGING_CONNECTIVITY.md`), this exact orchestrator pattern
still applies unchanged: swap the source DSN, re-run the full preflight
(a production key-diff will differ from a staging one), and treat the
existing staging-vs-production distinction in all reporting/documentation
as load-bearing — never silently reuse a "staging" report's language for a
production run, and never point this pattern at a source that hasn't been
explicitly approved for the specific run.
