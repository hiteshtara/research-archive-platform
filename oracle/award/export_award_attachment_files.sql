/*
 * Deduplicated physical-file metadata for every distinct FILE_ID actually
 * referenced by KCOEUS.AWARD_ATTACHMENT - not the whole (shared, much
 * larger) ATTACHMENT_FILE table. One row per physical file; repeated
 * AWARD_ATTACHMENT.FILE_ID values collapse to a single row here, matching
 * "do not upload duplicate physical files" for the future upload sprint.
 *
 * Metadata only: DBMS_LOB.GETLENGTH() returns a blob's byte length without
 * ever transferring its content, and the blob_source/file_size_bytes CASE
 * expressions only ever check IS NULL / call GETLENGTH() - no blob column
 * value itself is selected. No blob streaming happens in this query.
 *
 * Blob selection rule (source of truth for blob_source/file_size_bytes):
 *   ATTACHMENT_FILE.FILE_DATA is primary ("INLINE");
 *   FILE_DATA.DATA (via ATTACHMENT_FILE.FILE_DATA_ID = FILE_DATA.ID) is the
 *   fallback ("EXTERNAL") when the primary is null;
 *   neither present means the physical file's blob is missing entirely.
 */
SELECT
    af.FILE_ID                     AS file_id,
    af.FILE_DATA_ID                 AS file_data_id,
    af.FILE_NAME                    AS file_name,
    af.CONTENT_TYPE                  AS content_type,
    CASE
        WHEN af.FILE_DATA IS NOT NULL THEN 'INLINE'
        WHEN fd.DATA IS NOT NULL THEN 'EXTERNAL'
        ELSE NULL
    END                              AS blob_source,
    CASE
        WHEN af.FILE_DATA IS NOT NULL
            THEN DBMS_LOB.GETLENGTH(af.FILE_DATA)
        WHEN fd.DATA IS NOT NULL
            THEN DBMS_LOB.GETLENGTH(fd.DATA)
        ELSE NULL
    END                              AS file_size_bytes,
    af.UPDATE_TIMESTAMP              AS oracle_update_timestamp,
    af.UPDATE_USER                   AS oracle_update_user
FROM (
    SELECT DISTINCT FILE_ID
    FROM KCOEUS.AWARD_ATTACHMENT
    WHERE FILE_ID IS NOT NULL
) referenced
JOIN KCOEUS.ATTACHMENT_FILE af
    ON af.FILE_ID = referenced.FILE_ID
LEFT JOIN KCOEUS.FILE_DATA fd
    ON fd.ID = af.FILE_DATA_ID
ORDER BY af.FILE_ID
