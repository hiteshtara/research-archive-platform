-- PostgreSQL metadata verification for archived Subaward attachment files.

SELECT COUNT(*) AS attachment_metadata_count
FROM archive.subaward_attachment;

SELECT
    COUNT(*) FILTER (
        WHERE file_data_id IS NOT NULL
    ) AS attachment_rows_with_file_data_id,
    COUNT(*) FILTER (
        WHERE file_data_id IS NULL
    ) AS attachment_rows_without_file_data_id
FROM archive.subaward_attachment;

SELECT
    archive_status,
    COUNT(*) AS manifest_count
FROM archive.subaward_attachment_archive
GROUP BY archive_status
ORDER BY archive_status;

SELECT
    COUNT(*) FILTER (
        WHERE archive_status = 'ARCHIVED'
    ) AS uploaded_count,
    COUNT(*) FILTER (
        WHERE archive_status = 'MISSING'
    ) AS missing_blob_count,
    COUNT(*) FILTER (
        WHERE archive_status = 'FAILED'
    ) AS failed_upload_count,
    COALESCE(
        SUM(byte_size) FILTER (
            WHERE archive_status = 'ARCHIVED'
        ),
        0
    ) AS total_archived_bytes
FROM archive.subaward_attachment_archive;

SELECT
    file_data_id,
    COUNT(*) AS attachment_count
FROM archive.subaward_attachment
WHERE file_data_id IS NOT NULL
GROUP BY file_data_id
HAVING COUNT(*) > 1
ORDER BY attachment_count DESC, file_data_id;

SELECT COUNT(*) AS invalid_archived_metadata_count
FROM archive.subaward_attachment_archive
WHERE archive_status = 'ARCHIVED'
  AND (
      s3_bucket IS NULL
      OR s3_key IS NULL
      OR byte_size IS NULL
      OR sha256 IS NULL
      OR sha256 !~ '^[0-9a-f]{64}$'
      OR archived_timestamp IS NULL
  );

SELECT COUNT(*) AS conflicting_object_checksum_count
FROM (
    SELECT s3_bucket, s3_key
    FROM archive.subaward_attachment_archive
    WHERE archive_status = 'ARCHIVED'
    GROUP BY s3_bucket, s3_key
    HAVING COUNT(DISTINCT sha256) > 1
) conflicts;

SELECT COUNT(*) AS manifest_to_attachment_orphan_count
FROM archive.subaward_attachment_archive manifest
LEFT JOIN archive.subaward_attachment attachment
    ON attachment.attachment_id = manifest.attachment_id
WHERE attachment.attachment_id IS NULL;

SELECT COUNT(*) AS attachment_without_manifest_count
FROM archive.subaward_attachment attachment
LEFT JOIN archive.subaward_attachment_archive manifest
    ON manifest.attachment_id = attachment.attachment_id
WHERE manifest.attachment_id IS NULL;

SELECT COUNT(*) AS manifest_parent_mismatch_count
FROM archive.subaward_attachment_archive manifest
JOIN archive.subaward_attachment attachment
    ON attachment.attachment_id = manifest.attachment_id
WHERE manifest.subaward_id IS DISTINCT FROM attachment.subaward_id
   OR manifest.subaward_code IS DISTINCT FROM attachment.subaward_code
   OR manifest.sequence_number IS DISTINCT FROM attachment.sequence_number
   OR manifest.file_data_id IS DISTINCT FROM attachment.file_data_id
   OR manifest.original_file_name IS DISTINCT FROM attachment.file_name
   OR manifest.mime_type IS DISTINCT FROM attachment.mime_type
   OR manifest.document_id IS DISTINCT FROM attachment.document_id;

SELECT
    attachment_id,
    subaward_id,
    file_data_id,
    original_file_name,
    mime_type,
    s3_bucket,
    s3_key,
    byte_size,
    sha256,
    archive_status,
    archived_timestamp,
    error_message
FROM archive.subaward_attachment_archive
WHERE archive_status <> 'ARCHIVED'
ORDER BY attachment_id
LIMIT 100;
