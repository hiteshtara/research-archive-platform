-- Export regular Subaward attachment metadata only. FILE_DATA_ID and
-- DOCUMENT_ID are preserved; no binary file content is selected.
SELECT
    sa.attachment_id AS attachment_id,
    sa.subaward_id AS subaward_id,
    sa.subaward_code AS subaward_code,
    sa.sequence_number AS sequence_number,
    sa.attachment_type_code AS attachment_type_code,
    sat.description AS attachment_type_description,
    sa.document_id AS document_id,
    sa.file_data_id AS file_data_id,
    sa.file_name AS file_name,
    sa.mime_type AS mime_type,
    sa.document_status_code AS document_status_code,
    sa.description AS description,
    sa.last_update_timestamp AS last_update_timestamp,
    sa.last_update_user AS last_update_user,
    sa.update_timestamp AS update_timestamp,
    sa.update_user AS update_user,
    sa.ver_nbr AS ver_nbr,
    sa.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_ATTACHMENTS sa
LEFT JOIN KCOEUS.SUBAWARD_ATTACHMENT_TYPE sat
    ON sat.attachment_type_code = sa.attachment_type_code
ORDER BY sa.subaward_code, sa.sequence_number, sa.attachment_id;
