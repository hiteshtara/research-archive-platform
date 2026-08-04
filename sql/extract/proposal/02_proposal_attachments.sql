SELECT
    pa.proposal_attachments_id AS proposal_attachment_id,
    pa.proposal_id,
    pa.proposal_number,
    pa.sequence_number,

    pa.attachment_number,
    pa.attachment_title,
    pa.attachment_type_code,
    pa.file_name,
    pa.content_type,
    pa.comments,
    pa.document_status_code,
    pa.file_data_id,

    pa.update_timestamp AS source_update_timestamp,
    pa.update_user AS source_update_user

FROM proposal_attachments pa

ORDER BY
    pa.proposal_number,
    pa.sequence_number,
    pa.attachment_number;
