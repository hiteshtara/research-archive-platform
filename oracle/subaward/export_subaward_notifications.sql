-- Export Subaward notification text and audit data. The descriptor relates
-- OWNING_DOCUMENT_ID_FK to SUBAWARD_ID; the raw parent key is preserved.
SELECT
    sn.notification_id AS notification_id,
    sn.owning_document_id_fk AS owning_document_id_fk,
    sn.document_number AS document_number,
    sn.subaward_code AS subaward_code,
    sn.notification_type_id AS notification_type_id,
    sn.recipients AS recipients,
    sn.subject AS subject,
    sn.message AS message,
    sn.create_timestamp AS create_timestamp,
    sn.update_timestamp AS update_timestamp,
    sn.update_user AS update_user,
    sn.ver_nbr AS ver_nbr,
    sn.obj_id AS obj_id
FROM KCOEUS.SUBAWARD_NOTIFICATION sn
ORDER BY sn.subaward_code, sn.create_timestamp, sn.notification_id;
