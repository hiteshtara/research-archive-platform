-- Synthetic Subaward attachment metadata for local UI/API development ONLY.
--
-- Inserts 4 fake attachment_id rows (9000000001-9000000004, well outside
-- any real Oracle-sourced ID range) against subaward_id = 1, which already
-- exists locally with zero real attachment rows - so this never collides
-- with or modifies real archived data (see the ~490K real rows already in
-- archive.subaward_attachment from the ETL, untouched by this script).
--
-- Run tools/generate-local-attachment-fixtures.py first so the files
-- these rows reference actually exist on disk. This is NOT a
-- database/migrations/ file: it is dev-only synthetic data and must never
-- be applied to test/prod.
--
-- Prefer running both steps (plus a verification count) via
-- ./scripts/setup-local.sh instead of invoking this file directly.
--
-- Usage:
--   psql -h 127.0.0.1 -p 5432 -U mukadder -d research_archive \
--     -f scripts/seed-local-subaward-attachments.sql
--
-- To remove: see the DELETE statements at the bottom (commented out).

BEGIN;

INSERT INTO archive.subaward_attachment (
    attachment_id, subaward_id, subaward_code, sequence_number,
    attachment_type_description, file_name, mime_type,
    document_status_code, description, last_update_timestamp,
    last_update_user
) VALUES
    (9000000001, 1, '1', 1, 'Sample Agreement (synthetic)',
     'sample-agreement.pdf', 'application/pdf', 'A',
     'Synthetic sample agreement for local UI/API development - not a real BU document.',
     CURRENT_TIMESTAMP, 'local-dev-seed'),
    (9000000002, 1, '1', 1, 'Sample Budget (synthetic)',
     'sample-budget.xlsx',
     'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
     'A',
     'Synthetic sample budget spreadsheet for local UI/API development - not a real BU document.',
     CURRENT_TIMESTAMP, 'local-dev-seed'),
    (9000000003, 1, '1', 1, 'Sample Note (synthetic, not archived)',
     'sample-note.txt', 'text/plain', 'A',
     'Synthetic sample note - metadata only, deliberately not archived yet (demonstrates the "not archived" UI/API state).',
     CURRENT_TIMESTAMP, 'local-dev-seed'),
    (9000000004, 1, '1', 1, 'Sample Missing File (synthetic)',
     'sample-missing.pdf', 'application/pdf', 'A',
     'Synthetic attachment whose underlying file is deliberately absent from local-data/attachments/ (demonstrates the missing-file 404 path).',
     CURRENT_TIMESTAMP, 'local-dev-seed')
ON CONFLICT (attachment_id) DO NOTHING;

-- Archive rows for 9000000001, 9000000002, and 9000000004 only -
-- 9000000003 is intentionally left un-archived.
INSERT INTO archive.subaward_attachment_archive (
    attachment_id, subaward_id, subaward_code, sequence_number,
    original_file_name, mime_type, s3_bucket, s3_key, byte_size,
    archive_status, archived_timestamp
) VALUES
    (9000000001, 1, '1', 1, 'sample-agreement.pdf', 'application/pdf',
     'local-fixtures', 'sample-agreement.pdf', 626, 'ARCHIVED',
     CURRENT_TIMESTAMP),
    (9000000002, 1, '1', 1, 'sample-budget.xlsx',
     'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
     'local-fixtures', 'sample-budget.xlsx', 1647, 'ARCHIVED',
     CURRENT_TIMESTAMP),
    (9000000004, 1, '1', 1, 'sample-missing.pdf', 'application/pdf',
     'local-fixtures', 'sample-missing.pdf', 12345, 'ARCHIVED',
     CURRENT_TIMESTAMP)
ON CONFLICT (attachment_id) DO NOTHING;

COMMIT;

-- To remove this synthetic data later:
-- DELETE FROM archive.subaward_attachment_archive
--   WHERE attachment_id BETWEEN 9000000001 AND 9000000004;
-- DELETE FROM archive.subaward_attachment
--   WHERE attachment_id BETWEEN 9000000001 AND 9000000004;
