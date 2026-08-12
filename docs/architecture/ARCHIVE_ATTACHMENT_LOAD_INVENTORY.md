# Archive-Wide Attachment Load — Preflight Inventory (2026-08-12)

Read-only investigation only. No Oracle write, no S3 write, no Postgres
write, no load launched. All counts below are from live queries against
Kuali staging Oracle and dev PostgreSQL on 2026-08-12.

> **2026-08-12 update (implementation checkpoint)**: the orchestration
> wrapper described as a design in the original preflight below
> (`etl/attachment_orchestrator.py`) has now been built and tested (39
> new tests, all passing; full ETL suite 1012/1013, the one unrelated
> failure pre-dates this work). Two corrections to the original
> preflight surfaced during implementation, both load-bearing — see
> "Implementation findings" below for the full detail:
> 1. **Subaward's real achievable scope today is ~0 new attachment
>    metadata rows**, not 458,351 — its core `archive.subaward` record
>    population is itself only 513 of 88,818 real Oracle subawords
>    (0.6%), and `subaward_attachment.subaward_id` has a real foreign
>    key to `archive.subaward`. This is a separate, out-of-scope
>    undertaking, not an attachment-loader gap.
> 2. **Revised achievable "missing metadata" total: ~1,389,447**
>    (Award 995,000 + Proposal 394,447), not ~1.85 million.

## Implementation findings (orchestration wrapper build)

### Reused vs. new code

Award needed **no new metadata/upload logic at all** —
`etl/load_award_attachments.py` already had everything required
(`_run_create_batch`/`_run_load_batch` for metadata, `_run_upload` for
binaries, durable `archive.attachment_object.upload_status` tracking,
the correct INLINE/EXTERNAL `resolve_blob_location()` distinction). The
new orchestrator calls these functions directly, unchanged.

Proposal and Subaward had no batch-oriented, database-driven loader
(only a CSV-file-driven generic plugin framework) - new functions were
added, deliberately mirroring Award's proven shape, in
`etl/attachment_orchestrator.py`. Proposal's actual Postgres UPSERT
reuses `load_proposals_from_csv.py`'s existing, already-correct
`prepare_attachments()`/`upsert_proposal_attachments()` unchanged
(including its own documented guarantee: a metadata reload never
touches `upload_status`/`s3_bucket`/`object_key`/`file_size`/
`checksum`/`uploaded_at`).

### State model

| Concept | Award (`archive.attachment_object.upload_status`) | Proposal (`archive.proposal_attachment.upload_status`) | Subaward (`archive.subaward_attachment_archive.archive_status`) |
|---|---|---|---|
| CREATED (not yet attempted) | `PENDING` | `NOT_REQUESTED` | `PENDING` *(added by V073 - see below)* |
| IN_PROGRESS | `UPLOADING` | `IN_PROGRESS` | `UPLOADING` *(added by V073)* |
| UPLOADED | `UPLOADED` | `UPLOADED` | `ARCHIVED` |
| MISSING (Oracle blob genuinely absent) | `MISSING_SOURCE_CONTENT` | `MISSING_SOURCE` | `MISSING` |
| FAILED | `FAILED` | `FAILED` | `FAILED` |

REUSED and VERIFIED are not separate persisted status values in any of
the three tables - they are runtime/reconciliation classifications:
REUSED = a candidate found already `UPLOADED`/`ARCHIVED` with a matching
bucket/key, so the physical file is not re-streamed (counted as
`skipped_already_uploaded` in every stage's own report); VERIFIED = the
outcome of `reconcile_batch()`'s own post-batch S3 HEAD-check pass
(reported via `reconciliation.checked`/`reconciliation.clean`, not a
column). Award's and Proposal's existing schemas already had a complete
enough state set; only Subaward's did not (see below).

### V073 migration: required, not optional

`archive.subaward_attachment_archive.archive_status`'s original `CHECK`
constraint (V019) only ever allowed `ARCHIVED`/`MISSING`/`FAILED` - no
"not yet attempted" or "in progress" state existed at all, unlike Award
(`PENDING`/`UPLOADING` added by V036) and Proposal (`NOT_REQUESTED`/
`IN_PROGRESS` from V060 originally). Discovered while implementing the
Subaward binary stage - without this widening, the same durable
CREATED/IN_PROGRESS state Award and Proposal already have would be
impossible for Subaward. `database/migrations/V073__extend_subaward_attachment_archive_status.sql`
widens the constraint (pure addition, no existing row's value changes,
same shape as V036's own precedent for Award) and adds a `DEFAULT
'PENDING'`. **Not applied in this checkpoint** (no deploy/migration run
performed - see boundaries).

### The Subaward core-population blocker (new finding)

`archive.subaward_attachment` is a **child table of the core Subaward
record loader** (`load_subawards_from_csv.py`'s `DatasetSpec` list), not
an independently-loadable source - exactly the same relationship Award
attachments have to Award's own now-fully-loaded core population.
Live-verified: `archive.subaward` has 513 rows; `KCOEUS.SUBAWARD` has
**88,818** (0.6% loaded). `archive.subaward_attachment.subaward_id` is a
real foreign key to `archive.subaward(subaward_id)` (V018) - a metadata
row cannot be inserted for a `subaward_id` with no core record.

`etl/attachment_orchestrator.py`'s Subaward metadata stage handles this
correctly rather than crashing on the FK: candidate `file_data_id`
selection is scoped via `OracleDataSource.read_filtered(column=
"subaward_id", values=<the currently-loaded set>)`
(`_loaded_subaward_ids()`), and `_upsert_subaward_attachments()` has a
second, defensive per-row check (a shared `file_data_id` can be
referenced by a loaded AND an unloaded `subaward_id` at once) that skips
any row whose `subaward_id` still has no core record, counted explicitly
as `skipped_no_core_record` rather than silently dropped or crashing the
batch. In the current dev database this stage will correctly select and
process close to zero new rows - not a bug, a correct reflection of the
reachable population. Expanding Subaward's core population (building
`--create-batch`/`--load-batch` support into `load_subawards_from_csv.py`,
which does not exist today) is a separate, out-of-scope undertaking with
its own preflight, not part of this attachment work.

### Physical-file identity, independent of references

Award already has a dedicated dedup table
(`archive.attachment_object`, keyed by `file_id`). Proposal and Subaward
do not - the orchestrator treats `file_data_id` itself as the physical-
file identity for batching and upload-state (`GROUP BY`/`DISTINCT ON
(file_data_id)` throughout), and marks every reference row sharing a
`file_data_id` `UPLOADED`/`ARCHIVED` together in one `UPDATE ... WHERE
file_data_id = :file_data_id` statement - a shared file is streamed from
Oracle and `PUT` to S3 exactly once regardless of how many reference
rows point to it.

**Cross-module physical-file reuse - empirically checked, not assumed**:
a live `INTERSECT` query between `KCOEUS.PROPOSAL_ATTACHMENTS.FILE_DATA_ID`
and `KCOEUS.SUBAWARD_ATTACHMENTS.FILE_DATA_ID` (the only two modules that
could theoretically overlap, both resolving via `KCOEUS.FILE_DATA`)
returned **0** shared values. Award draws from a structurally different
Oracle source (`KCOEUS.ATTACHMENT_FILE.FILE_ID`, never `KCOEUS.FILE_DATA`)
so cannot share with either. Module-level distinct-file counts can
therefore be summed for cost/storage purposes without double-counting.
Every batch key and query in the implementation is nonetheless
module-scoped (never a bare, assumed-globally-unique identifier), so a
future overlap - if Oracle's data ever changed - could not be silently
double-uploaded or double-counted; verified by
`CrossModuleFileIdentityIsolationTest` in the new test suite.

### `etl_batch_item.entity_key` is `BIGINT` - cannot hold a UUID directly

`file_data_id` is a UUID string (Proposal/Subaward); `archive.etl_batch_item.entity_key`
is a plain `BIGINT` (V037) with no string alternative. Rather than a schema
change to the shared, domain-agnostic batch framework, the real UUID batch
membership is persisted in `archive.etl_batch.selection_parameters`
(JSONB, already present) as a `file_data_ids` array; `entity_key` becomes
a synthetic per-batch ordinal (`1..N`) used only to track that one
batch's own item-level `COMPLETED` status - never treated as a
cross-batch identity. Cross-batch "already selected" exclusion is
decided directly from durable Postgres existence
(`archive.proposal_attachment`/`archive.subaward_attachment`) rather
than from `etl_batch_item`, which is safe specifically because the
single whole-task advisory lock means only one orchestration process
ever selects batches at a time.

### Never mark a batch complete before reconciliation

Award's own `_run_upload()` already calls `finish_batch_processing(...,
BATCH_STATUS_COMPLETED)` internally regardless of per-file outcome -
existing, unmodified behavior, correct for Award's own resumability (a
`FAILED` file stays selectable by a future `--retry-failed` run
regardless of the batch's own status). The orchestrator adds its own,
additional gate on top of all three modules: after every binary-stage
batch, `reconcile_batch()` re-verifies S3 existence/size/`sha256` (via
`head_object`, comparing against the `Metadata.sha256` tag every upload
already sets) for every row the batch just marked done. Only a clean
reconciliation lets the orchestration loop continue; any mismatch stops
the **entire run** immediately (`run_orchestration()` returns a
`stopped_reason`), not just that module. For Proposal/Subaward's own new
upload-stage functions, the orchestrator controls the
`BATCH_STATUS_COMPLETED` marker directly and only sets it after
reconciliation succeeds.

### Bounded retry

`with_bounded_retry()` (exponential backoff, default 4 attempts) wraps
every Oracle read and batch-creation call, but classifies by exception
**type name** (`OperationalError`, `InterfaceError`, `TimeoutError`,
`ConnectionError`, S3's `EndpointConnectionError`/`ConnectionClosedError`/
`ReadTimeoutError`) - a business-logic outcome (missing blob, checksum
mismatch, a plain `ValueError`) is never retried, propagating
immediately so it can be recorded as `FAILED`/`MISSING` rather than
silently retried into a false-negative failure count.

## Revised cost estimate (separated categories, defensible assumptions)

Achievable scope today: Award ~90,000 new files (~85.5GB) + Proposal
~149,242 new files (~105.5GB) = **~239,242 new files, ~191GB**. Subaward
excluded from this estimate (see the core-population blocker above -
its real achievable volume today is close to zero).

| Category | Basis | Estimate |
|---|---|---|
| Fargate compute | 0.5-1 vCPU / 1-2GB (proven Award task size), ~$0.024-0.048/hr; **no measured binary-stage throughput exists yet** (this workload - Oracle BLOB streaming + SHA-256 + S3 PUT - is I/O-bound, unlike the Award core-record load's SQL-aggregation workload, so that ~24-min/5,000-Awards figure does not transfer) | Even a generous 48-hour total runtime: **~$1.15-2.30** - Fargate cost at this task size is negligible regardless of duration, as already established on the Award core-record load |
| S3 PUT requests | ~239,242 new objects × $0.005/1,000 (S3 Standard PUT/COPY/POST/LIST, us-east-1) | **~$1.20** (one-time) |
| S3 storage | ~191GB new × $0.023/GB-month (S3 Standard, us-east-1) | **~$4.39/month** (ongoing, not one-time) |
| Data transfer | Oracle→ECS is over the existing staging VPC peering; if standard same-region cross-VPC peering data-transfer pricing applies ($0.01/GB each way) this is a worst-case bound - the exact terms of BU's side of the peering are not fully known, so this may not apply at all | **~$0-2** (bounded worst case: 191GB × $0.01 ≈ $1.91) |
| Secrets Manager | Existing secrets only, resolved once per ECS task launch (not per-row) - no new secrets created | **< $0.01** |
| CloudWatch Logs | ~239,242 files × ~2-3 log lines × ~200 bytes ≈ 100-150MB ingestion ($0.50/GB) + storage ($0.03/GB-month) | **~$0.05-0.10** |
| Database (RDS) overhead | ~1.39M new metadata rows × ~200-500 bytes/row ≈ 280-700MB new Postgres storage - well within existing RDS autoscaling headroom (20GB allocated, 100GB max, ~6GB used as of the last check) | **negligible, no separate line item** |
| **Total one-time** | | **~$5-9** |
| **Total ongoing (monthly)** | New (~$4.39) + existing (~$0.56) S3 storage | **~$4.95/month** |

**Comfortably under the $50 stop threshold.** The real unknown remains
runtime, not cost - exactly what the canary batch (proposed below) is
for.

## 2026-08-12 update (final review - three blockers fixed)

A final review of the implementation checkpoint below found three real
gaps, all now fixed, tested, and re-verified against live (read-only)
dev data:

1. **`check_s3_existing_object` silently treated a size-mismatched
   existing object as "not found"**, which meant a caller fell through
   to Oracle and overwrote it with `put_object` - a direct violation of
   the no-overwrite guarantee. Fixed: the function now has exactly
   three outcomes - absent (`None`, proceed to Oracle), present and
   matching or with no prior expectation (returns the match, reuse),
   or present and disagreeing on size or (when both sides have a
   digest) checksum - which now **raises `S3ObjectMismatch`** instead
   of returning `None`. `proposal_binary_stage`/`subaward_binary_stage`
   catch that exception, record it in the batch report, and `break` out
   of the loop without ever calling Oracle or an S3 write for that key;
   `run_orchestration` checks for it immediately after the binary stage
   returns (before even attempting `reconcile_batch`) and stops the
   entire run. Regression coverage:
   `CheckS3ExistingObjectTest.test_mismatching_prior_size_raises_never_returns_not_found`,
   `test_mismatching_checksum_raises_when_both_sides_have_one`,
   `test_checksum_not_compared_when_s3_object_carries_no_tag`, and the
   new `S3MismatchStopsOrchestrationTest` class (proves zero
   `oracledb.connect`, zero `_stream_file_data_to_s3`, and zero
   `s3_client.put_object` calls after a mismatch, for both Proposal and
   Subaward, plus that `run_orchestration` stops before calling
   `reconcile_batch` at all).

2. **Proposal's (and Subaward's) metadata-load stage marked every
   selected item `COMPLETED` unconditionally**, regardless of whether
   Oracle actually returned it or the upsert itself succeeded. Fixed:
   `_run_load_proposal_attachment_batch`/`_run_load_subaward_attachment_batch`
   now compute `found_in_oracle` from the raw read, wrap the upsert call
   in a Postgres `SAVEPOINT` (`connection.begin_nested()` - required so
   an upsert failure doesn't poison the whole outer transaction and
   block the subsequent per-item status writes), and assign exactly one
   of `ITEM_STATUS_COMPLETED` (found + upserted),
   `ITEM_STATUS_MISSING_SOURCE` (selected but absent from Oracle's
   result), or `ITEM_STATUS_FAILED` (found in Oracle but the upsert
   itself raised) per item - mirroring
   `load_award_attachments._run_load_batch`'s own found/missing split.
   The batch is only advanced to `BATCH_STATUS_READY` when the upsert
   succeeded; on failure it is left at `CREATED` (safe to retry) and
   `run_orchestration`'s metadata loop now checks
   `result.get("batch_advanced_to_ready") is False` and stops the whole
   run rather than retrying the same failing batch forever. Regression
   coverage: `PerItemStatusFidelityTest` (6 tests: partially-returned
   Oracle batch, zero-row Oracle result, and upsert-failure - each for
   both Proposal and Subaward - plus the orchestration-stop test).
   Subaward received the identical correction in code, even though
   V073/Subaward execution remain deferred, per your instruction.

3. **Award's upload functions never tagged S3 objects with a SHA-256
   digest** - `reconcile_batch`'s own docstring had honestly disclosed
   this as a pre-existing gap, but the newest review reversed the
   earlier "leave Award's code untouched" boundary and required a fix.
   `load_award_attachments.stream_upload`'s `put_object` branch now
   sets `Metadata={"sha256": ...}` (the digest is already fully known
   before that call - a one-line addition). `_multipart_upload` cannot
   do the same at `create_multipart_upload` time, because the digest
   isn't known until every chunk has streamed through, and
   `complete_multipart_upload` has no `Metadata` parameter either - the
   minimal, standard fix is a same-bucket/same-key server-side
   `copy_object` with `MetadataDirective="REPLACE"` immediately after
   `complete_multipart_upload` succeeds, re-specifying `ContentType`
   (`REPLACE` overwrites all metadata/headers, not just the ones being
   added). Objects above S3's 5 GiB single-call `copy_object` limit
   would need `UploadPartCopy` instead - not implemented, since no
   attachment in this archive is known to approach that size.
   `reconcile_batch`'s Award branch and docstring were updated to match:
   sha256 is now genuinely cross-checked for any upload made after this
   fix (Award or Proposal/Subaward); a Postgres-recorded digest whose S3
   object carries no tag at all (every pre-fix Award object) is counted
   in a new `checksum_unavailable` field rather than silently ignored or
   treated as a mismatch - and is never reread or overwritten solely to
   backfill the tag. Regression coverage (in
   `test_award_attachment_loader.py`):
   `test_single_part_put_object_carries_sha256_metadata`,
   `test_multipart_upload_tags_sha256_via_post_completion_copy_object`,
   `test_multipart_copy_object_never_called_when_upload_is_aborted`.

### Live-data finding that reshapes the canary (read-only, no writes)

A read-only Postgres/Oracle-metadata query pass (no BLOB columns
selected, no batch created, nothing written - see below) found that the
originally proposed canary was not executable as designed, and also
turned up a real, load-bearing fact that changes what "new Award
upload" can mean right now:

- **`archive.attachment_object` currently has exactly 37,777 rows, and
  every one of them is already `UPLOADED`** (31,956 INLINE + 5,821
  EXTERNAL, zero `PENDING`). The Award attachment *metadata* stage has
  never been run yet in this dev database beyond that original legacy
  batch - there is no existing `PENDING` row to select for either
  blob_source, which is why the originally proposed "already-UPLOADED
  rows prove reuse" canary was unreachable: the normal candidate query
  (`upload_status = ANY(:statuses)`, never including `UPLOADED`) simply
  never selects them.
- Scanning the **entire** Oracle physical-file source
  (`oracle/award/export_award_attachment_files.sql`, metadata columns
  only - `FILE_ID`/`FILE_DATA_ID`/`blob_source`/`file_name`/
  `file_size_bytes`, no blob column, matching the production extraction
  SQL exactly) against the 37,777 already-loaded `file_id`s found
  **zero** unloaded INLINE candidates across all 127,758 distinct
  Oracle physical files, but one real unloaded EXTERNAL candidate
  (reported below). In other words: **Award's INLINE population is
  already 100% complete** in dev Postgres (every INLINE file Oracle has
  is already loaded and uploaded); 100% of the remaining ~89,981
  not-yet-loaded physical files are EXTERNAL. This means there is
  currently no genuine "new Award INLINE upload" to canary - not a gap
  in this fix, a fact about the current dev database's state. INLINE's
  code path already has 31,956 real historical successful uploads as
  evidence it works; the checksum-tagging fix will apply to it the next
  time any INLINE file *is* newly uploaded, but that isn't today.

### Corrected, executable canary plan

Real IDs, selected via read-only queries only (no Oracle BLOB read, no
S3 write, no batch created, nothing persisted):

| # | Proof required | Exact ID(s) | How it reaches the path |
|---|---|---|---|
| 1 | New Award EXTERNAL UUID upload | `file_id=46152`, `file_data_id=b3e42d47-7552-47e1-9f42-9a39e8ee65da`, award `203764-00001` seq `22`, `55203764 9550302075 06.20.17 KMS.pdf` (379,541 bytes) | Not in the 37,777 already-loaded set (verified live). The canary's Award metadata stage inserts it as a new `PENDING`/`EXTERNAL` row for the first time; the binary stage's `resolve_blob_location` routes it to `FILE_DATA`/`ID` (UUID `reference_id`, never int-coerced), streams the real BLOB, uploads with the new `Metadata={"sha256":...}` tag, marks `UPLOADED`. |
| 2 | Existing INLINE coverage (accepted in place of a new upload, per approval) | `file_id=1`, `award_attachment_id=1`, award `200012-00001` seq `2`, key `award-files/by-file-id/1/NCE_CRC.pdf` | **Approved as a canary limitation, not a failure**: Oracle's entire INLINE population is already loaded (see finding above), so there is no genuine "new INLINE upload" candidate. Read-only reconciliation only - status never reset, Oracle BLOB never reread, never re-uploaded. Results: Postgres (`upload_status=UPLOADED`, `file_size_bytes=17069`, `sha256=934e33...b0a3d`, `content_type=application/pdf`) matches S3 `head_object` (`ContentLength=17069`, `ContentType=application/pdf`, `ServerSideEncryption=AES256`) exactly; the actual downloaded bytes (17,069, real `GetObject`, not Oracle) recompute to the identical SHA-256 and are a structurally valid PDF (`%PDF-` header, `%%EOF` trailer present). `Metadata` is empty (`{}`) - confirms this is a legacy, pre-checksum-fix object, exactly the `checksum_unavailable` case `reconcile_batch` now handles. Application-level download compatibility: the real dev API's download endpoint (`GET /api/v1/awards/{awardId}/attachments/{attachmentId}/download`, `rap-dev-api-alb-727962412.us-east-1.elb.amazonaws.com`) is Cognito-gated and returned `401` with no credentials available in this environment - not exercised end-to-end over HTTP. However, `AwardArchiveService.downloadAttachment` does no transformation of its own (confirmed by reading it): it resolves the same `s3Bucket`/`s3Key`/`contentType`/`fileSizeBytes` already verified above and streams the object unchanged, so the verified S3 `GetObject` result *is* what that endpoint would serve. |
| 3 | Existing-object reuse without Oracle BLOB retrieval (Award) | Same `file_id=46152`, batch re-invoked a second time immediately after proof #1 succeeds | Award's own reuse mechanism (`load_award_attachments._run_upload`) is the pre-existing, unmodified `row.upload_status == "UPLOADED" and bucket/key match` skip - not `check_s3_existing_object` (Award never called that; it's Proposal/Subaward-only, a known and already-accepted asymmetry from the prior audit). No controlled-state manipulation needed: re-running the same batch after proof #1's real upload naturally exercises `skipped_already_uploaded`, verified by confirming zero `oracledb.connect`/`stream_upload` calls on the second pass. |
| 4 | New Proposal upload | `file_data_id=00084a07-7985-427c-b2ec-752cf69b2a8f`, `proposal_attachment_id=32512`, `Kaufman_ConservationIntl_9-24-15.pdf`, `upload_status='NOT_REQUESTED'` | Already-loaded metadata row (one of 11,142 real `NOT_REQUESTED` rows), selected normally by `select_proposal_upload_candidates`. `check_s3_existing_object` returns `None` (nothing at that key yet), Oracle `FILE_DATA` is streamed, uploaded with a new `Metadata={"sha256":...}` tag, marked `UPLOADED`. |
| 5 | Proposal reuse without Oracle retrieval | Same `file_data_id=00084a07-...`, in a **separate, explicit, controlled step** after proof #4 succeeds | After proof #4 leaves this row genuinely `UPLOADED` (real S3 object, real digest), one single-row, explicitly logged `UPDATE archive.proposal_attachment SET upload_status='IN_PROGRESS' WHERE file_data_id='00084a07-7985-427c-b2ec-752cf69b2a8f'` simulates the crash window (S3 PUT succeeded, Postgres UPDATE did not) - a canary-owned row, not a pre-existing production-like one, per your instruction not to corrupt one of those. Re-running `proposal_binary_stage` then exercises `check_s3_existing_object`'s match path: it HEAD-checks the real key, finds size/checksum agree with what's already recorded (nothing to disagree with - same object), and calls `mark_proposal_file_uploaded` **without ever calling `require_oracle_environment`/`oracledb.connect`** - verified by the absence of the "Resolving Oracle credentials" log line on this second pass, and confirmed as a general mechanism by `CrashWindowReuseTest` and the new `S3MismatchStopsOrchestrationTest`/`CheckS3ExistingObjectTest` cases. |
| 6 | Checksum requirement | Same objects as proofs #1 and #4 | Proven twice: (a) unit tests listed above, run in isolation; (b) live - after proofs #1 and #4 complete, `head_object` on the resulting S3 keys must show `Metadata.sha256` present and equal to the value now recorded in `archive.attachment_object.sha256`/`archive.proposal_attachment.checksum`. This HEAD-check is an explicit required step of the canary reconciliation, not assumed from the code alone. |

Subaward remains **excluded from the canary** (V073 not applied, core
population still 0.6% loaded) - the per-item status fix was made in
code for future use only, not exercised live.

Batch size for the canary: small (one metadata batch scoped to exactly
the IDs above, not `DEFAULT_BATCH_SIZE`), so the canary's own
Oracle/S3/Postgres reconciliation is fast to review by hand.

### Multipart checksum technical check (required before commit)

Three things were required to be confirmed before relying on the
post-completion `copy_object` approach for multipart SHA-256 tagging:

1. **Largest attachment size in the accessible source vs. the single-call
   `copy_object` 5 GiB limit.** Metadata-only Oracle scan (`DBMS_LOB.GETLENGTH()`,
   no blob content read - same pattern as the production extraction SQL):
   largest `KCOEUS.ATTACHMENT_FILE.FILE_DATA` (Award INLINE) is
   29,955,321 bytes (~28.6 MiB); largest `KCOEUS.FILE_DATA.DATA` (Award
   EXTERNAL + all of Proposal, shared table) is 194,350,900 bytes
   (~185.3 MiB), across 328,551 rows. Both are roughly 27x-180x under
   the 5 GiB (5,368,709,120 byte) limit - **no attachment in the
   accessible source can exceed it**, so `copy_object` is confirmed safe
   for the current scope; `UploadPartCopy` is not needed and not
   implemented.
2. **ContentType/encryption/metadata preservation.** Live-verified
   (2026-08-12) against the real dev `documents` bucket, under the
   loader's own actual IAM role (`award-files/by-file-id/*` prefix):
   `put_object` a test object → `head_object` (`ContentLength=6100`,
   `ContentType=application/pdf`, `ServerSideEncryption=AES256`) →
   `copy_object` with `MetadataDirective="REPLACE"` (the exact production
   call shape) → `head_object` again: `ContentLength` unchanged (6100),
   `ContentType` unchanged (preserved by re-specifying it, since
   `REPLACE` overwrites all metadata/headers), `ServerSideEncryption`
   unchanged (`AES256`, applied automatically by the bucket's own
   configured default - the code deliberately never hardcodes an
   algorithm, so a future bucket policy change is tracked rather than
   fought), `Metadata.sha256` now present. `get_object` confirmed the
   body bytes are byte-identical before/after (never truncated/rewritten).
   The only thing that changed was `VersionId` - the `documents` bucket
   has versioning enabled (`terraform/modules/s3/main.tf`), so a
   same-key `copy_object` creates a new version rather than an in-place
   overwrite; the prior version remains fully intact and recoverable.
   One minor, honestly-disclosed side effect: the bucket's lifecycle
   rule has no `noncurrent_version_expiration`, so every
   multipart-checksum-tagged object permanently carries two versions
   (a small, bounded storage cost - never more than 2x for those objects
   specifically, never growing further) unless a future lifecycle change
   adds noncurrent-version expiration.
3. **No attachment exceeds the CopyObject limit** (confirmed in #1), so
   the fallback (object tagging or another non-rewriting mechanism) was
   not needed and was not built.

Regression test:
`test_multipart_copy_object_never_hardcodes_an_encryption_algorithm`
(`test_award_attachment_loader.py`) pins the "rely on bucket default,
never hardcode" decision; `test_multipart_upload_tags_sha256_via_post_completion_copy_object`
pins ContentType preservation and the correct `Metadata`/`MetadataDirective` shape.

## Files changed (this checkpoint)

- `etl/attachment_orchestrator.py` (new) - the orchestration wrapper,
  now including the `S3ObjectMismatch` stop-condition and per-item
  status fidelity fixes
- `etl/tests/test_attachment_orchestrator.py` (new) - 60 tests
- `etl/load_award_attachments.py` (modified) - minimal SHA-256
  `Metadata` tagging added to `stream_upload`'s `put_object` branch and
  `_multipart_upload`'s post-completion `copy_object` call; nothing
  else in this file changed
- `etl/tests/test_award_attachment_loader.py` (modified) - 3 new tests
  for the checksum tagging above
- `oracle/proposal/export_proposal_attachment_file_ids.sql` (new)
- `etl/Dockerfile.loader` (modified) - one line,
  `COPY etl/attachment_orchestrator.py .` (staged as an isolated hunk;
  this file has unrelated pre-existing dirty lines from other,
  out-of-scope work that are deliberately left unstaged)
- This document and `docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md`
  (updated)

Deliberately **excluded** from this commit:

- `database/migrations/V073__extend_subaward_attachment_archive_status.sql`
  - future-facing only; Subaward execution remains deferred
- `oracle/subaward/export_subaward_attachment_file_ids.sql` - Subaward
  execution is deferred and this SQL is not needed by current
  Award/Proposal scope; the file stays in the working tree
  (untracked) for when Subaward execution resumes, but is not part of
  this commit
- Every other unrelated dirty/untracked file already present in the
  working tree before this checkpoint (semantic-search work, CARB-X
  loaders, evidence-embedding pipeline, Terraform, UI, etc.) - none of
  it was touched or staged

No other existing file was modified - `load_proposals_from_csv.py` and
every other existing loader/plugin remain byte-for-byte unchanged. Not
committed, pushed, or deployed.

## Module support (from code + schema evidence)

| Module | Attachment table(s) | ETL loader | Status |
|---|---|---|---|
| Award | `archive.award_attachment` + `archive.attachment_object` (dedup) | `etl/load_award_attachments.py` (dedicated, Sprint 1-3) | **Supported** |
| Proposal | `archive.proposal_attachment` (combined metadata+archive) | `etl.archive_etl.attachments` generic plugin (`ProposalAttachmentPlugin`) | **Supported** |
| Subaward | `archive.subaward_attachment` + `archive.subaward_attachment_archive` | generic plugin (`SubawardAttachmentPlugin`) | **Supported** |
| Negotiation | `archive.archived_attachment` (generic, `module_code='NEGOTIATION'`) | generic plugin (`NegotiationAttachmentPlugin`) | **Supported** |
| IRB | *(none)* | *(none)* | **Not supported** — no migration, no plugin, no loader anywhere in the codebase. The generic `archive.archived_attachment` table's `CHECK` constraint anticipates `IRB_PROTOCOL`/`IRB_PERSONNEL` as valid `module_code` values, but zero rows exist for either and no code path ever writes them. Explicitly excluded, not silently — if IRB attachment loading is ever wanted, it needs new loader code, not just enabling something already built. |

Verified via: `etl/archive_etl/attachments/runner.py`'s `PLUGINS` dict (exactly
these four module keys), `database/migrations/` (no `irb_attachment`/similar
migration exists), and a full-repo grep for `irb`+`attachment` co-occurrence
(no match outside this investigation's own artifacts).

## Reference rows, unique files, and status — by module

| Module | Oracle references | Postgres references (loaded) | Missing metadata rows | Distinct physical files (Oracle) | Already in S3 (verified) |
|---|---:|---:|---:|---:|---:|
| Award | 1,715,351 | 720,428 | **995,000** | 127,758 (13.4 refs/file) | 37,777 (100% of loaded subset) |
| Proposal | 405,779 | 11,332 | **394,447** | 149,432 (2.7 refs/file) | 190 (1.7% of loaded subset) |
| Subaward | 460,115 | 1,764 | **458,351** | 50,874 (9.0 refs/file) | 1,764 (100% of loaded subset) |
| Negotiation | 28,923 | 28,923 | **0** | 28,923 (1:1, no dedup) | 2,342 (8.1% — see below) |

**Never confuse a reference row with a unique physical file** — the Award
ratio alone (13.4 references per distinct file) shows why: one physical
document is commonly attached to many historical Award versions.

### Critical finding: metadata itself is far from fully loaded

This mirrors exactly the pattern already found and fixed for core Award
records (49,827→267,386 rows) on 2026-08-12: **the previously-loaded
attachment subset was never the full population** — it was loaded
piecemeal, historically, for whatever Award/Proposal/Subaward families
happened to be in scope at the time. Now that the full Award population is
loaded, hundreds of thousands of newly-relevant attachment references exist
in Oracle that were never brought into Postgres at all. Award, Proposal,
and Subaward each need a **metadata load first** (the generic plugin's
`iter_records`/CSV-fetch step), before any binary can be uploaded for the
new rows — this is a materially different, larger job than "upload the
remaining binaries for what's already in Postgres."

**Only Negotiation's metadata is fully loaded** (28,923 Oracle = 28,923
Postgres, exact match) — no new Negotiation metadata work exists.

### Negotiation: already 100% processed, not a live risk

Negotiation's 28,923 rows are **already fully attempted** — 2,342
`ARCHIVED` + 26,581 `MISSING` = 28,923 (100%). Every one of the 26,581
`MISSING` rows was a genuine attempt: `source_file_id` is populated (never
null) but Oracle's `KCOEUS.ATTACHMENT_FILE` has no matching row/BLOB for
that `file_id` — confirmed via direct sampling, error message
`"KCOEUS.ATTACHMENT_FILE row or BLOB is missing"` on 100% of the `MISSING`
rows. This is a genuine, historical **91.9% missing-blob rate for
Negotiation specifically** — far above what "a small number of genuinely
missing binaries" implies, and would immediately trip your own 1%
failure-rate stop condition if re-attempted as new work. It does not need
to be re-attempted: there is no unprocessed Negotiation row left, so this
is a closed, already-reconciled fact about the source data, not a live risk
for a new load. Flagging it prominently rather than silently omitting it.

### Proposal: metadata mostly loaded, binaries almost entirely not yet requested

Distinct from Award/Subaward: **all 11,332 already-loaded Proposal
attachment rows have a real, non-null `file_data_id`** (verified: zero
`NULL`), but `upload_status` is `NOT_REQUESTED` for 11,142 of them (98.3%)
— only 190 have actually been uploaded (247,988,862 bytes = ~248MB so
far). This is straightforward, genuine remaining work (not a data-quality
gap like Negotiation's) — Oracle has the content, it simply hasn't been
fetched yet.

## Byte and cost estimate

Sampled `DBMS_LOB.GETLENGTH()` over 2,000 rows each (bounded, read-only):
`ATTACHMENT_FILE` (Award/Negotiation source) averages **~950KB**/file;
`FILE_DATA` (Proposal/Subaward source) averages **~707KB**/file.

New unique files needed (distinct Oracle files minus what's already
verified in S3, per module):

| Module | New unique files (est.) | Avg size | Est. new bytes |
|---|---:|---:|---:|
| Award | ~90,000 (127,758 − 37,777) | ~950KB | ~85.5 GB |
| Proposal | ~149,242 (149,432 − 190) | ~707KB | ~105.5 GB |
| Subaward | ~49,110 (50,874 − 1,764, upper bound — see caveat) | ~707KB | ~34.7 GB |
| Negotiation | 0 (fully processed) | — | 0 |
| **Total** | **~288,352** | | **~225.7 GB** |

**Caveat**: the Subaward figure treats all 1,764 already-`ARCHIVED`
references as 1,764 distinct files (an upper-bound assumption on new work,
i.e. an undercount of what's still needed if any of those 1,764 share a
`file_data_id` — the true remaining count could be slightly higher, never
lower). Sampled average sizes (2,000-row `ROWNUM` sample) are a reasonable
estimate, not an exhaustive one — real totals will vary somewhat.

**Current S3 baseline** (CloudWatch, `research-archive-platform-dev-documents-770203350335`):
24,506,216,262 bytes (~24.5GB) across 38,701 objects.

**Cost estimate**:
- S3 storage: ~225.7GB new × $0.023/GB-month (S3 Standard, us-east-1) ≈
  **~$5.19/month** ongoing (not one-time)
- S3 PUT requests: ~288,352 new objects × $0.005/1,000 ≈ **~$1.44** (one-time)
- S3 storage of what's already there: ~24.5GB × $0.023 ≈ $0.56/month
  (unaffected, already being paid)
- Data transfer: Oracle→ECS is over the existing staging VPC peering (no
  AWS data-transfer charge); ECS→S3 is intra-region (no charge)
- Fargate compute: at the loader's proven task size (0.5-1 vCPU / 1-2GB,
  ~$0.024-0.048/hour), even a **multi-day** continuous run stays under
  **$5** in raw compute — Fargate cost at this task size is negligible
  regardless of duration (established directly on the Award load: a full
  6-hour run cost about $1)

**Total estimated cost: well under the $50 stop threshold** — on the order
of $10-15 for the one-time load plus a few dollars/month in ongoing S3
storage. Cost is not expected to be the limiting factor.

**Estimated runtime is the real unknown.** ~225.7GB must be streamed from
Oracle (chunked, SHA-256 computed incrementally) and uploaded to S3,
sequentially per the existing loader's design. This workload (I/O-bound
BLOB streaming, not SQL aggregation) has **no measured throughput baseline
in this project yet** — the Award ETL's ~24-minutes-per-5,000-Awards figure
does not transfer to this entirely different I/O pattern. A representative
canary batch (see below) is needed to establish a real throughput number
before committing to a total-runtime estimate, exactly as was done for the
Award population load (where the first real batch corrected an earlier
estimate by nearly an order of magnitude).

## Existing loader: safe, but resumability needs a wrapper

Read `etl/archive_etl/attachments/runner.py` and `manifest.py` in full.
Findings, stated precisely:

- **Safety is solid.** `process_attachment()` always re-verifies against
  live S3 (`head_object`) and a manifest match before ever calling
  `upload_object()`; S3 keys are deterministic
  (`plugin.s3_key(prefix, record)`); a checksum mismatch after upload
  raises rather than silently proceeding. No path deletes or blindly
  overwrites an existing object.
- **The checkpoint (`ManifestStore`/`FlexibleManifestStore`) is a local
  SQLite file**, not a durable, cross-task store. Within one long-running
  process it works fine. But per your own explicit requirement ("**Use
  persistent database checkpoints**" / "**Resume safely after
  interruption**" across separate `ecs run-task` launches), this is a real
  gap: a fresh ECS task starts with an empty local manifest, and while
  `head_object` alone still prevents any *unsafe* re-upload, the code
  unconditionally re-reads the entire Oracle BLOB (`reader.stream_to_path`)
  **before** it even checks whether the manifest+S3 state would allow a
  skip — so a naive "just launch runner.py in a fresh task" approach would
  be safe but not efficient: every resume would re-pay the full Oracle
  read cost for already-uploaded files.
- **`--sync-postgres` is one-directional** (manifest → Postgres), not a
  resume source. It doesn't populate a fresh local manifest from existing
  Postgres/S3 state at start-up.
- Real, durable, cross-task state **does already exist** for what's
  genuinely finished: Postgres's own `upload_status`/`archive_status`
  columns (`archive.attachment_object.upload_status`,
  `archive.proposal_attachment.upload_status`,
  `archive.subaward_attachment_archive.archive_status`,
  `archive.archived_attachment.archive_status`) are the real source of
  truth for "already done," and they're exactly what this preflight's own
  counts above were read from.

**Conclusion: does the current loader support safe unattended resume?**
**Partially.** It is always *safe* (idempotent, never destructive,
checksum-verified). It is not, out of the box, *efficient* for resuming
across separate Fargate task launches, and it has no built-in bounded-batch
or advisory-lock mechanism at all (every existing invocation processes an
entire CSV in one pass with no batch/lock concept — a new concept for this
domain, not present in `runner.py` today).

**What this means before launching anything further:** a narrow
orchestration wrapper is needed — mirroring the Award load's proven
pattern (`docs/runbooks/UNATTENDED_FARGATE_ETL_LOADS.md`): a Postgres
advisory lock (a new, attachment-specific lock key), bounded batches
selected by querying Postgres's own `upload_status`/`archive_status`
columns directly (skip-already-verified for free, no local-manifest
dependency), and a **separate checkpoint namespace per module** so one
module's progress never blocks or gets confused with another's — most
naturally implemented as new `entity_type` values (`AWARD_ATTACHMENT`,
`PROPOSAL_ATTACHMENT`, `SUBAWARD_ATTACHMENT`) in the existing, already-
proven `archive.etl_batch`/`etl_batch_item` framework (the same one the
Award load used, and whose own code comments already anticipate attachment
domain reuse). This wrapper does not yet exist and is not implemented as
part of this preflight.

## What is NOT yet done (by design, per your explicit boundaries)

- No canary batch has been run yet.
- No orchestration wrapper has been built yet.
- No ECS task has been launched for any attachment load.
- No OCR, chunking, Titan embedding, or RAG work of any kind.
- Existing attachments, Award/Proposal/Subaward/Negotiation business data,
  and all embedding populations are completely untouched (read-only
  investigation only, verified by every query above being a `SELECT`).
