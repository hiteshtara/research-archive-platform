-- Export Negotiation Activity attachment metadata to negotiation_attachments.csv.
--
-- NEGOTIATION_ATTACHMENT has no NEGOTIATION_ID of its own (see
-- repository-negotiation.xml: the collection is nested under
-- NegotiationActivity, inverse-foreignkey activityId) - the owning
-- Negotiation is resolved through NEGOTIATION_ACTIVITY.NEGOTIATION_ID.
--
-- Binary content resolution (blob_source/file_data_id/file_size_bytes
-- below): a physical file's content lives in EITHER
-- ATTACHMENT_FILE.FILE_DATA (INLINE) OR, when that column is null,
-- FILE_DATA.DATA (EXTERNAL, joined via ATTACHMENT_FILE.FILE_DATA_ID =
-- FILE_DATA.ID) - never assume INLINE is the only case. Confirmed
-- against live Kuali source (AttachmentFile.getData(): reads via
-- fileDataId/FILE_DATA whenever it's set, falling back to the inline
-- byte[] only when null) and against live Oracle data: as of 2026-08-15,
-- 26,572 of 28,923 Negotiation attachment physical files (91.9%) are
-- EXTERNAL-sourced, not inline - a prior version of this query never
-- selected FILE_DATA_ID at all, so every one of those was archived as
-- MISSING despite having real, retrievable content (see
-- docs/architecture/NEGOTIATION_ATTACHMENT_ACCESS_DESIGN.md's
-- "External-BLOB correction" section). Mirrors
-- oracle/award/export_award_attachment_files.sql's identical INLINE/
-- EXTERNAL classification exactly, and the same never-int()-coerce rule
-- for FILE_DATA_ID (a UUID string, e.g.
-- '995577d2-b20f-4b10-a4aa-5bc0d32f64b4' - see V072's incident note).
--
-- DBMS_LOB.GETLENGTH() returns a blob's byte length without transferring
-- its content - metadata only, no blob streaming happens in this query.
-- Blob content itself is streamed separately, by
-- etl/archive_etl/attachments/oracle_blob.py's InlineOrExternalBlobReader
-- (etl/archive_etl/attachments/plugins/negotiation.py wires it up).
SELECT
    att.attachment_id AS attachment_id,
    att.activity_id AS activity_id,
    a.negotiation_id AS negotiation_id,
    n.document_number AS document_number,
    n.associated_document_id AS associated_document_id,
    att.file_id AS file_id,
    af.file_data_id AS file_data_id,
    af.file_name AS file_name,
    af.content_type AS content_type,
    CASE
        WHEN af.file_data IS NOT NULL THEN 'INLINE'
        WHEN fd.data IS NOT NULL THEN 'EXTERNAL'
        ELSE NULL
    END AS blob_source,
    CASE
        WHEN af.file_data IS NOT NULL
            THEN DBMS_LOB.GETLENGTH(af.file_data)
        WHEN fd.data IS NOT NULL
            THEN DBMS_LOB.GETLENGTH(fd.data)
        ELSE NULL
    END AS file_size_bytes,
    att.description AS description,
    att.restricted AS restricted,
    att.update_timestamp AS update_timestamp,
    att.update_user AS update_user
FROM KCOEUS.NEGOTIATION_ATTACHMENT att
JOIN KCOEUS.NEGOTIATION_ACTIVITY a
    ON a.negotiation_activity_id = att.activity_id
JOIN KCOEUS.NEGOTIATION n
    ON n.negotiation_id = a.negotiation_id
LEFT JOIN KCOEUS.ATTACHMENT_FILE af
    ON af.file_id = att.file_id
LEFT JOIN KCOEUS.FILE_DATA fd
    ON fd.id = af.file_data_id
ORDER BY a.negotiation_id, att.attachment_id;
