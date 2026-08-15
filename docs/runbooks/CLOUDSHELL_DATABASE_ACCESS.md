# CloudShell Read-Only Database Access

Step-by-step connection walkthrough and query cookbook for the AWS
CloudShell VPC environment path to dev PostgreSQL. For *why* this
design exists, the one-time `archive_analyst` role-creation steps, the
security-group/Terraform details, and the 2026-08-15 setup incident,
see [`CLOUDSHELL_ANALYSIS.md`](CLOUDSHELL_ANALYSIS.md) - this doc
intentionally does not repeat that content, to avoid maintaining two
conflicting procedures.

## Purpose

Read-only investigation, reconciliation, schema discovery, and data
analysis against the authoritative AWS development RDS database.

Do not use local PostgreSQL for deployed-data reconciliation or ETL
completeness checks - see the main `CLAUDE.md`.

## Authoritative environment

| Setting | Value |
|---|---|
| AWS profile | `bu-nprd` |
| AWS account | `770203350335` |
| AWS region | `us-east-1` |
| CloudShell environment | `research-archive-analysis` |
| VPC | `vpc-0590614d7cfcdedf6` |
| Private subnet | `subnet-00fba12ee73ff0e3b` |
| CloudShell security group | `sg-002be83bf1cf249fa` |
| PostgreSQL database | `research_archive` |
| Read-only user | `archive_analyst` |
| RDS endpoint | `research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com` |
| PostgreSQL port | `5432` |

The CloudShell security group has zero inbound rules and permits
outbound traffic only to the RDS security group on TCP 5432. Do not
broaden these rules.

## 1. Refresh and verify AWS access on the Mac

```bash
buaws
AWS_PROFILE=bu-nprd AWS_REGION=us-east-1 \
  aws sts get-caller-identity --query Account --output text
```

Expected: `770203350335`. Stop if a different account appears.

## 2. Open the correct CloudShell environment

Open the AWS console in account `770203350335`, region `us-east-1`,
open CloudShell, and select the VPC environment named
`research-archive-analysis`.

Do not use the ordinary default CloudShell environment - it has no
route to the private RDS database. If the environment must be
recreated: VPC `vpc-0590614d7cfcdedf6`, subnet
`subnet-00fba12ee73ff0e3b`, security group `sg-002be83bf1cf249fa`. Do
not substitute another VPC, subnet, or security group.

## 3. Confirm the PostgreSQL client is available

At the CloudShell Bash prompt (`~ $`):

```bash
psql --version   # expect: psql (PostgreSQL) 16.x
```

Do not enter SQL at the Bash prompt - if it reports `command not found`
or a syntax error for `SELECT`, you are not connected to PostgreSQL yet.

## 4. Copy the analyst password on the Mac

```bash
cd ~/projects/research-archive-platform
scripts/mac-show-analyst-password.sh
```

Retrieves the credential from SSM Parameter Store and copies it to the
macOS clipboard - never prints it. Do not press Enter in the helper to
clear the clipboard until after pasting into CloudShell. Never paste the
password into chat, documentation, screenshots, shell history, or
source files.

## 5. Connect from CloudShell

```bash
psql \
  "host=research-archive-platform-dev-postgres.clb9d4mkglfd.us-east-1.rds.amazonaws.com port=5432 dbname=research_archive user=archive_analyst sslmode=require connect_timeout=10" \
  --password
```

At the `Password:` prompt, paste (Cmd+V) and press Enter - PostgreSQL
displays no characters while a password is typed or pasted, that is
normal. Return to the Mac helper and press Enter to clear the clipboard.
A successful connection shows the PostgreSQL prompt: `research_archive=>`.

## 6. Verify identity and read-only protection

```sql
SELECT current_user, current_database();
SHOW transaction_read_only;
```

Expected: `current_user = archive_analyst`,
`current_database = research_archive`, `transaction_read_only = on`.

```sql
SELECT rolname, rolcanlogin, rolcreatedb, rolcreaterole, rolsuper, rolreplication
FROM pg_roles
WHERE rolname = current_user;
```

The role must not be a superuser and must not have database-creation,
role-creation, or replication privileges.

## 7. Disable the psql pager

```
\pset pager off
```

Long results otherwise pause at `--More--` - if already stuck there,
press `q` first. Optional: `\x auto`, `\timing on`.

## 8. Permitted operations

SELECT-only: `SELECT`, `WITH`, `SHOW`, `EXPLAIN` (on `SELECT`), and
psql inspection commands (`\dt`, `\d`, `\q`). Do not run `INSERT`,
`UPDATE`, `DELETE`, `MERGE`, `TRUNCATE`, `ALTER`, `DROP`, `CREATE`,
`GRANT`, or `REVOKE` - `default_transaction_read_only=on` and
SELECT-only grants enforce this at the database level regardless, but
submit only read-only SQL regardless.

Or, for a single approved query without an interactive session:

```bash
scripts/run-cloudshell-analyst-query.sh -c "SELECT COUNT(*) FROM archive.negotiation"
```

(That script itself must be pasted into CloudShell via clipboard or
typed directly the first time - CloudShell cannot `git clone` this
repo.)

---

## Query cookbook

### Schema overview

```sql
SELECT COUNT(*) AS archive_table_count
FROM information_schema.tables
WHERE table_schema = 'archive' AND table_type = 'BASE TABLE';
```

The confirmed development baseline on 2026-08-15 was **113**. Treat
that as historical documentation - re-query before making decisions.

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'archive' AND table_type = 'BASE TABLE'
ORDER BY table_name;

SELECT schemaname, relname AS table_name, n_live_tup AS approximate_rows
FROM pg_stat_user_tables
WHERE schemaname = 'archive'
ORDER BY n_live_tup DESC, relname;
```

For authoritative counts, use `COUNT(*)` on the specific table under
investigation - `n_live_tup` is an estimate.

### Columns

```sql
SELECT table_name, ordinal_position, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'archive'
ORDER BY table_name, ordinal_position;

-- Or for one table:
\d+ archive.negotiation
```

### Foreign keys and constraints

```sql
SELECT
    child.relname AS child_table,
    parent.relname AS parent_table,
    pg_get_constraintdef(constraint_record.oid) AS relationship
FROM pg_constraint constraint_record
JOIN pg_class child ON child.oid = constraint_record.conrelid
JOIN pg_namespace child_schema ON child_schema.oid = child.relnamespace
JOIN pg_class parent ON parent.oid = constraint_record.confrelid
WHERE constraint_record.contype = 'f'
  AND child_schema.nspname = 'archive'
ORDER BY child.relname, parent.relname;
```

Attachment-related only:

```sql
SELECT
    child.relname AS child_table,
    parent.relname AS parent_table,
    pg_get_constraintdef(constraint_record.oid) AS relationship
FROM pg_constraint constraint_record
JOIN pg_class child ON child.oid = constraint_record.conrelid
JOIN pg_namespace child_schema ON child_schema.oid = child.relnamespace
JOIN pg_class parent ON parent.oid = constraint_record.confrelid
WHERE constraint_record.contype = 'f'
  AND child_schema.nspname = 'archive'
  AND (child.relname ILIKE '%attachment%' OR parent.relname ILIKE '%attachment%')
ORDER BY child.relname, parent.relname;
```

**Important**: the absence of a database foreign key does not mean two
records are unrelated - some relationships are preserved through source
identifiers and enforced by ETL/application logic rather than a
PostgreSQL constraint (Negotiation attachments are the concrete example
below).

All constraints in the archive schema:

```sql
SELECT
    table_record.relname AS table_name,
    constraint_record.conname AS constraint_name,
    CASE constraint_record.contype
        WHEN 'p' THEN 'PRIMARY KEY' WHEN 'f' THEN 'FOREIGN KEY'
        WHEN 'u' THEN 'UNIQUE' WHEN 'c' THEN 'CHECK' WHEN 'x' THEN 'EXCLUSION'
        ELSE constraint_record.contype::text
    END AS constraint_type,
    pg_get_constraintdef(constraint_record.oid) AS definition
FROM pg_constraint constraint_record
JOIN pg_class table_record ON table_record.oid = constraint_record.conrelid
JOIN pg_namespace table_schema ON table_schema.oid = table_record.relnamespace
WHERE table_schema.nspname = 'archive'
ORDER BY table_record.relname, constraint_type, constraint_record.conname;
```

### Attachment architectures (differ by domain - do not assume one common shape)

- **Award**: `award_attachment.file_id` → `attachment_object.file_id`.
  `award_attachment` holds Award relationship metadata;
  `attachment_object` holds shared physical-file/archive state.
- **Proposal**: `proposal_attachment` holds both metadata and
  binary-pipeline status fields in one table.
- **Subaward**: `subaward` → `subaward_attachment` →
  `subaward_attachment_archive`. `subaward_attachment` holds metadata;
  `subaward_attachment_archive` holds S3/archive status and binary
  integrity fields.
- **Negotiation**: `archived_attachment`, `module_code = 'NEGOTIATION'`,
  `parent_record_id = negotiation.negotiation_id` - by application
  convention only, **no PostgreSQL foreign key** enforces that link.
  Validate it explicitly (query below) when investigating integrity.

### Negotiation attachment integrity queries

```sql
SELECT archive_status, legacy_restricted_flag, COUNT(*) AS row_count
FROM archive.archived_attachment
WHERE module_code = 'NEGOTIATION'
GROUP BY archive_status, legacy_restricted_flag
ORDER BY archive_status, legacy_restricted_flag;
```

Re-query before relying on any of these - they are historical
snapshots, not live facts:

- **Pre-recovery baseline, 2026-08-15**: `ARCHIVED/N = 2,342`,
  `MISSING/N = 6,175`, `MISSING/Y = 20,406`.
- **After the one-file fixture proof, 2026-08-15** (attachment 29373
  only - see below): `ARCHIVED/N = 2,342`, `ARCHIVED/Y = 1`,
  `MISSING/N = 6,175`, `MISSING/Y = 20,405`. The full backfill has
  **not** run yet - do not assume more than this one row changed.

```sql
-- Duplicate source attachment IDs - expect 0 rows.
SELECT source_attachment_id, COUNT(*) AS row_count
FROM archive.archived_attachment
WHERE module_code = 'NEGOTIATION'
GROUP BY source_attachment_id
HAVING COUNT(*) > 1
ORDER BY source_attachment_id;

-- Orphaned parent records (no matching negotiation) - expect 0.
SELECT COUNT(*) AS orphaned_negotiation_attachments
FROM archive.archived_attachment attachment
LEFT JOIN archive.negotiation negotiation
    ON negotiation.negotiation_id = attachment.parent_record_id
WHERE attachment.module_code = 'NEGOTIATION'
  AND negotiation.negotiation_id IS NULL;
```

If the orphan count above is nonzero:

```sql
SELECT attachment.archived_attachment_id, attachment.source_attachment_id,
       attachment.parent_record_id, attachment.original_file_name,
       attachment.archive_status
FROM archive.archived_attachment attachment
LEFT JOIN archive.negotiation negotiation
    ON negotiation.negotiation_id = attachment.parent_record_id
WHERE attachment.module_code = 'NEGOTIATION'
  AND negotiation.negotiation_id IS NULL
ORDER BY attachment.parent_record_id, attachment.source_attachment_id;
```

### Negotiation external-BLOB recovery context

The Negotiation attachment exporter selected
`NEGOTIATION_ATTACHMENT.FILE_ID` but did not preserve/use
`ATTACHMENT_FILE.FILE_DATA_ID` - the UUID pointer to externally-stored
content in Oracle `KCOEUS.FILE_DATA`. Confirmed source classification:

| Blob source | Restricted N | Restricted Y | Total |
|---|---|---|---|
| Inline content | 2,342 | 0 | 2,342 |
| External recoverable content | 6,172 | 20,400 | 26,572 |
| Genuinely missing | 3 | 6 | 9 |

The fix (commit `ed7a211`) is implemented and proven on exactly one
fixture (Negotiation 12788, attachment 29373, File ID 164229 →
`FILE_DATA_ID 995577d2-b20f-4b10-a4aa-5bc0d32f64b4`, 140,288 bytes,
S3 checksum verified). **The full 26,572-row backfill has not run** -
it is paused pending explicit approval, using the resumable detached
ECS attachment framework, one task/writer at a time, reconciling
Oracle/PostgreSQL/S3 after every batch. Do not mark the 9 genuinely
missing rows as archived. Do not reprocess the 2,342 already-correct
inline rows. Do not update any of these statuses manually - they must
come from the tested, resumable pipeline.

### Domain identifier rules

- Award: `award_number` is the family identifier; `award_id` identifies
  an exact historical version.
- Proposal: `proposal_number` is the family identifier; `proposal_id`
  identifies an exact version.
- Negotiation: `negotiation_id` is the business identifier. There is no
  "Negotiation number."
- Subaward: `subaward_code` is the business identifier. Never call it
  "Subaward number."
- `document_number` identifies the exact Kuali workflow document when
  present.

```sql
SELECT 'award_number' AS family_type, COUNT(DISTINCT award_number) AS family_count
FROM archive.award_version
UNION ALL
SELECT 'proposal_number', COUNT(DISTINCT proposal_number) FROM archive.proposal_version
UNION ALL
SELECT 'negotiation_id', COUNT(DISTINCT negotiation_id) FROM archive.negotiation
UNION ALL
SELECT 'subaward_code', COUNT(DISTINCT subaward_code) FROM archive.subaward
ORDER BY family_type;
```

### Table size, indexes, migrations

```sql
SELECT table_schema, table_name,
       pg_size_pretty(pg_total_relation_size(format('%I.%I', table_schema, table_name)::regclass)) AS total_size,
       pg_total_relation_size(format('%I.%I', table_schema, table_name)::regclass) AS total_bytes
FROM information_schema.tables
WHERE table_schema = 'archive' AND table_type = 'BASE TABLE'
ORDER BY total_bytes DESC, table_name;

SELECT tablename AS table_name, indexname AS index_name, indexdef AS definition
FROM pg_indexes
WHERE schemaname = 'archive'
ORDER BY tablename, indexname;

SELECT * FROM archive.schema_migration ORDER BY version;
SELECT * FROM archive.schema_migration ORDER BY version DESC LIMIT 20;
```

Migration files in source control and rows in `archive.schema_migration`
must be reconciled before applying new migrations - Spring Boot never
applies them; the Python ETL does (see the main `CLAUDE.md`).

## 9. Disconnect safely

`\q` returns to the Bash prompt (`~ $`). Close CloudShell when done.

## Troubleshooting

**Connection hangs or times out** - probably the ordinary CloudShell
environment, not the VPC one. Ctrl+C, open `research-archive-analysis`,
retry. Do not add public access, broaden security groups, or substitute
unrelated infrastructure.

**Password authentication fails** - re-run
`scripts/mac-show-analyst-password.sh` on the Mac and confirm the
connection uses `user=archive_analyst`, not the admin/master credential.

**Helper reports `ParameterNotFound`** - the one-time role/credential
setup hasn't been completed. Verify from an already-authorized
connection: `SELECT rolname, rolcanlogin FROM pg_roles WHERE rolname =
'archive_analyst';`. If no row, stop and follow the one-time setup in
`CLOUDSHELL_ANALYSIS.md` - do not casually create a replacement role or
password.

**`command not found` for SQL** - SQL was entered at the Bash `~ $`
prompt. Connect with `psql` first and wait for `research_archive=>`.

**Output stops at `--More--`** - press `q`, then `\pset pager off`.

**AWS identity is wrong or expired** - `buaws`, then re-verify the
account is `770203350335`. Never continue using credentials that
resolve to another account.

## Security rules

- Never paste passwords, AWS credentials, raw JWTs, refresh tokens, or
  other secret values into chat.
- Never include credentials in screenshots, SQL files, shell history,
  documentation, or Git commits.
- Never print the analyst or administrator password.
- Use only the clipboard-based project helpers.
- Use `archive_analyst` for routine investigation; `archive_admin` only
  for explicitly approved administrative work.
- Do not expose RDS publicly or broaden the CloudShell security group
  beyond RDS TCP 5432.
- Do not use stale local PostgreSQL for deployed-data reconciliation.
- Do not manually modify archived source data or attachment archive
  statuses.
- Do not run ETL, migrations, Terraform, or deployment commands from an
  analyst session.

## Documentation maintenance

When this environment changes: update this doc and cross-check
[`CLOUDSHELL_ANALYSIS.md`](CLOUDSHELL_ANALYSIS.md) rather than letting
the two drift into conflicting procedures. Verify every account, VPC,
subnet, security-group, endpoint, and role identifier against live
infrastructure before changing it. Never commit secrets or copied
credentials.
