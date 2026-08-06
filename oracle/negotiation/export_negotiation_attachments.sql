-- Export Negotiation Activity attachment metadata to negotiation_attachments.csv.
--
-- NEGOTIATION_ATTACHMENT has no NEGOTIATION_ID of its own (see
-- repository-negotiation.xml: the collection is nested under
-- NegotiationActivity, inverse-foreignkey activityId) - the owning
-- Negotiation is resolved through NEGOTIATION_ACTIVITY.NEGOTIATION_ID.
-- Binary content is read separately, from ATTACHMENT_FILE.FILE_DATA via
-- FILE_ID, by the generic attachment plugin
-- (etl/archive_etl/attachments/plugins/negotiation.py) - this query only
-- resolves metadata plus the FILE_ID reference, never the BLOB itself.
SELECT
    att.attachment_id AS attachment_id,
    att.activity_id AS activity_id,
    a.negotiation_id AS negotiation_id,
    n.document_number AS document_number,
    n.associated_document_id AS associated_document_id,
    att.file_id AS file_id,
    af.file_name AS file_name,
    af.content_type AS content_type,
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
ORDER BY a.negotiation_id, att.attachment_id;
