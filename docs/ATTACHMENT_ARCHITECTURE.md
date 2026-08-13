# Attachment Storage Architecture: Local Development to Production

This document explains how the Subaward attachment feature's **local
development implementation** (synthetic fixtures, no AWS, no Oracle) relates
to the **production architecture** it is designed to slot into without any
API or database-schema changes, and gives a concrete migration path between
the two.

It complements, rather than replaces, two existing documents:

- [`docs/SUBAWARD_ATTACHMENT_ARCHIVE.md`](./SUBAWARD_ATTACHMENT_ARCHIVE.md) —
  the authoritative runbook for the real Oracle → S3 binary-archival ETL
  pipeline (already implemented). Treat it as the source of truth for exact
  CLI commands, manifest schema, and S3 key conventions.
- [`docs/ATTACHMENT_MODULE_INVENTORY.md`](./ATTACHMENT_MODULE_INVENTORY.md) —
  the authoritative per-module inventory of verified Oracle source tables
  (Subaward, Award, Proposal, Negotiation, Protocol).

Everything Oracle-table-related in this document is drawn directly from
those two documents and from the ETL code that implements them
(`etl/archive_etl/attachments/`) — nothing here is invented. Where something
could not be independently verified from this repository, it is called out
explicitly under "Unknowns" rather than guessed at.

## Known Production Gap

**`ARCHIVE_DOCUMENTS_BUCKET` is not currently set anywhere in Terraform, in
any environment.** `S3SubawardAttachmentStorage` reads this environment
variable and throws `IllegalStateException` if it's blank
(`api/src/main/java/edu/bu/archive/adapter/out/persistence/S3SubawardAttachmentStorage.java`).
The S3 documents bucket and the API's IAM permissions to read from it both
already exist — only this one environment variable is missing. This means
that today, in any real (non-`local`) environment, a real download attempt
would fail closed with a clear error rather than serving anything
incorrectly — it is a wiring gap, not a security or data-integrity issue.
See [§5](#5-switching-local--production) for the exact fix and
[§9](#9-operational-runbook) for how to verify it once applied.

## Table of contents

1. [Current Local Development Architecture](#1-current-local-development-architecture)
2. [Production Architecture](#2-production-architecture)
3. [Oracle Attachment Extraction](#3-oracle-attachment-extraction)
4. [S3 Storage Design](#4-s3-storage-design)
5. [Switching Local → Production](#5-switching-local--production)
6. [Local Development](#6-local-development)
7. [Security](#7-security)
8. [Future Enhancements](#8-future-enhancements)
9. [Operational Runbook](#9-operational-runbook)

---

## 1. Current Local Development Architecture

```
Browser
  ↓
Spring Boot API
  ↓
PostgreSQL attachment metadata
  ↓
LocalAttachmentStorage
  ↓
local-data/attachments
```

### Component walkthrough

| Step | Component | What actually happens |
|---|---|---|
| 1 | Browser | The UI's `SubawardWorkspacePage` Attachments tab calls the same API the production UI will call — no local-only branching exists in the frontend. |
| 2 | Spring Boot API | `SubawardArchiveController` → `SubawardArchiveService` → `SubawardArchiveRepository`. This code path is **identical** in local dev and production; nothing here changes between environments. |
| 3 | PostgreSQL attachment metadata | `archive.subaward_attachment` (Oracle-sourced metadata columns) and `archive.subaward_attachment_archive` (archive status, S3 bucket/key, checksum) — the real, unmodified schema from `database/migrations/V018__create_subaward_archive_tables.sql` and `V019__create_subaward_attachment_archive.sql`. |
| 4 | `LocalAttachmentStorage` | `LocalSubawardAttachmentStorage`, one of two implementations of the `SubawardAttachmentStorage` interface, selected via `app.attachments.storage=local` (the default in `application-local.yml`). |
| 5 | `local-data/attachments` | A gitignored directory containing 3 small synthetic files. |

The interface boundary is the entire point: `SubawardArchiveService` depends
only on `SubawardAttachmentStorage` (an interface), never on
`LocalSubawardAttachmentStorage` or `S3SubawardAttachmentStorage` directly.
Swapping implementations is a Spring property, not a code change.

### What is real

- The **database schema** — `archive.subaward_attachment` and
  `archive.subaward_attachment_archive`, unmodified, same tables production
  uses.
- The **business record** the fixtures attach to — `subaward_id = 94204` is
  a genuine subaward already loaded into the local Postgres instance by the
  ETL, chosen for two reasons: it has zero real attachments (safe to attach
  synthetic rows without colliding with or obscuring real data), and it is
  one of the highest `subaward_id` values in the table, which puts it near
  the top of the Subaward list page's default (no-search) first page — see
  [§6](#6-local-development) for why this specific choice matters for
  discoverability.
- The **API/service/repository code path** — controller, service,
  ownership/IDOR check, filename sanitization, and HTTP status/error
  semantics are all the same code that runs in production.
- The **HTTP contract** — response shape, status codes, `Content-Type`, and
  `Content-Disposition` headers are byte-for-byte what a real S3-backed
  download would return.

### What is synthetic

- **4 attachment metadata rows** (`attachment_id` `9000000001`–`9000000004`),
  inserted by `scripts/seed-local-subaward-attachments.sql`, using an ID
  range (9,000,000,001+) chosen specifically to be far outside any real
  Oracle-sourced ID (the real table's IDs top out well under 1,000,000
  today) so a seed re-run can never collide with real data.
- **3 physical files** (`sample-agreement.pdf`, `sample-budget.xlsx`,
  `sample-note.txt`) generated by `tools/generate-local-attachment-fixtures.py`
  — harmless, valid-format placeholder documents containing no real BU
  content.
- The **`local-fixtures` bucket sentinel** — a string that stands in for a
  real S3 bucket name in the `s3_bucket` column, recognized only by
  `LocalSubawardAttachmentStorage`.

### What is intentionally mocked

- **No Oracle connection of any kind.** Nothing in this feature's local path
  reads from or writes to BU Oracle.
- **No AWS SDK activity.** `AwsConfiguration`'s `S3Client` bean is itself
  gated behind `app.attachments.storage=s3` (`matchIfMissing = true`), so in
  local dev the bean is never even constructed — there is no dormant AWS
  client, no credential resolution attempt, nothing that could accidentally
  make a network call.
- **`LocalSubawardAttachmentStorage` entirely replaces the S3 read path.**
  It reads bytes off local disk instead of calling `s3.getObject(...)`, but
  reproduces the same failure semantics (missing object → 404, wrong
  bucket → 404) so the rest of the stack cannot tell the difference.

### Why we intentionally do NOT copy all BU attachments locally

1. **Scale.** `archive.subaward_attachment` alone already holds roughly
   490,000 real metadata rows loaded by the ETL. The real BLOB content
   behind even a fraction of those rows would be a large, slow, and
   pointless download for a developer whose job is to build UI/API features,
   not warehouse documents.
2. **This platform is explicitly read-only and metadata-first.** Per the
   project's own architecture (see the repository's `CLAUDE.md`), Oracle
   extraction happens only from a BU-VPN-connected machine, and the API/AWS
   side never touches Oracle directly. Bulk-copying real attachment binaries
   to every developer's laptop would work against that boundary in spirit
   even where it wouldn't technically cross it.
3. **Sensitivity.** Real subaward attachments are genuine BU financial,
   legal, and contractual documents (agreements, budgets, compliance
   correspondence). Multiplying the number of machines holding copies of
   that content — especially personal developer laptops outside any BU
   asset-management or encryption-at-rest guarantee — is unnecessary
   exposure for a problem that synthetic data solves just as well.
4. **Local dev needs contract fidelity, not data fidelity.** Every UI/API
   behavior this feature needs to exercise — a populated list, an empty
   list, a successful download, a "not archived yet" row, a "file missing"
   404, an ownership check across subawards — is fully reproducible with 3
   placeholder files and 4 metadata rows. Real content would not exercise
   any code path the synthetic data doesn't already cover.

---

## 2. Production Architecture

```
Oracle/Kuali
  ↓
ETL
  ↓
PostgreSQL attachment metadata
  ↓
Private S3 bucket
  ↓
S3AttachmentStorage
  ↓
Spring Boot API
  ↓
Authenticated user
```

**Every component below already exists in code.** The gap between "code
exists" and "production actually works end-to-end" is operational wiring,
called out explicitly in [§5](#5-switching-local--production) and
[§9](#9-operational-runbook) — this is not a system that needs to be built,
it needs to be turned on correctly.

| Component | What it is | Where it lives |
|---|---|---|
| Oracle/Kuali | BU's legacy Kuali Oracle database, `KCOEUS` schema, reachable only from a BU-VPN-connected machine. | Not part of this repository; external system. |
| ETL | Python scripts that (a) load Oracle-sourced *metadata* into Postgres via CSV/direct-Oracle extraction, and (b) separately stream Oracle attachment *binaries* to S3. | `etl/load_subawards_from_csv.py` (metadata), `etl/archive_etl/attachments/` + `etl/archive_subaward_attachments.py` (binaries). See [§3](#3-oracle-attachment-extraction). |
| PostgreSQL attachment metadata | The same two tables described in §1: `archive.subaward_attachment` (metadata) and `archive.subaward_attachment_archive` (archive status/location/checksum). | `database/migrations/V018__create_subaward_archive_tables.sql`, `V019__create_subaward_attachment_archive.sql`. |
| Private S3 bucket | The `documents` bucket (`${project_name}-${environment}-documents-${account_id}`), private, versioned, SSE-S3 encrypted, all public access blocked. Already provisioned by Terraform in every environment. | `terraform/modules/s3/main.tf`. |
| `S3AttachmentStorage` | `S3SubawardAttachmentStorage`, the other implementation of `SubawardAttachmentStorage`. Reads `ARCHIVE_DOCUMENTS_BUCKET`, validates the requested row's `s3_bucket` matches, calls `s3.getObject(...)`, maps `NoSuchKeyException`/404 to `NoSuchElementException`. | `api/src/main/java/edu/bu/archive/adapter/out/persistence/S3SubawardAttachmentStorage.java`. Active whenever `app.attachments.storage` is unset or `s3` (the default). |
| Spring Boot API | Identical controller/service/repository code as local dev (§1) — the only thing that changes is which `SubawardAttachmentStorage` bean Spring wires in. | `api/src/main/java/edu/bu/archive/adapter/in/web/SubawardArchiveController.java`, `.../application/subaward/SubawardArchiveService.java`. |
| Authenticated user | In every non-local environment, requests must carry a valid Cognito-issued JWT (`SecurityConfiguration`); local dev's `app.security.enabled=false` is a dev-only bypass. | `api/src/main/java/edu/bu/archive/config/SecurityConfiguration.java`. |

### The ETL never writes to Postgres and the API through the same path

Note the asymmetry that makes this design safe: the **API's IAM role is
read-only** against S3 (`s3:ListBucket` + `s3:GetObject` only — verified in
`terraform/modules/api_service/main.tf`, no `s3:PutObject` anywhere). Only
the **ETL**, run from a BU-managed, VPN-connected machine with its own
separate AWS credentials, ever uploads. The API can never write an
attachment, corrupt one, or delete one — it can only ever serve what the ETL
already archived. This mirrors the platform's broader "read-only historical
archive, never a system of record" design.

---

## 3. Oracle Attachment Extraction

### Expected Oracle source tables (Subaward)

Verified directly from `etl/archive_etl/attachments/oracle_blob.py` and
cross-checked against
[`docs/ATTACHMENT_MODULE_INVENTORY.md`](./ATTACHMENT_MODULE_INVENTORY.md#subaward):

```text
KCOEUS.SUBAWARD_ATTACHMENTS   -- attachment metadata (parent: KCOEUS.SUBAWARD)
KCOEUS.FILE_DATA              -- binary content
KCOEUS.SUBAWARD_ATTACHMENTS.FILE_DATA_ID = KCOEUS.FILE_DATA.ID   -- verified join
KCOEUS.FILE_DATA.DATA         -- the BLOB column read
```

The exact reader used for Subaward is `FileDataBlobReader`
(`oracle_blob.py`), which executes:

```sql
SELECT source.DATA
FROM KCOEUS.FILE_DATA source
WHERE source.ID = :file_reference
```

This is a different Oracle table/column pair than Award, Negotiation,
Protocol, and Protocol-personnel use — those four read
`KCOEUS.ATTACHMENT_FILE.FILE_DATA` via `FILE_ID` instead
(`AttachmentFileBlobReader`), not `KCOEUS.FILE_DATA`. Proposal uses the same
`FILE_DATA`/`FILE_DATA_ID` shape as Subaward. See the inventory doc's
per-module tables for the full column mapping (attachment ID, business key,
sequence number, filename, MIME type, timestamps, status) for each module —
it is not repeated here to avoid the two documents drifting out of sync.

### Attachment metadata flow

1. `etl/load_subawards_from_csv.py`'s `DatasetSpec(key="attachments", ...)`
   extracts `KCOEUS.SUBAWARD_ATTACHMENTS` (via Oracle directly, or a CSV
   export) and loads it into `archive.subaward_attachment` — pure metadata,
   no binary content, no S3 involvement at this stage.
2. This is a normal, idempotent ETL load like every other Subaward dataset
   (`INSERT ... ON CONFLICT DO UPDATE`, tracked via `archive.load_run`) — it
   has nothing to do with the binary-archival pipeline described next, and
   can run on its own schedule independent of it.

### Binary extraction flow

Implemented in `etl/archive_etl/attachments/`, invoked via
`etl/archive_subaward_attachments.py` (a thin wrapper around the generic
`archive_etl.attachments.runner.run(["--module", "subaward", ...])`). For
each metadata row with a non-null `file_data_id`:

1. Stream the Oracle BLOB in chunks (default 1 MiB, `--blob-chunk-size`),
   writing each chunk to a temporary local file and simultaneously feeding it
   into a running `hashlib.sha256()` digest.
2. Compute the S3 object key:
   `{prefix}/{subaward_id}/{attachment_id}/{sanitized_file_name}` — see
   [§4](#4-s3-storage-design) for the exact prefix Subaward uses today.
3. Upload the temporary file to S3 with user metadata `sha256`,
   `attachment-id`, `subaward-id`, `file-data-id`.
4. Re-`HEAD` the uploaded object and compare its `ContentLength` and
   `sha256` metadata against what was just computed — if they don't match,
   raise rather than silently trust the upload.
5. Write a row to `archive.subaward_attachment_archive` (`--sync-postgres`)
   recording `s3_bucket`, `s3_key`, `byte_size`, `sha256`, and
   `archive_status`.
6. Delete the temporary local file.

### Reconciliation

Three mechanisms exist today:

1. **`--verify-only` / `--dry-run`** — re-streams every BLOB from Oracle,
   recomputes size and SHA-256, and compares against both the local manifest
   and a live S3 `HEAD`, without uploading anything. Use this to confirm a
   past archival run is still intact without re-transferring data.
2. **`verify_manifest_orphans()`** — after a run, computes manifest rows
   whose `attachment_id` no longer appears in the current Oracle metadata
   pull (e.g. because the source row was deleted upstream), surfacing them
   as a count for manual review.
3. **`sql/verify/subaward_attachment_archive.sql`** — referenced by
   `docs/SUBAWARD_ATTACHMENT_ARCHIVE.md` as a post-sync verification query
   against Postgres. **Unverified in this pass** (not read while researching
   this document) — confirm its exact contents before relying on it in a
   runbook.

### Checksum validation

SHA-256 is computed once, while streaming the BLOB out of Oracle (never
computed from the uploaded S3 object, since that would defeat the purpose of
verifying the transfer). It is checked twice:

- Immediately after upload, via a `HEAD` request compared against the
  freshly-computed digest (fails the run with a `RuntimeError` if they
  differ — an upload is never left in a state where Postgres thinks it
  succeeded but the object doesn't match).
- On every subsequent run, as part of the idempotency check described next.

The Postgres column `archive.subaward_attachment_archive.sha256` has a
`CHECK` constraint (`sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'`), so a
malformed digest can never be silently persisted.

### Rerun behavior (idempotency)

A local SQLite manifest (`ManifestStore`, one row per `attachment_id`) is
consulted before ever touching Oracle or S3 for a given attachment. A row is
skipped (treated as already correctly archived, `resumed_count`) only when
**both**:

1. The manifest's stored status is `ARCHIVED` and its recorded metadata
   (filename, MIME type, size, etc.) matches the current Oracle metadata
   row, **and**
2. A live S3 `HEAD` on the recorded bucket/key returns the same
   `ContentLength` and `sha256` user-metadata value.

Object existence or ETag alone is deliberately never sufficient for a resume
skip — both the manifest and a live S3 check must agree. This means a
partially-completed or manually-tampered-with S3 object will always be
detected and re-processed on the next run, not silently trusted.

### Duplicate handling

- `archive.subaward_attachment.attachment_id` is the Oracle-sourced primary
  key — duplicates are structurally impossible at the metadata layer; the
  source system's own ID is authoritative.
- `archive.subaward_attachment_archive` carries
  `UNIQUE (s3_bucket, s3_key)` — since the key template embeds
  `attachment_id`, two different attachments can never collide on the same
  object key, and Postgres itself would reject an attempt to record two rows
  pointing at the same S3 object.

### Unknowns requiring verification by someone with Oracle/BU access

- **`oracle/subaward/export_subaward_attachments.sql`** — referenced by
  `docs/ATTACHMENT_MODULE_INVENTORY.md` as the extraction SQL contract, but
  no `oracle/` directory (at the repo root or under `etl/`) exists in this
  checkout, tracked or untracked. It may live in a separate/private
  location, or the reference may be stale. **Locate or reconstruct this file
  before relying on it.**
- **Whether the Oracle table/column shapes documented above are still
  accurate today.** They were verified at some point against a live BU
  Oracle connection (per the inventory doc's own evidence-level framework),
  but this repository has no way to independently re-confirm that against
  a live system — legacy-system schema drift is a real risk for any
  historical-archive project. Re-verify with a live `DESCRIBE` before a
  production run if any meaningful time has passed since the inventory doc
  was last updated.
- **`sql/verify/subaward_attachment_archive.sql`** — not independently
  confirmed in this pass; read it before depending on it in a runbook.

---

## 4. S3 Storage Design

### Bucket structure — requested design vs. what is actually implemented

The nested layout below is a reasonable target shape to describe, but **it
does not match what Terraform already provisions today**, and this document
recommends *not* introducing it:

```
attachments/
    subawards/
    awards/
    protocols/
```

What actually exists (`terraform/modules/s3/main.tf`) is a **single flat
`documents` bucket** (`${project_name}-${environment}-documents-${account_id}`)
with top-level, per-module prefixes and no `attachments/` parent:

```
subawards/
awards/
proposals/
negotiations/
irb/
```

Terraform even pre-creates zero-byte "folder marker" objects at each of
these five prefixes. **Recommendation: keep the existing flat structure.**
Introducing an `attachments/` parent prefix now would require renaming every
already-archived object (a real migration with real risk) purely for
cosmetic nesting, with no functional benefit — the bucket is already
single-purpose (documents only), so an extra parent segment doesn't add
useful separation.

### Object key format (implemented, verified)

```text
{prefix}/{parent_record_id}/{attachment_id}/{sanitized_file_name}
```

Concretely for Subaward: `subawards/{subaward_id}/{attachment_id}/{filename}`
— e.g. `subawards/94202/123456/original_file_name.pdf`. The attachment ID in
the path keeps keys stable even when two attachments share a filename.
`sanitized_file_name` strips path separators and unsafe characters before
being used as the final key segment (see [§7](#7-security)).

The literal `{prefix}` differs by module and is a plugin default, not a
framework constant — Subaward's real default is `subawards` (no `test/`
prefix); Award/Negotiation/Proposal/IRB currently default to a `test/`-
prefixed value, signaling those modules are still in a pilot/unapproved
state. Every prefix is overridable via `--s3-prefix` or a per-module
`*_S3_PREFIX` environment variable.

### Recommended metadata (implemented, verified)

Every uploaded object carries this S3 object user metadata:

| Key | Meaning |
|---|---|
| `sha256` | Hex-encoded SHA-256 of the object content, computed while streaming from Oracle. |
| `attachment-id` | The Oracle-sourced attachment ID (matches the Postgres row and the S3 key). |
| `subaward-id` | The parent Subaward's ID (`record-id` for the generic non-Subaward plugins). |
| `file-data-id` | The Oracle `FILE_DATA.ID`/`ATTACHMENT_FILE.FILE_ID` the object was sourced from — traceability back to the exact Oracle row. |

### Recommended encryption

**Implemented today: SSE-S3 (`AES256`)**, applied by default to every object
in the bucket via a bucket-level
`aws_s3_bucket_server_side_encryption_configuration`. This is adequate for
the current threat model (private bucket, all public access blocked, IAM
scoped to one read-only role).

**Recommendation for future hardening**: evaluate SSE-KMS with a
customer-managed key. The ETL code already supports this
(`--sse aws:kms` / `*_KMS_KEY_ID` env var per module), but **no KMS key or
bucket policy requiring it exists in Terraform today** — adopting it would
need a new `aws_kms_key` resource, a bucket policy statement enforcing
`s3:x-amz-server-side-encryption: aws:kms`, and an IAM `kms:Decrypt` grant
added to the API's task role. The main benefit over SSE-S3 is CloudTrail
data-event auditing of every decrypt (who/when accessed key material), not
stronger encryption per se.

### Recommended lifecycle (implemented, verified)

The documents bucket already transitions **every** object (no prefix
filter): 180 days → `STANDARD_IA`, 730 days → `GLACIER_IR`. This is a
reasonable default for a read-mostly historical archive and needs no change
for the Subaward attachment use case specifically.

### Recommended IAM (implemented, verified — keep as-is)

The API's ECS task role is granted exactly:

```hcl
statement {
  effect    = "Allow"
  actions   = ["s3:ListBucket"]
  resources = [var.documents_bucket_arn]
}
statement {
  effect    = "Allow"
  actions   = ["s3:GetObject"]
  resources = ["${var.documents_bucket_arn}/*"]
}
```

Read-only, no `s3:PutObject`/`s3:DeleteObject`. **Keep this asymmetry.** The
ETL's write path uses entirely separate AWS credentials, run from a
BU-managed machine — never grant the API's task role write access, and
never let the ETL run with the API's role.

### Recommended presigned URL lifetime

**Not implemented today.** The API currently proxies every download through
itself (`StreamingResponseBody`, bytes flow API → browser, never browser →
S3 directly). This is intentional: it keeps Spring Security's
authentication/authorization check in the request path for every download,
rather than needing a separate presigned-URL-issuing endpoint that would
still need its own auth check.

If a future need arises to reduce API load for large files (see
[§8](#8-future-enhancements)) by having the browser download directly from
S3, a presigned URL would need:

- A **short TTL** — 5–15 minutes is a reasonable starting recommendation,
  long enough for a user to start a download after receiving the link, short
  enough that a leaked/logged URL isn't a long-lived access token.
- The presigned URL still only ever generated **after** the same ownership
  check `SubawardArchiveService.downloadAttachment` already performs — a
  presigned URL is a bearer credential, so nothing about adopting one should
  weaken the existing authorization check that runs before it's issued.

---

## 5. Switching Local → Production

Step-by-step checklist for turning on real S3-backed attachments in an
environment (dev, test, or prod):

### 1. Confirm prerequisites are already satisfied (nothing to create)

- [ ] The `documents` S3 bucket already exists for this environment
      (`terraform/modules/s3/main.tf`, applied as part of the environment's
      normal Terraform stack — no new `terraform apply` needed for the
      bucket itself).
- [ ] The API's ECS task role already has `s3:ListBucket`/`s3:GetObject` on
      that bucket (`terraform/modules/api_service/main.tf`, conditional on
      `documents_bucket_arn` being passed in — confirm it is, in this
      environment's `main.tf`).

### 2. Fix the missing wiring (this is the actual gap — do this)

**`ARCHIVE_DOCUMENTS_BUCKET` is not set anywhere in Terraform today**, in
any environment. `S3SubawardAttachmentStorage` reads it via
`@Value("${ARCHIVE_DOCUMENTS_BUCKET:}")` and throws
`IllegalStateException` if blank — so today, in any environment where
`app.attachments.storage` is left at its default (`s3`), the first download
attempt fails closed with a clear error, rather than serving anything
incorrectly. Fix by adding the bucket name to that environment's
`additional_api_environment_variables` (or hardcode it into
`api_service`'s `local.base_environment` if it should never be optional):

```hcl
# terraform/environments/<env>/terraform.tfvars
additional_api_environment_variables = {
  ARCHIVE_DOCUMENTS_BUCKET = "research-archive-platform-<env>-documents-<account-id>"
  # ... any existing APP_AI_* entries stay as-is
}
```

Get the exact bucket name from `terraform output` in that environment
(`module.archive_s3.documents_bucket_name` or equivalent — check
`terraform/environments/<env>/outputs.tf` for the exact output name) rather
than hand-constructing it, to avoid a typo.

### 3. Disable `LocalAttachmentStorage` / enable `S3AttachmentStorage`

These are the same action: `app.attachments.storage` defaults to `s3`
already (`application.yml`), so **no change is needed in a non-local
environment** as long as `application-local.yml`'s override (`storage:
local`) is not active — which it never is outside the `local` Spring
profile. Nothing to do here beyond confirming the environment does not set
`SPRING_PROFILES_ACTIVE=local`.

### 4. Secrets Manager

Not applicable to S3 access itself (IAM role-based, no credential/secret
needed). If a future change requires a new secret (e.g. a KMS key ARN
reference, or credentials for a mechanism other than the task role), follow
the existing established pattern in `terraform/modules/secrets/main.tf`:
Terraform creates the empty secret container only; the value is always
populated out-of-band via `aws secretsmanager put-secret-value`, never by
Terraform, so re-applies never overwrite it.

### 5. Bucket creation

Already done (see step 1) — do not create a second/new bucket for this
feature. Reuse the existing `documents` bucket and its existing
`subawards/` prefix.

### 6. ETL changes

None required to the ETL *code*. To actually populate real data:

```bash
cd etl
uv run python archive_subaward_attachments.py \
  --s3-bucket research-archive-platform-<env>-documents-<account-id> \
  --sync-postgres
```

Run this from a BU-VPN-connected machine with real `ORACLE_*` and AWS
credentials configured (per `docs/SUBAWARD_ATTACHMENT_ARCHIVE.md`'s exact
commands — treat that runbook as authoritative for flags/env vars, this
document only summarizes it).

### 7. Verification

```sql
SELECT archive_status, COUNT(*)
FROM archive.subaward_attachment_archive
GROUP BY archive_status;
```

Then spot-check at least one real `ARCHIVED` row through the actual API:

```bash
curl -H "Authorization: Bearer <token>" \
  https://<api-host>/api/subawards/<real-id>/attachments
curl -H "Authorization: Bearer <token>" \
  -o downloaded.pdf \
  https://<api-host>/api/subawards/<real-id>/attachments/<attachment-id>/download
```

Confirm the downloaded file's SHA-256 matches
`archive.subaward_attachment_archive.sha256` for that row.

### 8. Rollback

This feature is **read-only from the API's perspective** — there is no data
migration to roll back. To revert:

- Remove/blank `ARCHIVE_DOCUMENTS_BUCKET` — `S3SubawardAttachmentStorage`
  fails closed (`IllegalStateException`, surfaced as a 500) rather than
  serving anything incorrect; no data is at risk either way.
- If a bad ETL run wrote incorrect archive rows, they can be corrected by
  re-running the archival job (it is idempotent — see [§3](#3-oracle-attachment-extraction))
  or, in the worst case, deleting the specific
  `archive.subaward_attachment_archive` rows and re-archiving — this never
  touches `archive.subaward_attachment` (the Oracle-sourced metadata) or any
  other domain's data.

---

## 6. Local Development

### How developers generate sample attachments

```bash
./scripts/setup-local.sh
```

This single command (1) generates the 3 synthetic files, (2) seeds matching
attachment metadata into the local Postgres instance, and (3) verifies
exactly 4 synthetic rows were created — failing loudly if not. It is safe to
re-run at any time (both steps are idempotent).

Under the hood it runs, in order:

1. `python3 tools/generate-local-attachment-fixtures.py`
2. `psql ... -f scripts/seed-local-subaward-attachments.sql`
3. A `SELECT COUNT(*)` check against the seeded ID range.

### Finding the seeded attachments in the UI

The synthetic attachments are seeded onto **`subaward_id = 94204`**, a real,
existing local subaward with zero real attachments. This ID was chosen
deliberately for discoverability: the Subaward list page's default view
(no search term) orders results by `subaward_id DESC`, and `94204` is one
of the highest IDs in the local database — so it appears **5th from the
top of the very first page**, with no searching, scrolling, or URL-editing
required.

After `./scripts/setup-local.sh` and starting the local API + UI
(`scripts/run-local.sh`):

1. Open the Subawards list (`http://localhost:5173/subawards`).
2. The row for subaward `4330` (subaward_id 94204) is near the top of the
   first page.
3. Click it, then open the **Attachments** tab.

Or jump straight there: `http://localhost:5173/subawards/94204`.

An earlier version of this seed used `subaward_id = 1` instead, which also
has zero real attachments but sits on the *last* page of roughly 3,700 (the
default sort puts the lowest ID dead last) — not reachable within a search
in any reasonable time. `scripts/seed-local-subaward-attachments.sql`
automatically cleans up any leftover rows from that earlier choice if
you're re-running it after an update.

### Where they live

`local-data/attachments/` at the repository root:

```
local-data/attachments/
  sample-agreement.pdf
  sample-budget.xlsx
  sample-note.txt
```

`application-local.yml` points `app.attachments.local-directory` at
`../local-data/attachments` (relative to `api/`, since the supported local
run method, `scripts/run-local.sh`, executes `mvn spring-boot:run` with
`api/` as the working directory).

### How to regenerate them

Re-run `./scripts/setup-local.sh`, or just
`python3 tools/generate-local-attachment-fixtures.py` if only the physical
files (not the database rows) need refreshing — it always overwrites the
3 files with fresh copies.

### How to remove them

```bash
rm -rf local-data/
```

and, if the database rows should also go:

```sql
DELETE FROM archive.subaward_attachment_archive
  WHERE attachment_id BETWEEN 9000000001 AND 9000000004;
DELETE FROM archive.subaward_attachment
  WHERE attachment_id BETWEEN 9000000001 AND 9000000004;
```

(These exact statements are also included, commented out, at the bottom of
`scripts/seed-local-subaward-attachments.sql`.)

### Why they are gitignored

- They are **regeneratable** from a committed script
  (`tools/generate-local-attachment-fixtures.py`) — there is no reason to
  store their bytes in git history when the generator that produces them
  deterministically is already committed.
- It keeps the repository's stance consistent even though these particular
  files contain no real data: this codebase's policy is that attachment
  binaries — real or synthetic — do not belong in git, matching the existing
  `data/`/`exports/` gitignore convention already used for ETL-local output.
- It removes any temptation to later "just drop a real sample file in
  there" without thinking — the directory is structurally a scratch space,
  not a place anything gets committed from.

---

## 7. Security

### Authentication

- **Non-local environments**: Cognito JWT OAuth2 resource server
  (`SecurityConfiguration`) — every request must carry a valid, unexpired
  JWT issued by the environment's Cognito User Pool.
- **Local dev**: `app.security.enabled=false` activates
  `LocalSecurityConfiguration`, a permit-all filter chain. This is a
  deliberate local-only convenience; it must never be set in a deployed
  environment.

### Authorization

- **Ownership check (IDOR protection)**: before serving a download,
  `SubawardArchiveService.downloadAttachment` looks up which subaward the
  requested `attachment_id` actually belongs to
  (`repository.findAttachmentSubawardId`) and rejects the request (404) if
  it doesn't match the `subawardId` in the URL. This prevents a user who
  knows/guesses one subaward's attachment ID from fetching it through a
  different subaward's URL.
- **No finer-grained, per-user or per-role authorization exists today** —
  any authenticated user who can reach the API at all can list/download any
  subaward's attachments. If BU ever needs to restrict attachment access to
  specific roles or a subaward's own PI/admin, that is new work, not
  something implicitly covered by the current design.

### S3 security (production)

- Bucket is **private**; all four public-access-block flags are enabled.
- **Versioning** enabled.
- **SSE-S3 (AES256)** encryption at rest by default.
- **`prevent_destroy`** lifecycle guard on the bucket resource itself.

### Least privilege

- API task role: `s3:ListBucket` + `s3:GetObject` only — **no write, no
  delete**, ever.
- ETL's upload credentials are entirely separate from the API's — never
  share or reuse credentials between the read path and the write path.

### Path traversal

- **`LocalSubawardAttachmentStorage`** resolves the requested key against
  its configured base directory and explicitly rejects anything that
  normalizes to a path outside it (`candidate.startsWith(baseDirectory)`),
  covering both `../`-style relative escapes and absolute-path substitution
  attempts in the stored key. Covered by
  `LocalSubawardAttachmentStorageTest`.
- **`S3SubawardAttachmentStorage`** has no filesystem path-traversal surface
  by construction — the "key" is just a string passed to the AWS SDK's
  `GetObjectRequest`, not resolved against any local filesystem. The
  relevant control there is the **bucket-match check**
  (`documentsBucket.equals(attachment.s3Bucket())`), which prevents a
  malformed/tampered database row from causing a read against an unexpected
  bucket.

### Content-Disposition

Every downloaded filename passes through `safeFileName` in
`SubawardArchiveService` before it is ever placed in the
`Content-Disposition` header: path separators and control characters
(including `\r`/`\n`, which could otherwise be used for HTTP response
header/splitting-style injection) are stripped, and the header is built via
`ContentDisposition.attachment().filename(..., UTF_8)`, which also URL/MIME
encodes the value correctly for non-ASCII filenames. This is defense in
depth independent of whichever storage backend served the bytes.

### Virus scanning (future)

Not implemented today, in either the ETL upload path or the API download
path. See [§8](#8-future-enhancements).

### Audit logging

No attachment-specific audit log exists today beyond whatever standard
application/access logging the API and its infrastructure already produce
(Spring Boot request logs, CloudWatch Logs from the ECS task, ALB access
logs if enabled). There is no structured "user X downloaded attachment Y at
time Z" record purpose-built for this feature. See [§8](#8-future-enhancements).

---

## 8. Future Enhancements

Listed for awareness and planning only — none of these are implemented, and
this document does not propose implementing them now:

- **Thumbnail previews** — generate small preview images for image/PDF
  attachments so the UI can show a visual preview before download.
- **Large file streaming improvements** — HTTP range-request support so a
  large download can be resumed rather than restarted from zero on
  interruption.
- **Virus scanning** — scan attachments (at ETL upload time, at API
  download time, or both) before they reach a user, e.g. via ClamAV, S3
  Object Lambda, or a managed malware-scanning service.
- **Deduplication** — content-addressed storage keyed by `sha256` so
  byte-identical attachments (a common occurrence with boilerplate template
  documents) aren't stored twice.
- **Multipart uploads** — for the ETL's own upload path, to handle very
  large BLOBs more efficiently/resiliently than a single-part PUT.
- **User-facing checksum verification** — surface the SHA-256 to the user
  (or verify it client-side) so a downloaded file's integrity can be
  independently confirmed, not just trusted from the server.
- **Formal retention policies** — a deliberate record-retention/legal-hold
  policy for attachments, distinct from the generic storage-class lifecycle
  transitions that already exist (which optimize for cost, not retention
  requirements).
- **Presigned URL downloads** — see [§4](#4-s3-storage-design)'s discussion;
  would reduce API load for large files at the cost of a new
  presigned-URL-issuing code path to secure correctly.
- **Per-user/role-based attachment authorization** — restrict which
  authenticated users can access a given subaward's attachments, beyond
  today's "any authenticated user, any subaward" model.
- **SSE-KMS with a customer-managed key** — for CloudTrail data-event
  auditing of decrypt operations; the ETL already supports the flag, only
  the Terraform-side key/policy is missing.
- **Structured audit logging** — a purpose-built log/table recording who
  downloaded which attachment and when.

---

## 9. Operational Runbook

What BU operations does, concretely, when moving a given environment from
"attachments not yet enabled" to "real S3-backed attachments in production."

### Pre-migration

- [ ] Confirm the environment's `documents` S3 bucket exists and is the one
      you intend to use (`terraform output` in that environment, or the AWS
      Console).
- [ ] Confirm the API's ECS task role already has the `s3:ListBucket`/
      `s3:GetObject` statement scoped to that bucket (it does, by default,
      whenever `documents_bucket_arn` is passed into the `api_service`
      module — verify this environment's `main.tf` actually passes it).
- [ ] Confirm `ARCHIVE_DOCUMENTS_BUCKET` is **not yet set** for this
      environment (today, it isn't set anywhere) — this is the expected,
      safe starting state: `S3SubawardAttachmentStorage` fails closed with a
      clear error rather than silently misbehaving.
- [ ] Confirm you have a BU-VPN-connected machine available with valid
      `ORACLE_USER`/`ORACLE_PASSWORD`/`ORACLE_DSN` and AWS credentials for
      the ETL run (per `docs/SUBAWARD_ATTACHMENT_ARCHIVE.md`).

### Migration

1. Add `ARCHIVE_DOCUMENTS_BUCKET` to the environment's
   `additional_api_environment_variables` in Terraform (see
   [§5](#5-switching-local--production) step 2 for the exact snippet), plan,
   and apply. This is the only Terraform change required.
2. Redeploy the API (new task definition revision picks up the new
   environment variable).
3. From the BU-VPN machine, run the ETL binary-archival job:
   `uv run python archive_subaward_attachments.py --s3-bucket <bucket> --sync-postgres`
   (see `docs/SUBAWARD_ATTACHMENT_ARCHIVE.md` for full flag reference).
4. Confirm the job completed without unexpected `FAILED` rows (some
   `MISSING` rows are expected and normal — they mean the Oracle metadata
   row had no associated file, not that something went wrong).

### Verification

1. `SELECT archive_status, COUNT(*) FROM archive.subaward_attachment_archive GROUP BY archive_status;`
   — sanity-check the overall distribution.
2. Pick 2–3 real `ARCHIVED` rows and confirm, through the actual deployed
   API (with a real auth token), that:
   - `GET /api/subawards/{id}/attachments` lists them with `archived: true`.
   - `GET /api/subawards/{id}/attachments/{attachmentId}/download` returns
     200, the correct `Content-Type`, and a byte-identical file (compare
     SHA-256 against the `archive.subaward_attachment_archive.sha256`
     column for that row).
3. Confirm a subaward with zero attachments still returns `[]` (the
   "empty state" — this should already work identically to local dev, since
   the query logic is unchanged).

### Rollback

Because the API only ever reads from S3, there is no destructive action to
undo:

- To immediately stop serving real attachments, remove/blank
  `ARCHIVE_DOCUMENTS_BUCKET` and redeploy — downloads fail closed (500,
  clear error) rather than serving anything wrong.
- If specific archive rows are wrong (e.g. pointing at the wrong S3 object),
  delete just those rows from `archive.subaward_attachment_archive` and
  re-run the ETL job — this never touches
  `archive.subaward_attachment` (the underlying Oracle metadata) or any
  other domain.
- No S3 objects need to be deleted as part of a rollback — an orphaned S3
  object with no matching Postgres row is inert (unreachable through the
  API, since the API only ever looks up objects via the Postgres row).

### Monitoring

- **CloudWatch Logs** — the API's ECS task logs (`aws_cloudwatch_log_group`
  in `api_service` module) will show the `IllegalStateException`/
  `NoSuchElementException` messages described throughout this document if
  something is misconfigured.
- **S3 access logging** — not currently enabled on the documents bucket.
  Consider enabling it (or S3 server access logs / CloudTrail data events)
  if per-object download auditing becomes a requirement; see
  [§8](#8-future-enhancements)'s "structured audit logging" item.
- **CloudTrail** — already captures IAM/API-level S3 actions (bucket
  policy changes, etc.) at the management-event level by default in most
  AWS accounts; this is a general AWS account setting, not something this
  feature specifically configures.

### Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Every download returns 500 with `ARCHIVE_DOCUMENTS_BUCKET is not configured` | The environment variable was never set for this environment/task definition. | Add it via Terraform (§5 step 2) and redeploy. |
| Every download returns 404 `Archived attachment object not found`, but the row's `archive_status = 'ARCHIVED'` | The `s3_bucket` column doesn't match the value the API was configured with, or the object was deleted/moved in S3 out of band. | Compare `archive.subaward_attachment_archive.s3_bucket` for the row against the deployed `ARCHIVE_DOCUMENTS_BUCKET` value; if they match, check the object actually exists at that key in S3 (drift), and re-run the ETL archival job for that attachment if not. |
| A specific attachment 404s with `Archived attachment not found` (not "object") | No `archive.subaward_attachment_archive` row exists yet for that attachment, or it exists with `archive_status != 'ARCHIVED'`. | Expected for `MISSING`/`FAILED` rows or attachments the ETL hasn't processed yet — not a bug. Check the ETL run's logs for that specific `attachment_id`. |
| List endpoint shows fewer attachments than expected for a subaward | The metadata load (`load_subawards_from_csv.py`) and the binary-archival job are two independent processes on two independent schedules — a gap between them is normal, not a bug, until both have run. | Confirm the metadata load has actually run for that subaward before troubleshooting the binary pipeline. |
