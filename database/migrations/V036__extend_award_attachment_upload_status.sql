-- Award Attachment Archive (Sprint 2: resumable S3 upload).
--
-- Sprint 1's upload_status CHECK constraint only ever needed PENDING and
-- SKIPPED (nothing uploaded anything yet). Sprint 2 introduces the actual
-- upload loader (etl/load_award_attachments.py --upload), which needs two
-- more states:
--   - UPLOADING: set immediately before a file's upload attempt begins, so
--     a crash mid-upload leaves durable, resumable evidence instead of a
--     row stuck looking untouched.
--   - MISSING_SOURCE_CONTENT: replaces Sprint 1's SKIPPED - a clearer name
--     now that it is an actionable, permanent classification (neither
--     ATTACHMENT_FILE.FILE_DATA nor FILE_DATA.DATA has a BLOB) rather than
--     a placeholder no loader yet acted on.
--
-- Forward-only: existing SKIPPED rows (none expected yet, since no real
-- load has run) are renamed rather than assumed absent.

UPDATE archive.attachment_object
   SET upload_status = 'MISSING_SOURCE_CONTENT'
 WHERE upload_status = 'SKIPPED';

ALTER TABLE archive.attachment_object
    DROP CONSTRAINT ck_attachment_object_upload_status;

ALTER TABLE archive.attachment_object
    ADD CONSTRAINT ck_attachment_object_upload_status
        CHECK (
            upload_status IN (
                'PENDING',
                'UPLOADING',
                'UPLOADED',
                'FAILED',
                'MISSING_SOURCE_CONTENT'
            )
        );

COMMENT ON COLUMN archive.attachment_object.upload_status IS
    'Set by etl/load_award_attachments.py. PENDING/MISSING_SOURCE_CONTENT '
    'by the metadata loader; UPLOADING/UPLOADED/FAILED by --upload. '
    'MISSING_SOURCE_CONTENT is permanent - neither blob source has '
    'content, so no upload is ever attempted for that row.';
