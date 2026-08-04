-- attachment_type_description is Oracle's own real PROPOSAL_ATTACHMENT_TYPE
-- taxonomy (7 rows, live-verified: 1=Internal Documents, 2=Budget,
-- 3=Subaward Documents, 4=Proposal Package, 5=Communications,
-- 6=Required Signature Page, 7=Other) - not the same as an
-- attachment's own free-text title, which can carry any user-chosen
-- name (e.g. a title containing "Guidelines" or "Transfer Package" is
-- still filed under Oracle's own Other/Proposal Package types
-- respectively - see V062's migration comment).

SELECT
    pa.proposal_attachments_id AS proposal_attachment_id,
    pa.proposal_id,
    pa.proposal_number,
    pa.sequence_number,

    pa.attachment_number,
    pa.attachment_title,
    pa.attachment_type_code,
    pat.description AS attachment_type_description,
    pa.file_name,
    pa.content_type,
    pa.comments,
    pa.document_status_code,
    pa.file_data_id,

    pa.update_timestamp AS source_update_timestamp,
    pa.update_user AS source_update_user

FROM proposal_attachments pa
LEFT JOIN proposal_attachment_type pat
    ON pat.attachment_type_code = pa.attachment_type_code

ORDER BY
    pa.proposal_number,
    pa.sequence_number,
    pa.attachment_number;
