# Subaward Attachment Binary Archive

## Architecture

Subaward attachment payloads are extracted only from a local computer connected
to the BU VPN. The exporter reads `KCOEUS.FILE_DATA.DATA` through the verified
relationship:

```text
KCOEUS.SUBAWARD_ATTACHMENTS.FILE_DATA_ID = KCOEUS.FILE_DATA.ID
```

AWS services and the Spring Boot API never connect to BU Oracle. The local
exporter streams each Oracle BLOB to a temporary local file, calculates SHA-256,
uploads the approved file to S3, and deletes the temporary file. PostgreSQL
stores metadata only.

## S3 object keys

The deterministic object key is:

```text
{prefix}/{subaward_id}/{attachment_id}/{sanitized_file_name}
```

With the default prefix:

```text
subawards/94202/123456/original_file_name.pdf
```

The attachment ID makes each object key stable even when source filenames are
duplicated. Sanitization removes path components and unsafe characters while
retaining a recognizable filename.

Every uploaded object has S3 user metadata for `sha256`, `attachment-id`,
`subaward-id`, and `file-data-id`. The exporter skips an existing object only
when:

1. The local manifest row matches the current attachment metadata, byte size,
   SHA-256, bucket, and key.
2. S3 `HEAD` returns the same byte size and SHA-256 user metadata.

Object existence or ETag alone is never sufficient for a resume skip.

## Local manifest contract

The default manifest is a durable SQLite database at:

```text
etl/exports/subawards/subaward_attachment_manifest.sqlite3
```

Table `attachment_manifest` contains:

| Column | Meaning |
|---|---|
| `attachment_id` | Oracle attachment primary key and manifest primary key |
| `subaward_id` | Physical Oracle Subaward parent |
| `subaward_code` | Source business-family code |
| `sequence_number` | Source sequence number |
| `file_data_id` | Verified `KCOEUS.FILE_DATA.ID` reference |
| `original_file_name` | Unsanitized source filename |
| `mime_type` | Source MIME type |
| `document_id` | Source document identifier |
| `attachment_source_update_timestamp` | Source attachment update timestamp |
| `attachment_last_update_timestamp` | Source attachment last-update timestamp |
| `s3_bucket` | Approved destination bucket |
| `s3_key` | Deterministic destination key |
| `byte_size` | Streamed BLOB size |
| `sha256` | Lowercase SHA-256 hex digest |
| `archive_status` | `ARCHIVED`, `MISSING`, or `FAILED` |
| `archived_timestamp` | Successful archive time in UTC |
| `error_message` | Sanitized failure summary; never BLOB data or credentials |
| `manifest_updated_at` | Last local manifest update in UTC |

V019 creates the equivalent PostgreSQL metadata table
`archive.subaward_attachment_archive`. The `--sync-postgres` switch is a
sync-only command: it applies pending migrations and upserts successful local
manifest rows in batches without connecting to Oracle or S3. No payload bytes
are written to PostgreSQL.

## Required local configuration

Set these without placing credentials in source control:

```text
ORACLE_USER
ORACLE_PASSWORD
ORACLE_DSN
AWS credentials through the standard boto3 credential chain
SUBAWARD_ATTACHMENT_S3_BUCKET
AWS_REGION
```

Optional configuration:

```text
SUBAWARD_ATTACHMENT_S3_PREFIX
SUBAWARD_ATTACHMENT_SSE
SUBAWARD_ATTACHMENT_KMS_KEY_ID
POSTGRES_HOST
POSTGRES_PORT
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD
```

Use `SUBAWARD_ATTACHMENT_SSE=aws:kms` and provide the approved KMS key ID when
bucket policy requires customer-managed KMS encryption. The default is S3
managed AES-256 encryption.

## Validated small-run command

From `etl/`, with the BU VPN connected:

```bash
.venv/bin/python archive_subaward_attachments.py \
  --subaward-id 94202 \
  --limit 10 \
  --s3-bucket "$SUBAWARD_ATTACHMENT_S3_BUCKET" \
  --s3-prefix subawards
```

The reusable entry point accepts the same options from the repository root:

```bash
uv run --project etl python etl/archive_attachments.py \
  --module subaward \
  --subaward-id 94202 \
  --limit 10 \
  --s3-bucket "$SUBAWARD_ATTACHMENT_S3_BUCKET" \
  --s3-prefix test/subawards
```

`etl/archive_subaward_attachments.py` remains available as a
backward-compatible wrapper. The generic runner currently registers only the
Subaward plugin; no other archive module is enabled.

The generic framework separates Oracle BLOB streaming, S3 storage, SQLite
manifest handling, and orchestration under
`etl/archive_etl/attachments/`. The Subaward plugin continues to own its CSV
mapping, deterministic key format, S3 object metadata, pilot validation, and
PostgreSQL synchronization.

For a non-mutating 10-file verification run, add `--dry-run` (an alias for the
existing `--verify-only` option):

```bash
uv run --project etl python etl/archive_attachments.py \
  --module subaward \
  --subaward-id 94202 \
  --limit 10 \
  --s3-bucket "$SUBAWARD_ATTACHMENT_S3_BUCKET" \
  --s3-prefix test/subawards \
  --dry-run
```

The command enforces these verified expectations for Subaward 94202:

- Attachment metadata count: 10
- `FILE_DATA` match count: 10

## Resume and full archive

Re-run the same command to verify resume behavior. Matching manifest and S3
checksums increment the resumed count and do not upload again.

Run the complete archive first:

```bash
.venv/bin/python archive_subaward_attachments.py \
  --s3-bucket "$SUBAWARD_ATTACHMENT_S3_BUCKET" \
  --s3-prefix subawards
```

Then synchronize PostgreSQL metadata without reading Oracle or uploading:

```bash
.venv/bin/python archive_subaward_attachments.py --sync-postgres
```

The pilot synchronization is deliberately scoped:

```bash
.venv/bin/python archive_subaward_attachments.py \
  --subaward-id 94202 \
  --sync-postgres
```

Oracle reads, S3 `HEAD`, and S3 upload operations retry transient failures with
bounded exponential backoff. Missing `FILE_DATA` rows are recorded as
`MISSING`; upload or read failures are recorded as `FAILED`.

## Verification

Verify the test archive against Oracle, the local manifest, and S3:

```bash
.venv/bin/python archive_subaward_attachments.py \
  --subaward-id 94202 \
  --limit 10 \
  --s3-bucket "$SUBAWARD_ATTACHMENT_S3_BUCKET" \
  --s3-prefix subawards \
  --verify-only
```

The verification run re-streams each BLOB to independently calculate its size
and SHA-256, then compares those values with both the manifest and S3 metadata.
It reports metadata count, `FILE_DATA` matches, missing BLOBs, upload failures,
archived bytes, checksum mismatches, and manifest orphans.

After `--sync-postgres`, run:

```bash
psql -f ../sql/verify/subaward_attachment_archive.sql
```

The PostgreSQL report adds status counts, duplicate `FILE_DATA_ID` usage,
metadata integrity, manifest-to-attachment orphans, attachments lacking
manifest rows, and parent-field mismatches.

## Security assumptions

- The local workstation and temporary directory meet BU data-handling policy.
- The S3 bucket and prefix are explicitly approved for Subaward attachments.
- Bucket policy blocks public access and requires encryption in transit and at
  rest.
- The executing AWS identity has only the required prefix-level `PutObject`,
  `HeadObject`, and KMS permissions.
- Oracle credentials, AWS credentials, BLOB contents, and signed URLs are never
  written to logs or the manifest.
- The API reads objects with its ECS task role and never returns bucket names,
  object keys, `FILE_DATA_ID` values, credentials, or signed S3 URLs.
